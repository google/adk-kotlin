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

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.utils.mlkit.GenerativeModelHelpers
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenaiPromptInstrumentedTest {

  @get:Rule val rule = createComposeRule()

  private lateinit var agent: LlmAgent
  private lateinit var runner: InMemoryRunner

  // The bytes of the test image bundled in the androidUnitTest assets (shared with the
  // instrumented test APK via the build.gradle.kts source set config). The image depicts a small
  // robot/bot logo.
  private val imageBytes: ByteArray by lazy {
    InstrumentationRegistry.getInstrumentation().context.assets.open(IMAGE_ASSET_NAME).use {
      it.readBytes()
    }
  }

  // initGenerativeModel is a suspend function, so the fixture is built inside runBlocking.
  @Before
  fun setUp() = runBlocking {
    val generativeModel = GenerativeModelHelpers.initGenerativeModel {
      modelConfig =
        ModelConfig.builder()
          .apply {
            releaseStage = ModelReleaseStage.STABLE
            preference = ModelPreference.FAST
          }
          .build()
    }
    agent =
      LlmAgent(
        name = "on_device_agent",
        model = GenaiPrompt.create(generativeModel, name = "gemini-nano"),
        instruction =
          Instruction(
            "You are a helpful assistant. Answer the user's question in one or two sentences."
          ),
      )
    runner = InMemoryRunner(agent = agent, appName = "GenaiPromptInstrumentedTestApp")
  }

  @Test
  fun llmAgent_respondsToEarthQuestion_nonStreaming_withoutErrors() {
    runTest {
      // Non-streaming mode returns a single aggregated turn with no partial chunks.
      val events =
        runner
          .runAsync(
            userId = "test-user",
            sessionId = "test-session",
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
            runConfig = RunConfig(streamingMode = StreamingMode.NONE),
          )
          .toList()
      logEvents(events)

      // No errors were reported on any event.
      assertThat(events).isNotEmpty()
      assertThat(events.mapNotNull { it.errorCode }).isEmpty()
      assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

      // Non-streaming mode emits no partial chunks (contrast with the streaming test).
      assertThat(events.filter { it.partial && it.author == agent.name }).isEmpty()

      // The agent produced a sensible final, model-authored response.
      val finalText = finalResponseText(events)
      Log.d(TAG, "final response text: $finalText")

      assertThat(finalText).isNotEmpty()
      // Sanity-check the answer is on-topic: it should mention "Earth" somewhere.
      assertThat(finalText.lowercase()).contains("earth")
    }
  }

  @Test
  fun llmAgent_respondsToEarthQuestion_streaming_withoutErrors() {
    runTest {
      // SSE streaming drives GenaiPrompt.generateContentStream(...), which emits partial chunks
      // followed by a single aggregated (non-partial) final response.
      val events =
        runner
          .runAsync(
            userId = "test-user",
            sessionId = "test-session-streaming",
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
            runConfig = RunConfig(streamingMode = StreamingMode.SSE),
          )
          .toList()
      logEvents(events)

      // No errors were reported on any event.
      assertThat(events).isNotEmpty()
      assertThat(events.mapNotNull { it.errorCode }).isEmpty()
      assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

      // Streaming mode emits incremental partial chunks before the final response.
      assertThat(events.filter { it.partial && it.author == agent.name }).isNotEmpty()

      // The agent produced a sensible final, model-authored response.
      val finalText = finalResponseText(events)
      Log.d(TAG, "final response text: $finalText")

      assertThat(finalText).isNotEmpty()
      // Sanity-check the answer is on-topic: it should mention "Earth" somewhere.
      assertThat(finalText.lowercase()).contains("earth")
    }
  }

  @Test
  fun llmAgent_describesInlineImage_nonStreaming_withoutErrors() {
    runTest {
      // The image is supplied as inline binary data (a Blob). Non-streaming mode returns a single
      // aggregated turn with no partial chunks.
      val events =
        runner
          .runAsync(
            userId = "test-user",
            sessionId = "test-session-image",
            newMessage =
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(text = IMAGE_QUESTION),
                    Part(inlineData = Blob(mimeType = IMAGE_MIME_TYPE, data = imageBytes)),
                  ),
              ),
            runConfig = RunConfig(streamingMode = StreamingMode.NONE),
          )
          .toList()
      logEvents(events)

      // No errors were reported on any event.
      assertThat(events).isNotEmpty()
      assertThat(events.mapNotNull { it.errorCode }).isEmpty()
      assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

      // Non-streaming mode emits no partial chunks (contrast with the streaming test).
      assertThat(events.filter { it.partial && it.author == agent.name }).isEmpty()

      // The agent produced a sensible, non-empty final, model-authored response for the image.
      val finalText = finalResponseText(events)
      Log.d(TAG, "final response text: $finalText")

      assertThat(finalText).isNotEmpty()
    }
  }

  @Test
  fun llmAgent_describesInlineImage_streaming_withoutErrors() {
    runTest {
      // The image is supplied as inline binary data (a Blob). SSE streaming emits partial chunks
      // followed by a single aggregated (non-partial) final response.
      val events =
        runner
          .runAsync(
            userId = "test-user",
            sessionId = "test-session-image-streaming",
            newMessage =
              Content(
                role = Role.USER,
                parts =
                  listOf(
                    Part(text = IMAGE_QUESTION),
                    Part(inlineData = Blob(mimeType = IMAGE_MIME_TYPE, data = imageBytes)),
                  ),
              ),
            runConfig = RunConfig(streamingMode = StreamingMode.SSE),
          )
          .toList()
      logEvents(events)

      // No errors were reported on any event.
      assertThat(events).isNotEmpty()
      assertThat(events.mapNotNull { it.errorCode }).isEmpty()
      assertThat(events.mapNotNull { it.errorMessage }).isEmpty()

      // Streaming mode emits incremental partial chunks before the final response.
      assertThat(events.filter { it.partial && it.author == agent.name }).isNotEmpty()

      // The agent produced a sensible, non-empty final, model-authored response for the image.
      val finalText = finalResponseText(events)
      Log.d(TAG, "final response text: $finalText")

      assertThat(finalText).isNotEmpty()
    }
  }

  /**
   * The agent's aggregated final answer: the text of every final-response part it authored,
   * concatenated and trimmed.
   */
  private fun finalResponseText(events: List<Event>): String =
    events
      .filter { it.isFinalResponse && it.author == agent.name }
      .flatMap { it.content?.parts.orEmpty() }
      .mapNotNull { it.text }
      .joinToString(separator = " ")
      .trim()

  private fun logEvents(events: List<Event>) {
    for (event in events) {
      Log.d(
        TAG,
        "event author=${event.author} partial=${event.partial} " +
          "text=${event.content?.parts?.joinToString { it.text ?: "" }} " +
          "errorCode=${event.errorCode} errorMessage=${event.errorMessage}",
      )
    }
  }

  private companion object {
    const val TAG = "GenaiPromptInstrTest"
    const val QUESTION = "Tell me something about planet Earth."
    const val IMAGE_QUESTION = "What do you see in this image? Answer in one or two sentences."
    const val IMAGE_ASSET_NAME = "test-image.png"
    const val IMAGE_MIME_TYPE = "image/png"
  }
}
