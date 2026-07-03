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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioral tests for [RoomSessionService] — covers the [SessionService] contract parity with
 * [InMemorySessionService], persistence-specific cases (cross-reopen survival, stale-write
 * detection, cross-DB isolation), JSON converter round-trips, and entity row construction.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionServiceTest {

  private lateinit var database: AdkSessionsDatabase
  private lateinit var service: RoomSessionService

  @Before
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AdkSessionsDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    service = RoomSessionService(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  /** Calls [RoomSessionService.appendEvent] discarding the (always equal) returned event. */
  private suspend fun RoomSessionService.append(session: Session, event: Event) {
    val unused = appendEvent(session, event)
  }

  /**
   * Builds an `agent`-authored [Event] for use in tests. `stateDelta` defaults to empty;
   * `timestamp` defaults to one millisecond after [Session.lastUpdateTime].
   */
  private fun Session.agentEvent(
    stateDelta: Map<String, Any> = emptyMap(),
    timestamp: Long = lastUpdateTime.toEpochMilliseconds() + 1,
    partial: Boolean = false,
  ): Event =
    Event(
      author = "agent",
      actions = EventActions(stateDelta = stateDelta.toMutableMap()),
      timestamp = timestamp,
      partial = partial,
    )

  // --------- lifecycle parity (port of InMemorySessionServiceTest) ---------

  @Test
  fun lifecycle_noSession() = runTest {
    assertThat(service.getSession(SessionKey("app-name", "user-id", "session-id"))).isNull()
    assertThat(service.listSessions("app-name", "user-id").sessions).isEmpty()
    assertThat(service.listEvents(SessionKey("app-name", "user-id", "session-id")).events).isEmpty()
  }

  @Test
  fun lifecycle_createSession_nullIdGeneratesUuid() = runTest {
    val session = service.createSession(SessionKey("app-name", "user-id", id = null))

    assertThat(session.key.id).isNotNull()
    assertThat(session.key.id!!).isNotEmpty()
    assertThat(session.key.appName).isEqualTo("app-name")
    assertThat(session.key.userId).isEqualTo("user-id")
    assertThat(session.state).isEmpty()
  }

  @Test
  fun createSession_blankId_throws() = runTest {
    runCatching { service.createSession(SessionKey("app-name", "user-id", "")) }
      .also { assertThat(it.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java) }
    runCatching { service.createSession(SessionKey("app-name", "user-id", "   ")) }
      .also { assertThat(it.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java) }
  }

  @Test
  fun lifecycle_createSession_explicitIdIsHonored() = runTest {
    val session = service.createSession(SessionKey("app-name", "user-id", "explicit-session-id"))

    assertThat(session.key.id).isEqualTo("explicit-session-id")
    assertThat(service.getSession(SessionKey("app-name", "user-id", "explicit-session-id")))
      .isNotNull()
  }

  @Test
  fun lifecycle_listSessions_includesMergedStateAndEmptyEvents() = runTest {
    val session = service.createSession(SessionKey("app-name", "user-id", "session-1"))
    service.append(
      session,
      session.agentEvent(
        stateDelta =
          mapOf(
            "sessionKey" to "sessionValue",
            "app:appKey" to "appValue",
            "user:userKey" to "userValue",
          )
      ),
    )

    val response = service.listSessions(session.key.appName, session.key.userId)
    val listed = response.sessions.single()

    assertThat(listed.key.id).isEqualTo(session.key.id)
    assertThat(listed.events).isEmpty()
    assertThat(listed.state["sessionKey"]).isEqualTo("sessionValue")
    assertThat(listed.state["app:appKey"]).isEqualTo("appValue")
    assertThat(listed.state["user:userKey"]).isEqualTo("userValue")
  }

  @Test
  fun lifecycle_deleteSession_missingKey_isNoOp() = runTest {
    service.deleteSession(SessionKey("app-name", "user-id", "missing"))
  }

  @Test
  fun deleteSession_cascadesEvents() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    service.append(session, session.agentEvent())
    assertThat(service.listEvents(session.key).events).hasSize(1)

    service.deleteSession(session.key)

    assertThat(service.listEvents(session.key).events).isEmpty()
  }

  @Test
  fun appendEvent_updatesSessionState() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val event =
      session.agentEvent(
        stateDelta =
          mapOf(
            "sessionKey" to "sessionValue",
            "app:appKey" to "appValue",
            "user:userKey" to "userValue",
          )
      )

    assertThat(service.appendEvent(session, event)).isEqualTo(event)

    val retrieved = service.getSession(session.key)!!
    assertThat(retrieved.state["sessionKey"]).isEqualTo("sessionValue")
    assertThat(retrieved.state["app:appKey"]).isEqualTo("appValue")
    assertThat(retrieved.state["user:userKey"]).isEqualTo("userValue")
  }

  @Test
  fun appendEvent_removesState() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val initialTs = session.lastUpdateTime.toEpochMilliseconds()
    service.append(
      session,
      session.agentEvent(
        stateDelta =
          mapOf(
            "sessionKey" to "sessionValue",
            "app:appKey" to "appValue",
            "user:userKey" to "userValue",
          ),
        timestamp = initialTs + 1,
      ),
    )
    service.append(
      session,
      session.agentEvent(
        stateDelta =
          mapOf(
            "sessionKey" to State.REMOVED,
            "app:appKey" to State.REMOVED,
            "user:userKey" to State.REMOVED,
          ),
        timestamp = initialTs + 2,
      ),
    )

    val retrieved = service.getSession(session.key)!!
    assertThat(retrieved.state.containsKey("sessionKey")).isFalse()
    assertThat(retrieved.state.containsKey("app:appKey")).isFalse()
    assertThat(retrieved.state.containsKey("user:userKey")).isFalse()

    // The REMOVED sentinel must also survive the JSON round-trip in the events table so a future
    // replay sees a real removal instead of an empty map.
    val replayedDelta = service.listEvents(session.key).events.last().actions.stateDelta
    assertThat(replayedDelta["sessionKey"]).isSameInstanceAs(State.REMOVED)
    assertThat(replayedDelta["app:appKey"]).isSameInstanceAs(State.REMOVED)
    assertThat(replayedDelta["user:userKey"]).isSameInstanceAs(State.REMOVED)
  }

  @Test
  fun appendEvent_updatesCallerSessionObject() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val event = session.agentEvent()

    service.append(session, event)

    assertThat(session.events).contains(event)
    assertThat(session.lastUpdateTime).isEqualTo(Instant.fromEpochMilliseconds(event.timestamp))
  }

  @Test
  fun appendEvent_emptyBucket_leavesStateRowUntouched() = runTest {
    // Establish a StorageAppState row with a known updateTime by writing an app: delta first.
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val initialTs = session.lastUpdateTime.toEpochMilliseconds()
    service.append(
      session,
      session.agentEvent(stateDelta = mapOf("app:k" to "v"), timestamp = initialTs + 1),
    )
    val appUpdateTimeAfterFirst = database.sessionsDao().getAppState("app")!!.updateTime

    // Append an event whose delta has NO app: keys — the app_states row should not be touched.
    service.append(
      session,
      session.agentEvent(stateDelta = mapOf("other" to "v"), timestamp = initialTs + 2),
    )

    assertThat(database.sessionsDao().getAppState("app")!!.updateTime)
      .isEqualTo(appUpdateTimeAfterFirst)
  }

  @Test
  fun appendEvent_tempKeys_visibleInMemoryOnly_neverPersisted() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    service.append(
      session,
      session.agentEvent(stateDelta = mapOf("temp:scratch" to "v", "real" to "r")),
    )

    // Caller's in-memory session sees the temp key (it's needed for the rest of the invocation).
    assertThat(session.state["temp:scratch"]).isEqualTo("v")

    // But the temp key is NOT persisted in the sessions.state column ...
    val retrieved = service.getSession(session.key)!!
    assertThat(retrieved.state.containsKey("temp:scratch")).isFalse()
    assertThat(retrieved.state["real"]).isEqualTo("r")

    // ... and is NOT in the persisted event_data either, so a future replay won't re-apply it.
    val replayedDelta = service.listEvents(session.key).events.last().actions.stateDelta
    assertThat(replayedDelta.containsKey("temp:scratch")).isFalse()
    assertThat(replayedDelta["real"]).isEqualTo("r")
  }

  @Test
  fun appendEvent_partialEvent_isNotPersisted() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val partial = session.agentEvent(stateDelta = mapOf("key" to "v"), partial = true)

    assertThat(service.appendEvent(session, partial)).isEqualTo(partial)

    assertThat(service.listEvents(session.key).events).isEmpty()
    assertThat(service.getSession(session.key)!!.state.containsKey("key")).isFalse()
  }

  // --------- GetSessionConfig filters ---------

  @Test
  fun getSession_numRecentEvents_keepsLastN() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    var ts = session.lastUpdateTime.toEpochMilliseconds()
    repeat(5) { service.append(session, session.agentEvent(timestamp = ++ts)) }

    val retrieved = service.getSession(session.key, GetSessionConfig(numRecentEvents = 2))!!

    assertThat(retrieved.events).hasSize(2)
  }

  @Test
  fun getSession_numRecentEventsZero_returnsEmptyEvents() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    service.append(session, session.agentEvent())

    val retrieved = service.getSession(session.key, GetSessionConfig(numRecentEvents = 0))!!

    assertThat(retrieved.events).isEmpty()
  }

  @Test
  fun getSession_afterTimestamp_filtersOlderEvents() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    var ts = session.lastUpdateTime.toEpochMilliseconds()
    val earlyTs = ++ts
    val lateTs = ts + 100
    service.append(session, session.agentEvent(timestamp = earlyTs))
    service.append(session, session.agentEvent(timestamp = lateTs))

    val retrieved =
      service.getSession(
        session.key,
        GetSessionConfig(afterTimestamp = Instant.fromEpochMilliseconds(lateTs)),
      )!!

    assertThat(retrieved.events.map { it.timestamp }).containsExactly(lateTs)
  }

  @Test
  fun getSession_numRecentEventsAndAfterTimestamp_composesBothFilters() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    var ts = session.lastUpdateTime.toEpochMilliseconds()
    val timestamps = List(5) { ++ts }
    for (timestamp in timestamps) {
      service.append(session, session.agentEvent(timestamp = timestamp))
    }

    // numRecentEvents alone would keep the last three (timestamps[2..4]); afterTimestamp restricts
    // to timestamps[3..4] first, so composing the two yields only those two events.
    val retrieved =
      service.getSession(
        session.key,
        GetSessionConfig(
          numRecentEvents = 3,
          afterTimestamp = Instant.fromEpochMilliseconds(timestamps[3]),
        ),
      )!!

    assertThat(retrieved.events.map { it.timestamp })
      .containsExactly(timestamps[3], timestamps[4])
      .inOrder()
  }

  @Test
  fun getSession_numRecentEventsLimitsAfterTimestampResults_keepsMostRecentOldestFirst() = runTest {
    val session = service.createSession(SessionKey("app", "user", "session1"))
    var ts = session.lastUpdateTime.toEpochMilliseconds()
    val timestamps = List(5) { ++ts }
    for (timestamp in timestamps) {
      service.append(session, session.agentEvent(timestamp = timestamp))
    }

    // afterTimestamp keeps timestamps[1..4]; numRecentEvents = 2 then keeps the two most recent,
    // returned oldest-first.
    val retrieved =
      service.getSession(
        session.key,
        GetSessionConfig(
          numRecentEvents = 2,
          afterTimestamp = Instant.fromEpochMilliseconds(timestamps[1]),
        ),
      )!!

    assertThat(retrieved.events.map { it.timestamp })
      .containsExactly(timestamps[3], timestamps[4])
      .inOrder()
  }

  // --------- persistence-specific ---------

  @Test
  fun session_persistsAcrossReopen() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val dbName = "test_persistence_${System.nanoTime()}.db"
    try {
      val firstDb = Room.databaseBuilder(context, AdkSessionsDatabase::class.java, dbName).build()
      val firstService = RoomSessionService(firstDb)
      val session = firstService.createSession(SessionKey("app", "user", "session1"))
      firstService.append(
        session,
        session.agentEvent(stateDelta = mapOf("sessionKey" to "sessionValue")),
      )
      firstDb.close()

      val secondDb = Room.databaseBuilder(context, AdkSessionsDatabase::class.java, dbName).build()
      val secondService = RoomSessionService(secondDb)
      val retrieved = secondService.getSession(SessionKey("app", "user", "session1"))!!
      assertThat(retrieved.state["sessionKey"]).isEqualTo("sessionValue")
      assertThat(retrieved.events).hasSize(1)
      secondDb.close()
    } finally {
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun appState_persistsAcrossSessions() = runTest {
    val firstSession = service.createSession(SessionKey("app", "user-a", "session1"))
    service.append(firstSession, firstSession.agentEvent(stateDelta = mapOf("app:shared" to "v")))

    val secondSession = service.createSession(SessionKey("app", "user-b", "session2"))

    assertThat(secondSession.state["app:shared"]).isEqualTo("v")
  }

  @Test
  fun userState_persistsAcrossSessionsForSameUser() = runTest {
    val firstSession = service.createSession(SessionKey("app", "user-a", "session1"))
    service.append(firstSession, firstSession.agentEvent(stateDelta = mapOf("user:pref" to "v")))

    val sameUserSession = service.createSession(SessionKey("app", "user-a", "session2"))
    val otherUserSession = service.createSession(SessionKey("app", "user-b", "session3"))

    assertThat(sameUserSession.state["user:pref"]).isEqualTo("v")
    assertThat(otherUserSession.state.containsKey("user:pref")).isFalse()
  }

  @Test
  fun getSession_loadedSession_hasNoPendingDelta() = runTest {
    val key = SessionKey("app", "user", "session1")
    val created = service.createSession(key)
    service.append(
      created,
      created.agentEvent(
        stateDelta = mapOf("app:appKey" to "v", "user:userKey" to "v", "sessionKey" to "v")
      ),
    )

    val loaded = service.getSession(key)!!

    // app:/user: state is overlaid for reads...
    assertThat(loaded.state["app:appKey"]).isEqualTo("v")
    assertThat(loaded.state["user:userKey"]).isEqualTo("v")
    // ...but a freshly loaded session must not report any of it as a pending change.
    assertThat(loaded.state.hasDelta).isFalse()
  }

  @Test
  fun appendEvent_callerBehindStorage_throws() = runTest {
    // Storage moved forward (another writer raced us) while the caller still holds the older
    // lastUpdateTime — this is the classic optimistic-concurrency conflict.
    val session = service.createSession(SessionKey("app", "user", "session1"))
    service.append(
      session,
      session.agentEvent(timestamp = session.lastUpdateTime.toEpochMilliseconds() + 100),
    )
    val storageTs = session.lastUpdateTime
    val staleSession =
      session.copy(
        lastUpdateTime = Instant.fromEpochMilliseconds(storageTs.toEpochMilliseconds() - 1)
      )

    val result = runCatching {
      service.appendEvent(
        staleSession,
        staleSession.agentEvent(timestamp = storageTs.toEpochMilliseconds() + 1),
      )
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun appendEvent_callerAheadOfStorage_throws() = runTest {
    // Caller's in-memory session claims a lastUpdateTime newer than storage — e.g. a fabricated
    // Session or a backup-restored DB. Must be rejected rather than silently writing backwards.
    val session = service.createSession(SessionKey("app", "user", "session1"))
    val storageTs = session.lastUpdateTime
    val futureSession =
      session.copy(
        lastUpdateTime = Instant.fromEpochMilliseconds(storageTs.toEpochMilliseconds() + 1_000)
      )

    val result = runCatching {
      service.appendEvent(
        futureSession,
        futureSession.agentEvent(timestamp = futureSession.lastUpdateTime.toEpochMilliseconds() + 1),
      )
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
  }

  // --------- factory ---------

  @Test
  fun fromContext_differentDatabaseNames_isolateStorage() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val dbA = "test_isolated_a_${System.nanoTime()}.db"
    val dbB = "test_isolated_b_${System.nanoTime()}.db"
    try {
      val serviceA = RoomSessionService.fromContext(context, dbA)
      val serviceB = RoomSessionService.fromContext(context, dbB)
      val unused = serviceA.createSession(SessionKey("app", "user", "shared-id"))

      assertThat(serviceA.getSession(SessionKey("app", "user", "shared-id"))).isNotNull()
      assertThat(serviceB.getSession(SessionKey("app", "user", "shared-id"))).isNull()

      // Close the underlying databases via the public close() method (vs. the test fixture's
      // close in @After which only handles the in-memory database).
      serviceA.close()
      serviceB.close()
    } finally {
      context.deleteDatabase(dbA)
      context.deleteDatabase(dbB)
    }
  }

  // A realistic complex tool result for our system: a nested JSON-shaped structure (maps, lists,
  // strings, ints, doubles, booleans, and a null inside a nested map) — the shape tools actually
  // return. It must persist through RoomSessionService and reload without blowing up.
  @Test
  fun runner_functionToolReturningNestedJsonResult_persistsAndReloads() = runTest {
    val toolResult =
      mapOf(
        "location" to "Mountain View",
        "current" to
          mapOf(
            "temp_c" to 42,
            "humidity_pct" to 55,
            "precipitation_mm" to 0.5,
            "is_day" to true,
            "condition" to "sunny",
            "wind_kph" to null,
          ),
        "forecast" to
          listOf(
            mapOf("day" to "Mon", "high_c" to 24, "low_c" to 12, "rain_chance" to 0.1),
            mapOf("day" to "Tue", "high_c" to 22, "low_c" to 11, "rain_chance" to 0.3),
          ),
        "alerts" to emptyList<String>(),
        "metadata" to mapOf("source" to "test", "cached" to false),
      )
    val tool = DummyTool(name = "get_weather") { _, _ -> toolResult }
    val model =
      DummyModel.createSequential(
        "model",
        listOf(
          LlmResponse(
            content =
              Content(
                role = "model",
                parts =
                  listOf(
                    Part(
                      functionCall =
                        FunctionCall(name = "get_weather", args = emptyMap(), id = "call-1")
                    )
                  ),
              )
          ),
          LlmResponse(content = Content(role = "model", parts = listOf(Part(text = "done")))),
        ),
      )
    val agent = LlmAgent(name = "agent", model = model, tools = listOf(tool))
    val runner = InMemoryRunner(agent = agent, appName = "app", sessionService = service)

    // Must not throw.
    val events =
      runner
        .runAsync(
          userId = "user",
          sessionId = "session-weather",
          newMessage = Content(role = "user", parts = listOf(Part(text = "weather?"))),
        )
        .toList()
    assertThat(events).isNotEmpty()
    assertThat(events.mapNotNull { it.errorCode }).isEmpty()

    // The function-response event survives a fresh read from Room with its nested structure intact.
    val reloaded = service.getSession(SessionKey("app", "user", "session-weather"))
    assertThat(reloaded).isNotNull()
    val response =
      reloaded!!
        .events
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionResponse }
        .first { it.name == "get_weather" }
        .response
    assertThat(response["location"]).isEqualTo("Mountain View")
    assertThat(response["forecast"] as List<*>).hasSize(2)
    assertThat(response["current"] as Map<*, *>).containsEntry("condition", "sunny")
  }
}
