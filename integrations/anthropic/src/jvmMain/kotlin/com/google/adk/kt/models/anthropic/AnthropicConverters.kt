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
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import com.anthropic.models.messages.Usage
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.toJsonSchema
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.jvm.optionals.getOrNull

/** Stop reasons that cut generation off, possibly in the middle of the last content block. */
private val TRUNCATING_STOP_REASONS =
  setOf(StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)

/** Builds the Messages API params for an ADK [LlmRequest]. */
internal fun LlmRequest.toMessageCreateParams(
  modelName: String,
  defaultMaxTokens: Int,
): MessageCreateParams {
  // Failing beats returning text an agent's output schema would not match.
  require(config.responseSchema == null) {
    "Claude does not support an output schema (responseSchema) yet."
  }
  val toolUseIds = ToolUseIds()
  val builder =
    MessageCreateParams.builder()
      .model(modelName)
      .maxTokens((config.maxOutputTokens ?: defaultMaxTokens).toLong())
      .messages(contents.mapNotNull { it.toMessageParam(toolUseIds) })
  config.systemInstruction?.text("\n")?.ifBlank { null }?.let { builder.system(it) }
  val tools =
    config.tools
      .orEmpty()
      .flatMap { it.functionDeclarations.orEmpty() }
      .map { ToolUnion.ofTool(it.toTool()) }
  if (tools.isNotEmpty()) {
    builder.tools(tools)
    // Only `auto` for now; explicit tool-choice modes are a follow-up.
    builder.toolChoice(ToolChoiceAuto.builder().build())
  }
  // Deprecated because newer models reject them; still sent when set, as the Python ADK does.
  @Suppress("DEPRECATION")
  run {
    config.temperature?.let { builder.temperature(it.toDouble()) }
    config.topP?.let { builder.topP(it.toDouble()) }
    config.topK?.let { builder.topK(it.toLong()) }
  }
  config.stopSequences?.let { builder.stopSequences(it) }
  return builder.build()
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
      .filterNot { isModel && it.isImageOrPdf() }
      .mapNotNull { it.toContentBlockParam(toolUseIds) }
  if (blocks.isEmpty()) return null
  return MessageParam.builder()
    .role(if (isModel) MessageParam.Role.ASSISTANT else MessageParam.Role.USER)
    .contentOfBlockParams(blocks)
    .build()
}

private fun Part.isImageOrPdf(): Boolean {
  val mimeType = inlineData?.mimeType ?: return false
  return mimeType.startsWith("image/") || mimeType.substringBefore(';').trim() == "application/pdf"
}

/**
 * Converts a [Part] to a content block, or null for a part with nothing to send: a thought without
 * a call or result, empty text (which the API rejects), or a thought signature from another model
 * with no content.
 */
private fun Part.toContentBlockParam(toolUseIds: ToolUseIds): ContentBlockParam? {
  // Locals, since properties from another module cannot be smart cast.
  val partText = text
  val call = functionCall
  val response = functionResponse
  val code = executableCode
  val codeResult = codeExecutionResult
  return when {
    // A thought-flagged call still goes out as tool_use, or its result would have no match.
    thought == true && call == null && response == null -> null
    // Empty text is falsy in the Python ADK, so a call or result on the same part still goes out.
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
    // Code execution from another model is sent as text, as the Python ADK does.
    code != null -> ContentBlockParam.ofText("Code:```python\n${code.code.orEmpty()}\n```")
    codeResult != null ->
      ContentBlockParam.ofText(
        "Execution Result:```code_output\n${codeResult.output.orEmpty()}\n```"
      )
    (partText != null || thoughtSignature != null) &&
      inlineData == null &&
      fileData == null &&
      toolCall == null &&
      toolResponse == null -> null
    else ->
      throw IllegalArgumentException(
        "Unsupported Part for Claude; image, file, and server-side tool parts are not supported yet."
      )
  }
}

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
  // A JSON Schema is always a JSON object, which converts to a string-keyed map.
  @Suppress("UNCHECKED_CAST")
  val schema =
    parameters?.let { jsonElementToAny(it.toJsonSchema()) as Map<String, Any?> }.orEmpty()
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
  toolUse().getOrNull()?.let {
    // Tool input is a JSON object; a literal `null` converts to null and becomes empty arguments.
    @Suppress("UNCHECKED_CAST")
    val args = it._input().convert(Map::class.java) as Map<String, Any?>?
    return Part(functionCall = FunctionCall(name = it.name(), args = args.orEmpty(), id = it.id()))
  }
  return null
}

/**
 * Folds the separately reported cache tokens into the genai-shaped [UsageMetadata] prompt count.
 */
private fun Usage.toUsageMetadata(): UsageMetadata {
  val cacheRead = cacheReadInputTokens().getOrNull()?.toInt()
  val cacheCreation = cacheCreationInputTokens().getOrNull()?.toInt()
  val prompt = inputTokens().toInt() + (cacheRead ?: 0) + (cacheCreation ?: 0)
  val candidates = outputTokens().toInt()
  return UsageMetadata(
    promptTokenCount = prompt,
    candidatesTokenCount = candidates,
    totalTokenCount = prompt + candidates,
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
