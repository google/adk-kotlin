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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * A [Model] for OpenAI's Chat Completions API and the endpoints compatible with it, such as Gemini
 * and Vertex AI Model Garden models, xAI's Grok, Groq, Mistral, Together, or a local server.
 *
 * Supports streaming, function calling, structured output, image input, and token usage. A user
 * turn with audio or a non-image file fails with [IllegalArgumentException], and logprobs are not
 * returned.
 */
class ChatCompletions
internal constructor(private val client: ChatCompletionsClient, override val name: String) : Model {

  /**
   * Creates a [ChatCompletions] model backed by an OpenAI-compatible Chat Completions endpoint.
   *
   * @param name The model name, such as `gpt-4o`, or `google/gemini-3.1-flash-lite` on Vertex AI.
   * @param apiKey The API key, sent as a bearer token; a blank one counts as unset. Falls back to
   *   `OPENAI_API_KEY` only when [httpOptions] sets no base URL, so that key never goes to a base
   *   URL the caller sets.
   * @param httpOptions The base URL (`OPENAI_BASE_URL`, else OpenAI's, when unset), extra headers,
   *   and timeout, which bounds each unary attempt but only the start of a stream.
   * @throws IllegalArgumentException If [httpOptions] has an invalid `baseUrl`, a negative timeout,
   *   a header the client cannot send, or any `apiVersion`.
   * @throws IllegalStateException If OpenAI's own endpoint would be called with no key.
   */
  @JvmOverloads
  constructor(
    name: String,
    apiKey: String? = null,
    httpOptions: HttpOptions? = null,
  ) : this(apiKeyClient(apiKey, httpOptions), name)

  /**
   * Creates a [ChatCompletions] model that calls [apiKeyProvider] before each request, for a key
   * that expires, such as a Google Cloud access token.
   *
   * @param name The model name, such as `gpt-4o`, or `google/gemini-3.1-flash-lite` on Vertex AI.
   * @param httpOptions The base URL (`OPENAI_BASE_URL`, else OpenAI's, when unset), extra headers,
   *   and timeout, which bounds each unary attempt but only the start of a stream.
   * @param apiKeyProvider Returns the API key, sent as a bearer token.
   * @throws IllegalArgumentException If [httpOptions] has an invalid `baseUrl`, a negative timeout,
   *   a header the client cannot send, or any `apiVersion`.
   */
  @JvmOverloads
  constructor(
    name: String,
    httpOptions: HttpOptions? = null,
    apiKeyProvider: () -> String,
  ) : this(
    KtorChatCompletionsClient({ bearerAuth(apiKeyProvider()) }, withEnvBaseUrl(httpOptions)),
    name,
  )

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    generate(request, stream).catch { error ->
      // Add mitigation advice to a quota error, as the Gemini model does.
      if (error is ChatCompletionsApiException && error.statusCode == 429) {
        throw ChatCompletionsApiException("$RESOURCE_EXHAUSTED_FIX\n\n${error.message}", error, 429)
      }
      throw error
    }

  @OptIn(FrameworkInternalApi::class)
  private fun generate(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val chatRequest = request.toChatCompletionRequest(name)
    logger.debug { "Chat Completions request: ${request.contents.size} contents, stream=$stream" }
    if (stream) {
      val aggregator = StreamingResponseAggregator()
      val state = ChatCompletionsStreamState()
      // Only content goes out as a partial; the finish reason and usage arrive on the aggregate.
      suspend fun forward(partials: List<LlmResponse>) {
        for (partial in partials) {
          val processed = aggregator.processResponse(partial)
          if (processed.content != null) emit(processed)
        }
      }
      client.createStream(chatRequest).collect { forward(state.onChunk(it)) }
      forward(state.finish())
      aggregator.aggregate()?.let { emit(it) }
    } else {
      emit(client.create(chatRequest).toLlmResponse())
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(ChatCompletions::class)

    /**
     * Creates a [ChatCompletions] model that calls [headersProvider] before each request, for an
     * authorization scheme other than a bearer key, such as a custom header or a signed token.
     *
     * @param name The model name, such as `gpt-4o`, or `google/gemini-3.1-flash-lite` on Vertex AI.
     * @param httpOptions The base URL (`OPENAI_BASE_URL`, else OpenAI's, when unset), headers that
     *   replace provided ones of the same name, and timeout, which bounds each unary attempt but
     *   only the start of a stream.
     * @param headersProvider Returns the headers to send with each request, such as
     *   `Authorization`.
     * @throws IllegalArgumentException If [httpOptions] has an invalid `baseUrl`, a negative
     *   timeout, a header the client cannot send, or any `apiVersion`.
     */
    @JvmStatic
    @JvmOverloads
    fun withHeadersProvider(
      name: String,
      httpOptions: HttpOptions? = null,
      headersProvider: () -> Map<String, String>,
    ): ChatCompletions =
      ChatCompletions(KtorChatCompletionsClient(headersProvider, withEnvBaseUrl(httpOptions)), name)
  }
}

/** Builds the client for the API-key constructor. */
private fun apiKeyClient(apiKey: String?, httpOptions: HttpOptions?): ChatCompletionsClient {
  val key = apiKeyOrDefault(apiKey, httpOptions)
  return KtorChatCompletionsClient({ bearerAuth(key) }, withEnvBaseUrl(httpOptions))
}

/**
 * [apiKey], or `OPENAI_API_KEY` when [httpOptions] sets no base URL, so that key never goes to a
 * base URL the caller sets. Fails rather than call OpenAI's own endpoint with no key, as the OpenAI
 * SDKs do; other servers, such as a local one, may need none.
 */
internal fun apiKeyOrDefault(
  apiKey: String?,
  httpOptions: HttpOptions?,
  env: (String) -> String? = System::getenv,
): String? {
  val key =
    apiKey?.ifBlank { null }
      ?: env("OPENAI_API_KEY")?.ifBlank { null }?.takeIf { httpOptions?.baseUrl == null }
  check(key != null || withEnvBaseUrl(httpOptions, env)?.baseUrl != null) {
    "ChatCompletions found no OpenAI API key: pass apiKey or set OPENAI_API_KEY."
  }
  return key
}

/**
 * [httpOptions] with the base URL from `OPENAI_BASE_URL` when it sets none, as the OpenAI SDKs do.
 */
internal fun withEnvBaseUrl(
  httpOptions: HttpOptions?,
  env: (String) -> String? = System::getenv,
): HttpOptions? {
  if (httpOptions?.baseUrl != null) return httpOptions
  val baseUrl = env("OPENAI_BASE_URL")?.ifBlank { null } ?: return httpOptions
  return (httpOptions ?: HttpOptions()).copy(baseUrl = baseUrl)
}

/**
 * Reassembles Chat Completions streaming chunks into partial [LlmResponse]s for the
 * [StreamingResponseAggregator]. Content deltas stream through directly; tool-call arguments arrive
 * as JSON fragments buffered per index and flushed once a `finish_reason` arrives.
 */
private class ChatCompletionsStreamState {
  private var modelVersion: String? = null
  private val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()

  fun onChunk(chunk: ChatCompletionResponse): List<LlmResponse> {
    chunk.error?.let {
      throw ChatCompletionsApiException(
        "Chat Completions stream error: ${it.type ?: it.code ?: "error"}"
      )
    }
    chunk.model?.let { modelVersion = it }
    val out = mutableListOf<LlmResponse>()
    val choice = chunk.choices.firstOrNull()
    val delta = choice?.delta
    delta?.contentText()?.takeIf { it.isNotEmpty() }?.let { out += textPartial(it) }
    delta?.toolCalls?.forEach { accumulate(it) }
    // Some servers send an empty `finish_reason` on every chunk before the real one.
    choice
      ?.finishReason
      ?.takeIf { it.isNotEmpty() }
      ?.let { reason ->
        val dropped = flushToolCalls(out)
        out += finishPartial(reason.toFinishReason(droppedMalformedCall = dropped), reason)
      }
    chunk.usage?.let {
      out +=
        LlmResponse(
          usageMetadata = it.toUsageMetadata(),
          modelVersion = modelVersion,
          partial = true,
        )
    }
    return out
  }

  private fun accumulate(toolCall: ChatToolCall) {
    // Some servers repeat an empty id or name on later chunks; neither may erase the first.
    val id = toolCall.id?.takeIf { it.isNotEmpty() }
    // Without an index, a delta with a new id starts a call and any other continues the last one.
    val startsCall = id != null && id != toolCalls.values.lastOrNull()?.id
    val index =
      toolCall.index ?: if (startsCall) toolCalls.size else (toolCalls.size - 1).coerceAtLeast(0)
    val accumulator = toolCalls.getOrPut(index) { ToolCallAccumulator() }
    id?.let { accumulator.id = it }
    toolCall.function?.name?.takeIf { it.isNotEmpty() }?.let { accumulator.name = it }
    toolCall.function?.arguments?.let { accumulator.arguments.append(it) }
    toolCall.extraContent.thoughtSignature()?.let { accumulator.thoughtSignature = it }
  }

  /**
   * Flushes tool calls left when the stream ends without a `finish_reason`, reporting a dropped
   * malformed call as MALFORMED_FUNCTION_CALL.
   */
  fun finish(): List<LlmResponse> {
    val out = mutableListOf<LlmResponse>()
    if (flushToolCalls(out)) out += finishPartial(FinishReason.MALFORMED_FUNCTION_CALL, null)
    return out
  }

  /**
   * Adds each buffered tool call to [out] as a partial and clears the buffer. A call with no name
   * or with malformed or truncated arguments is dropped rather than run; returns whether one was.
   */
  private fun flushToolCalls(out: MutableList<LlmResponse>): Boolean {
    var dropped = false
    for (accumulator in toolCalls.values) {
      val part =
        functionCallPart(
          accumulator.name,
          accumulator.arguments.toString(),
          accumulator.id,
          accumulator.thoughtSignature,
        )
      if (part == null) {
        dropped = true
        continue
      }
      out +=
        LlmResponse(
          content = Content(role = Role.MODEL, parts = listOf(part)),
          modelVersion = modelVersion,
          partial = true,
        )
    }
    toolCalls.clear()
    return dropped
  }

  private fun finishPartial(finishReason: FinishReason?, reason: String?): LlmResponse =
    LlmResponse(
      finishReason = finishReason,
      errorMessage = finishErrorMessage(finishReason, reason),
      modelVersion = modelVersion,
      partial = true,
    )

  private fun textPartial(text: String): LlmResponse =
    LlmResponse(
      content = Content(role = Role.MODEL, parts = listOf(Part(text = text))),
      modelVersion = modelVersion,
      partial = true,
    )

  private class ToolCallAccumulator {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
    var thoughtSignature: ByteArray? = null
  }
}

private const val RESOURCE_EXHAUSTED_FIX =
  "You have exceeded the Chat Completions API rate limit. Reduce the request rate or " +
    "check your provider's quota."
