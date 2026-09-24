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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.BlockedReason
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.CitationMetadata
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.LogprobsResult
import com.google.adk.kt.types.PromptFeedback
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

fun GenerateContentResponse.firstCandidate(): Candidate? {
  return this.candidates.firstOrNull()
}

@RunWith(JUnit4::class)
class LlmResponseTest {

  @Test
  fun testCreateWithoutLogprobs() {
    val response =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(content = modelMessage("Response text"), finishReason = FinishReason.STOP)
          )
      )

    val llmResponse = LlmResponse.from(response)

    assertEquals("Response text", llmResponse.content?.parts?.get(0)?.text)
    assertNull(llmResponse.errorCode)
  }

  @Test
  fun testCreatePartialStreamingChunkHasNoError() {
    // Partial streaming chunks have no finishReason until the stream completes.
    val response =
      GenerateContentResponse(
        candidates = listOf(Candidate(content = modelMessage("partial text"), finishReason = null))
      )

    val llmResponse = LlmResponse.from(response)

    assertNull(llmResponse.finishReason)
    assertNull(llmResponse.errorCode)
    assertNull(llmResponse.errorMessage)
  }

  @Test
  fun testCreateErrorCase() {
    val response =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(
              content = Content(role = Role.MODEL, parts = emptyList()),
              finishReason = FinishReason.SAFETY,
              finishMessage = "Safety filter triggered",
            )
          )
      )

    val llmResponse = LlmResponse.from(response)

    assertEquals(FinishReason.SAFETY, llmResponse.finishReason)
    assertEquals("SAFETY", llmResponse.errorCode)
    assertEquals("Safety filter triggered", llmResponse.errorMessage)
  }

  @Test
  fun testContentlessCandidateHasNoContent() {
    // No parts and not STOP: null content, not an empty Content (they serialize differently).
    val response =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(
              content = Content(role = Role.MODEL, parts = emptyList()),
              finishReason = FinishReason.SAFETY,
            )
          )
      )

    val llmResponse = LlmResponse.from(response)

    assertNull(llmResponse.content)
  }

  @Test
  fun testCreateStopWithEmptyPartsKeepsContent() {
    // A candidate that finished normally keeps its (empty) content rather than dropping it.
    val response =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(
              content = Content(role = Role.MODEL, parts = emptyList()),
              finishReason = FinishReason.STOP,
            )
          )
      )

    val llmResponse = LlmResponse.from(response)

    val content = assertNotNull(llmResponse.content)
    assertEquals(0, content.parts.size)
    assertNull(llmResponse.errorCode)
  }

  @Test
  fun testCreateNoCandidates() {
    val response =
      GenerateContentResponse(
        promptFeedback =
          PromptFeedback(
            blockReason = BlockedReason.SAFETY,
            blockReasonMessage = "Prompt blocked for safety",
          )
      )

    val llmResponse = LlmResponse.from(response)

    assertEquals(FinishReason.SAFETY, llmResponse.finishReason)
    assertEquals("SAFETY", llmResponse.errorCode)
    assertEquals("Prompt blocked for safety", llmResponse.errorMessage)
  }

  @Test
  fun testCreateIncludesModelVersion() {
    val response =
      GenerateContentResponse(
        modelVersion = "gemini-2.0-flash",
        candidates =
          listOf(
            Candidate(content = modelMessage("Response text"), finishReason = FinishReason.STOP)
          ),
      )
    val llmResponse = LlmResponse.from(response)
    assertEquals("gemini-2.0-flash", llmResponse.modelVersion)
  }

  @Test
  fun testCreatePropagatesLogprobsFromCandidate() {
    val response =
      GenerateContentResponse(
        candidates =
          listOf(
            Candidate(
              content = modelMessage("Response text"),
              finishReason = FinishReason.STOP,
              avgLogprobs = -0.5,
            )
          )
      )

    val llmResponse = LlmResponse.from(response)

    assertEquals(-0.5, llmResponse.avgLogprobs)
  }

  @Test
  fun testCustomMetadata() {
    val llmResponse =
      LlmResponse(
        content = modelMessage("Response text"),
        customMetadata = mapOf("label" to "experiment-a"),
      )

    assertEquals("experiment-a", llmResponse.customMetadata?.get("label"))
  }

  @Test
  fun cacheMetadata_defaultsToNull() {
    assertEquals(null, LlmResponse().cacheMetadata)
  }

  @Test
  fun cacheMetadata_canBeSet() {
    val metadata = CacheMetadata(fingerprint = "abc", contentsCount = 2)

    val llmResponse = LlmResponse(cacheMetadata = metadata)

    assertEquals(metadata, llmResponse.cacheMetadata)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val response =
      LlmResponse(
        content = modelMessage("hi"),
        usageMetadata = UsageMetadata(totalTokenCount = 3),
        finishReason = FinishReason.MAX_TOKENS,
        errorMessage = "truncated",
        partial = true,
        interrupted = true,
        modelVersion = "v1",
        citationMetadata = CitationMetadata(),
        groundingMetadata = GroundingMetadata(webSearchQueries = listOf("query")),
        errorCode = "MAX_TOKENS",
        customMetadata = mapOf("key" to null),
        avgLogprobs = -0.5,
        logprobsResult = LogprobsResult(logProbabilitySum = -1.5),
        cacheMetadata = CacheMetadata(fingerprint = "abc", contentsCount = 2),
      )

    assertEquals(response.copy(), response.toBuilder().build())
    assertEquals(response.copy(partial = false), response.toBuilder().partial(false).build())
  }
}
