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

package com.google.adk.kt.plugins.agentanalytics

import com.google.adk.kt.logging.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The result of one attempt to write a batch of rows. */
internal sealed interface WriteResult {
  /** BigQuery accepted every row. */
  data object Written : WriteResult

  /** A transient failure, such as an unavailable backend, that is worth another attempt. */
  data object Retryable : WriteResult

  /** A failure that drops the batch, counted under [reason]. */
  data class Dropped(val reason: String) : WriteResult
}

/** Writes batches of rows to the events table. */
internal interface RowWriter : AutoCloseable {
  /** Makes one attempt to write [rows]. Calls never overlap, though [close] may run during one. */
  suspend fun write(rows: List<Map<String, Any?>>): WriteResult

  /** Closes the connection while there is nothing to write; the next [write] opens a new one. */
  fun release()
}

/**
 * Counts dropped rows by reason. Its snapshot lists each batch-processor reason, even one with no
 * drops.
 */
internal class DropCounter {
  private val counts = ConcurrentHashMap(BatchProcessor.DROP_REASONS.associateWith { LongAdder() })

  fun add(reason: String, count: Int = 1) {
    counter(reason).add(count.toLong())
  }

  /** Returns how many rows were dropped for [reason] so far. */
  fun count(reason: String): Long = counter(reason).sum()

  /** Returns the number of rows dropped so far for each reason. */
  fun snapshot(): Map<String, Long> = counts.mapValues { it.value.sum() }.toSortedMap()

  private fun counter(reason: String): LongAdder = counts.computeIfAbsent(reason) { LongAdder() }
}

/**
 * Queues rows and writes them in batches, retrying failed ones, from one background coroutine in
 * [context], which must allow the blocking calls [writer] makes.
 *
 * Appending never blocks: a row that finds the queue full, or the processor shut down, is dropped
 * and counted in [drops]. The writer is released after [idleTimeout] without rows, since an open
 * connection keeps the JVM running, and the caller closes [writer] after [shutdown].
 */
internal class BatchProcessor(
  private val writer: RowWriter,
  private val batchSize: Int,
  private val flushInterval: Duration,
  private val queueMaxSize: Int,
  private val retryConfig: RetryConfig,
  context: CoroutineContext,
  private val drops: DropCounter,
  private val idleTimeout: Duration = IDLE_TIMEOUT,
) {
  private val reservedSlots = reservedTerminalSlots(queueMaxSize)

  private val lock = Any()
  // Guarded by lock.
  private val queue = ArrayDeque<Map<String, Any?>>()
  private var accepted = 0L
  // Why rows are refused once the processor stops taking them.
  private var refusal: String? = null
  // Set once the rows left are given up on, so that the worker does not count them again.
  private var abandoned = false

  // Rows written or dropped; it never decreases, so a flush cannot miss the count it waits for.
  private val finished = MutableStateFlow(0L)
  private val flushWaiters = AtomicInteger()
  private val wakeUp = Channel<Unit>(Channel.CONFLATED)
  // Its own Job, so shutdown leaves the caller's Job alone and the caller never waits for it.
  private val scope = CoroutineScope(context + Job() + CoroutineName("bigquery-analytics-writer"))
  // State the worker shares with callers is guarded by lock or atomic.
  @Suppress("UnsafeCoroutineCrossing")
  private val worker =
    scope
      .launch(start = CoroutineStart.LAZY) {
        try {
          writeBatches()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          onWorkerFailed(e)
        }
      }
      .also { it.invokeOnCompletion(::onWorkerCompleted) }

  /**
   * Queues [row] to be written; an [isTerminal] row, one that closes an invocation, may also take
   * the slots kept for such rows.
   */
  fun append(row: Map<String, Any?>, isTerminal: Boolean) {
    val dropReason =
      synchronized(lock) {
        val capacity = if (isTerminal) queueMaxSize else queueMaxSize - reservedSlots
        when {
          refusal != null -> refusal
          queue.size >= capacity -> QUEUE_FULL
          else -> {
            queue.addLast(row)
            accepted++
            null
          }
        }
      }
    if (dropReason != null) {
      drops.add(dropReason)
      val dropped = drops.count(dropReason)
      // Logs the 1st, 2nd, 4th, 8th... drop for each reason, so a full queue cannot flood the log.
      if (dropped.countOneBits() == 1) {
        logger.warn {
          "Dropped a BigQuery analytics row ($dropReason); $dropped dropped for this reason so far."
        }
      }
      return
    }
    worker.start()
    signalWorker()
  }

  /**
   * Waits until every row appended before the call is written or dropped.
   *
   * Meanwhile a batch is written as soon as it has a row, without waiting for the flush interval.
   */
  suspend fun flush() {
    val target = synchronized(lock) { accepted }
    flushWaiters.incrementAndGet()
    try {
      signalWorker()
      finished.first { it >= target }
    } finally {
      flushWaiters.decrementAndGet()
    }
  }

  /**
   * Stops accepting rows and blocks for up to [timeout] while the queued rows are written.
   *
   * Rows still queued or being written after [timeout] are dropped as `shutdown_timeout`, or as
   * `shutdown_cancelled` if the wait is interrupted.
   */
  fun shutdown(timeout: Duration) {
    synchronized(lock) {
      // Already shut down, or the worker failed and has dropped its rows.
      if (refusal != null) return
      refusal = SHUTDOWN_CANCELLED
    }
    val done = CountDownLatch(1)
    worker.invokeOnCompletion { done.countDown() }
    // A lazy worker must start for invokeOnCompletion to fire; with no rows it returns at once.
    worker.start()
    signalWorker()
    // Rows left by a worker that already ended, as on a rejected dispatch, did not time out.
    val reason =
      try {
        val completed = done.await(timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        if (completed) UNEXPECTED_ERROR else SHUTDOWN_TIMEOUT
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        SHUTDOWN_CANCELLED
      }
    val lost = abandonRemaining(reason)
    if (lost > 0) logger.warn { "Dropped $lost BigQuery analytics row(s) at shutdown ($reason)." }
    scope.cancel()
  }

  private suspend fun writeBatches() {
    while (true) {
      val batch = nextBatch() ?: return
      finish(batch.size, writeWithRetry(batch))
    }
  }

  // Waits for a full batch, a flush, shutdown, or the flush interval after the first row.
  private suspend fun nextBatch(): List<Map<String, Any?>>? {
    if (withTimeoutOrNull(idleTimeout) { awaitRows() } == null) {
      writer.release()
      awaitRows()
    }
    withTimeoutOrNull(flushInterval) { while (!isBatchReady()) wakeUp.receive() }
    return synchronized(lock) {
      if (queue.isEmpty()) null else List(min(batchSize, queue.size)) { queue.removeFirst() }
    }
  }

  private suspend fun awaitRows() {
    while (synchronized(lock) { queue.isEmpty() && refusal == null }) wakeUp.receive()
  }

  private fun isBatchReady(): Boolean =
    flushWaiters.get() > 0 || synchronized(lock) { queue.size >= batchSize || refusal != null }

  // Returns why the batch was dropped, or null once it is written.
  private suspend fun writeWithRetry(batch: List<Map<String, Any?>>): String? {
    var backoff = retryConfig.initialDelay.toKotlinDuration()
    var attempt = 1
    while (true) {
      val result =
        try {
          writer.write(batch)
        } catch (e: CancellationException) {
          // Only this worker's own cancellation ends it; a cancelled write drops just its batch.
          currentCoroutineContext().ensureActive()
          logger.error { "A BigQuery write was cancelled; dropped ${batch.size} row(s)." }
          WriteResult.Dropped(UNEXPECTED_ERROR)
        } catch (e: Exception) {
          logger.error {
            "Failed to write ${batch.size} BigQuery analytics row(s): ${e::class.simpleName}"
          }
          WriteResult.Dropped(UNEXPECTED_ERROR)
        }
      when (result) {
        WriteResult.Written -> return null
        is WriteResult.Dropped -> return result.reason
        WriteResult.Retryable -> {
          if (attempt > retryConfig.maxRetries) {
            logger.error {
              "Dropped ${batch.size} BigQuery analytics row(s) after $attempt failed attempt(s)."
            }
            return RETRY_EXHAUSTED
          }
          val wait = retryWait(backoff, retryConfig.maxDelay.toKotlinDuration())
          logger.warn { "BigQuery write attempt $attempt failed; retrying in $wait." }
          delay(wait)
          backoff *= retryConfig.multiplier
          attempt++
        }
      }
    }
  }

  // Skips a batch that a timed-out shutdown or a failed worker already counted.
  private fun finish(count: Int, dropReason: String?) {
    synchronized(lock) {
      if (abandoned) return
      if (dropReason != null) drops.add(dropReason, count)
      finished.value += count
    }
  }

  // A failed worker stays stopped; it drops the rows left and releases the writer.
  private fun onWorkerFailed(failure: Throwable) {
    synchronized(lock) { if (refusal == null) refusal = UNEXPECTED_ERROR }
    val lost = abandonRemaining(UNEXPECTED_ERROR)
    writer.release()
    logger.error {
      "The BigQuery analytics writer stopped (${failure::class.simpleName}); dropped $lost row(s)."
    }
  }

  // A worker cancelled before shutdown, such as by a dispatcher that rejected it, stops as failed.
  private fun onWorkerCompleted(cause: Throwable?) {
    if (cause == null) return
    val stoppedEarly = synchronized(lock) { refusal == null }
    if (stoppedEarly) onWorkerFailed(cause)
  }

  // Gives up on every row not yet written or dropped, counting them before a flush can return.
  private fun abandonRemaining(reason: String): Int =
    synchronized(lock) {
      abandoned = true
      queue.clear()
      val lost = (accepted - finished.value).toInt()
      drops.add(reason, lost)
      finished.value = accepted
      lost
    }

  // A conflated channel never rejects an element, and a pending wake-up is enough.
  private fun signalWorker() {
    val unused = wakeUp.trySend(Unit)
  }

  companion object {
    const val QUEUE_FULL = "queue_full"
    const val RETRY_EXHAUSTED = "retry_exhausted"
    const val NON_RETRYABLE = "non_retryable"
    const val PERMISSION_DENIED = "permission_denied"
    const val NOT_FOUND = "not_found"
    const val UNEXPECTED_ERROR = "unexpected_error"
    const val SHUTDOWN_TIMEOUT = "shutdown_timeout"
    const val SHUTDOWN_CANCELLED = "shutdown_cancelled"

    /** The drop reasons that [DropCounter] lists even with no drops. */
    val DROP_REASONS =
      listOf(
        QUEUE_FULL,
        RETRY_EXHAUSTED,
        NON_RETRYABLE,
        PERMISSION_DENIED,
        NOT_FOUND,
        UNEXPECTED_ERROR,
        SHUTDOWN_TIMEOUT,
        SHUTDOWN_CANCELLED,
      )

    private val logger = LoggerFactory.getLogger(BatchProcessor::class)

    private val IDLE_TIMEOUT = 10.seconds

    /** Returns the queue slots kept for rows that close an invocation. */
    fun reservedTerminalSlots(queueMaxSize: Int): Int =
      if (queueMaxSize <= 1) 0 else min(max(2, queueMaxSize / 100), queueMaxSize - 1)

    /** Returns [backoff] times a random factor between 1 and 2, capped at [maxDelay]. */
    fun retryWait(
      backoff: Duration,
      maxDelay: Duration,
      random: Random = Random.Default,
    ): Duration = minOf(backoff * (1 + random.nextDouble()), maxDelay)
  }
}
