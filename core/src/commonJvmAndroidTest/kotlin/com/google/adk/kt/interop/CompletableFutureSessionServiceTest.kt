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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class CompletableFutureSessionServiceTest {

  private val key = SessionKey("app", "user", "session")
  private val inMemory = InMemorySessionService()
  private val sessions = CompletableFutureSessionService.of(inMemory)

  @Test
  fun createSession_withoutId_canBeFetchedByTheGeneratedKey() {
    val created = sessions.createSession(SessionKey("app", "user", null)).get(5, SECONDS)

    val fetched = sessions.getSession(created.key).get(5, SECONDS)

    assertThat(created.key.id).isNotNull()
    assertThat(fetched?.key).isEqualTo(created.key)
  }

  @Test
  fun createSession_withState_storesTheState() {
    val created = sessions.createSession(key, mapOf("tier" to "gold")).get(5, SECONDS)

    assertThat(created.state["tier"]).isEqualTo("gold")
  }

  @Test
  fun getSession_missingSession_completesWithNull() {
    assertThat(sessions.getSession(key).get(5, SECONDS)).isNull()
  }

  @Test
  fun getSession_withConfig_returnsOnlyTheRecentEvents() {
    val session = sessions.createSession(key).get(5, SECONDS)
    val last = Event(author = "user", content = Content.fromText(Role.USER, "Second"))
    sessions
      .appendEvent(session, Event(author = "user", content = Content.fromText(Role.USER, "First")))
      .get(5, SECONDS)
    sessions.appendEvent(session, last).get(5, SECONDS)

    val fetched = sessions.getSession(key, GetSessionConfig(numRecentEvents = 1)).get(5, SECONDS)

    assertThat(fetched?.events?.map { it.id }).containsExactly(last.id)
  }

  @Test
  fun createSession_existingId_failsWithTheServiceException() {
    sessions.createSession(key).get(5, SECONDS)

    val thrown = assertFailsWith<ExecutionException> { sessions.createSession(key).get(5, SECONDS) }

    assertThat(thrown).hasCauseThat().isInstanceOf(SessionException::class.java)
  }

  @Test
  fun appendEvent_addsTheEventToListEvents() {
    val session = sessions.createSession(key).get(5, SECONDS)
    val event = Event(author = "user", content = Content.fromText(Role.USER, "Hello"))

    sessions.appendEvent(session, event).get(5, SECONDS)

    assertThat(sessions.listEvents(key).get(5, SECONDS).events.map { it.id })
      .containsExactly(event.id)
  }

  @Test
  fun deleteSession_removesTheSessionFromListSessions() {
    sessions.createSession(key).get(5, SECONDS)
    assertThat(sessions.listSessions("app", "user").get(5, SECONDS).sessions.map { it.key })
      .containsExactly(key)

    sessions.deleteSession(key).get(5, SECONDS)

    assertThat(sessions.listSessions("app", "user").get(5, SECONDS).sessions).isEmpty()
  }

  @Test
  fun closeSession_closesTheSessionInTheWrappedService() {
    val closed = mutableListOf<Session>()
    val recording =
      object : SessionService by inMemory {
        override suspend fun closeSession(session: Session) {
          closed.add(session)
        }
      }
    val session = sessions.createSession(key).get(5, SECONDS)

    CompletableFutureSessionService.of(recording).closeSession(session).get(5, SECONDS)

    assertThat(closed).containsExactly(session)
  }

  @Test
  fun flush_forwardsTheKeyOrNullForEverySession() {
    val flushed = mutableListOf<SessionKey?>()
    val recording =
      object : SessionService by inMemory {
        override suspend fun flush(key: SessionKey?) {
          flushed.add(key)
        }
      }
    val wrapper = CompletableFutureSessionService.of(recording)

    wrapper.flush(key).get(5, SECONDS)
    wrapper.flush().get(5, SECONDS)

    assertThat(flushed).containsExactly(key, null).inOrder()
  }

  @Test
  fun cancel_cancelsTheCall() = runBlocking {
    val hanging = HangingSessionService()
    val future = CompletableFutureSessionService.of(hanging).getSession(key)
    hanging.started.await()

    future.cancel(true)

    withTimeout(5.seconds) { hanging.canceled.await() }
  }

  @Test
  fun close_cancelsCallsInFlightAndLaterCalls(): Unit = runBlocking {
    val hanging = HangingSessionService()
    val wrapper = CompletableFutureSessionService.of(hanging)
    val inFlight = wrapper.getSession(key)
    hanging.started.await()

    wrapper.close()
    val later = wrapper.getSession(key)

    withTimeout(5.seconds) { hanging.canceled.await() }
    assertFailsWith<CancellationException> { inFlight.get(5, SECONDS) }
    assertFailsWith<CancellationException> { later.get(5, SECONDS) }
    assertThat(hanging.calls.get()).isEqualTo(1)
  }

  @Test
  fun close_autoCloseableService_closesTheService() {
    var closed = false
    val closeable =
      object : SessionService by inMemory, AutoCloseable {
        override fun close() {
          closed = true
        }
      }

    CompletableFutureSessionService.of(closeable).close()

    assertThat(closed).isTrue()
  }

  /** A session service whose [getSession] suspends until it is canceled. */
  private class HangingSessionService : SessionService by InMemorySessionService() {
    val calls = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val canceled = CompletableDeferred<Unit>()

    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
      calls.incrementAndGet()
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        canceled.complete(Unit)
      }
    }
  }
}
