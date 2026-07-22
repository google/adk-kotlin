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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.prompt.Candidate
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerateContentResponseAggregatorTest {

  @Before fun setUp() {}

  @Test
  fun aggregate_singleCandidate_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(
      AggregatedResponse(listOf(AggregatedCandidate(text = "Hello", finishReason = null)))
    )
    aggregator.processResponse(
      AggregatedResponse(listOf(AggregatedCandidate(text = " World", finishReason = null)))
    )
    assertThat(aggregator.aggregate().candidates.firstOrNull()?.text).isEqualTo("Hello World")
    assertThat(aggregator.aggregate().candidates.firstOrNull()?.finishReason).isNull()
  }

  @Test
  fun aggregate_multipleCandidates_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = "Hello", finishReason = null),
          AggregatedCandidate(text = "This is a", finishReason = null),
        )
      )
    )
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = " World", finishReason = null),
          AggregatedCandidate(text = " test", finishReason = null),
        )
      )
    )
    val aggregatedResponse = aggregator.aggregate()
    assertThat(aggregatedResponse.candidates).hasSize(2)
    assertThat(aggregatedResponse.candidates.first().text).isEqualTo("Hello World")
    assertThat(aggregatedResponse.candidates.get(1).text).isEqualTo("This is a test")
  }

  @Test
  fun aggregate_withFinishReason_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = "Hello", finishReason = null),
          AggregatedCandidate(text = "This is a", finishReason = null),
        )
      )
    )
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = " World", finishReason = Candidate.FinishReason.STOP),
          AggregatedCandidate(text = " test", finishReason = Candidate.FinishReason.MAX_TOKENS),
        )
      )
    )
    assertThat(aggregator.aggregate().candidates).hasSize(2)
    assertThat(aggregator.aggregate().candidates.first().finishReason)
      .isEqualTo(Candidate.FinishReason.STOP)
    assertThat(aggregator.aggregate().candidates.get(1).finishReason)
      .isEqualTo(Candidate.FinishReason.MAX_TOKENS)
  }

  @Test
  fun aggregate_emptyResponse_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(AggregatedResponse(emptyList()))
    assertThat(aggregator.aggregate().candidates).isEmpty()
  }

  @Test
  fun aggregate_nonEqualCandidatesLists_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = "Hello", finishReason = null),
          AggregatedCandidate(text = "This is a", finishReason = null),
        )
      )
    )
    aggregator.processResponse(
      AggregatedResponse(listOf(AggregatedCandidate(text = " World", finishReason = null)))
    )
    aggregator.processResponse(
      AggregatedResponse(
        listOf(
          AggregatedCandidate(text = "!", finishReason = null),
          AggregatedCandidate(text = " test", finishReason = null),
          AggregatedCandidate(text = "Third candidate", finishReason = null),
        )
      )
    )
    val aggregatedResponse = aggregator.aggregate()
    assertThat(aggregatedResponse.candidates).hasSize(3)
    assertThat(aggregatedResponse.candidates.first().text).isEqualTo("Hello World!")
    assertThat(aggregatedResponse.candidates.get(1).text).isEqualTo("This is a test")
    assertThat(aggregatedResponse.candidates.get(2).text).isEqualTo("Third candidate")
  }

  @Test
  fun aggregate_nullFinishReasonStillOverridesPrevious_success() {
    val aggregator = GenerateContentResponseAggregator()
    aggregator.processResponse(
      AggregatedResponse(
        listOf(AggregatedCandidate(text = "Hello", finishReason = Candidate.FinishReason.STOP))
      )
    )
    aggregator.processResponse(
      AggregatedResponse(listOf(AggregatedCandidate(text = " World", finishReason = null)))
    )
    assertThat(aggregator.aggregate().candidates.firstOrNull()?.finishReason).isNull()
  }
}
