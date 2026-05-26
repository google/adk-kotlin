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
import androidx.room.Room
import androidx.room.withTransaction
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A persistent [SessionService] backed by a Room/SQLite database in the consumer app's private
 * storage.
 *
 * Sessions, events, and app/user state survive process death and device reboot. Use this in place
 * of [InMemorySessionService] when constructing a [com.google.adk.kt.runners.Runner] in an Android
 * consumer app.
 *
 * Default usage — one instance per `Application`:
 * ```kotlin
 * val sessionService = RoomSessionService.fromContext(applicationContext)
 * ```
 *
 * Pass a distinct [databaseName] to give an agent its own SQLite file (e.g. when two agents in the
 * same app should not see each other's sessions even within the same `appName`):
 * ```kotlin
 * val agentASessions = RoomSessionService.fromContext(context, "agent_a.db")
 * val agentBSessions = RoomSessionService.fromContext(context, "agent_b.db")
 * ```
 *
 * Dispatching is handled by Room — `suspend fun` DAO calls run on Room's internal transaction
 * executor, so this class does not need to wrap calls in `withContext`.
 */
class RoomSessionService internal constructor(private val database: AdkSessionsDatabase) :
  SessionService {

  private val dao: SessionsDao = database.sessionsDao()

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
    require(key.id == null || key.id.isNotBlank()) { "SessionKey.id must not be blank" }
    val resolvedId = key.id ?: Uuid.random()
    val now = Clock.System.now().toEpochMilliseconds()

    // Wrap the session insert and the app/user state seeds in a single transaction so a partial
    // failure (e.g. process death) cannot leave a session row without its required state seeds.
    database.withTransaction {
      dao.insertSession(
        SessionRow(
          appName = key.appName,
          userId = key.userId,
          id = resolvedId,
          state = state ?: emptyMap(),
          createTimeEpochMs = now,
          updateTimeEpochMs = now,
        )
      )
      dao.insertAppStateIfAbsent(
        AppStateRow(appName = key.appName, state = emptyMap(), updateTimeEpochMs = now)
      )
      dao.insertUserStateIfAbsent(
        UserStateRow(
          appName = key.appName,
          userId = key.userId,
          state = emptyMap(),
          updateTimeEpochMs = now,
        )
      )
    }

    val resolvedKey = SessionKey(key.appName, key.userId, resolvedId)
    return buildSession(
      key = resolvedKey,
      sessionState = state ?: emptyMap(),
      events = mutableListOf(),
      lastUpdateMs = now,
    )
  }

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    val id = requireNotNull(key.id) { "SessionKey.id must not be null for getSession" }
    // Wrap reads in a transaction so the returned Session is a consistent snapshot across
    // session row, events, and app/user state — concurrent appendEvent calls can't tear it.
    return database.withTransaction {
      val row = dao.getSession(key.appName, key.userId, id) ?: return@withTransaction null

      val numRecent = config?.numRecentEvents
      val sinceTimestampMs = config?.afterTimestamp?.toEpochMilliseconds()
      val eventRows: List<EventRow> =
        when {
          numRecent == 0 -> emptyList()
          numRecent != null -> dao.listRecentEvents(key.appName, key.userId, id, numRecent)
          sinceTimestampMs != null ->
            dao.listEventsAfter(key.appName, key.userId, id, sinceTimestampMs)
          else -> dao.listEvents(key.appName, key.userId, id)
        }

      buildSession(
        key = SessionKey(row.appName, row.userId, row.id),
        sessionState = row.state,
        events = eventRows.map { JsonConverters.eventFromJson(it.eventData) }.toMutableList(),
        lastUpdateMs = row.updateTimeEpochMs,
      )
    }
  }

  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
    // Wrap reads in a transaction so the returned list is a consistent snapshot across
    // sessions and app/user state — concurrent appendEvent calls can't tear it.
    val sessions = database.withTransaction {
      val rows = dao.listSessions(appName, userId)
      // Fetch the per-(appName, userId) global state rows once for the whole list, not
      // per-session.
      val appState = dao.getAppState(appName)
      val userState = dao.getUserState(appName, userId)
      rows.map { row ->
        buildSession(
          key = SessionKey(row.appName, row.userId, row.id),
          sessionState = row.state,
          events = mutableListOf(),
          lastUpdateMs = row.updateTimeEpochMs,
          prefetchedAppState = appState,
          prefetchedUserState = userState,
        )
      }
    }
    return ListSessionsResponse(sessions = sessions)
  }

  override suspend fun deleteSession(key: SessionKey) {
    val id = requireNotNull(key.id) { "SessionKey.id must not be null for deleteSession" }
    dao.deleteSession(key.appName, key.userId, id)
    // Events cascade via FK ON DELETE CASCADE.
  }

  override suspend fun listEvents(key: SessionKey): ListEventsResponse {
    val id = requireNotNull(key.id) { "SessionKey.id must not be null for listEvents" }
    val rows = dao.listEvents(key.appName, key.userId, id)
    val events = rows.map { JsonConverters.eventFromJson(it.eventData) }
    return ListEventsResponse(events = events)
  }

  /**
   * Closes the underlying Room database, releasing the file handle. Primarily useful for tests that
   * construct and destroy services repeatedly. In a normal app the database stays open for the
   * process lifetime — Android does not require explicit close.
   */
  fun close() {
    database.close()
  }

  override suspend fun appendEvent(session: Session, event: Event): Event {
    if (event.partial) {
      // Match SessionService base behavior: partial events are short-circuited.
      return event
    }
    val id = requireNotNull(session.key.id) { "Session.key.id must not be null for appendEvent" }
    val appName = session.key.appName
    val userId = session.key.userId

    // Apply `temp:` keys to the caller's in-memory Session state only — they live for the
    // current invocation but must not be persisted. Mirrors Python's _apply_temp_state. This
    // happens BEFORE the persistence step so subsequent agents within the invocation can read
    // them (e.g. SequentialAgent reading a `temp:` output_key).
    for ((k, v) in event.actions.stateDelta) {
      if (k.startsWith(State.TEMP_PREFIX)) session.state[k] = v
    }

    // Split the persistable delta into three buckets (app / user / session). `temp:` keys are
    // dropped here so they never reach the state tables. Mirrors Python's
    // _trim_temp_delta_state + the prefix-based bucketing in _update_session_state.
    val appDelta = mutableMapOf<String, Any>()
    val userDelta = mutableMapOf<String, Any>()
    val sessionDelta = mutableMapOf<String, Any>()
    for ((k, v) in event.actions.stateDelta) {
      when {
        k.startsWith(State.TEMP_PREFIX) -> Unit // already applied above; not persisted
        k.startsWith(State.APP_PREFIX) -> appDelta[k.substring(State.APP_PREFIX.length)] = v
        k.startsWith(State.USER_PREFIX) -> userDelta[k.substring(State.USER_PREFIX.length)] = v
        else -> sessionDelta[k] = v
      }
    }

    // Serialize a copy of the event with `temp:` keys stripped from its stateDelta so the
    // persisted event log doesn't carry them either — otherwise a future replay would
    // re-introduce them. Caller's Event is untouched.
    val eventForStorage = trimTempKeysFromEvent(event)

    // The read-merge-write for each non-empty bucket happens inside the @Transaction so concurrent
    // appends across sessions of the same appName/userId cannot lose updates.
    dao.appendEventAtomic(
      appName = appName,
      userId = userId,
      sessionId = id,
      expectedUpdateTime = session.lastUpdateTime.toEpochMilliseconds(),
      appDelta = appDelta,
      userDelta = userDelta,
      sessionDelta = sessionDelta,
      eventRow =
        EventRow(
          id = event.id,
          appName = appName,
          userId = userId,
          sessionId = id,
          invocationId = event.invocationId,
          timestamp = event.timestamp,
          eventData = JsonConverters.eventToJson(eventForStorage),
        ),
    )

    // Sync the caller's in-memory Session object, mirroring InMemorySessionService.appendEvent.
    // State.applyDelta inside super.appendEvent ignores `temp:` keys (we already handled them
    // above) and applies the rest to the caller's session.state.
    val unused = super.appendEvent(session, event)
    return event
  }

  /**
   * Returns a copy of [event] whose `actions.stateDelta` has all `temp:` keys removed, leaving the
   * caller's [event] untouched.
   */
  private fun trimTempKeysFromEvent(event: Event): Event {
    if (event.actions.stateDelta.none { it.key.startsWith(State.TEMP_PREFIX) }) return event
    val trimmedDelta =
      event.actions.stateDelta.filterKeys { !it.startsWith(State.TEMP_PREFIX) }.toMutableMap()
    return event.copy(actions = event.actions.copy(stateDelta = trimmedDelta))
  }

  /**
   * Builds a [Session] with the session-scoped state merged with current app/user state.
   *
   * If [prefetchedAppState] / [prefetchedUserState] are supplied (e.g. by [listSessions], which
   * fetches them once per `(appName, userId)` instead of per session row), they are used directly
   * to avoid N+1 DAO queries. Otherwise the rows are fetched lazily.
   */
  private suspend fun buildSession(
    key: SessionKey,
    sessionState: Map<String, Any>,
    events: MutableList<Event>,
    lastUpdateMs: Long,
    prefetchedAppState: AppStateRow? = null,
    prefetchedUserState: UserStateRow? = null,
  ): Session {
    val appRow = prefetchedAppState ?: dao.getAppState(key.appName)
    val userRow = prefetchedUserState ?: dao.getUserState(key.appName, key.userId)
    // Build the full state map first, then construct State once. Using State.set (`merged[k] = v`)
    // would record every app:/user: key in the delta, so a freshly loaded session would wrongly
    // report the whole app/user state as modified this invocation.
    val mergedState = sessionState.toMutableMap()
    appRow?.state?.forEach { (k, v) -> mergedState["${State.APP_PREFIX}$k"] = v }
    userRow?.state?.forEach { (k, v) -> mergedState["${State.USER_PREFIX}$k"] = v }
    return Session(
      key = key,
      state = State(initialState = mergedState),
      events = events,
      lastUpdateTime = Instant.fromEpochMilliseconds(lastUpdateMs),
    )
  }

  companion object {
    /** Default DB filename under `<context.applicationContext>/databases/`. */
    const val DEFAULT_DATABASE_NAME: String = "adk_sessions.db"

    /**
     * Builds a [RoomSessionService] backed by a Room database under
     * `<applicationContext.getDatabasePath(databaseName)>`.
     *
     * Uses [Context.getApplicationContext] internally to avoid Activity leaks.
     */
    fun fromContext(
      context: Context,
      databaseName: String = DEFAULT_DATABASE_NAME,
    ): RoomSessionService {
      val database =
        Room.databaseBuilder(
            context.applicationContext,
            AdkSessionsDatabase::class.java,
            databaseName,
          )
          .build()
      return RoomSessionService(database)
    }
  }
}
