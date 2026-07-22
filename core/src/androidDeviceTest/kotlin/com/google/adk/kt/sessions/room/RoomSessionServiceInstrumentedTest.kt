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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
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
 * [appendEvent_persistsAcrossDatabaseReopen] and
 * [appendEvent_partWithOpaqueData_persistsAndDropsOpaqueData] are deterministic persistence checks
 * that need no model. The end-to-end check that drives a real on-device Gemini Nano turn through
 * [RoomSessionService] lives in the `mlkit` module's `RoomSessionGenaiPromptInstrumentedTest`.
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

  private companion object {
    const val APP_NAME = "RoomSessionServiceInstrumentedTestApp"
    const val TEST_DB_NAME = "room_session_service_instrumented_test.db"
  }
}
