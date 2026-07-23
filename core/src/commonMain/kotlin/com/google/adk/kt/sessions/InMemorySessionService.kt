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

import com.google.adk.kt.collections.concurrentMutableListOf
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory implementation of [SessionService] assuming [Session] objects are mutable regarding
 * their state map, events list, and last update time.
 *
 * This implementation stores sessions, user state, and app state directly in memory in a
 * thread-safe manner. It is suitable for testing or single-node deployments where persistence is
 * not required.
 *
 * Note: State merging (app/user state prefixed with `app:` / `user:`) occurs during retrieval
 * operations ([getSession], [createSession]).
 */
class InMemorySessionService : SessionService {
  private val mutex = Mutex()
  private val sessions: MutableMap<SessionKey, Session> = mutableMapOf()
  private val userState: MutableMap<UserKey, MutableMap<String, Any>> = mutableMapOf()
  private val appState: MutableMap<String, MutableMap<String, Any>> = mutableMapOf()

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
    require(key.id == null || key.id.isNotBlank()) { "SessionKey.id must not be blank" }
    return mutex.withLock {
      val resolvedSessionId = key.id ?: Uuid.random()

      val newSession =
        Session(
          key = SessionKey(key.appName, key.userId, resolvedSessionId),
          state = State(initialState = state ?: emptyMap()),
          events = mutableListOf(),
          lastUpdateTime = Clock.System.now(),
        )

      sessions[SessionKey(key.appName, key.userId, resolvedSessionId)] = newSession

      // Create a mutable copy and merge global state into it before returning.
      mergeWithGlobalState(key.appName, key.userId, copySession(newSession))
    }
  }

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
    mutex.withLock {
      val storedSession = sessions[key] ?: return null

      // Filter with non-mutating operations and build the copy from the result: the copy's event
      // list is concurrent (CopyOnWriteArrayList), which does not support in-place removeAll /
      // subList clearing.
      val sessionCopy = copySession(storedSession, filterEvents(storedSession.events, config))

      mergeWithGlobalState(key.appName, key.userId, sessionCopy)
    }

  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse =
    mutex.withLock {
      val sessionCopies =
        sessions.entries
          .asSequence()
          .filter { it.key.appName == appName && it.key.userId == userId }
          .map { (_, session) ->
            // Create copies with empty events and state for the response
            val copy = copySession(session)
            copy.events.clear()
            mergeWithGlobalState(appName, userId, copy)
          }
          .toList()

      ListSessionsResponse(sessions = sessionCopies)
    }

  override suspend fun deleteSession(key: SessionKey) {
    mutex.withLock { sessions.remove(key) }
  }

  override suspend fun listEvents(key: SessionKey): ListEventsResponse = mutex.withLock {
    val storedSession = sessions[key] ?: return ListEventsResponse()

    ListEventsResponse(events = storedSession.events.toList())
  }

  override suspend fun appendEvent(session: Session, event: Event): Event = mutex.withLock {
    val storedSession =
      sessions[session.key] ?: throw IllegalStateException("Session not found: ${session.key.id}")

    // Apply state delta logic to storedSession's state or global app/user state
    for ((stateKey, value) in event.actions.stateDelta) {
      when {
        stateKey.startsWith(State.APP_PREFIX) -> {
          val appStateKey = stateKey.substring(State.APP_PREFIX.length)
          val appMap = appState.getOrPut(storedSession.key.appName) { mutableMapOf() }
          if (value === State.REMOVED) {
            appMap.remove(appStateKey)
          } else {
            appMap[appStateKey] = value
          }
        }
        stateKey.startsWith(State.USER_PREFIX) -> {
          val userStateKey = stateKey.substring(State.USER_PREFIX.length)
          val userMap =
            userState.getOrPut(UserKey(storedSession.key.appName, storedSession.key.userId)) {
              mutableMapOf()
            }
          if (value === State.REMOVED) {
            userMap.remove(userStateKey)
          } else {
            userMap[userStateKey] = value
          }
        }
        else -> {
          if (value === State.REMOVED) {
            storedSession.state.remove(stateKey)
          } else {
            storedSession.state[stateKey] = value
          }
        }
      }
    }

    // Add event to the stored session's list
    storedSession.events.add(event)
    storedSession.lastUpdateTime = Instant.fromEpochMilliseconds(event.timestamp)
    // Also add to the session object passed by the caller to keep it in sync.
    val unused = super.appendEvent(session, event)

    return event
  }

  private fun copySession(original: Session, events: List<Event> = original.events): Session {
    return Session(
      key = original.key,
      state = State(initialState = original.state),
      // Keep the copy's event list concurrent too (toMutableList() would return a plain ArrayList),
      // so a caller iterating it while the runner appends does not hit a CME.
      events = concurrentMutableListOf<Event>().apply { addAll(events) },
      lastUpdateTime = original.lastUpdateTime,
    )
  }

  /**
   * Keeps the last [GetSessionConfig.numRecentEvents], then those at/after
   * [GetSessionConfig.afterTimestamp]. Both compose, mirroring Python's `InMemorySessionService`.
   */
  private fun filterEvents(events: List<Event>, config: GetSessionConfig?): List<Event> {
    if (config == null) return events
    var filtered = events
    config.numRecentEvents?.let { filtered = filtered.takeLast(it) }
    config.afterTimestamp?.let { threshold ->
      filtered = filtered.filterNot { Instant.fromEpochMilliseconds(it.timestamp) < threshold }
    }
    return filtered
  }

  private fun mergeWithGlobalState(appName: String, userId: String, session: Session): Session {
    appState[appName]?.forEach { (key, value) -> session.state["${State.APP_PREFIX}$key"] = value }
    userState[UserKey(appName, userId)]?.forEach { (key, value) ->
      session.state["${State.USER_PREFIX}$key"] = value
    }

    return session
  }

  private data class UserKey(val appName: String, val userId: String)
}
