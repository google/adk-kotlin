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
import com.google.adk.kt.testing.modelEvent
import com.google.adk.kt.testing.modelEventWithPromptTokens
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TokenThresholdEventCompactorTest {

  @Test
  fun compact_noTokenThresholdConfig_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    // Sliding-window-only config has no token-threshold fields, so this must be a no-op.
    val compactor =
      TokenThresholdEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1, summarizer = summarizer),
        agentName = "agent",
        branch = null,
      )
    val session = testSession()
    session.events.add(modelEventWithPromptTokens(150, invocationId = "inv_1", timestamp = 100L))

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_noTokenThresholdConfig_doesNotRequireSummarizer() = runTest {
    val sessionService = RecordingSessionService()
    // No token-threshold fields and no summarizer: compact() must no-op via the
    // hasTokenThresholdConfig() gate, before requireNotNull(summarizer) would throw.
    val compactor =
      TokenThresholdEventCompactor(
        EventsCompactionConfig(compactionInterval = 2, overlapSize = 1),
        agentName = "agent",
        branch = null,
      )
    val session = testSession()
    session.events.add(modelEventWithPromptTokens(500, invocationId = "inv_1", timestamp = 100L))

    compactor.compact(session, sessionService)

    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_belowThreshold_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 2, summarizer)
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("m1", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("u2", invocationId = "inv_2", timestamp = 200L))
    // Most recent prompt reported only 50 tokens; below the threshold of 100.
    session.events.add(modelEventWithPromptTokens(50, invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_aboveThreshold_appendsCompactionEvent() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 2, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    val e2 = modelEvent("m1", invocationId = "inv_1", timestamp = 110L)
    val e3 = userEvent("u2", invocationId = "inv_2", timestamp = 200L)
    val e4 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e1, e2, e3, e4))

    compactor.compact(session, sessionService)

    // retention=2 keeps e3,e4 raw; the older e1,e2 are summarized.
    assertEquals(listOf(e1, e2), summarizer.calls.single())
    assertEquals(1, sessionService.appended.size)
  }

  @Test
  fun compact_atExactThreshold_appendsCompactionEvent() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 110L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 2, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    val e2 = modelEvent("m1", invocationId = "inv_1", timestamp = 110L)
    val e3 = userEvent("u2", invocationId = "inv_2", timestamp = 200L)
    // Reports exactly the threshold (100); compaction fires because the gate is `>=`.
    val e4 = modelEventWithPromptTokens(100, invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e1, e2, e3, e4))

    compactor.compact(session, sessionService)

    assertEquals(listOf(e1, e2), summarizer.calls.single())
    assertEquals(1, sessionService.appended.size)
  }

  @Test
  fun compact_candidatesBelowRetention_doesNothing() = runTest {
    val summarizer = RecordingSummarizer()
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 5, summarizer)
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("m1", invocationId = "inv_1", timestamp = 110L))
    session.events.add(userEvent("u2", invocationId = "inv_2", timestamp = 200L))
    session.events.add(modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 210L))

    compactor.compact(session, sessionService)

    // 4 candidate events <= retention of 5: nothing to summarize even though the threshold is met.
    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_eventRetentionZero_compactsAllCandidates() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 210L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 0, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    val e2 = modelEvent("m1", invocationId = "inv_1", timestamp = 110L)
    val e3 = userEvent("u2", invocationId = "inv_2", timestamp = 200L)
    val e4 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e1, e2, e3, e4))

    compactor.compact(session, sessionService)

    assertEquals(listOf(e1, e2, e3, e4), summarizer.calls.single())
  }

  @Test
  fun compact_callWithResponseInRetainedTail_doesNotSummarizeCall() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 100L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 2, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    // The retention boundary (size 4 - retention 2 = 2) lands between the call and its response.
    val call =
      eventWithFunctionCall(invocationId = "inv_2", timestamp = 200L, callName = "t", callId = "c1")
    val response =
      eventWithFunctionResponse(invocationId = "inv_2", timestamp = 210L, name = "t", callId = "c1")
    val e4 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 220L)
    session.events.addAll(listOf(e1, call, response, e4))

    compactor.compact(session, sessionService)

    // longestSelfContainedPrefix drops the call, so the call and
    // its response stay together in the raw tail; only e1 is compacted.
    assertEquals(listOf(e1), summarizer.calls.single())
  }

  @Test
  fun compact_longestSelfContainedPrefix_trimsOpenCall() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 100L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 1, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    // An unmatched (open) function call must not be summarized away from a future response.
    val openCall =
      eventWithFunctionCall(invocationId = "inv_2", timestamp = 200L, callName = "t", callId = "c1")
    val e3 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e1, openCall, e3))

    compactor.compact(session, sessionService)

    // The open call is trimmed from the window; only e1 is compacted.
    assertEquals(listOf(e1), summarizer.calls.single())
  }

  @Test
  fun compact_prefixTrimsToEmpty_doesNothing() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 100L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 1, summarizer)
    val session = testSession()
    // The only candidate to compact is an unmatched (open) function call, so the
    // longestSelfContainedPrefix trims the window to empty -> no summary is produced.
    val openCall =
      eventWithFunctionCall(invocationId = "inv_1", timestamp = 100L, callName = "t", callId = "c1")
    val e2 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 200L)
    session.events.addAll(listOf(openCall, e2))

    compactor.compact(session, sessionService)

    assertTrue(summarizer.calls.isEmpty())
    assertTrue(sessionService.appended.isEmpty())
  }

  @Test
  fun compact_includesPriorSummaryInWindow() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 200L))
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 1, summarizer)
    val session = testSession()
    session.events.add(userEvent("u1", invocationId = "inv_1", timestamp = 100L))
    session.events.add(modelEvent("m1", invocationId = "inv_1", timestamp = 110L))
    session.events.add(
      compactionEvent(startTs = 100L, endTs = 110L, timestamp = 115L, summary = "prior summary")
    )
    val e3 = userEvent("u2", invocationId = "inv_2", timestamp = 200L)
    val e4 = modelEventWithPromptTokens(150, invocationId = "inv_2", timestamp = 210L)
    session.events.addAll(listOf(e3, e4))

    compactor.compact(session, sessionService)

    val window = summarizer.calls.single()
    // Window is [(prior summary), e3]: events at/under the prior compaction boundary (110) are
    // excluded, e4 is retained, and the prior summary is seeded so the new summary supersedes it.
    assertEquals(2, window.size)
    assertEquals(100L, window[0].timestamp)
    assertEquals("prior summary", window[0].content?.parts?.single()?.text)
    assertEquals(e3, window[1])
  }

  @Test
  fun compact_noUsageMetadata_estimateFallbackTriggers() = runTest {
    val summarizer = RecordingSummarizer(returning = compactionEvent(startTs = 100L, endTs = 100L))
    val sessionService = RecordingSessionService()
    val compactor = tokenThresholdCompactor(tokenThreshold = 10, eventRetentionSize = 1, summarizer)
    val session = testSession()
    // No usage metadata anywhere: 160 text chars -> ~40 estimated tokens, over the threshold of 10.
    val e1 = userEvent("a".repeat(80), invocationId = "inv_1", timestamp = 100L)
    val e2 = userEvent("b".repeat(80), invocationId = "inv_2", timestamp = 200L)
    session.events.addAll(listOf(e1, e2))

    compactor.compact(session, sessionService)

    assertEquals(listOf(e1), summarizer.calls.single())
    assertEquals(1, sessionService.appended.size)
  }

  @Test
  fun compact_nullSummarizer_throwsIllegalArgumentException() = runTest {
    val compactor =
      TokenThresholdEventCompactor(
        EventsCompactionConfig(tokenThreshold = 100, eventRetentionSize = 1, summarizer = null),
        agentName = "agent",
        branch = null,
      )

    assertFailsWith<IllegalArgumentException> {
      compactor.compact(testSession(), RecordingSessionService())
    }
  }

  @Test
  fun latestCompactionEvent_noCompactions_returnsNull() {
    val events = listOf(userEvent("u1", timestamp = 100L), modelEvent("m1", timestamp = 110L))

    assertNull(latestCompactionEvent(events))
  }

  @Test
  fun latestCompactionEvent_rollingChain_returnsNewest() {
    // Nested rolling summaries: each new one has the same start and a growing end, so it subsumes
    // its predecessor. The newest survives.
    val c1 = compactionEvent(startTs = 0L, endTs = 100L, summary = "c1")
    val c2 = compactionEvent(startTs = 0L, endTs = 200L, summary = "c2")
    val c3 = compactionEvent(startTs = 0L, endTs = 300L, summary = "c3")

    assertSame(c3, latestCompactionEvent(listOf(c1, c2, c3)))
  }

  @Test
  fun latestCompactionEvent_laterCompactionSubsumed_returnsContainingEarlier() {
    // The later compaction is fully inside the earlier one, so it is subsumed and the earlier
    // (containing) compaction wins even though it is not last in stream.
    val big = compactionEvent(startTs = 0L, endTs = 300L, summary = "big")
    val inner = compactionEvent(startTs = 100L, endTs = 200L, summary = "inner")

    assertSame(big, latestCompactionEvent(listOf(big, inner)))
  }

  @Test
  fun latestCompactionEvent_identicalRanges_returnsLaterInStream() {
    // Identical ranges: the earlier event is treated as subsumed by the later one.
    val earlier = compactionEvent(startTs = 0L, endTs = 100L, summary = "earlier")
    val later = compactionEvent(startTs = 0L, endTs = 100L, summary = "later")

    assertSame(later, latestCompactionEvent(listOf(earlier, later)))
  }

  @Test
  fun latestCompactionEvent_overlappingRanges_prefersStreamOrderOverMaxEndTimestamp() {
    // `a` ends later, but `b` is appended later and neither range contains the other. Stream order
    // selects `b`, whereas selecting by max endTimestamp (the prior behavior) would pick `a`.
    val a = compactionEvent(startTs = 100L, endTs = 300L, summary = "a")
    val b = compactionEvent(startTs = 0L, endTs = 200L, summary = "b")
    val events = listOf(a, b)

    assertSame(b, latestCompactionEvent(events))
  }

  @Test
  fun compact_sameTimestampAtRetentionBoundary_retainsWholeTieGroup() = runTest {
    // A same-timestamp group is never split across the compaction boundary: only events strictly
    // below the first retained event's timestamp are summarized. A and B share ts=200, so with
    // retention=1 both stay retained and only e1 (ts=100) is summarized, keeping the summary's
    // endTimestamp below every retained event.
    val summarizer =
      object : EventSummarizer {
        val windows = mutableListOf<List<Event>>()

        override suspend fun summarizeEvents(events: List<Event>): Event {
          windows.add(events.toList())
          return compactionEvent(
            startTs = events.first().timestamp,
            endTs = events.last().timestamp,
            summary = "SUM",
          )
        }
      }
    val sessionService = RecordingSessionService()
    val compactor =
      tokenThresholdCompactor(tokenThreshold = 100, eventRetentionSize = 1, summarizer)
    val session = testSession()
    val e1 = userEvent("u1", invocationId = "inv_1", timestamp = 100L)
    val a = modelEvent("A", invocationId = "inv_2", timestamp = 200L)
    val b = modelEventWithPromptTokens(150, text = "B", invocationId = "inv_2", timestamp = 200L)
    session.events.addAll(listOf(e1, a, b))

    compactor.compact(session, sessionService)

    // Only e1 is summarized; the whole same-timestamp group (A, B) is retained, and the summary's
    // endTimestamp (100) stays strictly below the retained events' timestamp (200).
    assertEquals(1, sessionService.appended.size)
    assertEquals(listOf(e1), summarizer.windows.single())
    assertEquals(100L, sessionService.appended.single().actions.compaction?.endTimestamp)
  }

  // ----- helpers -----

  private fun tokenThresholdCompactor(
    tokenThreshold: Int,
    eventRetentionSize: Int,
    summarizer: EventSummarizer,
  ): TokenThresholdEventCompactor =
    TokenThresholdEventCompactor(
      EventsCompactionConfig(
        tokenThreshold = tokenThreshold,
        eventRetentionSize = eventRetentionSize,
        summarizer = summarizer,
      ),
      agentName = "agent",
      branch = null,
    )

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
