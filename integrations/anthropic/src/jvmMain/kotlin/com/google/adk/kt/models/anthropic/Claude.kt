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
package com.google.adk.kt.models.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.RawContentBlockDelta
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import java.util.Optional
import java.util.concurrent.CompletionException
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await

/**
 * A [Model] backed by Anthropic's Claude models through the official Anthropic Java SDK.
 *
 * Supports unary and streaming generation, function (tool) calling, system instructions, and token
 * usage; extended thinking, image and document input, structured output, and prompt caching are not
 * yet handled. Pass a configured [AnthropicClient] to choose any backend the SDK supports.
 *
 * @property name The Claude model name (e.g. `claude-sonnet-4-5`).
 * @param client The Anthropic SDK client that sends the requests; the caller owns and closes it.
 * @param maxTokens The default `max_tokens` (8192), used when the request config sets none.
 */
class Claude
@JvmOverloads
constructor(
  override val name: String,
  private val client: AnthropicClient,
  private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : Model {

  /**
   * Creates a [Claude] model that calls the Anthropic API directly. The SDK client it creates is
   * closed when the model is garbage collected; pass your own [AnthropicClient] to close it sooner.
   *
   * @param name The Claude model name (e.g. `claude-sonnet-4-5`).
   * @param apiKey The Anthropic API key; falls back to the `ANTHROPIC_API_KEY` environment
   *   variable.
   * @param maxTokens The default `max_tokens` (8192), used when the request config sets none.
   */
  @JvmOverloads
  constructor(
    name: String,
    apiKey: String? = null,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
  ) : this(name, anthropicApiClient(apiKey), maxTokens)

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val params = request.toMessageCreateParams(name, maxTokens)
    logger.debug { "Claude request: ${request.contents.size} contents, stream=$stream" }
    val messages = client.async().messages()
    if (stream) {
      val accumulator = MessageAccumulator.create()
      var modelVersion: String? = null
      messages.createStreaming(params).asFlow().collect { event ->
        accumulator.accumulate(event)
        // The accumulator rejects a tool_use block with no argument deltas; Python reads it as {}.
        event
          .contentBlockStart()
          .getOrNull()
          ?.takeIf { it.contentBlock().isToolUse() }
          ?.let { accumulator.accumulate(emptyInputJsonDelta(it.index())) }
        event.messageStart().getOrNull()?.let { modelVersion = it.message().model().asString() }
        event.textDelta()?.let { emit(textPartial(it, modelVersion)) }
      }
      // The accumulated message is the whole turn, with blocks in the order the model sent them.
      emit(accumulator.message().toLlmResponse())
    } else {
      // Cancelling stops the wait, but the SDK's future cannot cancel the HTTP call it wraps.
      emit(messages.create(params).await().toLlmResponse())
    }
  }

  companion object {
    /** Default `max_tokens` when the request config sets none; the Messages API requires it. */
    private const val DEFAULT_MAX_TOKENS = 8192

    private val logger = LoggerFactory.getLogger(Claude::class)
  }
}

/**
 * Builds an SDK client for the Anthropic API, reading `ANTHROPIC_API_KEY` when [apiKey] is null.
 */
private fun anthropicApiClient(apiKey: String?): AnthropicClient {
  val builder = AnthropicOkHttpClient.builder().fromEnv()
  if (apiKey != null) builder.apiKey(apiKey)
  return builder.build()
}

private fun emptyInputJsonDelta(index: Long): RawMessageStreamEvent =
  RawMessageStreamEvent.ofContentBlockDelta(
    RawContentBlockDeltaEvent.builder()
      .index(index)
      .delta(RawContentBlockDelta.ofInputJson(""))
      .build()
  )

private fun RawMessageStreamEvent.textDelta(): String? =
  contentBlockDelta().getOrNull()?.delta()?.text()?.getOrNull()?.text()

private fun textPartial(text: String, modelVersion: String?): LlmResponse =
  LlmResponse(
    content = Content.fromText(Role.MODEL, text),
    modelVersion = modelVersion,
    partial = true,
  )

/** Bridges the SDK's callback stream to a [Flow]; cancelling the collector closes the stream. */
private fun <T> AsyncStreamResponse<T>.asFlow(): Flow<T> = callbackFlow {
  this@asFlow.subscribe(
    object : AsyncStreamResponse.Handler<T> {
      override fun onNext(value: T) {
        trySendBlocking(value)
      }

      override fun onComplete(error: Optional<Throwable>) {
        // Unwrap the future's wrapper so a stream failure surfaces the SDK's typed exception.
        val cause = error.getOrNull()
        close(if (cause is CompletionException) cause.cause ?: cause else cause)
      }
    }
  )
  awaitClose { this@asFlow.close() }
}
