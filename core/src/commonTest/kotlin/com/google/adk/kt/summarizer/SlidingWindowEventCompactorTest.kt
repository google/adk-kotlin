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
package com.google.adk.kt.summarizer

import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.eventWithFunctionCall
import com.google.adk.kt.testing.eventWithFunctionResponse
import com.google.adk.kt.testing.eventWithHitlRequest
import com.google.adk.kt.testing.modelEvent
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SlidingWindowEventCompactorTest {

  @Test
  fun compact_noEvents_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_belowCompactionInterval_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    // Only 1 invocation; compactionInterval = 2.
    session.events.add(userEvent("hi", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("hello", invocationId = "inv_1", timestamp = 110L))

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_noSlidingWindowConfig_doesNothing() = runTest {
    val sessionService = RecordingSessionService()
    // Config without sliding-window fields (and no summarizer) must be a safe no-op.
    val compactor = SlidingWindowEventCompactor(EventsCompactionConfig())
    val session = testSession()
    session.events.add(userEvent("hi", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("hello", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("bye", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEvent("see ya", invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_insufficientNewInvocationsAfterPriorCompaction_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    // One invocation already summarized by a prior compaction (endTimestamp = 110).
    session.events.add(userEvent("a", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("b", invocationId = "inv_1", timestamp = 110L))
    session.events.add(compactionEvent(startTs = 100L, endTs = 110L))
    // Only 1 new invocation since the boundary; compactionInterval = 2 is not met.
    session.events.add(userEvent("c", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEvent("d", invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_meetsThreshold_appendsCompactionEvent() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 210L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    session.events.add(userEvent("hi", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("hello", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("bye", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEvent("see ya", invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    assertEquals(1, summarizer.calls.size)
    assertEquals(4, summarizer.calls.single().size)
    assertEquals(1, sessionService.appended.size)
  }

  @Test
  fun compact_meetsThreshold_passesEventsInChronologicalOrder() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 210L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val e1 = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val e2 = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    val e3 = userEvent("bye", invocationId = "inv_2", timestamp = 200L)
    val e4 = modelEvent("see ya", invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e1, e2, e3, e4))

    compactor.compact(session, sessionService)

    assertEquals(listOf(e1, e2, e3, e4), summarizer.calls.single())
  }

  @Test
  fun compact_subsequentRun_includesOverlapInvocations() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 200L, endTs = 410L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    // First two invocations already compacted.
    session.events.add(userEvent("a", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("b", invocationId = "inv_1", timestamp = 110L))
    val secondInvocationUser = userEvent("c", invocationId = "inv_2", timestamp = 200L)
    val secondInvocationModel = modelEvent("d", invocationId = "inv_2", timestamp = 210L)
    session.events.add(secondInvocationUser)
    session.events.add(secondInvocationModel)
    session.events.add(
      compactionEvent(startTs = 100L, endTs = 210L, timestamp = 220L, summary = "prior summary")
    )
    // Two new invocations meet the threshold; with overlap=1, inv_2 is re-included.
    val thirdInvocationUser = userEvent("e", invocationId = "inv_3", timestamp = 300L)
    val thirdInvocationModel = modelEvent("f", invocationId = "inv_3", timestamp = 310L)
    val fourthInvocationUser = userEvent("g", invocationId = "inv_4", timestamp = 400L)
    val fourthInvocationModel = modelEvent("h", invocationId = "inv_4", timestamp = 410L)
    session.events.addAll(
      listOf(thirdInvocationUser, thirdInvocationModel, fourthInvocationUser, fourthInvocationModel)
    )

    compactor.compact(session, sessionService)

    assertEquals(
      listOf(
        secondInvocationUser,
        secondInvocationModel,
        thirdInvocationUser,
        thirdInvocationModel,
        fourthInvocationUser,
        fourthInvocationModel,
      ),
      summarizer.calls.single(),
    )
  }

  @Test
  fun compact_summarizerReturnsNull_doesNotAppendEvent() = runTest {
    val summarizer = RecordingSummarizer(returning = null)
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    session.events.add(userEvent("hi", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("hello", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("bye", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEvent("see ya", invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    assertEquals(1, summarizer.calls.size)
    assertEquals(4, summarizer.calls.single().size)
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_pendingFunctionCallInWindow_truncatesBeforeIt() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val firstUser = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val firstModel = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    val secondUser = userEvent("do it", invocationId = "inv_2", timestamp = 200L)
    val callEvent =
      eventWithFunctionCall(
        invocationId = "inv_2",
        timestamp = 210L,
        callName = "call_name",
        callId = "call_1",
      )
    session.events.addAll(listOf(firstUser, firstModel, secondUser, callEvent))

    compactor.compact(session, sessionService)

    // The call opens an obligation that doesn't close, so the safe prefix ends just before it.
    assertEquals(listOf(firstUser, firstModel, secondUser), summarizer.calls.single())
  }

  @Test
  fun compact_pendingFunctionCallAtWindowStart_skipsCompaction() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val callEvent =
      eventWithFunctionCall(
        invocationId = "inv_1",
        timestamp = 100L,
        callName = "call_name",
        callId = "call_1",
      )
    session.events.add(callEvent)
    session.events.add(modelEvent("trailing", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("hi", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEvent("hello", invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    // Window starts with the pending call, so it never reaches a balanced point → nothing to
    // summarize.
    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_resolvedFunctionCall_keepsItInWindow() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 220L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val firstUser = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val firstModel = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    val secondUser = userEvent("do it", invocationId = "inv_2", timestamp = 200L)
    val callEvent =
      eventWithFunctionCall(
        invocationId = "inv_2",
        timestamp = 210L,
        callName = "call_name",
        callId = "call_1",
      )
    val responseEvent =
      eventWithFunctionResponse(
        invocationId = "inv_2",
        timestamp = 220L,
        name = "call_name",
        callId = "call_1",
      )
    session.events.addAll(listOf(firstUser, firstModel, secondUser, callEvent, responseEvent))

    compactor.compact(session, sessionService)

    assertEquals(
      listOf(firstUser, firstModel, secondUser, callEvent, responseEvent),
      summarizer.calls.single(),
    )
  }

  @Test
  fun compact_pendingHitlConfirmationInWindow_truncatesBeforeIt() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val firstUser = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val firstModel = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    val secondUser = userEvent("do it", invocationId = "inv_2", timestamp = 200L)
    val hitlEvent = eventWithHitlRequest(invocationId = "inv_2", timestamp = 210L, callId = "h_1")
    session.events.addAll(listOf(firstUser, firstModel, secondUser, hitlEvent))

    compactor.compact(session, sessionService)

    // The pending HITL request opens an obligation that never closes, so the safe prefix ends just
    // before it.
    assertEquals(listOf(firstUser, firstModel, secondUser), summarizer.calls.single())
  }

  @Test
  fun compact_resolvedHitlConfirmation_keepsItInWindow() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 220L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val firstUser = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val firstModel = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    val secondUser = userEvent("do it", invocationId = "inv_2", timestamp = 200L)
    val hitlEvent = eventWithHitlRequest(invocationId = "inv_2", timestamp = 210L, callId = "h_1")
    val responseEvent =
      eventWithFunctionResponse(
        invocationId = "inv_2",
        timestamp = 220L,
        name = "tool",
        callId = "h_1",
      )
    session.events.addAll(listOf(firstUser, firstModel, secondUser, hitlEvent, responseEvent))

    compactor.compact(session, sessionService)

    assertEquals(
      listOf(firstUser, firstModel, secondUser, hitlEvent, responseEvent),
      summarizer.calls.single(),
    )
  }

  @Test
  fun compact_functionResponseAfterPendingHitl_doesNotOrphanCallResponse() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer)
      )
    val session = testSession()
    val firstUser = userEvent("hi", invocationId = "inv_1", timestamp = 100L)
    val firstModel = modelEvent("hello", invocationId = "inv_1", timestamp = 110L)
    // inv_2 issues a function call (call_1) whose response arrives only AFTER a still-pending HITL
    // confirmation (h_1). The call/response pair therefore is split by the pending HITL.
    val callEvent =
      eventWithFunctionCall(
        invocationId = "inv_2",
        timestamp = 200L,
        callName = "lookup",
        callId = "call_1",
      )
    val pendingHitl = eventWithHitlRequest(invocationId = "inv_2", timestamp = 210L, callId = "h_1")
    val responseEvent =
      eventWithFunctionResponse(
        invocationId = "inv_2",
        timestamp = 220L,
        name = "lookup",
        callId = "call_1",
      )
    session.events.addAll(listOf(firstUser, firstModel, callEvent, pendingHitl, responseEvent))

    compactor.compact(session, sessionService)

    // Only the fully-settled first turn is summarized. The call event is excluded because its
    // response is stranded behind the pending HITL; summarizing it would orphan call_1's response.
    assertEquals(listOf(firstUser, firstModel), summarizer.calls.single())
  }

  @Test
  fun compact_nullSummarizer_throwsIllegalArgumentException() = runTest {
    val compactor =
      SlidingWindowEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = null)
      )

    assertFailsWith<IllegalArgumentException> {
      compactor.compact(testSession(), RecordingSessionService())
    }
  }

  // ----- helpers -----

  /**
   * An [EventSummarizer] that records every event list passed to [summarizeEvents] in [calls] and
   * returns the preconfigured [returning] event.
   */
  private class RecordingSummarizer(private val returning: Event? = null) : EventSummarizer {
    val calls: MutableList<List<Event>> = mutableListOf()

    override suspend fun summarizeEvents(events: List<Event>): Event? {
      calls.add(events.toList())
      return returning
    }
  }

  /** A [SessionService] that records every event appended to [appended]. */
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
