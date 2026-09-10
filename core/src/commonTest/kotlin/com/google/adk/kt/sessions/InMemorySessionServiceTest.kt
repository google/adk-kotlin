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

package com.google.adk.kt.sessions

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.testing.SessionServiceAssertions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** Unit tests for [InMemorySessionService]. */
class InMemorySessionServiceTest {

  @Test
  fun createSession_blankId_throws() = runTest {
    val sessionService = InMemorySessionService()

    assertFailsWith<IllegalArgumentException> {
      sessionService.createSession(SessionKey("app-name", "user-id", ""))
    }
    assertFailsWith<IllegalArgumentException> {
      sessionService.createSession(SessionKey("app-name", "user-id", "   "))
    }
  }

  @Test
  fun createSession_explicitId_isHonored() = runTest {
    val sessionService = InMemorySessionService()

    val session =
      sessionService.createSession(SessionKey("app-name", "user-id", "explicit-session-id"))

    assertEquals("explicit-session-id", session.key.id)
    assertNotNull(
      sessionService.getSession(SessionKey("app-name", "user-id", "explicit-session-id"))
    )
  }

  @Test
  fun createSession_idWithSurroundingWhitespace_isUsedVerbatim() = runTest {
    val sessionService = InMemorySessionService()

    val session = sessionService.createSession(SessionKey("app-name", "user-id", "  spaced  "))

    assertEquals("  spaced  ", session.key.id)
    assertNotNull(sessionService.getSession(SessionKey("app-name", "user-id", "  spaced  ")))
  }

  @Test
  fun createSession_nullId_mintsId(): Unit = runBlocking {
    SessionServiceAssertions.createSessionMintsIdWhenAbsent(InMemorySessionService())
  }

  @Test
  fun createSession_withInitialState_retainsState(): Unit = runBlocking {
    SessionServiceAssertions.createSessionRetainsInitialState(InMemorySessionService())
  }

  @Test
  fun listSessions_afterAppend_mergesScopedStateAndOmitsTemp() = runTest {
    val sessionService = InMemorySessionService()

    val session = sessionService.createSession(SessionKey("app-name", "user-id", "session-1"))

    val stateDelta =
      mapOf(
        "sessionKey" to "sessionValue",
        "app:appKey" to "appValue",
        "user:userKey" to "userValue",
        "temp:tempKey" to "tempValue",
      )

    val event =
      Event(
        author = "agent",
        actions =
          EventActions(stateDelta = mutableMapOf<String, Any>().apply { putAll(stateDelta) }),
        timestamp = Clock.System.now().toEpochMilliseconds(),
      )

    assertEquals(event, sessionService.appendEvent(session, event))

    val response = sessionService.listSessions(session.key.appName, session.key.userId)
    val listedSession = response.sessions[0]

    assertEquals(1, response.sessions.size)
    assertEquals(session.key.id, listedSession.key.id)
    assertTrue(listedSession.events.isEmpty())
    assertEquals("sessionValue", listedSession.state["sessionKey"])
    assertEquals("appValue", listedSession.state["app:appKey"])
    assertEquals("userValue", listedSession.state["user:userKey"])
    // `temp:` is ephemeral and never persisted, so it does not appear in the stored/listed state.
    assertFalse(listedSession.state.containsKey("temp:tempKey"))
  }

  @Test
  fun listSessions_includesCreatedSession(): Unit = runBlocking {
    SessionServiceAssertions.listSessionsReturnsUsersSessions(InMemorySessionService())
  }

  @Test
  fun listSessions_unknownUser_isEmpty(): Unit = runBlocking {
    SessionServiceAssertions.listSessionsIsEmptyForUnknownUser(InMemorySessionService())
  }

  @Test
  fun listEvents_missingSession_returnsEmpty(): Unit = runBlocking {
    SessionServiceAssertions.listEventsIsEmptyForUnknownSession(InMemorySessionService())
  }

  @Test
  fun listEvents_returnsAppendsInOrder(): Unit = runBlocking {
    SessionServiceAssertions.listEventsReturnsAppendsInOrder(InMemorySessionService())
  }

  @Test
  fun deleteSession_removesSession(): Unit = runBlocking {
    SessionServiceAssertions.deleteRemovesSession(InMemorySessionService())
  }

  @Test
  fun deleteSession_unknownId_isNoOp(): Unit = runBlocking {
    SessionServiceAssertions.deleteUnknownSessionIsNoOp(InMemorySessionService())
  }

  @Test
  fun appendEvent_removesState() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    val key = session.key

    val stateDeltaAdd =
      mapOf(
        "sessionKey" to "sessionValue",
        "app:appKey" to "appValue",
        "user:userKey" to "userValue",
        "temp:tempKey" to "tempValue",
      )

    val eventAdd =
      Event(
        author = "agent",
        actions =
          EventActions(stateDelta = mutableMapOf<String, Any>().apply { putAll(stateDeltaAdd) }),
        timestamp = Clock.System.now().toEpochMilliseconds(),
      )

    assertEquals(eventAdd, sessionService.appendEvent(session, eventAdd))

    val retrievedSessionAdd = sessionService.getSession(key)
    assertEquals("sessionValue", retrievedSessionAdd?.state?.get("sessionKey"))

    val stateDeltaRemove =
      mapOf(
        "sessionKey" to State.REMOVED,
        "app:appKey" to State.REMOVED,
        "user:userKey" to State.REMOVED,
        "temp:tempKey" to State.REMOVED,
      )

    val eventRemove =
      Event(
        author = "agent",
        actions =
          EventActions(stateDelta = mutableMapOf<String, Any>().apply { putAll(stateDeltaRemove) }),
        timestamp = Clock.System.now().toEpochMilliseconds(),
      )

    assertEquals(eventRemove, sessionService.appendEvent(session, eventRemove))

    val retrievedSessionRemove = sessionService.getSession(key)
    assertNotNull(retrievedSessionRemove)
    assertFalse(retrievedSessionRemove.state.containsKey("sessionKey"))
    assertFalse(retrievedSessionRemove.state.containsKey("app:appKey"))
    assertFalse(retrievedSessionRemove.state.containsKey("user:userKey"))
    assertFalse(retrievedSessionRemove.state.containsKey("temp:tempKey"))
  }

  @Test
  fun appendEvent_tempKey_visibleInInvocationButNotPersisted(): Unit = runBlocking {
    SessionServiceAssertions.tempStateVisibleInInvocationButNotPersisted(InMemorySessionService())
  }

  @Test
  fun appendEvent_tempKey_trimmedFromReturnedEvent(): Unit = runBlocking {
    SessionServiceAssertions.tempStateTrimmedFromReturnedEvent(InMemorySessionService())
  }

  @Test
  fun appendEvent_tempKeyRemoved_reflectedOnLiveSession(): Unit = runBlocking {
    SessionServiceAssertions.tempStateRemovalReflectedOnLiveSession(InMemorySessionService())
  }

  @Test
  fun appendEvent_sessionScopedKey_isPersisted(): Unit = runBlocking {
    SessionServiceAssertions.appendPersistsSessionScopedState(InMemorySessionService())
  }

  @Test
  fun appendEvent_removedSentinel_deletesKey(): Unit = runBlocking {
    SessionServiceAssertions.appendRemovesStateWithSentinel(InMemorySessionService())
  }

  @Test
  fun appendEvent_syncsCallerSession(): Unit = runBlocking {
    SessionServiceAssertions.appendSyncsCallerSession(InMemorySessionService())
  }

  @Test
  fun appendEvent_appScopedKey_sharedAcrossSessions(): Unit = runBlocking {
    SessionServiceAssertions.appScopedStateSharedAcrossSessions(InMemorySessionService())
  }

  @Test
  fun appendEvent_userScopedKey_sharedForSameUser(): Unit = runBlocking {
    SessionServiceAssertions.userScopedStateSharedForSameUser(InMemorySessionService())
  }

  @Test
  fun appendEvent_partial_isNotPersisted(): Unit = runBlocking {
    SessionServiceAssertions.appendPartialNotPersisted(InMemorySessionService())
  }

  @Test
  fun getSession_afterCreate_returnsSession(): Unit = runBlocking {
    SessionServiceAssertions.createdSessionIsRetrievable(InMemorySessionService())
  }

  @Test
  fun getSession_unknownId_returnsNull(): Unit = runBlocking {
    SessionServiceAssertions.getUnknownSessionReturnsNull(InMemorySessionService())
  }

  @Test
  fun getSession_numRecentEventsOnly_returnsMostRecentEvents() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    listOf(100L, 200L, 300L, 400L, 500L).forEach { sessionService.appendEventAt(session, it) }

    val retrieved = sessionService.getSession(session.key, GetSessionConfig(numRecentEvents = 2))

    assertNotNull(retrieved)
    assertEquals(listOf(400L, 500L), retrieved.events.map { it.timestamp })
  }

  @Test
  fun getSession_numRecentEventsZero_returnsNoEvents() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    listOf(100L, 200L, 300L).forEach { sessionService.appendEventAt(session, it) }

    val retrieved = sessionService.getSession(session.key, GetSessionConfig(numRecentEvents = 0))

    assertNotNull(retrieved)
    assertTrue(retrieved.events.isEmpty())
  }

  @Test
  fun getSession_afterTimestampOnly_returnsEventsAtOrAfterTimestamp() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    listOf(100L, 200L, 300L, 400L, 500L).forEach { sessionService.appendEventAt(session, it) }

    val retrieved =
      sessionService.getSession(
        session.key,
        GetSessionConfig(afterTimestamp = Instant.fromEpochMilliseconds(300L)),
      )

    assertNotNull(retrieved)
    assertEquals(listOf(300L, 400L, 500L), retrieved.events.map { it.timestamp })
  }

  @Test
  fun getSession_numRecentEventsAndAfterTimestamp_appliesBothFilters() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    listOf(100L, 200L, 300L, 400L, 500L).forEach { sessionService.appendEventAt(session, it) }

    // Last-4 (200..500) then >=300 drops 200; the pre-fix code kept 200.
    val retrieved =
      sessionService.getSession(
        session.key,
        GetSessionConfig(numRecentEvents = 4, afterTimestamp = Instant.fromEpochMilliseconds(300L)),
      )

    assertNotNull(retrieved)
    assertEquals(listOf(300L, 400L, 500L), retrieved.events.map { it.timestamp })
  }

  @Test
  fun getSession_numRecentEventsTighterThanAfterTimestamp_appliesBothFilters() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    listOf(100L, 200L, 300L, 400L, 500L).forEach { sessionService.appendEventAt(session, it) }

    // afterTimestamp keeps all; confirms numRecentEvents still applies alongside it.
    val retrieved =
      sessionService.getSession(
        session.key,
        GetSessionConfig(numRecentEvents = 2, afterTimestamp = Instant.fromEpochMilliseconds(100L)),
      )

    assertNotNull(retrieved)
    assertEquals(listOf(400L, 500L), retrieved.events.map { it.timestamp })
  }

  private suspend fun InMemorySessionService.appendEventAt(session: Session, timestampMs: Long) {
    val unused =
      appendEvent(
        session,
        Event(
          author = "agent",
          actions = EventActions(stateDelta = mutableMapOf<String, Any>()),
          timestamp = timestampMs,
        ),
      )
  }
}
