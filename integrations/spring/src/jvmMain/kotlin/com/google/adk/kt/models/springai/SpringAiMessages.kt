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
package com.google.adk.kt.models.springai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import java.net.URI
import java.net.URL
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.EmptyUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeType

/**
 * Metadata key the Spring AI Google GenAI provider uses to carry Gemini thought signatures. These
 * must be replayed on the model turn of the next request or tool calling breaks, so they survive
 * the ADK round trip. See https://ai.google.dev/gemini-api/docs/thought-signatures.
 */
private const val THOUGHT_SIGNATURES_KEY = "thoughtSignatures"

/** Metadata key the Spring AI Google GenAI provider uses to flag a generation as thought text. */
private const val IS_THOUGHT_KEY = "isThought"

/** Converts an ADK [LlmRequest] into a Spring AI [Prompt]. */
internal fun LlmRequest.toSpringAiPrompt(defaultOptions: ChatOptions?): Prompt {
  val systemTexts = mutableListOf<String>()
  config.systemInstruction?.let { instruction ->
    instruction.textOrNull()?.let { systemTexts.add(it) }
  }

  val turnMessages = mutableListOf<Message>()
  for (content in contents) {
    when (content.role?.lowercase()) {
      Role.SYSTEM -> content.textOrNull()?.let { systemTexts.add(it) }
      Role.MODEL,
      "assistant" -> turnMessages.add(content.toAssistantMessage())
      else -> turnMessages.addAll(content.toUserMessages())
    }
  }

  val messages = buildList {
    if (systemTexts.isNotEmpty()) add(SystemMessage(systemTexts.joinToString("\n\n")))
    addAll(turnMessages)
  }

  val options = buildChatOptions(config, toolCallbacks(), defaultOptions)
  return if (options != null) Prompt(messages, options) else Prompt(messages)
}

/** Converts a Spring AI [ChatResponse] into an ADK [LlmResponse]. */
internal fun ChatResponse?.toLlmResponse(): LlmResponse {
  // Do not early-return on empty results: a streaming terminal chunk carries usage with no parts,
  // and the parts loop below already tolerates an empty results list.
  if (this == null) return LlmResponse()

  // Merge parts across every Generation: the Google GenAI provider returns one Generation per
  // response part, so reading only the first drops later text and function-call parts.
  val parts = buildList {
    for (generation in results) {
      val assistant = generation.output
      val signatures = assistant.thoughtSignatures()
      // Carry the thought flag so reasoning text is not replayed as content on the next turn (the
      // request side filters it); the provider marks a thinking generation via isThought metadata.
      val isThought = assistant.metadata?.get(IS_THOUGHT_KEY) as? Boolean == true
      assistant.text
        ?.takeIf { it.isNotEmpty() }
        ?.let { add(Part(text = it, thought = if (isThought) true else null)) }
      assistant.media.forEach { media -> media.toPart()?.let { add(it) } }
      // Thought signatures map positionally: the provider emits one per function-call part, in
      // order, so the index into signatures matches the function-call index.
      assistant.toolCalls
        .filter { it.type() == "function" }
        .forEachIndexed { index, toolCall ->
          add(
            Part(
              functionCall =
                FunctionCall(
                  name = toolCall.name(),
                  args = parseToolArgs(toolCall.arguments()),
                  id = toolCall.id(),
                ),
              thoughtSignature = signatures.getOrNull(index),
            )
          )
        }
    }
  }

  val finishReason =
    results
      .firstNotNullOfOrNull { it.metadata?.finishReason?.takeIf { reason -> reason.isNotBlank() } }
      ?.toFinishReason()
  return LlmResponse(
    content = if (parts.isEmpty()) null else Content(role = Role.MODEL, parts = parts),
    usageMetadata = metadata?.usage?.takeIf { it !is EmptyUsage }?.toUsageMetadata(),
    finishReason = finishReason,
    // A non-STOP finish reason surfaces as an error code and message (the reason name only, no
    // content), matching ADK's streaming aggregator and Gemini model instead of a silent non-STOP
    // turn.
    errorCode = finishReason?.takeIf { it != FinishReason.STOP }?.name,
    errorMessage = finishReason?.takeIf { it != FinishReason.STOP }?.name,
  )
}

/**
 * Parses a tool call's arguments JSON (already assembled by the provider) into a map. The literal
 * `null` and blank input mean "no arguments"; any other unparseable payload throws rather than
 * yielding empty arguments that ADK would run as a valid call. The raw arguments are never logged
 * or put in an exception (customer content).
 */
private fun parseToolArgs(raw: String?): Map<String, Any?> {
  if (raw.isNullOrBlank() || raw.trim() == "null") return emptyMap()
  return try {
    Json.fromJsonToMap(raw)
  } catch (_: RuntimeException) {
    // Rethrow shape only: the provider's parse exception can echo the raw arguments (customer
    // content), so neither its message nor the exception as a cause may be propagated.
    throw IllegalStateException("Failed to parse assembled tool-call arguments JSON.")
  }
}

private fun Content.toUserMessages(): List<Message> {
  val text = StringBuilder()
  val media = mutableListOf<Media>()
  val toolResponses = mutableListOf<ToolResponseMessage.ToolResponse>()

  for (part in parts) {
    val partText = part.text
    val functionResponse = part.functionResponse
    val inlineData = part.inlineData
    val fileData = part.fileData
    when {
      partText != null -> text.append(partText)
      functionResponse != null ->
        toolResponses.add(
          ToolResponseMessage.ToolResponse(
            functionResponse.id ?: "",
            functionResponse.name,
            Json.toJsonString(functionResponse.response),
          )
        )
      inlineData != null -> inlineData.toMedia()?.let { media.add(it) }
      fileData != null -> fileData.toMedia()?.let { media.add(it) }
    }
  }

  val messages = mutableListOf<Message>()
  // Emit the ToolResponseMessage first so tool results directly follow the model's tool-call turn,
  // then a UserMessage for any text or media (or an otherwise-empty turn). User text before the
  // tool results would reorder the turn relative to the model's tool call.
  if (toolResponses.isNotEmpty()) {
    messages.add(ToolResponseMessage.builder().responses(toolResponses).build())
  }
  if (text.isNotEmpty() || media.isNotEmpty() || toolResponses.isEmpty()) {
    messages.add(UserMessage.builder().text(text.toString()).media(media).build())
  }
  return messages
}

private fun Content.toAssistantMessage(): Message {
  val text = StringBuilder()
  val toolCalls = mutableListOf<AssistantMessage.ToolCall>()
  val signatures = mutableListOf<ByteArray>()
  val media = mutableListOf<Media>()

  for (part in parts) {
    val partText = part.text
    val functionCall = part.functionCall
    val inlineData = part.inlineData
    val fileData = part.fileData
    when {
      // Skip thought parts: they are the model's reasoning, not assistant content to replay.
      partText != null -> if (part.thought != true) text.append(partText)
      functionCall != null -> {
        toolCalls.add(
          AssistantMessage.ToolCall(
            functionCall.id ?: "",
            "function",
            functionCall.name,
            Json.toJsonString(functionCall.args),
          )
        )
        part.thoughtSignature?.let { signatures.add(it) }
      }
      inlineData != null -> inlineData.toMedia()?.let { media.add(it) }
      fileData != null -> fileData.toMedia()?.let { media.add(it) }
    }
  }

  if (toolCalls.isEmpty() && media.isEmpty()) return AssistantMessage(text.toString())
  val properties: Map<String, Any> =
    if (signatures.isEmpty()) emptyMap() else mapOf(THOUGHT_SIGNATURES_KEY to signatures)
  return AssistantMessage.builder()
    .content(text.toString())
    .properties(properties)
    .toolCalls(toolCalls)
    .media(media)
    .build()
}

private fun Content.textOrNull(): String? = text(includeThoughts = false).takeIf { it.isNotEmpty() }

// Maps a Spring AI [Media] back to an ADK [Part]. A reference-backed medium (Spring stores a URI as
// its String form, a URL as a URL) becomes fileData; inline bytes become inlineData. A medium with
// no MIME type or no readable data is skipped.
private fun Media.toPart(): Part? {
  val mime = mimeType?.toString() ?: return null
  return when (val raw = data) {
    is ByteArray -> Part(inlineData = Blob(mimeType = mime, data = raw))
    is URL -> Part(fileData = FileData(mimeType = mime, fileUri = raw.toString()))
    is String -> Part(fileData = FileData(mimeType = mime, fileUri = raw))
    else ->
      runCatching { dataAsByteArray }
        .getOrNull()
        ?.let { Part(inlineData = Blob(mimeType = mime, data = it)) }
  }
}

// Media parsing skips a part with an unparseable MIME type or URI rather than throwing, which also
// keeps a signed file URI or MIME string out of any exception message.
private fun Blob.toMedia(): Media? {
  val mime = mimeType?.toMimeTypeOrNull() ?: return null
  val bytes = data ?: return null
  return Media(mime, ByteArrayResource(bytes))
}

private fun FileData.toMedia(): Media? {
  val mime = mimeType?.toMimeTypeOrNull() ?: return null
  val uri = fileUri?.let { runCatching { URI.create(it) }.getOrNull() } ?: return null
  return Media(mime, uri)
}

private fun String.toMimeTypeOrNull(): MimeType? =
  runCatching { MimeType.valueOf(this) }.getOrNull()

private fun AssistantMessage.thoughtSignatures(): List<ByteArray> =
  (metadata?.get(THOUGHT_SIGNATURES_KEY) as? List<*>)?.filterIsInstance<ByteArray>() ?: emptyList()

private fun Usage.toUsageMetadata(): UsageMetadata =
  UsageMetadata(
    promptTokenCount = promptTokens,
    candidatesTokenCount = completionTokens,
    totalTokenCount = totalTokens,
  )

/**
 * Maps a provider finish-reason string to the ADK [FinishReason]. Gemini reasons match a
 * [FinishReason] name directly; the aliases cover other providers (OpenAI, Anthropic). An unknown
 * value falls back to OTHER, matching [FinishReason]'s own serializer.
 */
private fun String.toFinishReason(): FinishReason =
  when (lowercase()) {
    "stop",
    "tool_calls",
    "tool_use",
    "end_turn",
    "stop_sequence",
    "pause_turn",
    "function_call" -> FinishReason.STOP
    "length",
    "max_tokens" -> FinishReason.MAX_TOKENS
    "content_filter",
    "safety" -> FinishReason.SAFETY
    "recitation" -> FinishReason.RECITATION
    else -> runCatching { FinishReason.valueOf(uppercase()) }.getOrDefault(FinishReason.OTHER)
  }
