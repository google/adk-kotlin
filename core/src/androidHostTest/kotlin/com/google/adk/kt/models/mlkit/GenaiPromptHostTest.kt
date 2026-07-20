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

package com.google.adk.kt.models.mlkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking

/**
 * Host-side analogue of [GenaiPromptInstrumentedTest].
 *
 * [GenaiPromptInstrumentedTest] exercises the full ADK stack against the *real* on-device Gemini
 * Nano model, so it can only run on a physical device with ML Kit GenAI installed. This test drives
 * exactly the same stack but runs on the host JVM (Robolectric) by replacing the model boundary
 * with a mock.
 *
 * Everything between the runner and the model boundary is the real production code path.
 */
@RunWith(AndroidJUnit4::class)
class GenaiPromptHostTest {

  /**
   * Builds a mocked [GenerateContentResponse] with a single candidate carrying [text] and
   * [finishReason].
   */
  private fun fakeResponse(
    text: String,
    finishReason: Int? = Candidate.FinishReason.STOP,
  ): GenerateContentResponse {
    val candidate =
      mock<Candidate> {
        on { it.text } doReturn text
        on { it.finishReason } doReturn finishReason
      }
    return mock<GenerateContentResponse> { on { it.candidates } doReturn listOf(candidate) }
  }

  private fun buildRunner(generativeModel: GenerativeModel): Pair<LlmAgent, InMemoryRunner> {
    val agent =
      LlmAgent(
        name = "on_device_agent",
        model = GenaiPrompt.create(generativeModel, name = "gemini-nano"),
        instruction =
          Instruction(
            "You are a helpful assistant. Answer the user's question in one or two sentences."
          ),
      )
    return agent to InMemoryRunner(agent = agent, appName = "GenaiPromptHostTestApp")
  }

  @Test
  fun llmAgent_respondsToEarthQuestion_nonStreaming_withoutErrors() = runTest {
    val response = fakeResponse(MODEL_ANSWER)
    val model =
      mock<GenerativeModel> {
        onBlocking { generateContent(any<GenerateContentRequest>()) } doReturn response
      }
    val (agent, runner) = buildRunner(model)

    val events =
      runner
        .runAsync(
          userId = "test-user",
          sessionId = "test-session",
          newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
          runConfig = RunConfig(streamingMode = StreamingMode.NONE),
        )
        .toList()

    // No errors were reported on any event.
    assertThat(events).isNotEmpty()
    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

    // Non-streaming mode emits no partial chunks (contrast with the streaming test).
    assertThat(events.filter { it.partial && it.author == agent.name }).isEmpty()

    // The agent surfaced the model's (mocked) final, model-authored response.
    assertThat(finalResponseText(events, agent)).isEqualTo(MODEL_ANSWER)

    // The full request pipeline ran: the user's question and the agent instruction both reached the
    // ML Kit request, proving the runner -> agent -> GenaiPrompt -> conversions wiring end-to-end.
    val captor = argumentCaptor<GenerateContentRequest>()
    verifyBlocking(model) { generateContent(captor.capture()) }
    assertThat(captor.firstValue.text.textString).contains(QUESTION)
    assertThat(captor.firstValue.systemInstruction?.textString).contains("helpful assistant")
  }

  @Test
  fun llmAgent_respondsToEarthQuestion_streaming_emitsPartialsThenAggregatedFinal() = runTest {
    // The mocked model streams three chunks; only the last carries the STOP finish reason.
    // GenaiPrompt emits each chunk as a partial and then a single aggregated, non-partial final
    // response.
    val chunks =
      flowOf(
        fakeResponse("The Earth ", finishReason = null),
        fakeResponse("is the third planet ", finishReason = null),
        fakeResponse("from the Sun.", finishReason = Candidate.FinishReason.STOP),
      )
    val model =
      mock<GenerativeModel> {
        on { generateContentStream(any<GenerateContentRequest>()) } doReturn chunks
      }
    val (agent, runner) = buildRunner(model)

    val events =
      runner
        .runAsync(
          userId = "test-user",
          sessionId = "test-session-streaming",
          newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()

    // No errors were reported on any event.
    assertThat(events).isNotEmpty()
    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

    // Streaming mode emits incremental partial chunks before the final response.
    assertThat(events.filter { it.partial && it.author == agent.name }).isNotEmpty()

    // The aggregated final answer concatenates the streamed chunks.
    assertThat(finalResponseText(events, agent))
      .isEqualTo("The Earth is the third planet from the Sun.")

    // The full request pipeline ran: the user's question and the agent instruction both reached the
    // ML Kit request, proving the runner -> agent -> GenaiPrompt -> conversions wiring end-to-end.
    // generateContentStream is not a suspend function (it returns a Flow), so a plain verify with
    // an argument captor suffices here - no verifyBlocking needed.
    val captor = argumentCaptor<GenerateContentRequest>()
    val unused = verify(model).generateContentStream(captor.capture())
    assertThat(captor.firstValue.text.textString).contains(QUESTION)
    assertThat(captor.firstValue.systemInstruction?.textString).contains("helpful assistant")
  }

  /**
   * The agent's aggregated final answer: the text of every final-response part it authored,
   * concatenated and trimmed.
   */
  private fun finalResponseText(events: List<Event>, agent: LlmAgent): String =
    events
      .filter { it.isFinalResponse && it.author == agent.name }
      .flatMap { it.content?.parts.orEmpty() }
      .mapNotNull { it.text }
      .joinToString(separator = " ")
      .trim()

  private companion object {
    const val QUESTION = "Tell me something about planet Earth."
    const val MODEL_ANSWER =
      "Earth is the third planet from the Sun and the only known home to life."
  }
}
