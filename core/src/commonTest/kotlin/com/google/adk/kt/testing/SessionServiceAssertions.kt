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

package com.google.adk.kt.testing

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat

/**
 * Shared, cross-backend assertions every [SessionService] implementation must satisfy, grouped in
 * an object so the suite occupies a single name in the shared testing package. Each method verifies
 * ONE behavior, so a backend test delegates one `@Test` per method and adds its own
 * backend-specific tests on top. Event timestamps are advanced off the live session's
 * [Session.lastUpdateTime] so backends with an optimistic-concurrency check (Room) accept every
 * append.
 */
object SessionServiceAssertions {

  /** An `agent`-authored event whose timestamp is strictly after the session's last update. */
  private fun Session.nextAgentEvent(
    stateDelta: Map<String, Any>,
    partial: Boolean = false,
  ): Event =
    Event(
      author = "agent",
      actions = EventActions(stateDelta = stateDelta.toMutableMap()),
      timestamp = lastUpdateTime.toEpochMilliseconds() + 1,
      partial = partial,
    )

  // --- temp: state contract (all backends) ---

  /**
   * A `temp:` key is readable on the live session during the invocation but is never persisted,
   * while a non-`temp:` key persists.
   */
  suspend fun tempStateVisibleInInvocationButNotPersisted(
    service: SessionService,
    appName: String = "temp-contract-app",
    userId: String = "temp-contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val key = session.key
    val unused =
      service.appendEvent(
        session,
        session.nextAgentEvent(mapOf("temp:scratch" to "live", "keep" to "persisted")),
      )

    // Readable on the live session during the invocation.
    assertThat(session.state["temp:scratch"]).isEqualTo("live")
    assertThat(session.state["keep"]).isEqualTo("persisted")

    // Absent from a freshly loaded session; the non-`temp:` key persists.
    val reloaded = service.getSession(key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("temp:scratch")).isFalse()
    assertThat(reloaded.state["keep"]).isEqualTo("persisted")

    // Absent from the persisted event log; the non-`temp:` key survives there too.
    val persistedDeltas = service.listEvents(key).events.flatMap { it.actions.stateDelta.entries }
    assertThat(persistedDeltas.any { it.key == "temp:scratch" }).isFalse()
    assertThat(persistedDeltas.any { it.key == "keep" && it.value == "persisted" }).isTrue()
  }

  /** [SessionService.appendEvent] strips `temp:` keys from the returned event in place. */
  suspend fun tempStateTrimmedFromReturnedEvent(
    service: SessionService,
    appName: String = "temp-contract-app",
    userId: String = "temp-contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val returned =
      service.appendEvent(session, session.nextAgentEvent(mapOf("temp:scratch" to "live")))

    assertThat(returned.actions.stateDelta.containsKey("temp:scratch")).isFalse()
  }

  /**
   * A later append that removes one `temp:` key and adds another is reflected on the live session
   * (temp state is shared across appends within the invocation).
   */
  suspend fun tempStateRemovalReflectedOnLiveSession(
    service: SessionService,
    appName: String = "temp-contract-app",
    userId: String = "temp-contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val unusedFirst =
      service.appendEvent(session, session.nextAgentEvent(mapOf("temp:scratch" to "live")))
    assertThat(session.state["temp:scratch"]).isEqualTo("live")

    val unusedSecond =
      service.appendEvent(
        session,
        session.nextAgentEvent(mapOf("temp:scratch" to State.REMOVED, "temp:added" to "second")),
      )

    assertThat(session.state.containsKey("temp:scratch")).isFalse()
    assertThat(session.state["temp:added"]).isEqualTo("second")
  }

  // --- lifecycle + state (all backends) ---

  /** A created session is retrievable by its key. */
  suspend fun createdSessionIsRetrievable(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val created = service.createSession(SessionKey(appName, userId, id = null))
    val reloaded = service.getSession(created.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.key).isEqualTo(created.key)
  }

  /**
   * createSession with no id and no state mints a non-empty id, empty state, and is retrievable.
   */
  suspend fun createSessionMintsIdWhenAbsent(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val created = service.createSession(SessionKey(appName, userId, id = null))
    assertThat(created.key.id).isNotNull()
    assertThat(created.key.id!!).isNotEmpty()
    assertThat(created.state).isEmpty()
    assertThat(service.getSession(created.key)).isNotNull()
  }

  /** Non-temp initial state passed to createSession is retained and retrievable. */
  suspend fun createSessionRetainsInitialState(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val created =
      service.createSession(SessionKey(appName, userId, id = null), mapOf("seed" to "v"))
    val reloaded = service.getSession(created.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["seed"]).isEqualTo("v")
  }

  /** getSession of an unknown id returns null. */
  suspend fun getUnknownSessionReturnsNull(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    assertThat(service.getSession(SessionKey(appName, userId, "does-not-exist"))).isNull()
  }

  /** A created session is listed for its user. */
  suspend fun listSessionsReturnsUsersSessions(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val created = service.createSession(SessionKey(appName, userId, id = null))
    val ids = service.listSessions(appName, userId).sessions.map { it.key.id }
    assertThat(ids).contains(created.key.id)
  }

  /** listSessions is empty for a user with no sessions, even when another user has one. */
  suspend fun listSessionsIsEmptyForUnknownUser(
    service: SessionService,
    appName: String = "contract-app",
  ) {
    // Another user's session in the same app must not leak into an unrelated user's listing.
    val unused = service.createSession(SessionKey(appName, "other-user", id = null))
    assertThat(service.listSessions(appName, "user-with-no-sessions").sessions).isEmpty()
  }

  /** listEvents returns appended events in append order. */
  suspend fun listEventsReturnsAppendsInOrder(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val unusedFirst = service.appendEvent(session, session.nextAgentEvent(mapOf("first" to "1")))
    val unusedSecond = service.appendEvent(session, session.nextAgentEvent(mapOf("second" to "2")))

    val deltas = service.listEvents(session.key).events.map { it.actions.stateDelta }
    assertThat(deltas).hasSize(2)
    assertThat(deltas[0].containsKey("first")).isTrue()
    assertThat(deltas[1].containsKey("second")).isTrue()
  }

  /** deleteSession removes the session (a subsequent getSession returns null). */
  suspend fun deleteRemovesSession(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val created = service.createSession(SessionKey(appName, userId, id = null))
    service.deleteSession(created.key)
    assertThat(service.getSession(created.key)).isNull()
  }

  /** Deleting an unknown session is a no-op (does not throw). */
  suspend fun deleteUnknownSessionIsNoOp(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    service.deleteSession(SessionKey(appName, userId, "does-not-exist"))
  }

  /** A non-temp session-scoped key set via appendEvent is persisted. */
  suspend fun appendPersistsSessionScopedState(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val unused = service.appendEvent(session, session.nextAgentEvent(mapOf("k" to "v")))
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["k"]).isEqualTo("v")
  }

  /** appendEvent with the REMOVED sentinel deletes a session-scoped key. */
  suspend fun appendRemovesStateWithSentinel(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null), mapOf("k" to "v"))
    val unused = service.appendEvent(session, session.nextAgentEvent(mapOf("k" to State.REMOVED)))
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("k")).isFalse()
  }

  /** appendEvent mutates the caller's session: the event and its non-temp delta are applied. */
  suspend fun appendSyncsCallerSession(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val event = session.nextAgentEvent(mapOf("k" to "v"))
    val unused = service.appendEvent(session, event)

    assertThat(session.events).contains(event)
    assertThat(session.state["k"]).isEqualTo("v")
    assertThat(session.lastUpdateTime.toEpochMilliseconds()).isEqualTo(event.timestamp)
  }

  // --- app:/user: scoped state (InMemory + Room only; not Vertex) ---

  /** `app:`-scoped state set in one session is visible in another session of the same app. */
  suspend fun appScopedStateSharedAcrossSessions(
    service: SessionService,
    appName: String = "contract-app",
  ) {
    val writer = service.createSession(SessionKey(appName, "user-a", id = null))
    val unused = service.appendEvent(writer, writer.nextAgentEvent(mapOf("app:shared" to "v")))

    val reader = service.createSession(SessionKey(appName, "user-b", id = null))
    val reloaded = service.getSession(reader.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["app:shared"]).isEqualTo("v")
  }

  /** `user:`-scoped state is shared across the same user's sessions, but not another user's. */
  suspend fun userScopedStateSharedForSameUser(
    service: SessionService,
    appName: String = "contract-app",
  ) {
    val writer = service.createSession(SessionKey(appName, "user-a", id = null))
    val unused = service.appendEvent(writer, writer.nextAgentEvent(mapOf("user:pref" to "v")))

    val sameUser = service.createSession(SessionKey(appName, "user-a", id = null))
    val sameUserReloaded = service.getSession(sameUser.key)
    assertThat(sameUserReloaded).isNotNull()
    assertThat(sameUserReloaded!!.state["user:pref"]).isEqualTo("v")

    val otherUser = service.createSession(SessionKey(appName, "user-b", id = null))
    val otherUserReloaded = service.getSession(otherUser.key)
    assertThat(otherUserReloaded).isNotNull()
    assertThat(otherUserReloaded!!.state.containsKey("user:pref")).isFalse()
  }

  // --- partial events (InMemory + Room only; Vertex persists them remotely) ---

  /** A partial event is a no-op passthrough: returned unchanged, and never persisted. */
  suspend fun appendPartialNotPersisted(
    service: SessionService,
    appName: String = "contract-app",
    userId: String = "contract-user",
  ) {
    val session = service.createSession(SessionKey(appName, userId, id = null))
    val partial = session.nextAgentEvent(mapOf("k" to "v"), partial = true)

    assertThat(service.appendEvent(session, partial)).isEqualTo(partial)

    assertThat(service.listEvents(session.key).events).isEmpty()
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("k")).isFalse()
  }
}
