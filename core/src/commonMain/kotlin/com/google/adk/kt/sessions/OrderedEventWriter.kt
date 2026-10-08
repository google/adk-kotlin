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
import com.google.adk.kt.logging.LoggerFactory
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes one session's events to the store in order, one at a time, running each in
 * [coroutineScope] and passing it to [persist], which throws once the write fails. A write after
 * the session's failed one is dropped with the same cause. Not thread-safe: its owner calls it
 * under one lock.
 *
 * @param beforeComplete Sees the result of each write that started and was not stopped by an Error,
 *   before the write's waiters do. It runs even if the write is canceled after it started, and must
 *   return without throwing.
 */
internal class OrderedEventWriter(
  val key: SessionKey,
  private val coroutineScope: CoroutineScope,
  private val persist: suspend (Write) -> Unit,
  private val beforeComplete: suspend (Write, WriteResult) -> Unit,
) {
  /**
   * An event appended to the live session, on its way to the store.
   *
   * @property expectedUpdateTime The session's [Session.lastUpdateTime] before the append, which a
   *   store's stale-write check compares against the version it holds.
   * @property event The appended event, which must carry its own copy of the actions so later
   *   changes to them by the caller cannot reach the store.
   */
  class Write(val key: SessionKey, val expectedUpdateTime: Instant, val event: Event) {
    private val _result = MutableStateFlow<WriteResult?>(null)

    /** How the write completed, or null while it runs. */
    val result: WriteResult?
      get() = _result.value

    /** Waits until the write has completed. */
    suspend fun join() {
      val unused = await()
    }

    /** Waits until the write has completed, and returns how. */
    suspend fun await(): WriteResult = _result.filterNotNull().first()

    /** Completes the write with [result]; later calls change nothing. */
    internal fun complete(result: WriteResult) {
      _result.compareAndSet(null, result)
    }
  }

  /** How a write completed. */
  sealed interface WriteResult {
    /** The store has the event. */
    data object Persisted : WriteResult

    /** The store may not have the event, because of [cause]. */
    data class Failed(val cause: Throwable) : WriteResult
  }

  /**
   * The session's last write, which completes only after every earlier one unless it or an earlier
   * write was canceled before it started.
   */
  var lastWrite: Write? = null
    private set

  /** Why the session's last write failed, once that write has completed. */
  val lastWriteFailure: Throwable?
    get() = (lastWrite?.result as? WriteResult.Failed)?.cause

  /**
   * Starts writing [event] once the session's previous write and [after] have completed; only a
   * failure of the session's previous write drops it. [after] holds the last writes, by any
   * session, to the shared `app:` or `user:` state [event] changes, so changes to shared state
   * reach the store in the order they were made. A write canceled before it starts completes at
   * once, without waiting for them or calling [beforeComplete].
   */
  // The coroutine reads only immutable values, its own copy of after, and thread-safe results.
  @Suppress("UnsafeCoroutineCrossing")
  fun write(expectedUpdateTime: Instant, event: Event, after: Collection<Write>): Write {
    val write = Write(key, expectedUpdateTime, event)
    val previousWrite = lastWrite
    val previousSharedStateWrites = after.toList()
    lastWrite = write
    coroutineScope
      .launch {
        val result = waitThenPersist(write, previousWrite, previousSharedStateWrites)
        withContext(NonCancellable) { beforeComplete(write, result) }
        write.complete(result)
      }
      // Completes a write that was canceled before it started, or stopped by an Error.
      .invokeOnCompletion { cause -> if (cause != null) write.complete(WriteResult.Failed(cause)) }
    return write
  }

  private suspend fun waitThenPersist(
    write: Write,
    previousWrite: Write?,
    previousSharedStateWrites: List<Write>,
  ): WriteResult {
    // Waits even if canceled or dropped, so the last write completes after every earlier one.
    val previousResult =
      withContext(NonCancellable) {
        for (earlier in previousSharedStateWrites) earlier.join()
        previousWrite?.await()
      }
    if (previousResult is WriteResult.Failed) return previousResult
    return try {
      // The wait above ignores cancellation, so a canceled write stops here, before the store.
      currentCoroutineContext().ensureActive()
      persist(write)
      WriteResult.Persisted
    } catch (e: Exception) {
      // A cancellation lands here too, so a canceled write still completes through beforeComplete.
      // Type only: the message can carry session content, which a log must not.
      if (currentCoroutineContext().isActive) {
        logger.warn {
          "A buffered session write failed; later ones are dropped (${e::class.simpleName})."
        }
      }
      WriteResult.Failed(e)
    }
  }

  private companion object {
    val logger = LoggerFactory.getLogger(OrderedEventWriter::class)
  }
}
