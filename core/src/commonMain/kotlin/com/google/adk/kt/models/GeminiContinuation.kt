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

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.ModalityTokenCount
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata

private val logger = LoggerFactory.getLogger(Continuation::class)

/** The contents of a request that resumes a paused generation, and the token that resumes it. */
internal class ResumeRequest(val contents: List<Content>, val token: ByteArray)

/** One generation, carried across the requests that resume it after the model pauses it. */
internal class Continuation(
  private val contents: List<Content>,
  private val config: GenerateContentConfig,
) {
  private val parts = mutableListOf<Part>()
  private var token: ByteArray? = null
  private var resumes = 0

  /** The token usage summed over the requests recorded so far. */
  var usage: UsageMetadata? = null
    private set

  /** Whether the generation took more than one request. */
  private val resumed: Boolean
    get() = token != null

  /**
   * Returns the token that resumes [response], or null if it did not pause or has no token. Without
   * a `maxOutputTokens` limit, `MAX_TOKENS` is a pause too: the request reached its own output cap,
   * not the caller's.
   */
  fun resumeToken(response: GenerateContentResponse): ByteArray? {
    val candidate = response.candidates.firstOrNull() ?: return null
    val paused =
      candidate.finishReason == FinishReason.CONTINUATION ||
        (candidate.finishReason == FinishReason.MAX_TOKENS && config.maxOutputTokens == null)
    if (!paused) return null
    val token = candidate.continuationToken?.takeIf { it.isNotEmpty() }
    if (token == null && candidate.finishReason == FinishReason.CONTINUATION) {
      logger.warn {
        "The model paused the generation for continuation, but the response carries no" +
          " continuation token, so the partial output is returned."
      }
    }
    return token
  }

  /** Whether a request that ended with [nextToken] is resumed. */
  fun willResume(nextToken: ByteArray): Boolean =
    !nextToken.contentEquals(token) && resumes < MAX_RESUMES

  /**
   * Records one request's output and returns the next request to resume generation. Returns null if
   * generation finished, paused without a new token, or already resumed [MAX_RESUMES] times.
   */
  fun advance(
    nextToken: ByteArray?,
    newParts: List<Part>,
    newUsage: UsageMetadata?,
  ): ResumeRequest? {
    usage = addUsage(usage, newUsage)
    if (nextToken == null && !resumed) return null
    appendParts(newParts)
    if (nextToken == null) return null
    if (!willResume(nextToken)) {
      if (nextToken.contentEquals(token)) {
        // A token that did not change means the model made no progress.
        logger.warn {
          "The model returned the same continuation token twice; returning the output generated" +
            " so far."
        }
      } else {
        logger.warn {
          "The model paused the generation $MAX_RESUMES times; returning the output generated so" +
            " far."
        }
      }
      return null
    }
    token = nextToken
    resumes++
    logger.info { "The model paused the generation; resuming it." }
    return ResumeRequest(if (parts.isEmpty()) contents else contents + content(), nextToken)
  }

  /** Returns [response] with the content and usage of all requests, if the generation resumed. */
  fun complete(response: LlmResponse): LlmResponse =
    if (!resumed) {
      response
    } else {
      response.copy(
        content = if (parts.isEmpty()) response.content else content(),
        usageMetadata = usage,
      )
    }

  private fun content() = Content(role = Role.MODEL, parts = parts.toList())

  /** Appends [newParts], joining adjacent text parts that [canJoin] allows. */
  private fun appendParts(newParts: List<Part>) {
    for (part in newParts) {
      val previous = parts.lastOrNull()
      if (previous != null && canJoin(previous, part)) {
        parts[parts.lastIndex] =
          Part(
            text = previous.text.orEmpty() + part.text.orEmpty(),
            thought = previous.thought,
            thoughtSignature =
              previous.thoughtSignature?.takeIf { it.isNotEmpty() } ?: part.thoughtSignature,
          )
      } else {
        parts += part
      }
    }
  }

  /** Returns whether two parts are non-empty text of the same kind. */
  private fun canJoin(first: Part, second: Part): Boolean =
    first.isText() && second.isText() && (first.thought == true) == (second.thought == true)

  /** Returns the combined token usage of two requests. */
  private fun addUsage(total: UsageMetadata?, next: UsageMetadata?): UsageMetadata? {
    if (total == null || next == null) return total ?: next
    return next.copy(
      promptTokenCount = addCounts(total.promptTokenCount, next.promptTokenCount),
      candidatesTokenCount = addCounts(total.candidatesTokenCount, next.candidatesTokenCount),
      totalTokenCount = addCounts(total.totalTokenCount, next.totalTokenCount),
      thoughtsTokenCount = addCounts(total.thoughtsTokenCount, next.thoughtsTokenCount),
      toolUsePromptTokenCount =
        addCounts(total.toolUsePromptTokenCount, next.toolUsePromptTokenCount),
      cachedContentTokenCount =
        addCounts(total.cachedContentTokenCount, next.cachedContentTokenCount),
      promptTokensDetails = addModalityCounts(total.promptTokensDetails, next.promptTokensDetails),
      candidatesTokensDetails =
        addModalityCounts(total.candidatesTokensDetails, next.candidatesTokensDetails),
      toolUsePromptTokensDetails =
        addModalityCounts(total.toolUsePromptTokensDetails, next.toolUsePromptTokensDetails),
    )
  }

  private fun addCounts(first: Int?, second: Int?): Int? =
    if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

  /** Sums token counts that share a modality, keeping first-seen order. */
  private fun addModalityCounts(
    first: List<ModalityTokenCount>?,
    second: List<ModalityTokenCount>?,
  ): List<ModalityTokenCount>? {
    if (first == null && second == null) return null
    return (first.orEmpty() + second.orEmpty())
      .groupingBy { it.modality }
      .fold(0) { total, count -> total + (count.tokenCount ?: 0) }
      .map { (modality, tokenCount) ->
        ModalityTokenCount(modality = modality, tokenCount = tokenCount)
      }
  }

  companion object {
    const val MAX_RESUMES = 256
  }
}

/** What one streamed request generated, recorded to resume [continuation] if the request pauses. */
internal class StreamedOutput(private val continuation: Continuation) {
  private val _parts = mutableListOf<Part>()

  /** The parts recorded so far, excluding stream terminators. */
  val parts: List<Part>
    get() = _parts

  /** The token that resumes generation if this request paused, or null otherwise. */
  var token: ByteArray? = null
    private set

  /** The usage metadata from the last chunk that carried it, covering the whole request. */
  var usage: UsageMetadata? = null
    private set

  /**
   * Records [response] and returns it to aggregate, clearing the finish reason of a pause that is
   * resumed.
   */
  fun record(response: GenerateContentResponse): GenerateContentResponse {
    response.usageMetadata?.let { usage = it }
    val candidate = response.candidates.firstOrNull() ?: return response
    // A stream terminator carries nothing, and resending it would add an empty text part.
    _parts += candidate.content.parts.filterNot { it.isStreamTerminator() }
    val responseToken = continuation.resumeToken(response) ?: return response
    token = responseToken
    // A pause that is not resumed ends the generation, so it keeps its finish reason.
    if (!continuation.willResume(responseToken)) return response
    return response.copy(
      candidates = listOf(candidate.copy(finishReason = null)) + response.candidates.drop(1)
    )
  }
}

/** Returns whether this part is non-empty text, with at most a thought flag and a signature. */
private fun Part.isText(): Boolean =
  !text.isNullOrEmpty() &&
    this == Part(text = text, thought = thought, thoughtSignature = thoughtSignature)
