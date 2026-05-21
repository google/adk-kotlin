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

import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.BlockedReason
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.PromptFeedback
import com.google.adk.kt.types.Role
import kotlin.test.assertEquals
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
    assertEquals("Safety filter triggered", llmResponse.errorMessage)
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
}
