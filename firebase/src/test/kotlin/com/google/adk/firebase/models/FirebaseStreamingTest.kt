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

package com.google.adk.firebase.models

import com.google.adk.kt.types.FinishReason
import com.google.common.truth.Truth.assertThat
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.type.BlockReason
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.PromptFeedback
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.UsageMetadata
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [streamToLlmResponses], fed with canned chunk flows.
 *
 * The error path (when the SDK throws mid-stream) is tested live in `FirebaseIntegrationTest`.
 */
@RunWith(JUnit4::class)
class FirebaseStreamingTest {

  // The SDK keeps these constructors internal, so the tests call them reflectively.
  @OptIn(PublicPreviewAPI::class)
  private fun firebaseResponse(promptFeedback: PromptFeedback? = null): GenerateContentResponse =
    GenerateContentResponse::class
      .java
      .getConstructor(
        List::class.java,
        InferenceSource::class.java,
        PromptFeedback::class.java,
        UsageMetadata::class.java,
        String::class.java,
      )
      .newInstance(emptyList<Any>(), InferenceSource.IN_CLOUD, promptFeedback, null, null)

  private fun blockedPromptFeedback(message: String): PromptFeedback =
    PromptFeedback::class
      .java
      .getConstructor(BlockReason::class.java, List::class.java, String::class.java)
      .newInstance(BlockReason.SAFETY, emptyList<Any>(), message)

  /** An empty chunk stream yields no responses. */
  @Test
  fun emptyStream_emitsNothing() {
    val responses = runBlocking { streamToLlmResponses(emptyFlow()).toList() }

    assertThat(responses).isEmpty()
  }

  /**
   * A prompt-block chunk is emitted as a partial, then a terminal response flagged with the error.
   */
  @Test
  fun blockFeedbackChunk_emitsPartialThenErrorTerminal() {
    val blocked = firebaseResponse(promptFeedback = blockedPromptFeedback("blocked"))

    val responses = runBlocking { streamToLlmResponses(flowOf(blocked)).toList() }

    assertThat(responses).hasSize(2)

    // First: the block chunk itself, surfaced as a partial.
    assertThat(responses[0].partial).isTrue()
    assertThat(responses[0].errorCode).isEqualTo(FinishReason.SAFETY.name)

    // Then: the aggregated terminal response, carrying the block as a non-partial error.
    val terminal = responses[1]
    assertThat(terminal.partial).isFalse()
    assertThat(terminal.finishReason).isEqualTo(FinishReason.SAFETY)
    assertThat(terminal.errorCode).isEqualTo(FinishReason.SAFETY.name)
    assertThat(terminal.errorMessage).isEqualTo("blocked")
  }

  /** An empty chunk yields an empty partial, then an empty, error-free final response. */
  @Test
  fun contentlessChunk_emitsPartialThenEmptyTerminal() {
    val empty = firebaseResponse()

    val responses = runBlocking { streamToLlmResponses(flowOf(empty)).toList() }

    assertThat(responses).hasSize(2)

    // First: the contentless chunk, surfaced as a partial.
    assertThat(responses[0].partial).isTrue()
    assertThat(responses[0].errorCode).isNull()

    // Then: the aggregated non-partial terminal frame — empty and error-free.
    val terminal = responses[1]
    assertThat(terminal.partial).isFalse()
    assertThat(terminal.content).isNull()
    assertThat(terminal.finishReason).isNull()
    assertThat(terminal.errorCode).isNull()
    assertThat(terminal.errorMessage).isNull()
  }
}
