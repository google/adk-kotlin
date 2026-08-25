/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.litertlm

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall as AdkFunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type as AdkSchemaType
import com.google.ai.edge.litertlm.Content as LiteRtLmContent
import com.google.ai.edge.litertlm.Contents as LiteRtLmContents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteRtLmMessage
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role as LiteRtLmRole
import com.google.ai.edge.litertlm.ToolCall as LiteRtLmToolCall
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [Model] implementation that uses the LiteRT-LM runtime to generate content.
 *
 * @param engine The [Engine] to use for generation.
 * @param ownsEngine Whether this model owns the engine and should close it when closed.
 * @param name The name of the model.
 */
class LiteRtLmModel
private constructor(
  val engine: LiteRtLmEngine,
  private val ownsEngine: Boolean = false,
  override val name: String = "LiteRtLmModel",
) : Model, AutoCloseable {

  private val initializationLock = Any()

  private val activeConversation = ActiveLiteRtLmConversation()

  private val conversationMutex = Mutex()

  companion object {
    private val logger = LoggerFactory.getLogger(LiteRtLmModel::class)

    /**
     * Creates a [LiteRtLmModel] instance with a pre-created [Engine]. The caller is responsible for
     * closing the [Engine].
     */
    fun create(engine: Engine, name: String = "LiteRtLmModel") =
      LiteRtLmModel(DefaultLiteRtLmEngine(engine), ownsEngine = false, name = name)

    /**
     * Creates a [LiteRtLmModel] instance with a custom [LiteRtLmEngine]. Used primarily for
     * testing.
     */
    fun create(engine: LiteRtLmEngine, name: String = "LiteRtLmModel") =
      LiteRtLmModel(engine, ownsEngine = false, name = name)

    /**
     * Creates a [LiteRtLmModel] instance that owns the [Engine]. The [Engine] will be closed when
     * this model is closed.
     */
    fun create(config: EngineConfig, name: String = "LiteRtLmModel"): LiteRtLmModel {
      return LiteRtLmModel(DefaultLiteRtLmEngine(Engine(config)), ownsEngine = true, name = name)
    }
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    // Log only non-sensitive metadata; the request carries user prompts and system instructions
    // which must not reach the logs.
    logger.trace { "generateContent: ${request.contents.size} content(s), stream: $stream" }

    return if (stream) generateContentStreaming(request) else generateContentNonStreaming(request)
  }

  /**
   * Streams responses through the shared [StreamingResponseAggregator]: every chunk is emitted as a
   * partial [LlmResponse], followed by a single aggregated final response.
   *
   * On success the conversation cache is committed from the aggregated response; a content-free
   * turn discards it. A generation error or cancellation propagates to the collector, so the
   * agent's error handling runs, discarding the incomplete conversation on the way out.
   */
  @OptIn(FrameworkInternalApi::class)
  private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    // The lock is released before the final response, the only one that triggers tool dispatch,
    // so a tool re-entering this model can take the lock instead of deadlocking. Partials are
    // emitted under the lock; one that precedes an error still reaches the caller.
    val finalResponse = conversationMutex.withLock {
      val (conversation, liteRtLmLastMessage) = getOrCreateConversation(request)

      val aggregator = StreamingResponseAggregator()
      conversation
        .rawStreamingResponses(liteRtLmLastMessage)
        .map { aggregator.processResponse(it) }
        .onCompletion { cause ->
          // Error, cancellation, or caller failure leaves the conversation incomplete; discard it.
          if (cause != null) discardActiveConversation()
        }
        .collect { emit(it) }

      // LiteRT-LM reports completion through onDone and errors through onError, so a turn that
      // finishes always stops normally. Report STOP on the aggregated final, as the other backends
      // do, so Event.finishReason and the call_llm span are populated.
      val finalResponse = aggregator.aggregate()?.copy(finishReason = FinishReason.STOP)
      val modelResponseContent = finalResponse?.content
      if (modelResponseContent != null) {
        // Commit the aggregated turn as the next turn's cache key.
        synchronized(activeConversation) {
          activeConversation.update(
            conversation,
            request.contents + modelResponseContent.withoutGeneratedFunctionCallIds(),
          )
        }
      } else {
        // A content-free turn leaves the native conversation ambiguous, so discard it.
        discardActiveConversation()
      }
      finalResponse
    }
    finalResponse?.let { emit(it) }
  }

  /** Generates a single non-streaming response, committing or discarding the conversation cache. */
  private fun generateContentNonStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    // Emit after releasing the lock, so a tool re-entering this model can take it instead of
    // deadlocking. Emitting outside the try also preserves the original exception if the caller
    // throws while consuming the response.
    val response = conversationMutex.withLock {
      val (conversation, liteRtLmLastMessage) = getOrCreateConversation(request)
      try {
        // A returned message is a normal completion (errors throw instead), so report STOP, as the
        // other backends do, to populate Event.finishReason and the call_llm span.
        val modelResponse =
          conversation
            .sendMessage(liteRtLmLastMessage)
            .toLlmResponse(partial = false)
            .copy(finishReason = FinishReason.STOP)
        // Commit the cache key (request contents + model response) for the next turn.
        modelResponse.content?.let { modelResponseContent ->
          synchronized(activeConversation) {
            activeConversation.update(
              conversation,
              request.contents + modelResponseContent.withoutGeneratedFunctionCallIds(),
            )
          }
        }
        modelResponse
      } catch (e: Exception) {
        // Discard the incomplete conversation, then let the error reach the collector.
        discardActiveConversation()
        throw e
      }
    }
    emit(response)
  }

  /**
   * Bridges the callback-based [LiteRtLmConversation.sendMessageAsync] into a cold flow of raw,
   * un-aggregated partial responses.
   *
   * This must be a [callbackFlow] rather than a plain [flow]: LiteRT-LM invokes [MessageCallback]
   * on a native/JNI thread, which cannot call a `flow` builder's suspending `emit`, whereas the
   * channel's `trySend` is safe to call from any thread. The channel is buffered
   * [Channel.UNLIMITED] so a slow collector never causes a chunk to be dropped.
   *
   * A content-free chunk carries no text and no tool call, so it has nothing to aggregate and is
   * dropped here rather than surfaced to the caller as an empty partial response (see
   * [isContentFree]).
   */
  private fun LiteRtLmConversation.rawStreamingResponses(
    message: LiteRtLmMessage
  ): Flow<LlmResponse> =
    callbackFlow {
        sendMessageAsync(
          message,
          object : MessageCallback {
            override fun onMessage(message: LiteRtLmMessage) {
              val response = message.toLlmResponse(partial = true)
              if (!response.isContentFree()) {
                val unused = trySend(response)
              }
            }

            override fun onDone() {
              channel.close()
            }

            override fun onError(throwable: Throwable) {
              channel.close(throwable)
            }
          },
        )
        // sendMessageAsync exposes no cancellation handle, so there is nothing to unregister here;
        // discarding an incomplete conversation on cancellation is handled by the collector.
        awaitClose {}
      }
      .buffer(Channel.UNLIMITED)

  private fun getOrCreateConversation(
    request: LlmRequest
  ): Pair<LiteRtLmConversation, LiteRtLmMessage> {
    synchronized(initializationLock) {
      if (!engine.isInitialized()) {
        engine.initialize()
      }
    }

    val history = request.contents.dropLast(1)
    val lastMessage =
      request.contents.lastOrNull() ?: throw IllegalArgumentException("Empty request contents")

    val liteRtLmLastMessage = mapContentToLiteRtLmMessage(lastMessage)

    // Released before building the replacement, and outside the lock, since closing blocks.
    discardActiveConversation(keepMatching = history)

    val conversation =
      synchronized(activeConversation) {
        if (activeConversation.matches(history)) {
          activeConversation.conversation!!
        } else {
          val liteRtLmTools =
            request.config.tools
              ?.flatMap { tool ->
                tool.functionDeclarations.orEmpty().map { declaration ->
                  tool(ManualOpenApiTool(declaration))
                }
              }
              .orEmpty()

          val systemInstruction =
            request.config.systemInstruction?.let { si ->
              val parts = si.parts.mapNotNull { mapPartToContent(it) }
              LiteRtLmContents.of(parts)
            }

          val initialMessages = history.map { mapContentToLiteRtLmMessage(it) }

          val conversationConfig =
            ConversationConfig(
              systemInstruction = systemInstruction,
              initialMessages = initialMessages,
              tools = liteRtLmTools,
              automaticToolCalling = false,
            )

          val newConversation = engine.createConversation(conversationConfig)
          activeConversation.update(newConversation, history)
          newConversation
        }
      }

    return Pair(conversation, liteRtLmLastMessage)
  }

  override fun close() {
    // Safely close the active conversation when the model itself is closed.
    discardActiveConversation()
    if (ownsEngine) {
      engine.close()
    }
  }

  /**
   * Releases the cached conversation, unless its history is [keepMatching]. Detached under the lock
   * but released outside it, since releasing needs the lock the terminal callback holds.
   */
  private fun discardActiveConversation(keepMatching: List<AdkContent>? = null) {
    val detached =
      synchronized(activeConversation) {
        if (keepMatching != null && activeConversation.matches(keepMatching)) null
        else activeConversation.detach()
      }
    detached?.let { releaseConversation(it) }
  }

  /**
   * Cancels generation and then closes [conversation]. Both are best-effort: a conversation that
   * has already finished or closed throws instead of reporting it.
   */
  private fun releaseConversation(conversation: LiteRtLmConversation) {
    try {
      conversation.cancelProcess()
    } catch (e: Exception) {
      logger.warn(e) { "Cancelling generation failed; closing the conversation anyway." }
    }
    try {
      conversation.close()
    } catch (e: Exception) {
      logger.warn(e) { "Closing the conversation failed." }
    }
  }
}

// --- Helpers for Mapping ---

private fun mapContentToLiteRtLmMessage(adkContent: AdkContent): LiteRtLmMessage {
  val role =
    if (adkContent.parts.any { it.functionResponse != null }) {
      LiteRtLmRole.TOOL
    } else {
      when (adkContent.role) {
        "user" -> LiteRtLmRole.USER
        "model" -> LiteRtLmRole.MODEL
        "system" -> LiteRtLmRole.SYSTEM
        "tool" -> LiteRtLmRole.TOOL
        else -> LiteRtLmRole.USER
      }
    }
  val parts = adkContent.parts.mapNotNull { mapPartToContent(it) }
  val contents = LiteRtLmContents.of(parts)

  return when (role) {
    LiteRtLmRole.USER -> LiteRtLmMessage.user(contents)
    LiteRtLmRole.SYSTEM -> LiteRtLmMessage.system(contents)
    LiteRtLmRole.TOOL -> LiteRtLmMessage.tool(contents)
    LiteRtLmRole.MODEL -> {
      val toolCalls =
        adkContent.parts.mapNotNull { part ->
          part.functionCall?.let { fc -> LiteRtLmToolCall(fc.name, fc.args) }
        }
      LiteRtLmMessage.model(contents, toolCalls)
    }
  }
}

private fun mapPartToContent(part: AdkPart): LiteRtLmContent? {
  // Use local variables to enable smart casts on properties from other module
  val text = part.text
  val inlineData = part.inlineData
  val fileData = part.fileData
  val functionResponse = part.functionResponse

  return when {
    text != null -> LiteRtLmContent.Text(text)
    inlineData != null -> {
      val mimeType = inlineData.mimeType.orEmpty().lowercase()
      val data = inlineData.data ?: byteArrayOf()
      when {
        mimeType.startsWith("image/") -> LiteRtLmContent.ImageBytes(data)
        mimeType.startsWith("audio/") -> LiteRtLmContent.AudioBytes(data)
        else -> null
      }
    }
    fileData != null -> {
      val mimeType = fileData.mimeType.orEmpty().lowercase()
      val path = fileData.fileUri.orEmpty()
      when {
        mimeType.startsWith("image/") -> LiteRtLmContent.ImageFile(path)
        mimeType.startsWith("audio/") -> LiteRtLmContent.AudioFile(path)
        else -> null
      }
    }
    functionResponse != null -> {
      LiteRtLmContent.ToolResponse(functionResponse.name, functionResponse.response)
    }
    else -> null // functionCall is handled separately
  }
}

fun LiteRtLmMessage.toLlmResponse(partial: Boolean = false): LlmResponse {
  val adkParts =
    this.contents.contents
      .map { liteRtLmContent ->
        when (liteRtLmContent) {
          is LiteRtLmContent.Text -> AdkPart(text = liteRtLmContent.text)
          is LiteRtLmContent.ImageBytes -> AdkPart(text = "[Image Bytes]")
          is LiteRtLmContent.ImageFile ->
            AdkPart(text = "[Image File: ${liteRtLmContent.absolutePath}]")
          is LiteRtLmContent.AudioBytes -> AdkPart(text = "[Audio Bytes]")
          is LiteRtLmContent.AudioFile ->
            AdkPart(text = "[Audio File: ${liteRtLmContent.absolutePath}]")
          is LiteRtLmContent.ToolResponse ->
            AdkPart(text = "[Tool Response: ${liteRtLmContent.name}]")
        }
      }
      .toMutableList()

  if (this.toolCalls.isNotEmpty()) {
    for (toolCall in this.toolCalls) {
      adkParts.add(
        AdkPart(functionCall = AdkFunctionCall(name = toolCall.name, args = toolCall.arguments))
      )
    }
  }

  return LlmResponse(content = AdkContent(role = "model", parts = adkParts), partial = partial)
}

/**
 * True if there is nothing to aggregate: no function call and no non-empty text. Dropping such a
 * chunk loses nothing because [toLlmResponse] populates only content, never a finish reason, usage,
 * or error.
 */
private fun LlmResponse.isContentFree(): Boolean =
  content?.parts?.none { it.functionCall != null || !it.text.isNullOrEmpty() } ?: true

/**
 * Drops the function call ids the aggregator generates, since the framework strips them from the
 * history it sends back. Keeping them would make the cache key of a tool-calling turn unmatchable.
 */
private fun AdkContent.withoutGeneratedFunctionCallIds(): AdkContent {
  fun AdkFunctionCall.isGenerated() =
    id?.startsWith(AdkFunctionCall.ADK_FUNCTION_CALL_ID_PREFIX) == true

  if (parts.none { it.functionCall?.isGenerated() == true }) return this
  return copy(
    parts =
      parts.map { part ->
        val functionCall = part.functionCall
        if (functionCall?.isGenerated() == true)
          part.copy(functionCall = functionCall.copy(id = null))
        else part
      }
  )
}

// --- Manual Tool Adapter ---

internal class ManualOpenApiTool(private val declaration: FunctionDeclaration) : OpenApiTool {
  override fun execute(paramsJsonString: String): String {
    throw UnsupportedOperationException("Manual tool execution not supported")
  }

  override fun getToolDescriptionJsonString(): String {
    val tool = mutableMapOf<String, Any>()
    tool["name"] = declaration.name
    tool["description"] = declaration.description
    declaration.parameters?.let { params -> tool["parameters"] = params.toMap() }
    // Describing what the tool returns helps the model decide whether to call it at all, and this
    // description is plain JSON, so there is nothing stopping it carrying the response schema.
    declaration.response?.let { response -> tool["response"] = response.toMap() }
    return Json.toJsonString(tool)
  }
}

internal fun Schema.toMap(): Map<String, Any> {
  val map = mutableMapOf<String, Any>()

  type?.let { t ->
    val typeName =
      when (t) {
        AdkSchemaType.OBJECT -> "object"
        AdkSchemaType.STRING -> "string"
        AdkSchemaType.INTEGER -> "integer"
        AdkSchemaType.NUMBER -> "number"
        AdkSchemaType.BOOLEAN -> "boolean"
        AdkSchemaType.ARRAY -> "array"
        AdkSchemaType.NULL -> "null"
        // A schema carrying only `anyOf` has no type of its own; naming one here would sit next
        // to the alternatives and contradict them.
        else -> if (anyOf != null) null else "string"
      }
    typeName?.let { map["type"] = it }
  }

  description?.let { map["description"] = it }
  properties?.let { props -> map["properties"] = props.mapValues { (_, schema) -> schema.toMap() } }
  items?.let { map["items"] = it.toMap() }
  required?.let { map["required"] = it }
  enum?.let { map["enum"] = it }
  // The tool description is plain JSON rather than a typed backend schema, so every constraint a
  // caller can express is emitted verbatim under its JSON Schema name.
  format?.let { map["format"] = it }
  nullable?.let { map["nullable"] = it }
  default?.let { map["default"] = it }
  anyOf?.let { schemas -> map["anyOf"] = schemas.map { it.toMap() } }
  title?.let { map["title"] = it }
  pattern?.let { map["pattern"] = it }
  minimum?.let { map["minimum"] = it }
  maximum?.let { map["maximum"] = it }
  minLength?.let { map["minLength"] = it }
  maxLength?.let { map["maxLength"] = it }
  minItems?.let { map["minItems"] = it }
  maxItems?.let { map["maxItems"] = it }
  minProperties?.let { map["minProperties"] = it }
  maxProperties?.let { map["maxProperties"] = it }

  return map
}
