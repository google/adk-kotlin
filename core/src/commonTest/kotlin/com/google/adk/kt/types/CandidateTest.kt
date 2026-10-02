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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CandidateTest {
  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val candidate =
      Candidate(
        content = Content(role = Role.MODEL),
        finishReason = FinishReason.CONTINUATION,
        finishMessage = "message",
        citationMetadata = CitationMetadata(),
        groundingMetadata = GroundingMetadata(),
        avgLogprobs = 0.5,
        logprobsResult = LogprobsResult(),
        continuationToken = byteArrayOf(1, 2, 3),
      )

    assertEquals(candidate.copy(), candidate.toBuilder().build())
    assertEquals(
      candidate.copy(finishMessage = "other"),
      candidate.toBuilder().finishMessage("other").build(),
    )
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_onlyContentSet_matchesConstructorDefaults() {
    assertEquals(Candidate(content = Content()), Candidate.builder().content(Content()).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_contentNotSet_throws() {
    assertFailsWith<IllegalStateException> { Candidate.builder().build() }
  }

  @Test
  fun equals_sameContinuationToken_returnsTrue() {
    assertEquals(candidate(byteArrayOf(1, 2, 3)), candidate(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun equals_differentContinuationToken_returnsFalse() {
    assertNotEquals(candidate(byteArrayOf(1, 2, 3)), candidate(byteArrayOf(1, 2, 4)))
  }

  @Test
  fun equals_nanAvgLogprobs_returnsTrue() {
    val candidate = candidate(byteArrayOf(1, 2, 3)).copy(avgLogprobs = Double.NaN)

    assertEquals(candidate, candidate.copy())
    assertEquals(candidate.hashCode(), candidate.copy().hashCode())
  }

  @Test
  fun equals_anyPropertyDiffers_returnsFalse() {
    val base = Candidate(content = Content())
    val changed =
      listOf(
        base.copy(content = Content(role = Role.MODEL)),
        base.copy(finishReason = FinishReason.STOP),
        base.copy(finishMessage = "message"),
        base.copy(citationMetadata = CitationMetadata()),
        base.copy(groundingMetadata = GroundingMetadata()),
        base.copy(avgLogprobs = 0.5),
        base.copy(logprobsResult = LogprobsResult()),
        base.copy(continuationToken = byteArrayOf(1)),
      )

    for (candidate in changed) {
      assertNotEquals(base, candidate)
    }
  }

  @Test
  fun hashCode_sameContinuationToken_returnsSameHashCode() {
    assertEquals(
      candidate(byteArrayOf(1, 2, 3)).hashCode(),
      candidate(byteArrayOf(1, 2, 3)).hashCode(),
    )
  }

  private fun candidate(token: ByteArray?) =
    Candidate(
      content = Content(role = Role.MODEL),
      finishReason = FinishReason.CONTINUATION,
      continuationToken = token,
    )
}
