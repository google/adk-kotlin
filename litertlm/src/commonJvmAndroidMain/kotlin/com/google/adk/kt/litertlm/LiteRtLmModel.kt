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

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content as AdkContent
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
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

    if (stream) {
      return callbackFlow {
        conversationMutex.withLock {
          val (conversation, liteRtLmLastMessage) =
            try {
              getOrCreateConversation(request)
            } catch (e: Exception) {
              val unused = trySend(LlmResponse(errorMessage = e.message ?: e.toString()))
              channel.close()
              return@withLock
            }

          var isCompleted = false
          val accumulatedText = StringBuilder()
          var lastResponse: LlmResponse? = null

          conversation.sendMessageAsync(
            liteRtLmLastMessage,
            object : MessageCallback {
              override fun onMessage(message: LiteRtLmMessage) {
                val response = message.toLlmResponse(partial = true)

                response.content?.parts?.forEach { part ->
                  part.text?.let { accumulatedText.append(it) }
                }

                lastResponse = response
                val unused = trySend(response)
              }

              override fun onDone() {
                val finalParts = mutableListOf<AdkPart>()
                if (accumulatedText.isNotEmpty()) {
                  finalParts.add(AdkPart(text = accumulatedText.toString()))
                }
                lastResponse
                  ?.content
                  ?.parts
                  ?.filter { it.functionCall != null }
                  ?.let { finalParts.addAll(it) }

                val finalResponse =
                  LlmResponse(
                    content = AdkContent(role = "model", parts = finalParts),
                    partial = false,
                  )

                finalResponse.content?.let { modelResponseContent ->
                  synchronized(activeConversation) {
                    activeConversation.update(conversation, request.contents + modelResponseContent)
                  }
                }

                val unused = trySend(finalResponse)
                isCompleted = true
                channel.close()
              }

              override fun onError(throwable: Throwable) {
                // Discard conversation on generation failures.
                synchronized(activeConversation) { activeConversation.clear() }
                val unused =
                  trySend(LlmResponse(errorMessage = throwable.message ?: throwable.toString()))
                isCompleted = true
                channel.close(throwable)
              }
            },
          )
          awaitClose {
            // If the flow is cancelled before completion, the conversation state is incomplete.
            // In that case, discard and close the conversation.
            if (!isCompleted) {
              synchronized(activeConversation) { activeConversation.clear() }
            }
          }
        }
      }
    } else {
      return flow {
        conversationMutex.withLock {
          val (conversation, liteRtLmLastMessage) =
            try {
              getOrCreateConversation(request)
            } catch (e: Exception) {
              emit(LlmResponse(errorMessage = e.message ?: e.toString()))
              return@withLock
            }

          try {
            val responseMessage = conversation.sendMessage(liteRtLmLastMessage)
            val response = responseMessage.toLlmResponse(partial = false)
            // Update the cache key on successful generation completion to be the requests's
            // contents
            // followed by the model's generated response. This prepares the cache for the next
            // turn.
            response.content?.let { modelResponseContent ->
              synchronized(activeConversation) {
                activeConversation.update(conversation, request.contents + modelResponseContent)
              }
            }
            emit(response)
          } catch (e: Exception) {
            // Discard conversation on generation failures.
            synchronized(activeConversation) { activeConversation.clear() }
            emit(LlmResponse(errorMessage = e.message ?: e.toString()))
          }
        }
      }
    }
  }

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

    val conversation =
      synchronized(activeConversation) {
        if (activeConversation.matches(history)) {
          activeConversation.conversation!!
        } else {
          activeConversation.clear()

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
    synchronized(activeConversation) { activeConversation.clear() }
    if (ownsEngine) {
      engine.close()
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
        else -> "string"
      }
    map["type"] = typeName
  }

  description?.let { map["description"] = it }
  properties?.let { props -> map["properties"] = props.mapValues { (_, schema) -> schema.toMap() } }
  items?.let { map["items"] = it.toMap() }
  required?.let { map["required"] = it }
  enum?.let { map["enum"] = it }

  return map
}
