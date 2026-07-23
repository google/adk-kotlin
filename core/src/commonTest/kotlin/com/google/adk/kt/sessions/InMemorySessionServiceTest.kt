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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** Unit tests for [InMemorySessionService]. */
class InMemorySessionServiceTest {

  @Test
  fun lifecycle_noSession() = runTest {
    val sessionService = InMemorySessionService()

    assertNull(sessionService.getSession(SessionKey("app-name", "user-id", "session-id")))
    assertTrue(sessionService.listSessions("app-name", "user-id").sessions.isEmpty())
    assertTrue(
      sessionService.listEvents(SessionKey("app-name", "user-id", "session-id")).events.isEmpty()
    )
  }

  @Test
  fun lifecycle_createSession_nullIdGeneratesUuid() = runTest {
    val sessionService = InMemorySessionService()

    val session = sessionService.createSession(SessionKey("app-name", "user-id", id = null))

    assertTrue(session.key.id!!.isNotEmpty())
    assertEquals("app-name", session.key.appName)
    assertEquals("user-id", session.key.userId)
    assertTrue(session.state.isEmpty())
  }

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
  fun lifecycle_createSession_explicitIdIsHonored() = runTest {
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
  fun lifecycle_getSession() = runTest {
    val sessionService = InMemorySessionService()

    val session = sessionService.createSession(SessionKey("app-name", "user-id", id = null))

    val retrievedSession = sessionService.getSession(session.key)

    assertNotNull(retrievedSession)
    assertEquals(session.key.id, retrievedSession.key.id)
  }

  @Test
  fun lifecycle_listSessions() = runTest {
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
    assertEquals("tempValue", listedSession.state["temp:tempKey"])
  }

  @Test
  fun lifecycle_deleteSession() = runTest {
    val sessionService = InMemorySessionService()

    val session = sessionService.createSession(SessionKey("app-name", "user-id", id = null))
    val key = session.key

    sessionService.deleteSession(key)

    assertNull(sessionService.getSession(key))
  }

  @Test
  fun lifecycle_deleteSession_missingKey_isNoOp() = runTest {
    val sessionService = InMemorySessionService()

    // Should not throw.
    sessionService.deleteSession(SessionKey("app-name", "user-id", "missing"))
  }

  @Test
  fun appendEvent_updatesSessionState() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))

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

    val retrievedSession = sessionService.getSession(session.key)
    assertEquals("sessionValue", retrievedSession?.state?.get("sessionKey"))
    assertEquals("appValue", retrievedSession?.state?.get("app:appKey"))
    assertEquals("userValue", retrievedSession?.state?.get("user:userKey"))
    assertEquals("tempValue", retrievedSession?.state?.get("temp:tempKey"))
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
  fun sequentialAgents_shareTempState() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))
    val key = session.key

    val stateDelta1 = mapOf("temp:agent1_output" to "data")
    val event1 =
      Event(
        author = "agent",
        actions =
          EventActions(stateDelta = mutableMapOf<String, Any>().apply { putAll(stateDelta1) }),
        timestamp = Clock.System.now().toEpochMilliseconds(),
      )
    assertEquals(event1, sessionService.appendEvent(session, event1))

    var retrievedSession = sessionService.getSession(key)
    assertEquals("data", retrievedSession?.state?.get("temp:agent1_output"))

    val stateDelta2 =
      mapOf("temp:agent2_output" to "processed_data", "temp:agent1_output" to State.REMOVED)
    val event2 =
      Event(
        author = "agent",
        actions =
          EventActions(stateDelta = mutableMapOf<String, Any>().apply { putAll(stateDelta2) }),
        timestamp = Clock.System.now().toEpochMilliseconds(),
      )
    assertEquals(event2, sessionService.appendEvent(session, event2))

    retrievedSession = sessionService.getSession(key)
    assertNotNull(retrievedSession)
    assertFalse(retrievedSession.state.containsKey("temp:agent1_output"))
    assertEquals("processed_data", retrievedSession.state.get("temp:agent2_output"))
  }

  @Test
  fun appendEvent_updatesCallerSessionObject() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))

    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf<String, Any>()),
        timestamp = 123456789L,
      )

    assertEquals(event, sessionService.appendEvent(session, event))
    assertTrue(session.events.contains(event))
    assertEquals(Instant.fromEpochMilliseconds(123456789L), session.lastUpdateTime)
  }

  @Test
  fun appendEvent_updatesCallerSessionState() = runTest {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "session1"))

    val event =
      Event(
        author = "agent",
        actions = EventActions(stateDelta = mutableMapOf<String, Any>("key" to "value")),
        timestamp = 123456789L,
      )

    assertEquals(event, sessionService.appendEvent(session, event))

    val retrievedSession = sessionService.getSession(session.key)
    assertEquals("value", retrievedSession?.state?.get("key"))
    assertTrue(session.state.containsKey("key"))
    assertEquals("value", session.state["key"])
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
