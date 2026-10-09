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
import com.google.adk.kt.sessions.OrderedEventWriter.WriteResult
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class OrderedEventWriterTest {

  private val parent = Job()
  private val scope = CoroutineScope(Dispatchers.Default + parent)
  // Runs its tasks in order on one thread, which lets awaitStarted() see that a write has started.
  private val oneThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val oneThreadScope = CoroutineScope(oneThread + parent)
  private val store = FakeStore()

  @AfterTest
  fun tearDown() {
    parent.cancel()
    oneThread.close()
  }

  @Test
  fun write_storeBlocked_returnsBeforeWriteCompletes() =
    runBlocking<Unit> {
      val event = event()
      store.block(event)

      val write = writer().write(T0, event, after = emptyList())

      assertNull(write.result)
      store.unblockAll()
      assertEquals(WriteResult.Persisted, write.await())
      assertEquals(listOf(event.id), store.persistedIds)
    }

  @Test
  fun write_several_persistsThemInOrderOneAtATime() =
    runBlocking<Unit> {
      val writer = writer()
      store.persistDelay = 5.milliseconds
      val events = List(5) { event() }

      val writes = events.map { writer.write(T0, it, after = emptyList()) }
      writes.last().join()

      assertEquals(events.map { it.id }, store.persistedIds)
      assertEquals(1, store.maxInFlight)
      assertSame(writes.last(), writer.lastWrite)
    }

  @Test
  fun write_persistThrows_completesFailedWithThatCause() =
    runBlocking<Unit> {
      val event = event()
      val failure = IllegalStateException("store unavailable")
      store.fail(event, cause = failure)

      val result = writer().write(T0, event, after = emptyList()).await()

      assertSame(failure, assertIs<WriteResult.Failed>(result).cause)
    }

  @Test
  fun write_afterFailedWrite_isDroppedWithoutPersisting() =
    runBlocking<Unit> {
      val writer = writer()
      val failing = event()
      val failure = IllegalStateException("store unavailable")
      store.block(failing)
      store.fail(failing, cause = failure)

      val unusedFailing = writer.write(T0, failing, after = emptyList())
      val dropped = writer.write(T0, event(), after = emptyList())
      store.unblockAll()

      assertEquals(WriteResult.Failed(failure), dropped.await())
      assertEquals(listOf(failing.id), store.attemptedIds)
      assertSame(failure, writer.lastWriteFailure)
    }

  @Test
  fun write_afterAnotherSessionsFailedWrite_stillPersists() =
    runBlocking<Unit> {
      val failing = event()
      store.fail(failing)
      val otherSessionsWrite = writer(OTHER_KEY).write(T0, failing, after = emptyList())

      val result = writer().write(T0, event(), after = listOf(otherSessionsWrite)).await()

      assertEquals(WriteResult.Persisted, result)
    }

  @Test
  fun write_afterAnotherSessionsPendingWrite_waitsForItBeforePersisting() =
    runBlocking<Unit> {
      val shared = event()
      store.block(shared)
      val otherSessionsWrite = writer(OTHER_KEY).write(T0, shared, after = emptyList())
      val mine = event()

      val write = writer().write(T0, mine, after = listOf(otherSessionsWrite))
      delay(SETTLE)

      assertFalse(mine.id in store.attemptedIds, "the write did not wait for the one it follows")
      store.unblockAll()
      assertEquals(WriteResult.Persisted, write.await())
      assertEquals(listOf(shared.id, mine.id), store.persistedIds)
    }

  @Test
  fun write_droppedWhileAnotherSessionsWriteIsPending_completesOnlyAfterIt() =
    runBlocking<Unit> {
      val writer = writer()
      val failing = event()
      val shared = event()
      val failure = IllegalStateException("store unavailable")
      store.fail(failing, cause = failure)
      store.block(shared)
      val otherSessionsWrite = writer(OTHER_KEY).write(T0, shared, after = emptyList())

      val unusedFailing = writer.write(T0, failing, after = emptyList())
      val dropped = writer.write(T0, event(), after = listOf(otherSessionsWrite))
      delay(SETTLE)

      assertNull(dropped.result, "the dropped write completed before the write it follows")
      store.unblockAll()
      assertEquals(WriteResult.Failed(failure), dropped.await())
    }

  @Test
  fun write_beforeComplete_seesResultBeforeWaiters() =
    runBlocking<Unit> {
      val release = CompletableDeferred<Unit>()
      val seen = CompletableDeferred<Pair<WriteResult?, WriteResult>>()
      val writer =
        writer(
          beforeComplete = { write, result ->
            seen.complete(write.result to result)
            release.await()
          }
        )

      val write = writer.write(T0, event(), after = emptyList())
      val (visibleResult, passedResult) = seen.await()
      delay(SETTLE)

      assertNull(visibleResult)
      assertEquals(WriteResult.Persisted, passedResult)
      assertNull(write.result, "the write completed before beforeComplete returned")
      release.complete(Unit)
      assertEquals(WriteResult.Persisted, write.await())
    }

  @Test
  fun lastWriteFailure_runningOrPersisted_isNull() =
    runBlocking<Unit> {
      val writer = writer()
      val event = event()
      store.block(event)

      val write = writer.write(T0, event, after = emptyList())

      assertNull(writer.lastWriteFailure)
      store.unblockAll()
      write.join()
      assertNull(writer.lastWriteFailure)
    }

  @Test
  fun write_persistThrowsCancellationException_completesFailedAfterBeforeComplete() =
    runBlocking<Unit> {
      val event = event()
      val failure = CancellationException("store timed out")
      store.fail(event, cause = failure)
      val beforeCompleteResult = CompletableDeferred<WriteResult>()

      val result =
        writer(beforeComplete = { _, result -> beforeCompleteResult.complete(result) })
          .write(T0, event, after = emptyList())
          .await()

      assertEquals(WriteResult.Failed(failure), result)
      assertEquals(result, beforeCompleteResult.await())
    }

  @Test
  fun write_persistThrowsError_completesFailedAndRethrowsIt() =
    runBlocking<Unit> {
      // Catches the Error the write rethrows, which would otherwise reach the global handler.
      val escaped = CompletableDeferred<Throwable>()
      val handler = CoroutineExceptionHandler { _, error -> escaped.complete(error) }
      val failingScope = CoroutineScope(Dispatchers.Default + Job(parent) + handler)
      val beforeCompleteCalled = MutableStateFlow(false)
      val event = event()
      val error = NotImplementedError("store stub")
      store.fail(event, cause = error)
      val writer =
        writer(scope = failingScope, beforeComplete = { _, _ -> beforeCompleteCalled.value = true })

      val result = writer.write(T0, event, after = emptyList()).await()

      assertEquals(WriteResult.Failed(error), result)
      assertSame(error, escaped.await())
      assertFalse(beforeCompleteCalled.value)
    }

  @Test
  fun write_scopeCanceledBeforeItStarted_completesFailedWithoutBeforeComplete() =
    runBlocking<Unit> {
      val beforeCompleteCalled = MutableStateFlow(false)
      val writer = writer(beforeComplete = { _, _ -> beforeCompleteCalled.value = true })
      parent.cancel()

      val result = writer.write(T0, event(), after = emptyList()).await()

      assertIs<CancellationException>(assertIs<WriteResult.Failed>(result).cause)
      assertTrue(store.attemptedIds.isEmpty())
      assertFalse(beforeCompleteCalled.value)
    }

  @Test
  fun write_scopeCanceledWhilePersisting_completesFailedAfterBeforeComplete() =
    runBlocking<Unit> {
      val beforeCompleteResult = CompletableDeferred<WriteResult>()
      val writer =
        writer(
          beforeComplete = { _, result ->
            // Suspending on the canceled job throws unless beforeComplete runs as NonCancellable.
            yield()
            beforeCompleteResult.complete(result)
          }
        )
      val event = event()
      store.block(event)
      val write = writer.write(T0, event, after = emptyList())
      store.awaitAttempt(event)

      parent.cancel()

      val result = write.await()
      assertIs<CancellationException>(assertIs<WriteResult.Failed>(result).cause)
      assertEquals(result, withTimeout(5.seconds) { beforeCompleteResult.await() })
    }

  @Test
  fun write_scopeCanceledWhileWaitingForPreviousWrite_completesAfterItThroughBeforeComplete() =
    runBlocking<Unit> {
      val completedIds = MutableStateFlow(emptyList<String>())
      val writer =
        writer(
          scope = oneThreadScope,
          beforeComplete = { write, _ -> completedIds.update { it + write.event.id } },
        )
      val first = event()
      val second = event()
      store.block(first)
      val unusedFirst = writer.write(T0, first, after = emptyList())
      val secondWrite = writer.write(T0, second, after = emptyList())
      store.awaitAttempt(first)
      awaitStarted()

      parent.cancel()

      assertIs<CancellationException>(assertIs<WriteResult.Failed>(secondWrite.await()).cause)
      assertEquals(listOf(first.id, second.id), completedIds.value)
    }

  @Test
  fun write_scopeCanceledWhileWaitingForAnotherSessionsWrite_neverReachesStore() =
    runBlocking<Unit> {
      val shared = event()
      store.block(shared)
      val otherSessionsWrite = writer(OTHER_KEY).write(T0, shared, after = emptyList())
      val mine = event()
      // Only this write's scope is canceled, so the write it waits for still persists afterwards.
      val mineJob = Job(parent)
      val write =
        writer(scope = CoroutineScope(oneThread + mineJob))
          .write(T0, mine, after = listOf(otherSessionsWrite))
      store.awaitAttempt(shared)
      awaitStarted()

      mineJob.cancel()
      store.unblockAll()

      assertIs<CancellationException>(assertIs<WriteResult.Failed>(write.await()).cause)
      assertEquals(WriteResult.Persisted, otherSessionsWrite.await())
      assertFalse(mine.id in store.attemptedIds, "a canceled write reached the store")
    }

  @Test
  fun complete_calledTwice_keepsFirstResult() {
    val write = OrderedEventWriter.Write(KEY, T0, event())

    write.complete(WriteResult.Persisted)
    write.complete(WriteResult.Failed(IllegalStateException("late")))

    assertEquals(WriteResult.Persisted, write.result)
  }

  private fun writer(
    key: SessionKey = KEY,
    scope: CoroutineScope = this.scope,
    beforeComplete: suspend (OrderedEventWriter.Write, WriteResult) -> Unit = { _, _ -> },
  ) = OrderedEventWriter(key, scope, store::persist, beforeComplete)

  /** Returns once every write launched on [oneThread] so far has started. */
  private suspend fun awaitStarted() = withContext(oneThread) {}

  private companion object {
    const val APP = "app"
    val KEY = SessionKey(APP, "user", "session")
    val OTHER_KEY = SessionKey(APP, "other-user", "other")
    val T0: Instant = Instant.fromEpochMilliseconds(0)

    // Long enough for a write that does not wait to reach the store first.
    val SETTLE: Duration = 100.milliseconds

    fun event(): Event = Event(author = "agent", invocationId = "inv")
  }
}
