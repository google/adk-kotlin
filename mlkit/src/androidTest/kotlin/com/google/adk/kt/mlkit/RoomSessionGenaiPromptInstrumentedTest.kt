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

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.room.RoomSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
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
 * On-device instrumented test that drives a real Gemini Nano turn through [GenaiPrompt] and an
 * [InMemoryRunner] backed by the Room-persisted [RoomSessionService], asserting the turn's events
 * survive a database reopen. Requires a device with AI Core / Gemini Nano.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionGenaiPromptInstrumentedTest {

  // Launches a ComponentActivity to keep the test app in the foreground; AICore rejects on-device
  // GenAI calls made from the background (BACKGROUND_USE_BLOCKED).
  @get:Rule val rule = createComposeRule()

  private lateinit var context: Context
  private lateinit var sessionService: RoomSessionService

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(TEST_DB_NAME)
    sessionService = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
  }

  @After
  fun tearDown() {
    runCatching { sessionService.close() }
    context.deleteDatabase(TEST_DB_NAME)
  }

  @Test
  fun runTurn_withGenaiPrompt_persistsEventsAcrossReopen(): Unit = runBlocking {
    // initGenerativeModel is suspend and downloads Gemini Nano if needed.
    val generativeModel = GenerativeModelHelpers.initGenerativeModel {
      modelConfig =
        ModelConfig.builder()
          .apply {
            releaseStage = ModelReleaseStage.STABLE
            preference = ModelPreference.FULL
          }
          .build()
    }
    val agent =
      LlmAgent(
        name = "room_session_agent",
        model = GenaiPrompt.create(generativeModel, name = "gemini-nano"),
        instruction = Instruction("You are a helpful assistant. Answer in one short sentence."),
      )
    val runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)

    val userId = "user-e2e"
    val sessionId = "session-e2e"
    val events =
      runner
        .runAsync(
          userId = userId,
          sessionId = sessionId,
          newMessage = Content(role = Role.USER, parts = listOf(Part(text = QUESTION))),
          runConfig = RunConfig(streamingMode = StreamingMode.NONE),
        )
        .toList()

    assertThat(events).isNotEmpty()
    assertThat(events.mapNotNull { it.errorCode }).isEmpty()

    // The turn's events were persisted to the Room-backed session.
    val key = SessionKey(APP_NAME, userId, sessionId)
    val session = sessionService.getSession(key)
    assertThat(session).isNotNull()
    assertThat(session!!.events).isNotEmpty()

    // And they survive a database reopen.
    sessionService.close()
    val reopened = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val persisted = reopened.getSession(key)
      assertThat(persisted).isNotNull()
      assertThat(persisted!!.events.map { it.id })
        .containsAtLeastElementsIn(session.events.map { it.id })
    } finally {
      reopened.close()
    }
  }

  private companion object {
    const val APP_NAME = "RoomSessionGenaiPromptInstrumentedTestApp"
    const val TEST_DB_NAME = "room_session_genai_prompt_instrumented_test.db"
    const val QUESTION = "Tell me something about planet Earth."
  }
}
