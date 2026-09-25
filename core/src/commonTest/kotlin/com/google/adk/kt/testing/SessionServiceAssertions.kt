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
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.sessions.State
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith

/**
 * Shared, cross-backend assertions every [SessionService] implementation must satisfy, grouped in
 * an object so the suite occupies a single name in the shared testing package. Each method verifies
 * ONE behavior, so a backend test delegates one `@Test` per method and adds its own
 * backend-specific tests on top. Event timestamps are advanced off the live session's
 * [Session.lastUpdateTime] so backends with an optimistic-concurrency check (Room) accept every
 * append.
 */
object SessionServiceAssertions {

  private const val APP_NAME = "contract-app"
  private const val USER_ID = "contract-user"

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
  suspend fun tempStateVisibleInInvocationButNotPersisted(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
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
  suspend fun tempStateTrimmedFromReturnedEvent(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val returned =
      service.appendEvent(session, session.nextAgentEvent(mapOf("temp:scratch" to "live")))

    assertThat(returned.actions.stateDelta.containsKey("temp:scratch")).isFalse()
  }

  /**
   * A later append that removes one `temp:` key and adds another is reflected on the live session
   * (temp state is shared across appends within the invocation), while neither `temp:` key reaches
   * persisted storage.
   */
  suspend fun tempStateRemovalReflectedOnLiveSession(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val unusedFirst =
      service.appendEvent(session, session.nextAgentEvent(mapOf("temp:scratch" to "live")))
    assertThat(session.state["temp:scratch"]).isEqualTo("live")

    val unusedSecond =
      service.appendEvent(
        session,
        session.nextAgentEvent(mapOf("temp:scratch" to State.REMOVED, "temp:added" to "second")),
      )

    // Reflected on the live session during the invocation.
    assertThat(session.state.containsKey("temp:scratch")).isFalse()
    assertThat(session.state["temp:added"]).isEqualTo("second")

    // Neither `temp:` key is persisted (absent from a reloaded session and the event log).
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("temp:scratch")).isFalse()
    assertThat(reloaded.state.containsKey("temp:added")).isFalse()
    val persistedDeltas =
      service.listEvents(session.key).events.flatMap { it.actions.stateDelta.keys }
    assertThat(persistedDeltas.any { it.startsWith(State.TEMP_PREFIX) }).isFalse()
  }

  // --- lifecycle + state (all backends) ---

  /** A created session is retrievable by its key. */
  suspend fun createdSessionIsRetrievable(service: SessionService) {
    val created = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val reloaded = service.getSession(created.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.key).isEqualTo(created.key)
  }

  /**
   * createSession with no id and no state mints a non-empty id, empty state, and is retrievable.
   */
  suspend fun createSessionMintsIdWhenAbsent(service: SessionService) {
    val created = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    assertThat(created.key.id).isNotNull()
    assertThat(created.key.id!!).isNotEmpty()
    assertThat(created.key.appName).isEqualTo(APP_NAME)
    assertThat(created.key.userId).isEqualTo(USER_ID)
    assertThat(created.state).isEmpty()
    assertThat(service.getSession(created.key)).isNotNull()
  }

  /** Non-temp initial state passed to createSession is retained and retrievable. */
  suspend fun createSessionRetainsInitialState(service: SessionService) {
    val created =
      service.createSession(SessionKey(APP_NAME, USER_ID, id = null), mapOf("seed" to "v"))
    // The returned instance carries the seed independently of the reload below.
    assertThat(created.state["seed"]).isEqualTo("v")
    val reloaded = service.getSession(created.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["seed"]).isEqualTo("v")
  }

  /** getSession of an unknown id returns null. */
  suspend fun getUnknownSessionReturnsNull(service: SessionService) {
    assertThat(service.getSession(SessionKey(APP_NAME, USER_ID, "does-not-exist"))).isNull()
  }

  /** A created session is listed for its user. */
  suspend fun listSessionsReturnsUsersSessions(service: SessionService) {
    val created = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val ids = service.listSessions(APP_NAME, USER_ID).sessions.map { it.key.id }
    assertThat(ids).contains(created.key.id)
  }

  /** listSessions is empty for a user with no sessions, even when another user has one. */
  suspend fun listSessionsIsEmptyForUnknownUser(service: SessionService) {
    // Another user's session in the same app must not leak into an unrelated user's listing.
    val unused = service.createSession(SessionKey(APP_NAME, "other-user", id = null))
    assertThat(service.listSessions(APP_NAME, "user-with-no-sessions").sessions).isEmpty()
  }

  /** listEvents returns appended events in append order. */
  suspend fun listEventsReturnsAppendsInOrder(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val unusedFirst = service.appendEvent(session, session.nextAgentEvent(mapOf("first" to "1")))
    val unusedSecond = service.appendEvent(session, session.nextAgentEvent(mapOf("second" to "2")))

    val deltas = service.listEvents(session.key).events.map { it.actions.stateDelta }
    assertThat(deltas).hasSize(2)
    assertThat(deltas[0].containsKey("first")).isTrue()
    assertThat(deltas[1].containsKey("second")).isTrue()
  }

  /** listEvents of an unknown session returns no events. */
  suspend fun listEventsIsEmptyForUnknownSession(service: SessionService) {
    assertThat(service.listEvents(SessionKey(APP_NAME, USER_ID, "does-not-exist")).events).isEmpty()
  }

  /** deleteSession removes the session (a subsequent getSession returns null). */
  suspend fun deleteRemovesSession(service: SessionService) {
    val created = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    service.deleteSession(created.key)
    assertThat(service.getSession(created.key)).isNull()
  }

  /** Deleting an unknown session is a no-op (does not throw). */
  suspend fun deleteUnknownSessionIsNoOp(service: SessionService) {
    service.deleteSession(SessionKey(APP_NAME, USER_ID, "does-not-exist"))
  }

  /** A non-temp session-scoped key set via appendEvent is persisted. */
  suspend fun appendPersistsSessionScopedState(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val unused = service.appendEvent(session, session.nextAgentEvent(mapOf("k" to "v")))
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["k"]).isEqualTo("v")
  }

  /** appendEvent with the REMOVED sentinel deletes a session-scoped key. */
  suspend fun appendRemovesStateWithSentinel(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null), mapOf("k" to "v"))
    val unused = service.appendEvent(session, session.nextAgentEvent(mapOf("k" to State.REMOVED)))
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("k")).isFalse()
  }

  /** appendEvent mutates the caller's session: the event and its non-temp delta are applied. */
  suspend fun appendSyncsCallerSession(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val event = session.nextAgentEvent(mapOf("k" to "v"))
    val unused = service.appendEvent(session, event)

    assertThat(session.events).contains(event)
    assertThat(session.state["k"]).isEqualTo("v")
    assertThat(session.lastUpdateTime.toEpochMilliseconds()).isEqualTo(event.timestamp)
  }

  // --- createSession id conflicts (InMemory + Room only; not Vertex) ---

  /**
   * createSession rejects an id already taken for the same app and user, leaving the stored session
   * intact. VertexAiSessionService is excluded: it surfaces the managed backend's own duplicate
   * error rather than a [SessionException].
   */
  suspend fun createSessionRejectsDuplicateId(service: SessionService) {
    val taken = SessionKey(APP_NAME, USER_ID, "taken-id")
    val session = service.createSession(taken)
    val unused = service.appendEvent(session, session.nextAgentEvent(mapOf("keep" to "v")))

    val failure = assertFailsWith<SessionException> { service.createSession(taken) }

    assertThat(failure).hasMessageThat().isEqualTo(SessionException.SESSION_ALREADY_EXISTS)
    // The stored session is untouched: the rejected create neither replaced it nor dropped its
    // event and state.
    val stored = service.getSession(taken)
    assertThat(stored).isNotNull()
    assertThat(stored!!.state["keep"]).isEqualTo("v")
    assertThat(service.listEvents(taken).events).hasSize(1)
  }

  /** The same id under a different user is a different session, so it is created, not rejected. */
  suspend fun createSessionAllowsSameIdForDifferentUser(service: SessionService) {
    val first = service.createSession(SessionKey(APP_NAME, "user-a", "shared-id"))

    val second = service.createSession(SessionKey(APP_NAME, "user-b", "shared-id"))

    assertThat(second.key.id).isEqualTo("shared-id")
    assertThat(second.key.userId).isEqualTo("user-b")
    // Creating the second must not have displaced the first.
    assertThat(service.getSession(first.key)).isNotNull()
  }

  // --- app:/user: scoped state (InMemory + Room only; not Vertex) ---

  /** `app:`-scoped state set in one session is visible in another session of the same app. */
  suspend fun appScopedStateSharedAcrossSessions(service: SessionService) {
    val writer = service.createSession(SessionKey(APP_NAME, "user-a", id = null))
    val unused = service.appendEvent(writer, writer.nextAgentEvent(mapOf("app:shared" to "v")))

    val reader = service.createSession(SessionKey(APP_NAME, "user-b", id = null))
    // createSession pre-merges global state into the returned instance, independently of
    // getSession.
    assertThat(reader.state["app:shared"]).isEqualTo("v")
    val reloaded = service.getSession(reader.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["app:shared"]).isEqualTo("v")
  }

  /** `user:`-scoped state is shared across the same user's sessions, but not another user's. */
  suspend fun userScopedStateSharedForSameUser(service: SessionService) {
    val writer = service.createSession(SessionKey(APP_NAME, "user-a", id = null))
    val unused = service.appendEvent(writer, writer.nextAgentEvent(mapOf("user:pref" to "v")))

    val sameUser = service.createSession(SessionKey(APP_NAME, "user-a", id = null))
    // createSession pre-merges global state into the returned instance, independently of
    // getSession.
    assertThat(sameUser.state["user:pref"]).isEqualTo("v")
    val sameUserReloaded = service.getSession(sameUser.key)
    assertThat(sameUserReloaded).isNotNull()
    assertThat(sameUserReloaded!!.state["user:pref"]).isEqualTo("v")

    val otherUser = service.createSession(SessionKey(APP_NAME, "user-b", id = null))
    assertThat(otherUser.state.containsKey("user:pref")).isFalse()
    val otherUserReloaded = service.getSession(otherUser.key)
    assertThat(otherUserReloaded).isNotNull()
    assertThat(otherUserReloaded!!.state.containsKey("user:pref")).isFalse()
  }

  /** Initial state is scoped by prefix at creation, the way a state delta is on append. */
  suspend fun createSessionScopesPrefixedInitialState(service: SessionService) {
    val seeded =
      service.createSession(
        SessionKey(APP_NAME, "user-a", id = null),
        mapOf("plain" to "v", "app:shared" to "a", "user:pref" to "u", "temp:scratch" to "t"),
      )

    // `temp:` lives for one invocation, so a seeded one could never be read back: it is dropped
    // outright here, unlike a `temp:` delta on append, which the live session sees first.
    assertThat(seeded.state.containsKey("temp:scratch")).isFalse()
    val reloaded = service.getSession(seeded.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["plain"]).isEqualTo("v")
    assertThat(reloaded.state["app:shared"]).isEqualTo("a")
    assertThat(reloaded.state["user:pref"]).isEqualTo("u")
    assertThat(reloaded.state.containsKey("temp:scratch")).isFalse()

    // The scoped halves went to their own scopes, not into this one session's state.
    val sameApp = service.createSession(SessionKey(APP_NAME, "user-b", id = null))
    assertThat(sameApp.state["app:shared"]).isEqualTo("a")
    assertThat(sameApp.state.containsKey("user:pref")).isFalse()
    val sameUser = service.createSession(SessionKey(APP_NAME, "user-a", id = null))
    assertThat(sameUser.state["user:pref"]).isEqualTo("u")

    // Prove the scoped keys live only in their shared scopes, not also in the session row: removing
    // them from the scopes clears them on reload. A backend that kept a copy in the row would still
    // return the stale value here, since a scope drop cannot reach it.
    val unused =
      service.appendEvent(
        sameUser,
        sameUser.nextAgentEvent(mapOf("app:shared" to State.REMOVED, "user:pref" to State.REMOVED)),
      )
    val afterRemove = service.getSession(seeded.key)
    assertThat(afterRemove).isNotNull()
    assertThat(afterRemove!!.state.containsKey("app:shared")).isFalse()
    assertThat(afterRemove.state.containsKey("user:pref")).isFalse()
  }

  /**
   * A removal sentinel in an initial state is dropped, not honored: creating a session must not be
   * a way to erase a key a scope already holds.
   */
  suspend fun createSessionDropsRemovalSentinelInInitialState(service: SessionService) {
    // Seed both scopes, then a later session seeds a removal sentinel for each of those keys and a
    // plain one. Every sentinel is dropped: honoring a scoped one would erase a key another session
    // owns, and a plain one stored verbatim would render its marker string into a prompt.
    val unused =
      service.createSession(
        SessionKey(APP_NAME, "user-a", id = null),
        mapOf("app:shared" to "a", "user:pref" to "u"),
      )

    val remover =
      service.createSession(
        SessionKey(APP_NAME, "user-a", id = null),
        mapOf("app:shared" to State.REMOVED, "user:pref" to State.REMOVED, "plain" to State.REMOVED),
      )
    assertThat(remover.state["app:shared"]).isEqualTo("a")
    assertThat(remover.state["user:pref"]).isEqualTo("u")
    assertThat(remover.state.containsKey("plain")).isFalse()
    val reloaded = service.getSession(remover.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("plain")).isFalse()
  }

  /**
   * A scoped seed at creation merges onto what the scope already holds, for `app:` and `user:`
   * both. [createSessionScopesPrefixedInitialState] only ever seeds an empty scope, so it would
   * still pass if a seed replaced the scope outright; this is the case that needs the read-merge.
   */
  suspend fun createSessionMergesScopedSeedOntoExistingState(service: SessionService) {
    // Populate both scopes first, via an append on one session.
    val writer = service.createSession(SessionKey(APP_NAME, "user-a", id = null))
    val unused =
      service.appendEvent(
        writer,
        writer.nextAgentEvent(mapOf("app:first" to "1", "user:first" to "1")),
      )

    // A second session seeds the other key in each scope; the existing keys must survive.
    val seeded =
      service.createSession(
        SessionKey(APP_NAME, "user-a", id = null),
        mapOf("app:second" to "2", "user:second" to "2"),
      )
    assertThat(seeded.state["app:first"]).isEqualTo("1")
    assertThat(seeded.state["app:second"]).isEqualTo("2")
    assertThat(seeded.state["user:first"]).isEqualTo("1")
    assertThat(seeded.state["user:second"]).isEqualTo("2")

    val reloaded = service.getSession(seeded.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state["app:first"]).isEqualTo("1")
    assertThat(reloaded.state["app:second"]).isEqualTo("2")
    assertThat(reloaded.state["user:first"]).isEqualTo("1")
    assertThat(reloaded.state["user:second"]).isEqualTo("2")
  }

  // --- partial events (all backends: base, InMemory, Room, Vertex) ---

  /** A partial event is a no-op passthrough: returned unchanged, and never persisted. */
  suspend fun appendPartialNotPersisted(service: SessionService) {
    val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
    val partial = session.nextAgentEvent(mapOf("k" to "v", "temp:x" to "t"), partial = true)

    assertThat(service.appendEvent(session, partial)).isSameInstanceAs(partial)

    assertThat(service.listEvents(session.key).events).isEmpty()
    val reloaded = service.getSession(session.key)
    assertThat(reloaded).isNotNull()
    assertThat(reloaded!!.state.containsKey("k")).isFalse()
    assertThat(reloaded.state.containsKey("temp:x")).isFalse()
  }
}
