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
package com.google.adk.kt.mlkit

import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentResponse

/**
 * An aggregated candidate is an intermediate representation of a candidate used internally for
 * processing ML Kit [GenerateContentResponse] responses.
 */
internal data class AggregatedCandidate(val text: String, val finishReason: Int?)

private fun Candidate.toAggregatedCandidate() = AggregatedCandidate(text, finishReason)

/**
 * An aggregated response is an intermediate representation of a response used internally for
 * processing ML Kit [GenerateContentResponse] responses.
 */
internal data class AggregatedResponse(val candidates: List<AggregatedCandidate>)

internal fun GenerateContentResponse.toAggregatedResponse() =
  AggregatedResponse(candidates.map { it.toAggregatedCandidate() })

/**
 * Aggregates [GenerateContentResponse]s from streaming inference.
 *
 * The class is not thread-safe. Users must synchronize calls to its methods when calling from
 * multiple threads.
 */
internal class GenerateContentResponseAggregator {
  private var aggregatedResponse: AggregatedResponse = AggregatedResponse(emptyList())

  /**
   * Processes a single [AggregatedResponse] and updates the aggregated response.
   *
   * Each candidate's text is concatenated with the corresponding candidate's text in the aggregated
   * response. The finish reason of the aggregated candidate is the finish reason of the last
   * response's candidate.
   *
   * The method is not thread-safe.
   *
   * @param response The [AggregatedResponse] to process.
   */
  internal fun processResponse(response: AggregatedResponse) {
    val aggregatedIterator = aggregatedResponse.candidates.iterator()
    val responseIterator = response.candidates.iterator()

    val aggregatedCandidates = mutableListOf<AggregatedCandidate>()
    while (responseIterator.hasNext() || aggregatedIterator.hasNext()) {
      val newCandidate = if (responseIterator.hasNext()) responseIterator.next() else null
      val oldCandidate = if (aggregatedIterator.hasNext()) aggregatedIterator.next() else null
      aggregatedCandidates.add(aggregateCandidate(oldCandidate, newCandidate))
    }

    aggregatedResponse = AggregatedResponse(aggregatedCandidates)
  }

  /**
   * Returns the aggregated [AggregatedResponse] from all the responses processed so far.
   *
   * @return The aggregated [AggregatedResponse].
   */
  internal fun aggregate(): AggregatedResponse {
    return aggregatedResponse
  }

  private fun aggregateCandidate(
    oldCandidate: AggregatedCandidate?,
    newCandidate: AggregatedCandidate?,
  ): AggregatedCandidate {

    if (oldCandidate == null && newCandidate == null) {
      throw IllegalArgumentException("Both candidates are null, cannot aggregate.")
    }

    return AggregatedCandidate(
      (oldCandidate?.text ?: "") + (newCandidate?.text ?: ""),
      (newCandidate ?: oldCandidate)?.finishReason,
    )
  }
}
