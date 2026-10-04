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
@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.models.anthropic

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import com.anthropic.models.messages.Usage
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.toJsonSchema
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.UsageMetadata
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.hours

private val logger = LoggerFactory.getLogger(Claude::class)

// Each warning is logged once per process, as Python's `warnings.warn` shows it once.
private val warnedThinkingLevel = AtomicBoolean()
private val warnedSampling = AtomicBoolean()

/**
 * A Vertex AI resource name, read as the Python ADK reads it; groups 1 to 3 are the project,
 * location, and model id.
 */
internal val VERTEX_RESOURCE_NAME =
  Regex("^projects/([^/]+)/locations/([^/]+)/(?:publishers/anthropic/models|endpoints)/([^/:]+)")

private val IMAGE_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

private const val PDF_MEDIA_TYPE = "application/pdf"

/** Output-schema keywords Claude rejects; it also rejects a `minItems` above 1. */
private val UNSUPPORTED_SCHEMA_KEYWORDS =
  setOf(
    "minimum",
    "maximum",
    "minLength",
    "maxLength",
    "maxItems",
    "minProperties",
    "maxProperties",
  )

/** The string formats Claude's structured output supports. */
private val SUPPORTED_STRING_FORMATS =
  setOf("date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid")

/** Stop reasons that cut generation off, possibly in the middle of the last content block. */
private val TRUNCATING_STOP_REASONS =
  setOf(StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)

/** Builds the Messages API params for an ADK [LlmRequest]. */
internal fun LlmRequest.toMessageCreateParams(
  modelName: String,
  defaultMaxTokens: Int,
  effort: OutputConfig.Effort?,
): MessageCreateParams {
  val toolUseIds = ToolUseIds()
  val cacheControl = cacheControl()
  val messages = contents.mapNotNull { it.toMessageParam(toolUseIds) }
  val builder =
    MessageCreateParams.builder()
      .model(modelId(modelName))
      .maxTokens((config.maxOutputTokens ?: defaultMaxTokens).toLong())
      .messages(if (cacheControl == null) messages else messages.withCacheBreakpoint(cacheControl))
  config.systemInstruction
    ?.text("\n")
    ?.ifBlank { null }
    ?.let { system ->
      if (cacheControl == null) {
        builder.system(system)
      } else {
        builder.systemOfTextBlockParams(
          listOf(TextBlockParam.builder().text(system).cacheControl(cacheControl).build())
        )
      }
    }
  val tools =
    config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }.map { it.toTool() }
  if (tools.isNotEmpty()) {
    val marked =
      if (cacheControl == null) {
        tools
      } else {
        tools.dropLast(1) + tools.last().toBuilder().cacheControl(cacheControl).build()
      }
    builder.tools(marked.map { ToolUnion.ofTool(it) })
    // Only `auto` for now; explicit tool-choice modes are a follow-up.
    builder.toolChoice(ToolChoiceAuto.builder().build())
  }
  val thinking = config.thinkingConfig?.toThinkingParam()
  if (thinking != null) builder.thinking(thinking)
  if (
    config.thinkingConfig?.thinkingLevel != null && warnedThinkingLevel.compareAndSet(false, true)
  ) {
    logger.warn { "Claude ignores ThinkingConfig.thinkingLevel; set the model's effort instead." }
  }
  val format = config.responseSchema?.toOutputFormat()
  if (effort != null || format != null) {
    val outputConfig = OutputConfig.builder()
    if (effort != null) outputConfig.effort(effort)
    if (format != null) outputConfig.format(format)
    builder.outputConfig(outputConfig.build())
  }
  // Newer models reject sampling parameters while reasoning, so they are dropped as in Python.
  if (effort != null || (thinking != null && !thinking.isDisabled())) {
    val samplingSet = config.temperature != null || config.topP != null || config.topK != null
    if (samplingSet && warnedSampling.compareAndSet(false, true)) {
      logger.warn { "Claude ignores temperature, topP, and topK while thinking or effort is on." }
    }
  } else {
    // Deprecated because newer models reject them; still sent when set, as the Python ADK does.
    @Suppress("DEPRECATION")
    run {
      config.temperature?.let { builder.temperature(it.toDouble()) }
      config.topP?.let { builder.topP(it.toDouble()) }
      config.topK?.let { builder.topK(it.toLong()) }
    }
  }
  config.stopSequences?.let { builder.stopSequences(it) }
  return builder.build()
}

/** The bare model id, read from a Vertex AI resource name when [modelName] is one. */
private fun modelId(modelName: String): String =
  VERTEX_RESOURCE_NAME.find(modelName)?.groupValues?.get(3) ?: modelName

/**
 * Maps an ADK [ThinkingConfig] to Claude's thinking mode as the Python ADK does: a budget of 0
 * turns thinking off, a negative one lets the model pick its depth, and a positive one caps it.
 */
private fun ThinkingConfig.toThinkingParam(): ThinkingConfigParam {
  val budget =
    requireNotNull(thinkingBudget) {
      "Claude needs ThinkingConfig.thinkingBudget: 0 turns thinking off, -1 lets the model choose" +
        " its depth, and a positive value of at least 1024 sets a token budget."
    }
  return when {
    budget == 0 -> ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build())
    // Newer models accept only adaptive thinking, and without `display` Claude redacts it.
    budget < 0 ->
      ThinkingConfigParam.ofAdaptive(
        ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build()
      )
    else -> ThinkingConfigParam.ofEnabled(budget.toLong())
  }
}

/** Maps an ADK response schema to Claude's structured JSON output format. */
private fun Schema.toOutputFormat(): JsonOutputFormat {
  val schema = jsonElementToAny(toJsonSchema()).toClaudeOutputSchema().asJsonSchemaMap()
  return JsonOutputFormat.builder()
    .schema(
      JsonOutputFormat.Schema.builder()
        .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
        .build()
    )
    .build()
}

/**
 * Adapts a JSON Schema to Claude's structured output as Anthropic's SDKs do: objects forbid
 * undeclared properties, and keywords Claude rejects move into the description. Claude ignores
 * `nullable`, so a nullable schema gets an explicit `null` option.
 */
private fun Any?.toClaudeOutputSchema(): Any? {
  if (this !is Map<*, *>) return this
  val rejected = mutableListOf<String>()
  val schema = buildMap {
    for ((key, value) in this@toClaudeOutputSchema) {
      if (key == "nullable") continue
      if (isRejectedKeyword(key, value)) {
        rejected += "$key: $value"
        continue
      }
      this[key] =
        when (key) {
          "properties" -> (value as Map<*, *>).mapValues { it.value.toClaudeOutputSchema() }
          "items" -> value.toClaudeOutputSchema()
          "anyOf" -> (value as List<*>).map { it.toClaudeOutputSchema() }
          else -> value
        }
    }
    if (rejected.isNotEmpty()) {
      val note = rejected.joinToString(", ", prefix = "(", postfix = ")")
      this["description"] = listOfNotNull(this["description"], note).joinToString(" ")
    }
    if (this["type"] == "object") this["additionalProperties"] = false
  }
  return if (this["nullable"] == true) schema.allowingNull() else schema
}

/** This schema with `null` added to the values it allows. */
private fun Map<Any?, Any?>.allowingNull(): Map<Any?, Any?> {
  val type = this["type"]
  val enum = this["enum"] as? List<*>
  val anyOf = this["anyOf"] as? List<*>
  return when {
    // Claude rejects an enum under a type list; the enum alone already sets the values.
    enum != null -> this - "type" + ("enum" to enum + null)
    type is String -> this + ("type" to listOf(type, "null"))
    anyOf != null -> this + ("anyOf" to anyOf + mapOf("type" to "null"))
    else -> this
  }
}

private fun isRejectedKeyword(key: Any?, value: Any?): Boolean =
  key in UNSUPPORTED_SCHEMA_KEYWORDS ||
    (key == "minItems" && ((value as? Number)?.toLong() ?: 0) > 1) ||
    (key == "format" && value !in SUPPORTED_STRING_FORMATS)

/** A JSON Schema is always a JSON object, which converts to a string-keyed map. */
@Suppress("UNCHECKED_CAST")
private fun Any?.asJsonSchemaMap(): Map<String, Any?> = this as Map<String, Any?>

/**
 * The cache breakpoint for this request, or null when it is not cached: without a cache config, or
 * when the previous prompt was smaller than [ContextCacheConfig.minTokens].
 */
private fun LlmRequest.cacheControl(): CacheControlEphemeral? {
  val cache = cacheConfig ?: return null
  val previousPromptTokens = cacheableContentsTokenCount
  if (previousPromptTokens != null && previousPromptTokens < cache.minTokens) return null
  // An hour is the longest Claude keeps a prefix; a shorter lifetime gets its 5-minute default.
  return CacheControlEphemeral.builder()
    .apply { if (cache.ttl >= 1.hours) ttl(CacheControlEphemeral.Ttl.TTL_1H) }
    .build()
}

/**
 * Marks the last block Claude can cache, so the next turn reads the conversation so far from the
 * cache.
 */
private fun List<MessageParam>.withCacheBreakpoint(
  cacheControl: CacheControlEphemeral
): List<MessageParam> {
  for (i in indices.reversed()) {
    val blocks = this[i].content().asBlockParams()
    for (j in blocks.indices.reversed()) {
      val marked = blocks[j].withCacheControl(cacheControl) ?: continue
      val message =
        this[i].toBuilder().contentOfBlockParams(blocks.toMutableList().apply { set(j, marked) })
      return toMutableList().apply { set(i, message.build()) }
    }
  }
  return this
}

/** This block with a cache breakpoint, or null for a thinking block, which cannot take one. */
private fun ContentBlockParam.withCacheControl(
  cacheControl: CacheControlEphemeral
): ContentBlockParam? =
  when {
    isText() -> ContentBlockParam.ofText(asText().toBuilder().cacheControl(cacheControl).build())
    isImage() -> ContentBlockParam.ofImage(asImage().toBuilder().cacheControl(cacheControl).build())
    isDocument() ->
      ContentBlockParam.ofDocument(asDocument().toBuilder().cacheControl(cacheControl).build())
    isToolUse() ->
      ContentBlockParam.ofToolUse(asToolUse().toBuilder().cacheControl(cacheControl).build())
    isToolResult() ->
      ContentBlockParam.ofToolResult(asToolResult().toBuilder().cacheControl(cacheControl).build())
    else -> null
  }

/**
 * Converts an ADK [Content] to a Messages API turn, or null when it yields no blocks, since the API
 * rejects a message with empty content.
 */
private fun Content.toMessageParam(toolUseIds: ToolUseIds): MessageParam? {
  val isModel =
    role.equals(Role.MODEL, ignoreCase = true) || role.equals("assistant", ignoreCase = true)
  // Claude takes no images or PDFs in assistant turns, so the Python ADK drops them there.
  val blocks =
    parts
      .filterNot { isModel && it.inlineData?.mediaType()?.isImageOrPdf() == true }
      .mapNotNull { it.toContentBlockParam(toolUseIds) }
  if (blocks.isEmpty()) return null
  return MessageParam.builder()
    .role(if (isModel) MessageParam.Role.ASSISTANT else MessageParam.Role.USER)
    .contentOfBlockParams(blocks)
    .build()
}

/** The MIME type without parameters, in lower case. */
private fun Blob.mediaType(): String? = mimeType?.substringBefore(';')?.trim()?.lowercase()

private fun String.isImageOrPdf(): Boolean = startsWith("image/") || this == PDF_MEDIA_TYPE

/**
 * Converts a [Part] to a content block, or null for a part with nothing to send: an unsigned
 * thought without a call or result, empty text (which the API rejects), or a thought signature with
 * no content.
 */
private fun Part.toContentBlockParam(toolUseIds: ToolUseIds): ContentBlockParam? {
  // Locals, since properties from another module cannot be smart cast.
  val partText = text
  val call = functionCall
  val response = functionResponse
  val blob = inlineData
  val code = executableCode
  val codeResult = codeExecutionResult
  return when {
    // A thought-flagged call still goes out as tool_use, or its result would have no match.
    thought == true && call == null && response == null -> toThinkingBlock()
    // Empty text is falsy in the Python ADK, so other content on the same part still goes out.
    thought != true && !partText.isNullOrEmpty() -> ContentBlockParam.ofText(partText)
    call != null ->
      ContentBlockParam.ofToolUse(
        ToolUseBlockParam.builder()
          .id(toolUseIds.forCall(call.id, call.name))
          .name(call.name)
          .input(JsonValue.from(call.args))
          .build()
      )
    response != null ->
      ContentBlockParam.ofToolResult(
        ToolResultBlockParam.builder()
          .toolUseId(toolUseIds.forResult(response.id, response.name))
          .content(response.response.toToolResultText())
          .isError(false)
          .build()
      )
    blob != null -> blob.toMediaBlock()
    // Code execution from another model is sent as text, as the Python ADK does.
    code != null -> ContentBlockParam.ofText("Code:```python\n${code.code.orEmpty()}\n```")
    codeResult != null ->
      ContentBlockParam.ofText(
        "Execution Result:```code_output\n${codeResult.output.orEmpty()}\n```"
      )
    (partText != null || thoughtSignature != null) &&
      fileData == null &&
      toolCall == null &&
      toolResponse == null -> null
    else ->
      throw IllegalArgumentException(
        "Unsupported Part for Claude; file and server-side tool parts are not supported yet."
      )
  }
}

/**
 * Converts a signed thought to a thinking block, even with the empty text newer models return, so
 * Claude can continue from it unmodified; a thought with no text becomes a redacted block. An
 * unsigned thought is dropped, since Claude verifies every thinking block by its signature.
 */
private fun Part.toThinkingBlock(): ContentBlockParam? {
  val signature = thoughtSignature?.toString(Charsets.UTF_8)?.ifEmpty { null } ?: return null
  val thinking = text ?: return ContentBlockParam.ofRedactedThinking(signature)
  return ContentBlockParam.ofThinking(
    ThinkingBlockParam.builder().thinking(thinking).signature(signature).build()
  )
}

/**
 * Converts an inline image or PDF to a base64 content block; Claude takes no other inline media.
 */
private fun Blob.toMediaBlock(): ContentBlockParam {
  val bytes = requireNotNull(data) { "An image or PDF part for Claude needs its data inline." }
  val mediaType = mediaType()
  return when {
    mediaType == PDF_MEDIA_TYPE ->
      ContentBlockParam.ofDocument(DocumentBlockParam.Source.ofBase64(bytes.toBase64()))
    mediaType != null && mediaType in IMAGE_MEDIA_TYPES ->
      ContentBlockParam.ofImage(
        ImageBlockParam.Source.ofBase64(
          Base64ImageSource.builder()
            .data(bytes.toBase64())
            .mediaType(Base64ImageSource.MediaType.of(mediaType))
            .build()
        )
      )
    else ->
      throw IllegalArgumentException(
        "Unsupported media for Claude; it takes JPEG, PNG, GIF, and WebP images and PDF documents."
      )
  }
}

private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

/**
 * Keeps `tool_use` and `tool_result` ids paired when history ids are missing or invalid, such as a
 * call made by another model, as the Python ADK does. One instance covers one request's history.
 */
private class ToolUseIds {
  private class PendingCall(val name: String, val id: String)

  private val remapped = mutableMapOf<String, String>()
  /** Id-less calls not yet answered, oldest first. */
  private val pending = mutableListOf<PendingCall>()
  private var nextFallback = 0

  /** Returns the id to send for a call, queuing a fresh fallback when the call has none. */
  fun forCall(id: String?, name: String): String =
    known(id) ?: newFallback().also { pending += PendingCall(name, it) }

  /**
   * Returns the id to send for a result. One without an id takes the oldest pending call with the
   * same name, else the oldest pending call.
   */
  fun forResult(id: String?, name: String): String {
    known(id)?.let {
      return it
    }
    if (pending.isEmpty()) return newFallback()
    val index = pending.indexOfFirst { it.name == name }.coerceAtLeast(0)
    return pending.removeAt(index).id
  }

  /** A valid id as-is, an invalid one remapped consistently, or null when the id is missing. */
  private fun known(id: String?): String? =
    when {
      id.isNullOrEmpty() -> null
      VALID_TOOL_USE_ID.matches(id) -> id
      else -> remapped.getOrPut(id) { newFallback() }
    }

  private fun newFallback(): String = "toolu_fallback_${nextFallback++}"

  companion object {
    /** The pattern the API requires of `tool_use` ids. */
    private val VALID_TOOL_USE_ID = Regex("[a-zA-Z0-9_-]+")
  }
}

/**
 * Renders a function response as `tool_result` text, as the Python ADK does: a `content` field is
 * sent as-is, ADK's `{"result": value}` wrapper for a non-map tool return is unwrapped, and any
 * other map is serialized as JSON.
 */
private fun Map<String, Any?>.toToolResultText(): String {
  val content = this["content"]
  val result = this["result"]
  return when {
    content is List<*> && content.isNotEmpty() ->
      content.joinToString("\n") { item ->
        if (item is Map<*, *> && item["type"] == "text" && item["text"] != null) {
          item["text"].toString()
        } else {
          item.toJsonText()
        }
      }
    content is String && content.isNotEmpty() -> content
    keys == setOf("result") && result != null -> result.toJsonText()
    isNotEmpty() -> Json.toJsonString(this)
    else -> ""
  }
}

/** A string as-is, anything else as JSON. */
private fun Any?.toJsonText(): String = this as? String ?: Json.toJsonString(this)

/**
 * Maps an ADK [FunctionDeclaration] to a Messages API tool; the input schema is always an object.
 */
private fun FunctionDeclaration.toTool(): Tool {
  val schema = parameters?.let { jsonElementToAny(it.toJsonSchema()).asJsonSchemaMap() }.orEmpty()
  return Tool.builder()
    .name(name)
    .description(description)
    .inputSchema(
      JsonValue.from(
        mapOf("properties" to emptyMap<String, Any?>()) + schema + ("type" to "object")
      )
    )
    .build()
}

/** Converts a complete Messages API response to an ADK [LlmResponse]. */
internal fun Message.toLlmResponse(): LlmResponse {
  val stopReason = stopReason().getOrNull()
  val blocks = content()
  // A cut-off response can end in a tool_use with partial input, which must not run.
  val truncated = stopReason in TRUNCATING_STOP_REASONS && blocks.lastOrNull()?.isToolUse() == true
  val parts = (if (truncated) blocks.dropLast(1) else blocks).mapNotNull { it.toPart() }
  val finishReason = stopReason.toFinishReason()
  val nonStop = finishReason?.takeIf { it != FinishReason.STOP }
  return LlmResponse(
    content = if (parts.isEmpty()) null else Content(role = Role.MODEL, parts = parts),
    usageMetadata = usage().toUsageMetadata(),
    finishReason = finishReason,
    // Unlike Python, a finish other than STOP sets errorCode, as the Gemini model does.
    errorCode = nonStop?.name,
    errorMessage = nonStop?.let { "Claude stop reason: $stopReason" },
    modelVersion = model().asString(),
  )
}

/** Converts a response content block to an ADK [Part], or null for block types not yet handled. */
private fun ContentBlock.toPart(): Part? {
  text().getOrNull()?.let {
    return Part(text = it.text())
  }
  // Keep the signature so the next request can send the thought back to Claude.
  thinking().getOrNull()?.let {
    return Part(
      text = it.thinking(),
      thought = true,
      thoughtSignature = it.signature().ifEmpty { null }?.toByteArray(Charsets.UTF_8),
    )
  }
  redactedThinking().getOrNull()?.let {
    return Part(thought = true, thoughtSignature = it.data().toByteArray(Charsets.UTF_8))
  }
  toolUse().getOrNull()?.let {
    // Tool input is a JSON object; a literal `null` converts to null and becomes empty arguments.
    @Suppress("UNCHECKED_CAST")
    val args = it._input().convert(Map::class.java) as Map<String, Any?>?
    return Part(functionCall = FunctionCall(name = it.name(), args = args.orEmpty(), id = it.id()))
  }
  return null
}

/**
 * Folds the separately reported cache tokens into the genai-shaped [UsageMetadata] prompt count,
 * and splits the thinking tokens Claude counts as output into their own count.
 */
private fun Usage.toUsageMetadata(): UsageMetadata {
  val cacheRead = cacheReadInputTokens().getOrNull()?.toInt()
  val cacheCreation = cacheCreationInputTokens().getOrNull()?.toInt()
  val prompt = inputTokens().toInt() + (cacheRead ?: 0) + (cacheCreation ?: 0)
  val output = outputTokens().toInt()
  val thoughts = outputTokensDetails().getOrNull()?.thinkingTokens()?.toInt()?.coerceAtMost(output)
  return UsageMetadata(
    promptTokenCount = prompt,
    candidatesTokenCount = output - (thoughts ?: 0),
    totalTokenCount = prompt + output,
    thoughtsTokenCount = thoughts,
    cachedContentTokenCount = cacheRead,
  )
}

/**
 * Maps a Messages API `stop_reason` to an ADK [FinishReason] as the Python ADK does: null when
 * absent, and FINISH_REASON_UNSPECIFIED for any other value.
 */
private fun StopReason?.toFinishReason(): FinishReason? =
  when (this?.value()) {
    null -> null
    StopReason.Value.END_TURN,
    StopReason.Value.STOP_SEQUENCE,
    StopReason.Value.TOOL_USE,
    StopReason.Value.PAUSE_TURN -> FinishReason.STOP
    StopReason.Value.MAX_TOKENS -> FinishReason.MAX_TOKENS
    StopReason.Value.REFUSAL -> FinishReason.SAFETY
    else -> FinishReason.FINISH_REASON_UNSPECIFIED
  }
