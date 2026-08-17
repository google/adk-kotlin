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

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ThinkingConfig
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives real Gemini Nano turns through [GenaiPrompt] with thinking mode on and off.
 *
 * Requires a device with AI Core whose base model supports thinking mode; on an older model the
 * request still succeeds but no thought comes back, so the thinking tests fail.
 */
@RunWith(AndroidJUnit4::class)
class GenaiPromptThinkingInstrumentedTest {

  // Launches a ComponentActivity to keep the test app in the foreground; AICore rejects on-device
  // GenAI calls made from the background (BACKGROUND_USE_BLOCKED).
  @get:Rule val rule = createComposeRule()

  private lateinit var generativeModel: GenerativeModel

  // Per test, not per class: the model has to be created after the rule foregrounds the app, and
  // initGenerativeModel is a suspend call, hence runBlocking.
  @Before
  fun setUp() = runBlocking {
    generativeModel = GenerativeModelHelpers.initGenerativeModel {
      modelConfig =
        ModelConfig.builder()
          .apply {
            releaseStage = ModelReleaseStage.STABLE
            preference = ModelPreference.FULL
          }
          .build()
    }
  }

  @After
  fun tearDown() {
    // Guarded: when setUp fails - AI Core rejects a backgrounded app, for instance - closing an
    // uninitialized client would mask the real cause behind an
    // UninitializedPropertyAccessException.
    if (::generativeModel.isInitialized) generativeModel.close()
  }

  @Test
  fun llmAgent_includeThoughtsNonStreaming_returnsThoughtAndAnswer() {
    val (agent, events) =
      runTurn(ThinkingConfig(includeThoughts = true), StreamingMode.NONE, "thinking-blocking")

    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

    // The model reasoned, and the reasoning is surfaced separately from the answer it produced.
    val parts = finalResponseParts(events, agent)
    assertWithMessage(NO_THOUGHT_HINT).that(thoughtText(parts)).isNotEmpty()
    assertThat(answerText(parts)).isNotEmpty()
  }

  @Test
  fun llmAgent_includeThoughtsStreaming_returnsThoughtBeforeAnswer() {
    val (agent, events) =
      runTurn(ThinkingConfig(includeThoughts = true), StreamingMode.SSE, "thinking-streaming")

    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()
    assertThat(events.filter { it.partial && it.author == agent.name }).isNotEmpty()

    // Assert both exist before comparing positions, so a missing answer reports as a missing
    // answer.
    val parts = finalResponseParts(events, agent)
    assertWithMessage(NO_THOUGHT_HINT).that(thoughtText(parts)).isNotEmpty()
    assertThat(answerText(parts)).isNotEmpty()
    assertThat(parts.indexOfFirst { it.thought == true })
      .isLessThan(parts.indexOfFirst { it.thought != true })
  }

  /** Thinking still runs, but the thoughts stay hidden - the same contract as a cloud model. */
  @Test
  fun llmAgent_thinkingWithoutIncludeThoughts_returnsAnswerWithoutThought() {
    val (agent, events) =
      runTurn(ThinkingConfig(includeThoughts = false), StreamingMode.NONE, "hidden-thoughts")

    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

    val parts = finalResponseParts(events, agent)
    assertThat(parts.filter { it.thought == true }).isEmpty()
    assertThat(answerText(parts)).isNotEmpty()
  }

  /** No thinking config at all - thinking is never switched on. This is the example app's off. */
  @Test
  fun llmAgent_noThinkingConfig_returnsAnswerWithoutThought() {
    val (agent, events) = runTurn(thinkingConfig = null, StreamingMode.NONE, "no-thinking")

    assertThat(events.mapNotNull { it.errorCode }).isEmpty()
    assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

    val parts = finalResponseParts(events, agent)
    assertThat(parts.filter { it.thought == true }).isEmpty()
    assertThat(answerText(parts)).isNotEmpty()
  }

  /** Runs one turn against the shared model, closing the runner it built for it. */
  private fun runTurn(
    thinkingConfig: ThinkingConfig?,
    streamingMode: StreamingMode,
    sessionId: String,
  ): Pair<LlmAgent, List<Event>> {
    val agent =
      LlmAgent(
        name = "on_device_thinking_agent",
        model = GenaiPrompt.create(generativeModel, name = "gemini-nano"),
        instruction = Instruction("You are a helpful assistant. Keep the answer to one sentence."),
        generateContentConfig = GenerateContentConfig(thinkingConfig = thinkingConfig),
      )
    InMemoryRunner(agent = agent, appName = "GenaiPromptThinkingInstrumentedTestApp").use { runner
      ->
      val events = runBlocking {
        runner
          .runAsync(
            userId = "test-user",
            sessionId = sessionId,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
            runConfig = RunConfig(streamingMode = streamingMode),
          )
          .toList()
      }
      logEvents(events)
      return agent to events
    }
  }

  /** Every part of every final-response event the agent authored, in order. */
  private fun finalResponseParts(events: List<Event>, agent: LlmAgent): List<Part> =
    events
      .filter { it.isFinalResponse && it.author == agent.name }
      .flatMap { it.content?.parts.orEmpty() }

  private fun thoughtText(parts: List<Part>): String =
    parts.filter { it.thought == true }.mapNotNull { it.text }.joinToString("")

  private fun answerText(parts: List<Part>): String =
    parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("")

  private fun logEvents(events: List<Event>) {
    for (event in events) {
      Log.d(
        TAG,
        "event author=${event.author} partial=${event.partial} " +
          "parts=${event.content?.parts?.map { "thought=${it.thought} chars=${it.text?.length}" }} " +
          "errorCode=${event.errorCode}",
      )
    }
  }

  private companion object {
    const val TAG = "GenaiThinkingInstrTest"
    const val QUESTION = "A shop sells pens at 3 for 5 dollars. How much do 12 pens cost?"
    // The likeliest cause of an empty thought, so name it in the failure rather than the reader
    // having to work it out from "expected not to be empty".
    const val NO_THOUGHT_HINT =
      "No thought returned. Thinking needs a base model that supports it; check " +
        "GenerativeModel.isThinkingModeAvailable() on this device."
  }
}
