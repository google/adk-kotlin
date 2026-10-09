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
package com.google.adk.kt.models.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lenient serializer for the Chat Completions wire format: unknown fields are ignored so new server
 * fields do not break decoding, and null/default fields are omitted from request bodies.
 */
internal val chatCompletionsJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
  explicitNulls = false
  isLenient = true
}

/** A request to the Chat Completions API (`POST /chat/completions`). */
@Serializable
internal data class ChatCompletionRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val tools: List<ChatTool>? = null,
  @SerialName("tool_choice") val toolChoice: String? = null,
  @SerialName("response_format") val responseFormat: ChatResponseFormat? = null,
  val temperature: Float? = null,
  @SerialName("top_p") val topP: Float? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
  val stop: List<String>? = null,
  @SerialName("presence_penalty") val presencePenalty: Float? = null,
  @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
  val seed: Int? = null,
  val stream: Boolean? = null,
  @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

/** Structured output: `json_object` for any JSON, or `json_schema` with [jsonSchema]. */
@Serializable
internal data class ChatResponseFormat(
  val type: String,
  @SerialName("json_schema") val jsonSchema: ChatJsonSchema? = null,
)

/** A named JSON Schema for structured output. */
@Serializable
internal data class ChatJsonSchema(val name: String, val schema: JsonObject, val strict: Boolean)

/** Streaming options; `includeUsage` asks the server to emit a final usage-only chunk. */
@Serializable
internal data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

/**
 * A chat message. Also models a streaming `delta`, where `role` may be absent and `content` and
 * `toolCalls` arrive in fragments. `content` is a string, or a list of text and image parts.
 */
@Serializable
internal data class ChatMessage(
  val role: String? = null,
  val content: JsonElement? = null,
  @SerialName("tool_calls") val toolCalls: List<ChatToolCall>? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** The text of a message's `content`, joining the text parts when it is a list. */
internal fun ChatMessage.contentText(): String? =
  when (val content = content) {
    is JsonPrimitive -> content.contentOrNull
    is JsonArray ->
      content
        .mapNotNull { part ->
          (part as? JsonObject)
            ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
        }
        .joinToString("")
    else -> null
  }

/** A tool call. `index` is present only in streaming deltas, to correlate argument fragments. */
@Serializable
internal data class ChatToolCall(
  val id: String? = null,
  val index: Int? = null,
  val type: String? = null,
  val function: ChatFunctionCall? = null,
  @SerialName("extra_content") val extraContent: ChatExtraContent? = null,
)

/**
 * Provider data on a tool call. Gemini puts the call's thought signature here and rejects a later
 * request whose history leaves it out.
 */
@Serializable internal data class ChatExtraContent(val google: GoogleExtraContent? = null)

/** Gemini's data on a tool call; [thoughtSignature] is base64. */
@Serializable
internal data class GoogleExtraContent(
  @SerialName("thought_signature") val thoughtSignature: String? = null
)

/** The name (sent once) and JSON-encoded arguments (streamed in fragments) of a tool call. */
@Serializable
internal data class ChatFunctionCall(val name: String? = null, val arguments: String? = null)

/** A tool the model may call. */
@Serializable
internal data class ChatTool(
  // Always emitted: the API requires `type` on every tool, and `encodeDefaults` is off here.
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
  val function: ChatFunctionDef,
)

/** A function tool definition. `parameters` is a JSON Schema object with lowercase types. */
@Serializable
internal data class ChatFunctionDef(
  val name: String,
  val description: String? = null,
  val parameters: JsonObject,
)

/** A non-streaming Chat Completions response, and the envelope of a streaming chunk. */
@Serializable
internal data class ChatCompletionResponse(
  val model: String? = null,
  val choices: List<ChatChoice> = emptyList(),
  val usage: ChatUsage? = null,
  // OpenAI-compatible endpoints may report a failure mid-stream as a `data: {"error": ...}` event.
  val error: ChatError? = null,
)

/** One choice: `message` on a non-streaming response, `delta` on a streaming chunk. */
@Serializable
internal data class ChatChoice(
  val message: ChatMessage? = null,
  val delta: ChatMessage? = null,
  @SerialName("finish_reason") val finishReason: String? = null,
)

/** An error payload, which may arrive as a standalone SSE event on a stream. */
@Serializable internal data class ChatError(val type: String? = null, val code: String? = null)

/** Token accounting. */
@Serializable
internal data class ChatUsage(
  @SerialName("prompt_tokens") val promptTokens: Int? = null,
  @SerialName("completion_tokens") val completionTokens: Int? = null,
  @SerialName("total_tokens") val totalTokens: Int? = null,
  @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
  @SerialName("completion_tokens_details")
  val completionTokensDetails: CompletionTokensDetails? = null,
)

/** The cached part of the prompt tokens. */
@Serializable
internal data class PromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int? = null)

/** The reasoning part of the completion tokens. */
@Serializable
internal data class CompletionTokensDetails(
  @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)
