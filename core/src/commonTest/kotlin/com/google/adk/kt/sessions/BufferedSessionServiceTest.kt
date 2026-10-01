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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.retry.ExponentialBackoff
import com.google.adk.kt.retry.RetryPolicy
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.SessionServiceAssertions
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.nextEvent
import com.google.adk.kt.testing.userMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BufferedSessionServiceTest {

  private val parent = Job()
  private val store = FakeStore()
  private val service =
    BufferedSessionService(
      store,
      Dispatchers.Default + parent,
      BufferedSessionServiceConfig(
        retryPolicy = ExponentialBackoff(maxAttempts = 3, initialDelay = Duration.ZERO)
      ),
    )

  @AfterTest
  fun tearDown() {
    parent.cancel()
  }

  @Test
  fun appendEvent_storeBlocked_returnsBeforeTheStoreHasTheEvent() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false

    append(session)

    assertEquals(1, session.events.size)
    assertTrue(store.inner.listEvents(KEY).events.isEmpty())
    store.appendsOpen.value = true
    service.flush(KEY)
    assertEquals(1, store.inner.listEvents(KEY).events.size)
  }

  @Test
  fun flush_severalAppends_persistsThemInAppendOrder() = runBlocking {
    val session = service.createSession(KEY)
    store.appendDelay.value = 5.milliseconds

    val appended = List(3) { service.appendEvent(session, session.nextEvent()) }
    service.flush(KEY)

    assertEquals(appended.map { it.id }, store.inner.listEvents(KEY).events.map { it.id })
  }

  @Test
  fun appendEvent_eventChangedAfterward_persistsTheAppendedVersion() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false

    val appended = service.appendEvent(session, session.nextEvent(mapOf("kept" to "v")))
    appended.actions.stateDelta["late"] = "v"
    store.appendsOpen.value = true
    service.flush(KEY)

    assertEquals(
      mapOf<String, Any>("kept" to "v"),
      store.inner.listEvents(KEY).events.single().actions.stateDelta,
    )
  }

  @Test
  fun appendEvent_partial_neverReachesTheStore() = runBlocking {
    val session = service.createSession(KEY)

    val unused = service.appendEvent(session, session.nextEvent().copy(partial = true))
    service.flush(KEY)

    assertTrue(store.expectedUpdateTimes.value.isEmpty(), "a partial event reached the store")
  }

  @Test
  fun flush_withAndWithoutKey_passesTheFlushOnToTheStore() = runBlocking {
    service.flush(KEY)
    service.flush()

    assertEquals(listOf(KEY, null), store.flushedKeys.value)
  }

  @Test
  fun flush_severalAppends_passesEachWriteTheVersionItReplaced() = runBlocking {
    val session = service.createSession(KEY)
    val created = session.lastUpdateTime

    val first = service.appendEvent(session, session.nextEvent())
    append(session)
    service.flush(KEY)

    assertEquals(
      listOf(created, Instant.fromEpochMilliseconds(first.timestamp)),
      store.expectedUpdateTimes.value,
    )
  }

  @Test
  fun getSession_writesBuffered_waitsForThem() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false
    append(session)

    val read = async { service.getSession(KEY) }
    delay(SETTLE)

    assertFalse(read.isCompleted, "the read did not wait for the buffered write")
    store.appendsOpen.value = true
    assertEquals(1, assertNotNull(read.await()).events.size)
  }

  @Test
  fun getSession_withConfig_passesItOnToTheStore() = runBlocking {
    val session = service.createSession(KEY)
    append(session)
    val last = service.appendEvent(session, session.nextEvent())

    val read = service.getSession(KEY, GetSessionConfig(numRecentEvents = 1))

    assertEquals(listOf(last.id), assertNotNull(read).events.map { it.id })
  }

  @Test
  fun listEvents_writesBuffered_waitsForThem() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false
    append(session)

    val read = async { service.listEvents(KEY) }
    delay(SETTLE)

    assertFalse(read.isCompleted, "the read did not wait for the buffered write")
    store.appendsOpen.value = true
    assertEquals(1, read.await().events.size)
  }

  @Test
  fun getSession_userStateChangedInAnotherSession_waitsForThatChange() = runBlocking {
    val writer = service.createSession(KEY)
    val unusedReader = service.createSession(KEY.copy(id = "reader"))
    val unusedStranger = service.createSession(SessionKey(APP, "other-user", "stranger"))
    store.appendsOpen.value = false

    append(writer, mapOf("user:theme" to "dark"))
    val read = async { service.getSession(KEY.copy(id = "reader")) }
    delay(SETTLE)

    // Another user's session shares no user: state, so its read does not wait.
    withTimeout(5.seconds) {
      assertNotNull(service.getSession(SessionKey(APP, "other-user", "stranger")))
    }
    assertFalse(read.isCompleted, "the read did not wait for the shared state change")
    store.appendsOpen.value = true
    assertEquals("dark", assertNotNull(read.await()).state["user:theme"])
  }

  @Test
  fun createSession_appStateChangedInAnotherSession_waitsForThatChange() = runBlocking {
    val writer = service.createSession(KEY)
    store.appendsOpen.value = false

    append(writer, mapOf("app:flag" to "on"))
    val created = async { service.createSession(SessionKey(APP, "other-user", "new")) }
    delay(SETTLE)

    assertFalse(created.isCompleted, "the new session did not wait for the shared state change")
    store.appendsOpen.value = true
    assertEquals("on", created.await().state["app:flag"])
  }

  @Test
  fun listSessions_appStateChangedByAnotherUser_waitsForThatChange() =
    runBlocking<Unit> {
      val writer = service.createSession(SessionKey(APP, "other-user", "writer"))
      store.appendsOpen.value = false

      append(writer, mapOf("app:flag" to "on"))
      val listing = async { service.listSessions(APP, USER) }
      delay(SETTLE)

      assertFalse(listing.isCompleted, "the listing did not wait for the shared state change")
      store.appendsOpen.value = true
      listing.await()
    }

  @Test
  fun listSessions_sessionStateChangeBuffered_waitsForThatChange() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false

    append(session, mapOf("k" to "v"))
    val listing = async { service.listSessions(APP, USER) }
    delay(SETTLE)

    assertFalse(listing.isCompleted, "the listing did not wait for the session's own change")
    store.appendsOpen.value = true
    assertEquals("v", listing.await().sessions.single().state["k"])
  }

  @Test
  fun listSessions_anotherUsersSessionStateChangeBuffered_doesNotWaitForIt() =
    runBlocking<Unit> {
      val stranger = service.createSession(SessionKey(APP, "other-user", "stranger"))
      store.appendsOpen.value = false

      append(stranger, mapOf("k" to "v"))

      val unusedListing = withTimeout(5.seconds) { service.listSessions(APP, USER) }
    }

  @Test
  fun flush_writeFailsThenSucceeds_persistsTheEventOnce() = runBlocking {
    val session = service.createSession(KEY)
    store.failingAppends.value = 2

    append(session)
    service.flush(KEY)

    assertEquals(1, store.inner.listEvents(KEY).events.size)
  }

  @Test
  fun flush_writeFailsAfterTheStoreCommitsIt_persistsEachEventOnce() = runBlocking {
    val session = service.createSession(KEY)
    val created = session.lastUpdateTime
    store.acceptsHeldEvents.value = true
    store.failingAppendsAfterCommit.value = 1

    val first = service.appendEvent(session, session.nextEvent())
    val second = service.appendEvent(session, session.nextEvent())
    service.flush(KEY)

    assertEquals(listOf(first.id, second.id), store.inner.listEvents(KEY).events.map { it.id })
    // Both the first event's attempt and its retry replace `created`.
    assertEquals(
      listOf(created, created, Instant.fromEpochMilliseconds(first.timestamp)),
      store.expectedUpdateTimes.value,
    )
  }

  @Test
  fun flush_writeFailsTwice_waitsTheRetryDelayBeforeEachRetry() = runBlocking {
    val delayedAttempts = MutableStateFlow(emptyList<Int>())
    val retryPolicy =
      object : RetryPolicy {
        override fun shouldRetry(failure: Exception, failedAttempts: Int): Boolean = true

        override fun nextRetryDelay(failedAttempts: Int): Duration {
          delayedAttempts.update { it + failedAttempts }
          return RETRY_DELAY
        }
      }
    val retrying =
      BufferedSessionService(
        store,
        Dispatchers.Default + parent,
        BufferedSessionServiceConfig(retryPolicy),
      )
    val session = retrying.createSession(KEY)
    store.failingAppends.value = 2
    val started = TimeSource.Monotonic.markNow()

    val unused = retrying.appendEvent(session, session.nextEvent())
    retrying.flush(KEY)

    assertEquals(listOf(1, 2), delayedAttempts.value)
    assertTrue(started.elapsedNow() >= RETRY_DELAY * 2, "a retry did not wait for its delay")
    assertEquals(1, store.inner.listEvents(KEY).events.size)
  }

  @Test
  fun flush_retriesRunOut_throwsOnceAndTheReloadedSessionRecovers() = runBlocking {
    val session = service.createSession(KEY)
    store.failingAppends.value = 3

    append(session)
    val failure = assertFailsWith<SessionException> { service.flush(KEY) }
    service.flush(KEY)
    val reloaded = assertNotNull(service.getSession(KEY))
    val kept = service.appendEvent(reloaded, reloaded.nextEvent())
    service.flush(KEY)

    assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, failure.message)
    assertSame(store.failureToThrow.value, failure.cause)
    assertEquals(listOf(kept.id), store.inner.listEvents(KEY).events.map { it.id })
  }

  @Test
  fun flush_writeItWaitsOnFails_leavesNothingElseToReport() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.appendsOpen.value = false
      append(session)
      // Undispatched, so the flush is waiting on the write before it fails.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { service.flush(KEY) }
        }
      store.failingAppends.value = 3
      store.appendsOpen.value = true
      flush.await()

      service.flush()
    }

  @Test
  fun flush_writeItWaitsOnFailsThenEvictedByNewerFailures_stillThrows() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.appendsOpen.value = false
      store.sessionFlushesOpen.value = false
      append(session)
      // Undispatched, so the flush is waiting on the write before it fails.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { service.flush(KEY) }
        }
      store.failingAppends.value = 3
      store.appendsOpen.value = true
      // The flush has seen its write fail and now waits on the store.
      store.flushedKeys.first { KEY in it }

      store.failingAppends.value = 3 * KEPT_FAILURES
      repeat(KEPT_FAILURES) { append(service.createSession(SessionKey(APP, USER, "other-$it"))) }
      val othersFailed = assertFailsWith<SessionException> { service.flush() }
      // Only the other sessions' failures are left, so this session's was evicted.
      assertEquals(KEPT_FAILURES - 1, othersFailed.suppressedExceptions.size)
      store.sessionFlushesOpen.value = true

      assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, flush.await().message)
    }

  @Test
  fun appendEvent_afterRetriesRanOut_throwsOnceWithoutApplyingTheEvent() = runBlocking {
    val session = service.createSession(KEY)
    store.failingAppends.value = 3
    append(session)
    // A read waits for the failed write without reporting it.
    val reloaded = assertNotNull(service.getSession(KEY))

    val failure =
      assertFailsWith<SessionException> { service.appendEvent(session, session.nextEvent()) }
    val kept = service.appendEvent(reloaded, reloaded.nextEvent())
    service.flush(KEY)

    assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, failure.message)
    assertEquals(1, session.events.size)
    assertEquals(listOf(kept.id), store.inner.listEvents(KEY).events.map { it.id })
  }

  @Test
  fun getSession_afterRetriesRanOut_returnsWhatTheStoreHolds() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.failingAppends.value = 3
      append(session)

      val read = assertNotNull(service.getSession(KEY))
      val unusedEvents = service.listEvents(KEY)

      assertTrue(read.events.isEmpty())
      assertFailsWith<SessionException> { service.flush(KEY) }
    }

  @Test
  fun flush_storeThrowsCancellationException_retriesTheWrite() = runBlocking {
    val session = service.createSession(KEY)
    store.failureToThrow.value = CancellationException("store timed out")
    store.failingAppends.value = 2

    append(session)
    service.flush(KEY)

    assertEquals(1, store.inner.listEvents(KEY).events.size)
  }

  @Test
  fun flush_storeCancellationExceptionOutlastsRetries_reportsTheFailedWriteAndKeepsRunning() =
    runBlocking {
      val session = service.createSession(KEY)
      store.failureToThrow.value = CancellationException("store timed out")
      store.failingAppends.value = 3

      append(session)
      assertFailsWith<SessionException> { service.flush(KEY) }
      val reloaded = assertNotNull(service.getSession(KEY))
      append(reloaded)
      service.flush(KEY)

      assertEquals(1, store.inner.listEvents(KEY).events.size)
    }

  @Test
  fun flush_alwaysRetryingWriteCanceled_stopsRetryingAndReportsIt() =
    runBlocking<Unit> {
      val alwaysRetry =
        object : RetryPolicy {
          override fun shouldRetry(failure: Exception, failedAttempts: Int): Boolean = true

          override fun nextRetryDelay(failedAttempts: Int): Duration = Duration.ZERO
        }
      val retrying =
        BufferedSessionService(
          store,
          Dispatchers.Default + parent,
          BufferedSessionServiceConfig(alwaysRetry),
        )
      val session = retrying.createSession(KEY)
      store.failingAppends.value = Int.MAX_VALUE
      val unused = retrying.appendEvent(session, session.nextEvent())
      store.failingAppends.first { it < Int.MAX_VALUE }

      parent.cancel()

      withTimeout(5.seconds) { assertFailsWith<SessionException> { retrying.flush(KEY) } }
    }

  @Test
  fun flush_storeThrowsAnError_reportsTheFailedWriteAndRethrowsTheError() =
    runBlocking<Unit> {
      // Catches the Error the worker rethrows, which would otherwise reach the global handler.
      val escaped = CompletableDeferred<Throwable>()
      val handler = CoroutineExceptionHandler { _, error -> escaped.complete(error) }
      val erroring = BufferedSessionService(store, Dispatchers.Default + parent + handler)
      val session = erroring.createSession(KEY)
      store.failureToThrow.value = NotImplementedError("store stub")
      store.failingAppends.value = 1

      val unused = erroring.appendEvent(session, session.nextEvent())

      assertFailsWith<SessionException> { erroring.flush(KEY) }
      assertIs<NotImplementedError>(escaped.await())
    }

  @Test
  fun flush_sharedStateChangedInTwoSessions_persistsTheChangesInAppendOrder() = runBlocking {
    val first = service.createSession(KEY)
    val second = service.createSession(KEY.copy(id = "second"))
    store.appendDelayBySessionId.value = mapOf(SESSION to 50.milliseconds)

    append(first, mapOf("user:theme" to "light"))
    append(second, mapOf("user:theme" to "dark"))
    service.flush()

    assertEquals("dark", assertNotNull(store.inner.getSession(KEY)).state["user:theme"])
  }

  @Test
  fun flush_appStateChangedByTwoUsers_persistsTheChangesInAppendOrder() = runBlocking {
    val first = service.createSession(KEY)
    val second = service.createSession(SessionKey(APP, "other-user", "second"))
    store.appendDelayBySessionId.value = mapOf(SESSION to 50.milliseconds)

    append(first, mapOf("app:flag" to "off"))
    append(second, mapOf("app:flag" to "on"))
    service.flush()

    assertEquals("on", assertNotNull(store.inner.getSession(KEY)).state["app:flag"])
  }

  @Test
  fun flush_userStateChangedByAnotherUser_doesNotWaitForIt() = runBlocking {
    val stranger = service.createSession(SessionKey(APP, "other-user", "stranger"))
    val session = service.createSession(KEY)
    store.appendDelayBySessionId.value = mapOf("stranger" to 10.seconds)

    append(stranger, mapOf("user:theme" to "light"))
    append(session, mapOf("user:theme" to "dark"))

    withTimeout(5.seconds) { service.flush(KEY) }
  }

  @Test
  fun flush_userStateChangedAfterAnAppStateChangeElsewhere_doesNotWaitForIt() = runBlocking {
    val other = service.createSession(KEY.copy(id = "other"))
    val session = service.createSession(KEY)
    store.appendDelayBySessionId.value = mapOf("other" to 10.seconds)

    append(other, mapOf("app:flag" to "on"))
    append(session, mapOf("user:theme" to "dark"))

    withTimeout(5.seconds) { service.flush(KEY) }
  }

  @Test
  fun flush_appStateChangedAfterAUserStateChangeElsewhere_doesNotWaitForIt() = runBlocking {
    val other = service.createSession(KEY.copy(id = "other"))
    val session = service.createSession(KEY)
    store.appendDelayBySessionId.value = mapOf("other" to 10.seconds)

    append(other, mapOf("user:theme" to "light"))
    append(session, mapOf("app:flag" to "on"))

    withTimeout(5.seconds) { service.flush(KEY) }
  }

  @Test
  fun flush_earlierSharedStateChangeFails_persistsTheLaterOne() = runBlocking {
    val first = service.createSession(KEY)
    val second = service.createSession(KEY.copy(id = "second"))
    store.failingAppends.value = 3

    append(first, mapOf("user:theme" to "light"))
    append(second, mapOf("user:theme" to "dark"))
    service.flush(KEY.copy(id = "second"))

    assertEquals("dark", assertNotNull(store.inner.getSession(KEY)).state["user:theme"])
  }

  @Test
  fun appendEvent_serviceStopped_throwsWithoutChangingTheSession() = runBlocking {
    val session = service.createSession(KEY)
    parent.cancel()

    assertFailsWith<IllegalStateException> { service.appendEvent(session, session.nextEvent()) }

    assertTrue(session.events.isEmpty())
  }

  @Test
  fun flush_serviceCanceledWithAWriteBuffered_reportsTheFailedWrite() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.appendsOpen.value = false
      append(session)

      parent.cancel()

      assertFailsWith<SessionException> { service.flush(KEY) }
    }

  @Test
  fun appendEvent_withoutSessionId_throws() =
    runBlocking<Unit> {
      val session = Session(SessionKey(APP, USER, id = null))

      assertFailsWith<IllegalArgumentException> {
        service.appendEvent(session, session.nextEvent())
      }
      assertFailsWith<IllegalArgumentException> { service.flush(SessionKey(APP, USER, id = null)) }
    }

  @Test
  fun closeSession_writesBuffered_waitsForThem() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false
    append(session)

    val closing = async { service.closeSession(session) }
    delay(SETTLE)

    assertFalse(closing.isCompleted, "closeSession did not wait for the buffered write")
    store.appendsOpen.value = true
    closing.await()
  }

  @Test
  fun closeSession_noWritesBuffered_passesTheSessionOnToTheStore() = runBlocking {
    val session = service.createSession(KEY)

    service.closeSession(session)

    assertSame(session, store.closedSessions.value.single())
  }

  @Test
  fun deleteSession_writesBuffered_waitsForThem() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false
    append(session)

    val deleting = async { service.deleteSession(KEY) }
    delay(SETTLE)

    assertFalse(deleting.isCompleted, "the delete did not wait for the buffered write")
    store.appendsOpen.value = true
    deleting.await()
    assertNull(store.inner.getSession(KEY))
    assertEquals(1, store.expectedUpdateTimes.value.size)
  }

  @Test
  fun deleteSession_writeFails_throwsAndKeepsTheSessionUntilRetried() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.failingAppends.value = 3
      append(session)

      val failure = assertFailsWith<SessionException> { service.deleteSession(KEY) }
      assertNotNull(store.inner.getSession(KEY))
      service.deleteSession(KEY)

      assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, failure.message)
      assertNull(store.inner.getSession(KEY))
    }

  @Test
  fun deleteSession_failureNotReportedYet_throws() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.failingAppends.value = 3
      append(session)
      // Waits for the failed write without reporting it.
      val unusedSession = service.getSession(KEY)

      assertFailsWith<SessionException> { service.deleteSession(KEY) }

      assertNotNull(store.inner.getSession(KEY))
    }

  @Test
  fun deleteSession_userStateChangeBuffered_persistsItFirst() = runBlocking {
    val session = service.createSession(KEY)
    store.appendsOpen.value = false
    append(session, mapOf("user:theme" to "dark"))

    val deleting = async { service.deleteSession(KEY) }
    delay(SETTLE)

    assertFalse(deleting.isCompleted, "the delete did not wait for the user: state change")
    store.appendsOpen.value = true
    deleting.await()
    val other = service.createSession(KEY.copy(id = "other"))
    assertEquals("dark", other.state["user:theme"])
  }

  @Test
  fun deleteSession_afterAFlushReportedTheFailure_deletesTheSession() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.failingAppends.value = 3
      append(session)
      assertFailsWith<SessionException> { service.flush(KEY) }

      service.deleteSession(KEY)

      assertNull(store.inner.getSession(KEY))
    }

  @Test
  fun deleteSession_whileAFlushWaits_bothWaitForTheWrite() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.appendsOpen.value = false
      append(session)

      val flushing = async { service.flush(KEY) }
      val deleting = async { service.deleteSession(KEY) }
      delay(SETTLE)

      assertFalse(flushing.isCompleted, "the flush did not wait for the buffered write")
      assertFalse(deleting.isCompleted, "the delete did not wait for the buffered write")
      store.appendsOpen.value = true
      flushing.await()
      deleting.await()
      assertNull(store.inner.getSession(KEY))
    }

  @Test
  fun deleteSession_whileAFlushWaitsOnAWriteThatFails_bothThrowAndTheSessionStays() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.appendsOpen.value = false
      append(session)
      // Undispatched, so both wait on the write before it fails.
      val flushing =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { service.flush(KEY) }
        }
      val deleting =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { service.deleteSession(KEY) }
        }

      store.failingAppends.value = 3
      store.appendsOpen.value = true
      val unusedFlushFailure = flushing.await()
      val unusedDeleteFailure = deleting.await()

      assertNotNull(store.inner.getSession(KEY))
    }

  @Test
  fun flush_afterWritesPersisted_leavesNoWorkerRunning() = runBlocking {
    val session = service.createSession(KEY)

    append(session)
    service.flush(KEY)

    val serviceJob = parent.children.single()
    withTimeout(5.seconds) { while (serviceJob.children.any()) delay(10.milliseconds) }
  }

  @Test
  fun flush_withoutKey_waitsForEverySession() = runBlocking {
    val first = service.createSession(KEY)
    val second = service.createSession(KEY.copy(id = "second"))
    store.appendDelay.value = 20.milliseconds

    append(first)
    append(second)
    service.flush()

    assertEquals(1, store.inner.listEvents(KEY).events.size)
    assertEquals(1, store.inner.listEvents(KEY.copy(id = "second")).events.size)
  }

  @Test
  fun flush_withoutKey_reportsAFailureUntilItsSessionReportsIt() =
    runBlocking<Unit> {
      val session = service.createSession(KEY)
      store.failingAppends.value = 3

      append(session)
      val failure = assertFailsWith<SessionException> { service.flush() }
      assertFailsWith<SessionException> { service.flush() }
      assertFailsWith<SessionException> { service.flush(KEY) }
      service.flush()

      assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, failure.message)
    }

  @Test
  fun flush_withoutKey_reportsEveryFailedSessionInOneException() = runBlocking {
    val first = service.createSession(KEY)
    val second = service.createSession(KEY.copy(id = "second"))
    store.failingAppends.value = 6

    append(first)
    append(second)
    val failure = assertFailsWith<SessionException> { service.flush() }

    assertEquals(1, failure.suppressedExceptions.size)
  }

  @Test
  fun close_writesBuffered_persistsThemAndRejectsLaterAppends() {
    val session = runBlocking { service.createSession(KEY) }
    store.appendDelay.value = 20.milliseconds
    runBlocking { append(session) }

    service.close()

    runBlocking {
      assertEquals(1, store.inner.listEvents(KEY).events.size)
      assertFailsWith<IllegalStateException> { service.appendEvent(session, session.nextEvent()) }
    }
  }

  @Test
  fun close_writeFails_throws() {
    val session = runBlocking { service.createSession(KEY) }
    store.failingAppends.value = 3
    runBlocking { append(session) }

    assertFailsWith<SessionException> { service.close() }
  }

  @Test
  fun close_noWritesBuffered_passesTheFlushOnToTheStore() {
    service.close()

    assertEquals(listOf<SessionKey?>(null), store.flushedKeys.value)
  }

  @Test
  fun runAsync_slowStore_persistsTheFinalResponseBeforeEmittingIt() = runBlocking {
    store.appendDelay.value = 20.milliseconds
    val callContent = modelFunctionCall("lookup", id = "call-1")
    val textContent = modelMessage("done")
    val agent =
      DummyAgent("agent") { context ->
        emit(Event(author = "agent", invocationId = context.invocationId, content = callContent))
        emit(Event(author = "agent", invocationId = context.invocationId, content = textContent))
      }
    val runner = InMemoryRunner(agent = agent, appName = APP, sessionService = service)

    val events =
      runner
        .runAsync(USER, SESSION, newMessage = userMessage("hi"))
        .onEach { event ->
          if (event.isFinalResponse) {
            val stored = store.inner.listEvents(KEY).events.map { it.id }
            assertTrue(event.id in stored, "the final response was emitted before it persisted")
          }
        }
        .toList()

    assertEquals(2, events.size)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun configToBuilder_matchesCopy() {
    val config =
      BufferedSessionServiceConfig(
        retryPolicy = ExponentialBackoff(maxAttempts = 2),
        cacheConfig = SessionCacheConfig(maxSessions = 7),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(cacheConfig = null), config.toBuilder().cacheConfig(null).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun configBuilder_unsetProperties_matchConstructorDefaults() {
    assertEquals(BufferedSessionServiceConfig(), BufferedSessionServiceConfig.builder().build())
  }

  // --- Contract every SessionService satisfies, over a buffered InMemorySessionService. ---

  @Test
  fun appendEvent_tempKey_visibleInInvocationButNotPersisted() = runBlocking {
    SessionServiceAssertions.tempStateVisibleInInvocationButNotPersisted(service)
  }

  @Test
  fun appendEvent_tempKey_trimmedFromReturnedEvent() = runBlocking {
    SessionServiceAssertions.tempStateTrimmedFromReturnedEvent(service)
  }

  @Test
  fun appendEvent_tempKeyRemoved_reflectedOnLiveSession() = runBlocking {
    SessionServiceAssertions.tempStateRemovalReflectedOnLiveSession(service)
  }

  @Test
  fun getSession_afterCreate_returnsSession() = runBlocking {
    SessionServiceAssertions.createdSessionIsRetrievable(service)
  }

  @Test
  fun createSession_nullId_mintsId() = runBlocking {
    SessionServiceAssertions.createSessionMintsIdWhenAbsent(service)
  }

  @Test
  fun createSession_withInitialState_retainsState() = runBlocking {
    SessionServiceAssertions.createSessionRetainsInitialState(service)
  }

  @Test
  fun getSession_unknownId_returnsNull() = runBlocking {
    SessionServiceAssertions.getUnknownSessionReturnsNull(service)
  }

  @Test
  fun listSessions_includesCreatedSession() = runBlocking {
    SessionServiceAssertions.listSessionsReturnsUsersSessions(service)
  }

  @Test
  fun listSessions_unknownUser_isEmpty() = runBlocking {
    SessionServiceAssertions.listSessionsIsEmptyForUnknownUser(service)
  }

  @Test
  fun listEvents_returnsAppendsInOrder() = runBlocking {
    SessionServiceAssertions.listEventsReturnsAppendsInOrder(service)
  }

  @Test
  fun listEvents_missingSession_returnsEmpty() = runBlocking {
    SessionServiceAssertions.listEventsIsEmptyForUnknownSession(service)
  }

  @Test
  fun deleteSession_removesSession() = runBlocking {
    SessionServiceAssertions.deleteRemovesSession(service)
  }

  @Test
  fun deleteSession_unknownId_isNoOp() = runBlocking {
    SessionServiceAssertions.deleteUnknownSessionIsNoOp(service)
  }

  @Test
  fun appendEvent_sessionScopedKey_isPersisted() = runBlocking {
    SessionServiceAssertions.appendPersistsSessionScopedState(service)
  }

  @Test
  fun appendEvent_removedSentinel_deletesKey() = runBlocking {
    SessionServiceAssertions.appendRemovesStateWithSentinel(service)
  }

  @Test
  fun appendEvent_syncsCallerSession() = runBlocking {
    SessionServiceAssertions.appendSyncsCallerSession(service)
  }

  @Test
  fun createSession_duplicateExplicitId_throwsAndKeepsExistingSession() = runBlocking {
    SessionServiceAssertions.createSessionRejectsDuplicateId(service)
  }

  @Test
  fun createSession_sameIdDifferentUser_isAllowed() = runBlocking {
    SessionServiceAssertions.createSessionAllowsSameIdForDifferentUser(service)
  }

  @Test
  fun appendEvent_appScopedKey_sharedAcrossSessions() = runBlocking {
    SessionServiceAssertions.appScopedStateSharedAcrossSessions(service)
  }

  @Test
  fun appendEvent_userScopedKey_sharedForSameUser() = runBlocking {
    SessionServiceAssertions.userScopedStateSharedForSameUser(service)
  }

  @Test
  fun createSession_prefixedInitialState_isScoped() = runBlocking {
    SessionServiceAssertions.createSessionScopesPrefixedInitialState(service)
  }

  @Test
  fun createSession_removalSentinelInInitialState_isDropped() = runBlocking {
    SessionServiceAssertions.createSessionDropsRemovalSentinelInInitialState(service)
  }

  @Test
  fun createSession_scopedSeed_mergesOntoExistingState() = runBlocking {
    SessionServiceAssertions.createSessionMergesScopedSeedOntoExistingState(service)
  }

  @Test
  fun appendEvent_partial_isNotPersisted() = runBlocking {
    SessionServiceAssertions.appendPartialNotPersisted(service)
  }

  /** Appends the next event of [session], with [stateDelta], where the test ignores the result. */
  private suspend fun append(session: Session, stateDelta: Map<String, Any> = emptyMap()) {
    val unused = service.appendEvent(session, session.nextEvent(stateDelta))
  }

  private companion object {
    const val APP = "buffered-app"
    const val USER = "user"
    const val SESSION = "session"
    val KEY = SessionKey(APP, USER, SESSION)

    // Long enough for a read that does not wait to finish first.
    val SETTLE: Duration = 100.milliseconds

    val RETRY_DELAY: Duration = 50.milliseconds

    // How many unreported failures the service keeps, as its KDoc states.
    const val KEPT_FAILURES = 1024
  }

  /**
   * An [InMemorySessionService] whose appends a test can hold ([appendsOpen]), delay
   * ([appendDelay], [appendDelayBySessionId]), or fail with [failureToThrow] before storing the
   * event ([failingAppends]) or after ([failingAppendsAfterCommit]). It rejects a stale write
   * unless [acceptsHeldEvents] lets it return an event it already holds, as the delegate contract
   * asks, and records the expected update time of each write it accepts in [expectedUpdateTimes].
   * It records each flush in [flushedKeys], holding a session's flush while [sessionFlushesOpen] is
   * false, and each closed session in [closedSessions].
   */
  private class FakeStore(val inner: InMemorySessionService = InMemorySessionService()) :
    SessionService by inner {
    val appendsOpen = MutableStateFlow(true)
    val appendDelay = MutableStateFlow(Duration.ZERO)
    val appendDelayBySessionId = MutableStateFlow(emptyMap<String, Duration>())
    val failingAppends = MutableStateFlow(0)
    val failingAppendsAfterCommit = MutableStateFlow(0)
    val acceptsHeldEvents = MutableStateFlow(false)
    val failureToThrow = MutableStateFlow<Throwable>(IllegalStateException("store unavailable"))
    val expectedUpdateTimes = MutableStateFlow(emptyList<Instant>())
    val flushedKeys = MutableStateFlow(emptyList<SessionKey?>())
    val sessionFlushesOpen = MutableStateFlow(true)
    val closedSessions = MutableStateFlow(emptyList<Session>())

    override suspend fun flush(key: SessionKey?) {
      flushedKeys.update { it + key }
      if (key != null) sessionFlushesOpen.first { it }
    }

    override suspend fun closeSession(session: Session) {
      closedSessions.update { it + session }
    }

    override suspend fun appendEvent(session: Session, event: Event): Event {
      appendsOpen.first { it }
      delay(appendDelayBySessionId.value[session.key.id] ?: appendDelay.value)
      if (failingAppends.getAndUpdate { maxOf(it - 1, 0) } > 0) throw failureToThrow.value
      val stored = inner.getSession(session.key)
      val held = stored?.events?.find { it.id == event.id }
      if (held != null && acceptsHeldEvents.value) {
        expectedUpdateTimes.update { it + session.lastUpdateTime }
        return held
      }
      check(session.lastUpdateTime == stored?.lastUpdateTime) { "stale write" }
      expectedUpdateTimes.update { it + session.lastUpdateTime }
      val appended = inner.appendEvent(session, event)
      if (failingAppendsAfterCommit.getAndUpdate { maxOf(it - 1, 0) } > 0) {
        throw failureToThrow.value
      }
      return appended
    }
  }
}
