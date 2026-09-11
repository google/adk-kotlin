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

/*
 * Shared, cross-backend assertions every [SessionService] implementation must satisfy. Each helper
 * verifies ONE behavior, so a backend test delegates one @Test per helper and adds its own
 * backend-specific tests on top. Event timestamps are advanced off the live session's
 * [Session.lastUpdateTime] so backends with an optimistic-concurrency check (Room) accept every
 * append.
 */

private const val APP_NAME = "temp-contract-app"
private const val USER_ID = "temp-contract-user"

/** An `agent`-authored event whose timestamp is strictly after the session's last update. */
private fun Session.nextAgentEvent(stateDelta: Map<String, Any>): Event =
  Event(
    author = "agent",
    actions = EventActions(stateDelta = stateDelta.toMutableMap()),
    timestamp = lastUpdateTime.toEpochMilliseconds() + 1,
  )

/**
 * A `temp:` key is readable on the live session during the invocation but is never persisted, while
 * a non-`temp:` key persists.
 */
suspend fun assertTempStateVisibleInInvocationButNotPersisted(service: SessionService) {
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
suspend fun assertTempStateTrimmedFromReturnedEvent(service: SessionService) {
  val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
  val returned =
    service.appendEvent(session, session.nextAgentEvent(mapOf("temp:scratch" to "live")))

  assertThat(returned.actions.stateDelta.containsKey("temp:scratch")).isFalse()
}

/**
 * A later append that removes one `temp:` key and adds another is reflected on the live session
 * (temp state is shared across appends within the invocation).
 */
suspend fun assertTempStateRemovalReflectedOnLiveSession(service: SessionService) {
  val session = service.createSession(SessionKey(APP_NAME, USER_ID, id = null))
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
