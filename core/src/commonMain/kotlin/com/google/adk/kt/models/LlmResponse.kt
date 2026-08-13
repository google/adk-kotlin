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
package com.google.adk.kt.models

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.LogprobsResult
import com.google.adk.kt.types.UsageMetadata
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * LLM response class that provides the first candidate response from the model if available.
 * Otherwise, contains the error code and message.
 *
 * @property content The generative content of the response. This should only contain content from
 *   the user or the model, and not any framework or system-generated data.
 * @property usageMetadata The usage metadata of the LlmResponse.
 * @property finishReason The finish reason of the response.
 * @property errorMessage Error message if the response is an error.
 * @property partial Indicates whether the text content is part of an unfinished text stream. Only
 *   used for streaming mode and when the content is plain text.
 * @property interrupted Flag indicating that LLM was interrupted when generating the content.
 *   Usually it's due to user interruption during a bidi streaming.
 * @property modelVersion The model version used to generate the response.
 * @property citationMetadata The citation metadata of the response.
 * @property groundingMetadata The grounding metadata of the response.
 * @property errorCode Error code if the response is an error. The code varies by model.
 * @property customMetadata Optional key-value pairs labeling the response. The entire map must be
 *   JSON serializable.
 * @property cacheMetadata Context cache metadata for this response, populated when context caching
 *   is enabled. `null` when caching is disabled or no cache information is available.
 */
@Serializable
data class LlmResponse(
  val content: Content? = null,
  val usageMetadata: UsageMetadata? = null,
  val finishReason: FinishReason? = null,
  val errorMessage: String? = null,
  val partial: Boolean = false,
  val interrupted: Boolean = false,
  val modelVersion: String? = null,
  val citationMetadata: CitationMetadata? = null,
  val groundingMetadata: GroundingMetadata? = null,
  val errorCode: String? = null,
  val customMetadata: Map<String, @Contextual Any?>? = null,
  val avgLogprobs: Double? = null,
  val logprobsResult: LogprobsResult? = null,
  val cacheMetadata: CacheMetadata? = null,
) {
  /**
   * Fluent builder for [LlmResponse], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var content: Content? = null
    private var usageMetadata: UsageMetadata? = null
    private var finishReason: FinishReason? = null
    private var errorMessage: String? = null
    private var partial: Boolean = false
    private var interrupted: Boolean = false
    private var modelVersion: String? = null
    private var citationMetadata: CitationMetadata? = null
    private var groundingMetadata: GroundingMetadata? = null
    private var errorCode: String? = null
    private var customMetadata: Map<String, @Contextual Any?>? = null
    private var avgLogprobs: Double? = null
    private var logprobsResult: LogprobsResult? = null
    private var cacheMetadata: CacheMetadata? = null

    fun content(content: Content?): Builder = apply { this.content = content }

    fun usageMetadata(usageMetadata: UsageMetadata?): Builder = apply {
      this.usageMetadata = usageMetadata
    }

    fun finishReason(finishReason: FinishReason?): Builder = apply {
      this.finishReason = finishReason
    }

    fun errorMessage(errorMessage: String?): Builder = apply { this.errorMessage = errorMessage }

    fun partial(partial: Boolean): Builder = apply { this.partial = partial }

    fun interrupted(interrupted: Boolean): Builder = apply { this.interrupted = interrupted }

    fun modelVersion(modelVersion: String?): Builder = apply { this.modelVersion = modelVersion }

    fun citationMetadata(citationMetadata: CitationMetadata?): Builder = apply {
      this.citationMetadata = citationMetadata
    }

    fun groundingMetadata(groundingMetadata: GroundingMetadata?): Builder = apply {
      this.groundingMetadata = groundingMetadata
    }

    fun errorCode(errorCode: String?): Builder = apply { this.errorCode = errorCode }

    fun customMetadata(customMetadata: Map<String, @Contextual Any?>?): Builder = apply {
      this.customMetadata = customMetadata
    }

    fun avgLogprobs(avgLogprobs: Double?): Builder = apply { this.avgLogprobs = avgLogprobs }

    fun logprobsResult(logprobsResult: LogprobsResult?): Builder = apply {
      this.logprobsResult = logprobsResult
    }

    fun cacheMetadata(cacheMetadata: CacheMetadata?): Builder = apply {
      this.cacheMetadata = cacheMetadata
    }

    fun build(): LlmResponse =
      LlmResponse(
        content = content,
        usageMetadata = usageMetadata,
        finishReason = finishReason,
        errorMessage = errorMessage,
        partial = partial,
        interrupted = interrupted,
        modelVersion = modelVersion,
        citationMetadata = citationMetadata,
        groundingMetadata = groundingMetadata,
        errorCode = errorCode,
        customMetadata = customMetadata,
        avgLogprobs = avgLogprobs,
        logprobsResult = logprobsResult,
        cacheMetadata = cacheMetadata,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()

    /**
     * Creates an [LlmResponse] from a [GenerateContentResponse].
     *
     * @param response The [GenerateContentResponse] to create the [LlmResponse] from.
     * @return The [LlmResponse].
     */
    fun from(response: GenerateContentResponse): LlmResponse {
      val candidate = response.candidates.firstOrNull()
      val finishReason =
        candidate?.finishReason ?: response.promptFeedback?.blockReason?.toFinishReason()

      return LlmResponse(
        content = candidate?.content,
        usageMetadata = response.usageMetadata,
        finishReason = finishReason,
        errorCode = finishReason?.takeIf { it != FinishReason.STOP }?.name,
        errorMessage =
          finishReason
            ?.takeIf { it != FinishReason.STOP }
            ?.let {
              candidate?.finishMessage
                ?: response.promptFeedback?.blockReasonMessage
                ?: "Unknown error."
            },
        modelVersion = response.modelVersion,
        citationMetadata = candidate?.citationMetadata,
        groundingMetadata = candidate?.groundingMetadata,
        avgLogprobs = candidate?.avgLogprobs,
        logprobsResult = candidate?.logprobsResult,
      )
    }
  }
}

/**
 * Serializes this response for the `call_llm` span's `gcp.vertex.agent.llm_response` attribute.
 *
 * Uses the shared [adkJson] serializer, which omits null/empty fields (`exclude_none`) and produces
 * byte-identical JSON on every platform.
 */
@OptIn(FrameworkInternalApi::class)
internal fun LlmResponse.toTracePayload(): JsonElement = adkJson.encodeToJsonElement(this)
