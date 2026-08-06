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
import com.google.firebase.ai.type.BlockReason
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.PromptFeedback
import com.google.firebase.ai.type.PublicPreviewAPI
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
 * The Firebase SDK's response and exception types can't be built or mocked here, so the error path
 * (the SDK throws mid-stream) is tested live in `FirebaseIntegrationTest`.
 */
@OptIn(PublicPreviewAPI::class)
@RunWith(JUnit4::class)
class FirebaseStreamingTest {

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
    val blocked =
      GenerateContentResponse(
        emptyList(),
        PromptFeedback(BlockReason.SAFETY, emptyList(), "blocked"),
        null,
      )

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

  /**
   * A chunk with neither content nor error still aggregates to a non-partial empty terminal frame,
   * so the turn always concludes: the partial is followed by an empty, error-free terminal response
   * rather than ending in silence.
   */
  @Test
  fun contentlessChunk_emitsPartialThenEmptyTerminal() {
    val empty = GenerateContentResponse(emptyList(), null, null)

    val responses = runBlocking { streamToLlmResponses(flowOf(empty)).toList() }

    assertThat(responses).hasSize(2)

    // First: the contentless chunk, surfaced as a partial.
    assertThat(responses[0].partial).isTrue()
    assertThat(responses[0].errorCode).isNull()

    // Then: the aggregated terminal frame that concludes the turn — empty and error-free.
    val terminal = responses[1]
    assertThat(terminal.partial).isFalse()
    assertThat(terminal.content).isNull()
    assertThat(terminal.finishReason).isNull()
    assertThat(terminal.errorCode).isNull()
    assertThat(terminal.errorMessage).isNull()
  }
}
