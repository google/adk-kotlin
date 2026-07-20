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

package com.google.adk.kt.sessions.room

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.mlkit.GenaiPrompt
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumented tests for [RoomSessionService], exercising the real Android SQLite stack
 * (the host-side `RoomSessionServiceTest` runs against a simulated DB).
 *
 * [appendEvent_persistsAcrossDatabaseReopen] is a deterministic persistence check that needs no
 * model. [runTurn_withGenaiPrompt_persistsEventsAcrossReopen] is an end-to-end check that drives a
 * real on-device Gemini Nano turn through an [InMemoryRunner] backed by [RoomSessionService],
 * mirroring the ML Kit instrumentation setup; it requires a device with AI Core / Gemini Nano.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionServiceInstrumentedTest {

  // Launches a ComponentActivity to keep the test app in the foreground.
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
  fun appendEvent_persistsAcrossDatabaseReopen(): Unit = runBlocking {
    val key = SessionKey(APP_NAME, "user-persist", "session-persist")
    val session = sessionService.createSession(key)
    val event =
      Event(
        author = "agent",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "persisted answer"))),
      )
    val appended = sessionService.appendEvent(session, event)

    // Reopen the on-disk database with a fresh service instance to prove durability.
    sessionService.close()
    val reopened = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val persisted = reopened.getSession(key)
      assertThat(persisted).isNotNull()
      assertThat(persisted!!.events.map { it.id }).contains(appended.id)
      assertThat(persisted.events.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text })
        .contains("persisted answer")
    } finally {
      reopened.close()
    }
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun appendEvent_partWithOpaqueData_persistsAndDropsOpaqueData(): Unit = runBlocking {
    // In production `opaqueData` can hold an arbitrary, non-serializable object (e.g. the original
    // Firebase `Part` cached for a thought). It is `@Transient`, so appending must not crash and
    // the
    // field must come back as null after a real on-device persist + database reopen.
    val key = SessionKey(APP_NAME, "user-opaque", "session-opaque")
    val session = sessionService.createSession(key)
    val event =
      Event(
        author = "agent",
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(Part(text = "opaque answer", opaqueData = "stand-in-for-non-serializable")),
          ),
      )
    val appended = sessionService.appendEvent(session, event)

    sessionService.close()
    val reopened = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val persisted = reopened.getSession(key)
      assertThat(persisted).isNotNull()
      assertThat(persisted!!.events.map { it.id }).contains(appended.id)
      val part =
        persisted.events
          .flatMap { it.content?.parts.orEmpty() }
          .first { it.text == "opaque answer" }
      assertThat(part.text).isEqualTo("opaque answer")
      assertThat(part.opaqueData).isNull()
    } finally {
      reopened.close()
    }
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
    const val APP_NAME = "RoomSessionServiceInstrumentedTestApp"
    const val TEST_DB_NAME = "room_session_service_instrumented_test.db"
    const val QUESTION = "Tell me something about planet Earth."
  }
}
