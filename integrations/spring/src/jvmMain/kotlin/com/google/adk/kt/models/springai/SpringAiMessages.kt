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
  if (this == null || results.isEmpty()) return LlmResponse()

  val generation = results.first()
  val assistant = generation.output
  val signatures = assistant.thoughtSignatures()

  val parts = buildList {
    assistant.text?.takeIf { it.isNotEmpty() }?.let { add(Part(text = it)) }
    var functionCallIndex = 0
    for (toolCall in assistant.toolCalls) {
      if (toolCall.type() != "function") continue
      val functionCall =
        FunctionCall(
          name = toolCall.name(),
          args = parseToolArgs(toolCall.arguments()),
          id = toolCall.id(),
        )
      add(
        Part(
          functionCall = functionCall,
          thoughtSignature = signatures.getOrNull(functionCallIndex),
        )
      )
      functionCallIndex++
    }
  }

  val finishReason = generation.metadata?.finishReason?.takeIf { it.isNotBlank() }?.toFinishReason()
  return LlmResponse(
    content = if (parts.isEmpty()) null else Content(role = Role.MODEL, parts = parts),
    usageMetadata = metadata?.usage?.takeIf { it !is EmptyUsage }?.toUsageMetadata(),
    finishReason = finishReason,
    // Surface a non-STOP finish reason as an error code (the reason name only, no content), so the
    // blocking path matches ADK's streaming aggregator and Gemini model instead of returning a
    // silent non-STOP turn.
    errorCode = finishReason?.takeIf { it != FinishReason.STOP }?.name,
  )
}

/**
 * Parses tool-call arguments JSON to a map. Streaming providers may deliver arguments as partial
 * JSON fragments, so a parse failure yields an empty map rather than aborting the turn; the raw
 * arguments are never logged or put in an exception (customer content).
 */
// The declared return type is non-null, but Gson returns null (not a throw) for the JSON literal
// `null`, so the elvis is a real runtime guard rather than a useless one.
@Suppress("USELESS_ELVIS")
private fun parseToolArgs(raw: String?): Map<String, Any?> {
  if (raw.isNullOrBlank()) return emptyMap()
  return try {
    Json.fromJsonToMap(raw) ?: emptyMap()
  } catch (_: RuntimeException) {
    emptyMap()
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
  // Emit a UserMessage for any text or media, or for an otherwise-empty turn; when the turn carries
  // only function responses, emit just the ToolResponseMessage so the request does not end on the
  // model's tool-call turn (which providers reject).
  if (text.isNotEmpty() || media.isNotEmpty() || toolResponses.isEmpty()) {
    messages.add(UserMessage.builder().text(text.toString()).media(media).build())
  }
  if (toolResponses.isNotEmpty()) {
    messages.add(ToolResponseMessage.builder().responses(toolResponses).build())
  }
  return messages
}

private fun Content.toAssistantMessage(): Message {
  val text = StringBuilder()
  val toolCalls = mutableListOf<AssistantMessage.ToolCall>()
  val signatures = mutableListOf<ByteArray>()

  for (part in parts) {
    val partText = part.text
    val functionCall = part.functionCall
    when {
      partText != null -> text.append(partText)
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
    }
  }

  if (toolCalls.isEmpty()) return AssistantMessage(text.toString())
  val properties: Map<String, Any> =
    if (signatures.isEmpty()) emptyMap() else mapOf(THOUGHT_SIGNATURES_KEY to signatures)
  return AssistantMessage.builder()
    .content(text.toString())
    .properties(properties)
    .toolCalls(toolCalls)
    .build()
}

private fun Content.textOrNull(): String? =
  parts.mapNotNull { it.text }.joinToString("").takeIf { it.isNotEmpty() }

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

/** Maps a provider finish-reason string to the ADK [FinishReason], defaulting unknowns to OTHER. */
private fun String.toFinishReason(): FinishReason =
  when (lowercase()) {
    "stop",
    "tool_calls",
    "tool_use",
    "end_turn",
    "function_call" -> FinishReason.STOP
    "length",
    "max_tokens" -> FinishReason.MAX_TOKENS
    "content_filter",
    "safety" -> FinishReason.SAFETY
    "recitation" -> FinishReason.RECITATION
    else -> FinishReason.OTHER
  }
