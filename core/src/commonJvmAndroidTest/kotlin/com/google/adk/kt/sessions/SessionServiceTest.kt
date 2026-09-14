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
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SessionServiceTest {

  @Test
  fun appendEvent_validEvent_appendsToSessionEvents() {
    runBlocking {
      val session = Session(SessionKey("test-app", "user-id", "session-id"))
      val event = Event(author = Role.USER)

      val result = sessionService.appendEvent(session, event)

      assertThat(result).isEqualTo(event)
      assertThat(session.events).containsExactly(event)
    }
  }

  @Test
  fun appendEvent_withStateDelta_updatesSessionState() {
    runBlocking {
      val state = State(initialState = mapOf("key1" to "value1"))
      val session = Session(SessionKey("test-app", "user-id", "session-id"), state = state)
      val event = Event(author = "agent")
      event.actions.stateDelta["key1"] = "new-value1"
      event.actions.stateDelta["key2"] = "value2"

      val unused = sessionService.appendEvent(session, event)

      assertThat(session.state["key1"]).isEqualTo("new-value1")
      assertThat(session.state["key2"]).isEqualTo("value2")
    }
  }

  @Test
  fun appendEvent_partialEvent_doesNotAppendOrApplyDelta() {
    runBlocking {
      val session = Session(SessionKey("test-app", "user-id", "session-id"))
      val event = Event(author = "agent", partial = true)
      event.actions.stateDelta["key1"] = "new-value1"

      val result = sessionService.appendEvent(session, event)

      assertThat(result).isEqualTo(event)
      assertThat(session.events).isEmpty()
      assertThat(session.state.containsKey("key1")).isFalse()
    }
  }

  @Test
  fun appendEvent_stateDeltaWithTempKeys_appliesToLiveSessionAndTrimsFromEvent() {
    runBlocking {
      val session = Session(SessionKey("test-app", "user-id", "session-id"))
      val event = Event(author = "agent")
      event.actions.stateDelta["temp:key1"] = "value1"

      val unused = sessionService.appendEvent(session, event)

      // `temp:` is applied to the in-memory session (readable by later agents in the invocation)
      assertThat(session.state["temp:key1"]).isEqualTo("value1")
      // but stripped from the event in place, so it is never persisted.
      assertThat(event.actions.stateDelta.containsKey("temp:key1")).isFalse()
    }
  }

  @Test
  fun appendEvent_tempKeyRemoved_removesFromLiveSessionAndTrimsFromEvent() {
    runBlocking {
      val session = Session(SessionKey("test-app", "user-id", "session-id"))
      val seed = Event(author = "agent")
      seed.actions.stateDelta["temp:key1"] = "value1"
      val unusedSeed = sessionService.appendEvent(session, seed)
      assertThat(session.state["temp:key1"]).isEqualTo("value1")

      val remove = Event(author = "agent")
      remove.actions.stateDelta["temp:key1"] = State.REMOVED
      val unused = sessionService.appendEvent(session, remove)

      // A `temp:` key set to REMOVED is removed from the live session,
      assertThat(session.state.containsKey("temp:key1")).isFalse()
      // and likewise stripped from the event before persistence.
      assertThat(remove.actions.stateDelta.containsKey("temp:key1")).isFalse()
    }
  }

  @Test
  fun appendEvent_stateDeltaWithRemovedKey_removesKeyFromSessionState() {
    runBlocking {
      val state = State(initialState = mapOf("key1" to "value1"))
      val session = Session(SessionKey("test-app", "user-id", "session-id"), state = state)
      val event = Event(author = "agent")
      event.actions.stateDelta["key1"] = State.REMOVED

      val unused = sessionService.appendEvent(session, event)

      assertThat(session.state.containsKey("key1")).isFalse()
    }
  }

  companion object {
    private val sessionService =
      object : SessionService {
        override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
          error("Not implemented for testing")

        override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
          error("Not implemented for testing")

        override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse =
          error("Not implemented for testing")

        override suspend fun deleteSession(key: SessionKey): Unit =
          error("Not implemented for testing")

        override suspend fun listEvents(key: SessionKey): ListEventsResponse =
          error("Not implemented for testing")
      }
  }
}
