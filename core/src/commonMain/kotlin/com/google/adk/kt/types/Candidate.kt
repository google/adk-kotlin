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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** Represents a possible response from the model. */
data class Candidate
@JvmOverloads
constructor(
  /** The content of the candidate. */
  val content: Content,
  /** The reason why the model stopped generating content. */
  val finishReason: FinishReason? = null,
  /** The message associated with the finish reason. */
  val finishMessage: String? = null,
  /** The citation metadata associated with the candidate. */
  val citationMetadata: CitationMetadata? = null,
  /** The grounding metadata associated with the candidate. */
  val groundingMetadata: GroundingMetadata? = null,
  /** The average log probability of the candidate's tokens. */
  val avgLogprobs: Double? = null,
  /** Detailed log probabilities for the chosen and top candidate tokens. */
  val logprobsResult: LogprobsResult? = null,
  /**
   * An opaque token that resumes generation when [finishReason] is [FinishReason.CONTINUATION]. The
   * framework typically handles it: ADK's Gemini model resumes paused generations itself.
   */
  val continuationToken: ByteArray? = null,
) {
  // Hand-written because of the ByteArray: add new properties here and in hashCode, Builder,
  // toBuilder, and tests.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Candidate) return false

    return content == other.content &&
      finishReason == other.finishReason &&
      finishMessage == other.finishMessage &&
      citationMetadata == other.citationMetadata &&
      groundingMetadata == other.groundingMetadata &&
      // Compared like a generated equals, so NaN and -0.0 stay consistent with hashCode().
      compareValues(avgLogprobs, other.avgLogprobs) == 0 &&
      logprobsResult == other.logprobsResult &&
      continuationToken.contentEquals(other.continuationToken)
  }

  override fun hashCode(): Int {
    var result = content.hashCode()
    result = 31 * result + (finishReason?.hashCode() ?: 0)
    result = 31 * result + (finishMessage?.hashCode() ?: 0)
    result = 31 * result + (citationMetadata?.hashCode() ?: 0)
    result = 31 * result + (groundingMetadata?.hashCode() ?: 0)
    result = 31 * result + (avgLogprobs?.hashCode() ?: 0)
    result = 31 * result + (logprobsResult?.hashCode() ?: 0)
    result = 31 * result + (continuationToken?.contentHashCode() ?: 0)
    return result
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .content(content)
      .finishReason(finishReason)
      .finishMessage(finishMessage)
      .citationMetadata(citationMetadata)
      .groundingMetadata(groundingMetadata)
      .avgLogprobs(avgLogprobs)
      .logprobsResult(logprobsResult)
      .continuationToken(continuationToken)

  /**
   * Fluent builder for [Candidate], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var content: Content? = null
    private var finishReason: FinishReason? = null
    private var finishMessage: String? = null
    private var citationMetadata: CitationMetadata? = null
    private var groundingMetadata: GroundingMetadata? = null
    private var avgLogprobs: Double? = null
    private var logprobsResult: LogprobsResult? = null
    private var continuationToken: ByteArray? = null

    fun content(content: Content): Builder = apply { this.content = content }

    fun finishReason(finishReason: FinishReason?): Builder = apply {
      this.finishReason = finishReason
    }

    fun finishMessage(finishMessage: String?): Builder = apply {
      this.finishMessage = finishMessage
    }

    fun citationMetadata(citationMetadata: CitationMetadata?): Builder = apply {
      this.citationMetadata = citationMetadata
    }

    fun groundingMetadata(groundingMetadata: GroundingMetadata?): Builder = apply {
      this.groundingMetadata = groundingMetadata
    }

    fun avgLogprobs(avgLogprobs: Double?): Builder = apply { this.avgLogprobs = avgLogprobs }

    fun logprobsResult(logprobsResult: LogprobsResult?): Builder = apply {
      this.logprobsResult = logprobsResult
    }

    fun continuationToken(continuationToken: ByteArray?): Builder = apply {
      this.continuationToken = continuationToken
    }

    fun build(): Candidate =
      Candidate(
        content = checkNotNull(content) { "Candidate.Builder requires content to be set." },
        finishReason = finishReason,
        finishMessage = finishMessage,
        citationMetadata = citationMetadata,
        groundingMetadata = groundingMetadata,
        avgLogprobs = avgLogprobs,
        logprobsResult = logprobsResult,
        continuationToken = continuationToken,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
