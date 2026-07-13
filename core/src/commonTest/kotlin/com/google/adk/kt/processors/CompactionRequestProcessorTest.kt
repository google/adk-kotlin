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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.summarizer.EventSummarizer
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.modelEventWithPromptTokens
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CompactionRequestProcessorTest {

  @Test
  fun process_noCompactionConfig_doesNotCompact() = runTest {
    val sessionService = RecordingSessionService()
    val session = testSession()
    session.events.add(modelEventWithPromptTokens(500, invocationId = "inv_1", timestamp = 100L))
    val context =
      InvocationContext(
        session = session,
        agent = DummyAgent(name = "agent"),
        sessionService = sessionService,
        eventsCompactionConfig = null,
      )

    val unused = CompactionRequestProcessor().process(context, LlmRequest()) {}

    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun process_slidingWindowOnlyConfig_doesNotCompact() = runTest {
    val sessionService = RecordingSessionService()
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEventWithPromptTokens(500, invocationId = "inv_1", timestamp = 110L))
    val context =
      InvocationContext(
        session = session,
        agent = DummyAgent(name = "agent"),
        sessionService = sessionService,
        // No token-threshold fields: the token compactor must not run.
        eventsCompactionConfig =
          EventsCompactionConfig(
            compactionInterval = 2,
            overlapSize = 0,
            summarizer = RecordingSummarizer(),
          ),
      )

    val unused = CompactionRequestProcessor().process(context, LlmRequest()) {}

    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun process_overThreshold_appendsCompactionEvent() = runTest {
    val sessionService = RecordingSessionService()
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 100L))
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEventWithPromptTokens(500, invocationId = "inv_2", timestamp = 200L))
    val context =
      InvocationContext(
        session = session,
        agent = DummyAgent(name = "agent"),
        sessionService = sessionService,
        eventsCompactionConfig =
          EventsCompactionConfig(
            tokenThreshold = 100,
            eventRetentionSize = 1,
            summarizer = summarizer,
          ),
      )
    val request = LlmRequest()

    val result = CompactionRequestProcessor().process(context, request) {}

    // The processor only appends a compaction event to the session; it never modifies the request.
    assertSame(request, result)
    assertEquals(1, sessionService.appended.size)
    assertTrue(sessionService.appended.single().actions.compaction != null)
    assertTrue(session.events.any { it.actions.compaction != null })
  }

  @Test
  fun process_belowThreshold_doesNotCompact() = runTest {
    val sessionService = RecordingSessionService()
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    // Most recent prompt reported only 50 tokens; below the threshold of 100.
    session.events.add(modelEventWithPromptTokens(50, invocationId = "inv_2", timestamp = 200L))
    val context =
      InvocationContext(
        session = session,
        agent = DummyAgent(name = "agent"),
        sessionService = sessionService,
        eventsCompactionConfig =
          EventsCompactionConfig(
            tokenThreshold = 100,
            eventRetentionSize = 1,
            summarizer = RecordingSummarizer(),
          ),
      )

    val unused = CompactionRequestProcessor().process(context, LlmRequest()) {}

    assertTrue(sessionService.appended.isEmpty())
  }

  // ----- helpers -----

  private class RecordingSummarizer(private val returning: Event? = null) : EventSummarizer {
    val calls: MutableList<List<Event>> = mutableListOf()

    override suspend fun summarizeEvents(events: List<Event>): Event? {
      calls.add(events.toList())
      return returning
    }
  }

  private class RecordingSessionService : SessionService {
    val appended: MutableList<Event> = mutableListOf()

    override suspend fun appendEvent(session: Session, event: Event): Event {
      appended.add(event)
      return super.appendEvent(session, event)
    }

    override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
      error("not used")

    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
      error("not used")

    override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse =
      error("not used")

    override suspend fun deleteSession(key: SessionKey) = error("not used")

    override suspend fun listEvents(key: SessionKey): ListEventsResponse = error("not used")
  }
}
