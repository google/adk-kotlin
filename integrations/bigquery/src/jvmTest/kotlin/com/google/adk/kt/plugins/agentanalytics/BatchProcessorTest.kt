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

import java.time.Duration as JavaDuration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout

class BatchProcessorTest {

  private val drops = DropCounter()
  private val processors = mutableListOf<BatchProcessor>()

  @AfterTest
  fun shutDownProcessors() {
    for (processor in processors) processor.shutdown(100.milliseconds)
  }

  @Test
  fun append_fullBatch_isWrittenWithoutWaitingForTheInterval(): Unit = runBlocking {
    val writer = FakeRowWriter()
    val processor = processor(writer, batchSize = 3)

    repeat(3) { processor.append(row(it), isTerminal = false) }

    eventually { writer.batches.isNotEmpty() }
    assertEquals(listOf(3), writer.batches.map { it.size })
  }

  @Test
  fun append_partialBatch_isWrittenAfterTheInterval(): Unit = runBlocking {
    val writer = FakeRowWriter()
    val processor = processor(writer, batchSize = 10, flushInterval = 50.milliseconds)

    repeat(2) { processor.append(row(it), isTerminal = false) }

    eventually { writer.batches.isNotEmpty() }
    assertEquals(listOf(2), writer.batches.map { it.size })
  }

  @Test
  fun flush_partialBatch_isWrittenWithoutWaitingForTheInterval(): Unit = runBlocking {
    val writer = FakeRowWriter()
    val processor = processor(writer, batchSize = 10)
    repeat(2) { processor.append(row(it), isTerminal = false) }

    withTimeout(5.seconds) { processor.flush() }

    assertEquals(listOf(listOf(row(0), row(1))), writer.batches)
  }

  @Test
  fun flush_waitsForTheWriteInFlight(): Unit = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val writer = FakeRowWriter(gate = gate)
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }

    val flush = launch { processor.flush() }
    delay(50)
    assertFalse(flush.isCompleted)
    gate.complete(Unit)

    withTimeout(5.seconds) { flush.join() }
  }

  @Test
  fun flush_doesNotWaitForRowsAppendedAfterIt(): Unit = runBlocking {
    val laterRowAppended = CompletableDeferred<Unit>()
    val laterRowWritten = CompletableDeferred<Unit>()
    val writer =
      object : RowWriter {
        override suspend fun write(rows: List<Map<String, Any?>>): WriteResult {
          // Row 0 is still being written when row 1 is appended; row 1 stays blocked.
          if (rows.single() == row(0)) laterRowAppended.await() else laterRowWritten.await()
          return WriteResult.Written
        }

        override fun release() {}

        override fun close() {}
      }
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)

    // Undispatched, so the flush takes its target before row 1 is appended.
    val flush = launch(start = CoroutineStart.UNDISPATCHED) { processor.flush() }
    processor.append(row(1), isTerminal = false)
    laterRowAppended.complete(Unit)

    withTimeout(5.seconds) { flush.join() }
    laterRowWritten.complete(Unit)
  }

  @Test
  fun flush_withNothingAppended_returnsAtOnce(): Unit = runBlocking {
    val processor = processor(FakeRowWriter())

    withTimeout(5.seconds) { processor.flush() }
  }

  @Test
  fun append_queueFull_dropsTheIncomingRowAndCountsIt(): Unit = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val writer = FakeRowWriter(gate = gate)
    val processor = processor(writer, queueMaxSize = 1)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }

    // Row 0 is being written, so row 1 fills the queue.
    for (id in 1..3) processor.append(row(id), isTerminal = false)
    gate.complete(Unit)
    processor.flush()

    assertEquals(listOf(listOf(row(0)), listOf(row(1))), writer.batches)
    assertEquals(mapOf(BatchProcessor.QUEUE_FULL to 2L), drops.snapshot().nonZero())
  }

  @Test
  fun append_terminalRows_takeTheSlotsKeptForThem(): Unit = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val writer = FakeRowWriter(gate = gate)
    // Two of the five slots are kept for terminal rows.
    val processor = processor(writer, queueMaxSize = 5)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }

    for (id in 1..4) processor.append(row(id), isTerminal = false)
    for (id in 5..7) processor.append(row(id), isTerminal = true)
    gate.complete(Unit)
    processor.flush()

    assertEquals(listOf(0, 1, 2, 3, 5, 6).map(::row), writer.batches.flatten())
    assertEquals(mapOf(BatchProcessor.QUEUE_FULL to 2L), drops.snapshot().nonZero())
  }

  @Test
  fun reservedTerminalSlots_matchesPythonAdk() {
    val expected =
      mapOf(1 to 0, 2 to 1, 3 to 2, 10 to 2, 500 to 5, 10_000 to 100) // queueMaxSize to reserved

    assertEquals(expected, expected.keys.associateWith(BatchProcessor::reservedTerminalSlots))
  }

  @Test
  fun retryWait_isTheBackoffTimesARandomFactorBetweenOneAndTwo() {
    assertEquals(2.seconds, BatchProcessor.retryWait(2.seconds, 10.seconds, FixedRandom(0.0)))
    assertEquals(3.seconds, BatchProcessor.retryWait(2.seconds, 10.seconds, FixedRandom(0.5)))
  }

  @Test
  fun retryWait_isCappedAtMaxDelay() {
    assertEquals(10.seconds, BatchProcessor.retryWait(8.seconds, 10.seconds, FixedRandom(0.5)))
  }

  @Test
  fun retryableFailure_isRetriedUntilMaxRetriesThenDropped(): Unit = runBlocking {
    val writer = FakeRowWriter(WriteResult.Retryable, WriteResult.Retryable, WriteResult.Retryable)
    val processor = processor(writer, batchSize = 2, retryConfig = noDelayRetries(maxRetries = 2))
    repeat(2) { processor.append(row(it), isTerminal = false) }

    processor.flush()

    assertEquals(3, writer.batches.size)
    assertEquals(mapOf(BatchProcessor.RETRY_EXHAUSTED to 2L), drops.snapshot().nonZero())
  }

  @Test
  fun retryableFailure_retriesWithTheSameRows(): Unit = runBlocking {
    val writer = FakeRowWriter(WriteResult.Retryable, WriteResult.Written)
    val processor = processor(writer, retryConfig = noDelayRetries(maxRetries = 3))
    processor.append(row(0), isTerminal = false)

    processor.flush()

    assertEquals(2, writer.batches.size)
    assertSame(writer.batches[0].single(), writer.batches[1].single())
    assertEquals(emptyMap(), drops.snapshot().nonZero())
  }

  @Test
  fun droppedResult_isCountedWithoutRetrying(): Unit = runBlocking {
    val writer = FakeRowWriter(WriteResult.Dropped(BatchProcessor.PERMISSION_DENIED))
    val processor = processor(writer, retryConfig = noDelayRetries(maxRetries = 3))
    processor.append(row(0), isTerminal = false)

    processor.flush()

    assertEquals(1, writer.batches.size)
    assertEquals(mapOf(BatchProcessor.PERMISSION_DENIED to 1L), drops.snapshot().nonZero())
  }

  @Test
  fun writerException_isCountedAndLaterRowsAreStillWritten(): Unit = runBlocking {
    val writer = FakeRowWriter(failure = IllegalStateException("boom"))
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    processor.flush()

    processor.append(row(1), isTerminal = false)
    processor.flush()

    assertEquals(listOf(listOf(row(0)), listOf(row(1))), writer.batches)
    assertEquals(mapOf(BatchProcessor.UNEXPECTED_ERROR to 1L), drops.snapshot().nonZero())
  }

  @Test
  fun writeCancelledFromOutside_dropsItsBatchAndLaterRowsAreStillWritten(): Unit = runBlocking {
    // A cancelled future throws CancellationException although the worker is still active.
    val writer = FakeRowWriter(failure = CancellationException("future cancelled"))
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    processor.flush()

    processor.append(row(1), isTerminal = false)
    processor.flush()

    assertEquals(listOf(listOf(row(0)), listOf(row(1))), writer.batches)
    assertEquals(mapOf(BatchProcessor.UNEXPECTED_ERROR to 1L), drops.snapshot().nonZero())
  }

  @Test
  fun writerError_stopsTheProcessorWithoutHangingFlush(): Unit = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val writer = FakeRowWriter(gate = gate, failure = NoClassDefFoundError("missing"))
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }
    processor.append(row(1), isTerminal = false)

    gate.complete(Unit)
    withTimeout(5.seconds) { processor.flush() }
    processor.append(row(2), isTerminal = false)

    // Rows 0 and 1 are lost with the worker, and row 2 is refused.
    assertEquals(mapOf(BatchProcessor.UNEXPECTED_ERROR to 3L), drops.snapshot().nonZero())
    eventually { writer.releases.get() == 1 }
  }

  @Test
  fun dispatcherThatRejectsTheWorker_dropsItsRowsWithoutHangingFlush(): Unit = runBlocking {
    val rejecting = Executors.newSingleThreadExecutor().apply { shutdown() }.asCoroutineDispatcher()
    val writer = FakeRowWriter()
    val processor = processor(writer, context = rejecting)

    processor.append(row(0), isTerminal = false)
    withTimeout(5.seconds) { processor.flush() }

    assertEquals(mapOf(BatchProcessor.UNEXPECTED_ERROR to 1L), drops.snapshot().nonZero())
    eventually { writer.releases.get() == 1 }
  }

  @Test
  fun shutdown_writesTheQueuedRows() {
    val writer = FakeRowWriter()
    val processor = processor(writer, batchSize = 2)
    repeat(3) { processor.append(row(it), isTerminal = false) }

    processor.shutdown(DRAIN_TIMEOUT)

    assertEquals((0..2).map(::row), writer.batches.flatten())
    assertEquals(emptyMap(), drops.snapshot().nonZero())
  }

  @Test
  fun shutdown_afterTheTimeout_dropsTheRowsNotWritten(): Unit = runBlocking {
    val writer = FakeRowWriter(gate = CompletableDeferred())
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }
    processor.append(row(1), isTerminal = false)

    processor.shutdown(100.milliseconds)

    // The row being written and the row behind it.
    assertEquals(mapOf(BatchProcessor.SHUTDOWN_TIMEOUT to 2L), drops.snapshot().nonZero())
    withTimeout(5.seconds) { processor.flush() }
  }

  @Test
  fun shutdown_afterTheTimeout_cancelsTheWriteInFlight(): Unit = runBlocking {
    val writeStarted = CompletableDeferred<Unit>()
    val writeCancelled = CompletableDeferred<Unit>()
    val writer =
      object : RowWriter {
        override suspend fun write(rows: List<Map<String, Any?>>): WriteResult {
          writeStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            writeCancelled.complete(Unit)
          }
        }

        override fun release() {}

        override fun close() {}
      }
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    withTimeout(5.seconds) { writeStarted.await() }

    processor.shutdown(100.milliseconds)

    withTimeout(5.seconds) { writeCancelled.await() }
  }

  @Test
  fun shutdown_interrupted_dropsTheRowsLeftAsCancelled(): Unit = runBlocking {
    val writer = FakeRowWriter(gate = CompletableDeferred())
    val processor = processor(writer)
    processor.append(row(0), isTerminal = false)
    eventually { writer.batches.isNotEmpty() }
    processor.append(row(1), isTerminal = false)

    val stopping = thread { processor.shutdown(1.minutes) }
    stopping.interrupt()
    eventually { !stopping.isAlive }

    assertEquals(mapOf(BatchProcessor.SHUTDOWN_CANCELLED to 2L), drops.snapshot().nonZero())
  }

  @Test
  fun shutdown_withNothingAppended_returnsAtOnce() {
    val processor = processor(FakeRowWriter())

    val elapsed = measureTime { processor.shutdown(DRAIN_TIMEOUT) }

    assertTrue(elapsed < 5.seconds, "took $elapsed")
  }

  @Test
  fun shutdown_leavesTheCallersJobAlone() {
    val callerJob = Job()
    val writer = FakeRowWriter()
    val processor = processor(writer, context = Dispatchers.Default + callerJob)
    processor.append(row(0), isTerminal = false)
    assertTrue(callerJob.children.none())

    processor.shutdown(DRAIN_TIMEOUT)

    assertTrue(callerJob.isActive)
    assertEquals(listOf(row(0)), writer.batches.flatten())
  }

  @Test
  fun append_afterShutdown_isDroppedAsCancelled() {
    val writer = FakeRowWriter()
    val processor = processor(writer)
    processor.shutdown(DRAIN_TIMEOUT)

    processor.append(row(0), isTerminal = true)

    assertEquals(emptyList(), writer.batches)
    assertEquals(mapOf(BatchProcessor.SHUTDOWN_CANCELLED to 1L), drops.snapshot().nonZero())
  }

  @Test
  fun droppedRows_areLoggedOnlyAtPowersOfTwo() {
    val processor = processor(FakeRowWriter())
    processor.shutdown(DRAIN_TIMEOUT)

    val records = logRecordsDuring { repeat(5) { processor.append(row(it), isTerminal = false) } }

    val expected =
      listOf(1, 2, 4).map {
        "Dropped a BigQuery analytics row (shutdown_cancelled); $it dropped for this reason so far."
      }
    assertEquals(expected, records.map { SimpleFormatter().formatMessage(it) })
    assertEquals(mapOf(BatchProcessor.SHUTDOWN_CANCELLED to 5L), drops.snapshot().nonZero())
  }

  @Test
  fun idleWriter_isReleasedOncePerIdleSpell() {
    val scheduler = TestCoroutineScheduler()
    val writer = FakeRowWriter()
    val processor =
      processor(writer, idleTimeout = 2.seconds, context = StandardTestDispatcher(scheduler))

    processor.append(row(0), isTerminal = false)
    scheduler.advanceTimeBy(1.seconds)
    processor.append(row(1), isTerminal = false)
    scheduler.advanceTimeBy(1_500.milliseconds)
    // Past the idle timeout after row 0, but row 1 restarted the wait.
    assertEquals(0, writer.releases.get())

    scheduler.advanceTimeBy(1.seconds)
    assertEquals(1, writer.releases.get())
    scheduler.advanceTimeBy(1.minutes)
    assertEquals(1, writer.releases.get())
    assertEquals(listOf(listOf(row(0)), listOf(row(1))), writer.batches)
  }

  @Test
  fun dropCounter_reportsEveryReasonEvenWithoutDrops() {
    val reasons =
      listOf(
        BatchProcessor.QUEUE_FULL,
        BatchProcessor.RETRY_EXHAUSTED,
        BatchProcessor.NON_RETRYABLE,
        BatchProcessor.PERMISSION_DENIED,
        BatchProcessor.NOT_FOUND,
        BatchProcessor.UNEXPECTED_ERROR,
        BatchProcessor.SHUTDOWN_TIMEOUT,
        BatchProcessor.SHUTDOWN_CANCELLED,
      )

    assertEquals(reasons.associateWith { 0L }, DropCounter().snapshot())
  }

  @Test
  fun dropCounter_addsUpEachReason() {
    val counter = DropCounter()

    counter.add(BatchProcessor.NOT_FOUND)
    counter.add(BatchProcessor.NOT_FOUND, 2)

    assertEquals(3L, counter.count(BatchProcessor.NOT_FOUND))
    assertEquals(mapOf(BatchProcessor.NOT_FOUND to 3L), counter.snapshot().nonZero())
  }

  @Test
  fun processorsSharingADropCounter_addUpTheirDrops() {
    val first = processor(FakeRowWriter())
    first.shutdown(DRAIN_TIMEOUT)
    first.append(row(0), isTerminal = false)
    val second = processor(FakeRowWriter())
    second.shutdown(DRAIN_TIMEOUT)

    second.append(row(1), isTerminal = false)

    assertEquals(mapOf(BatchProcessor.SHUTDOWN_CANCELLED to 2L), drops.snapshot().nonZero())
  }

  private fun processor(
    writer: RowWriter,
    batchSize: Int = 1,
    flushInterval: Duration = 1.minutes,
    queueMaxSize: Int = 100,
    retryConfig: RetryConfig = RetryConfig(maxRetries = 0),
    idleTimeout: Duration = 1.minutes,
    context: CoroutineContext = Dispatchers.Default,
  ) =
    BatchProcessor(
        writer = writer,
        batchSize = batchSize,
        flushInterval = flushInterval,
        queueMaxSize = queueMaxSize,
        retryConfig = retryConfig,
        context = context,
        drops = drops,
        idleTimeout = idleTimeout,
      )
      .also { processors += it }

  private fun noDelayRetries(maxRetries: Int) =
    RetryConfig(
      maxRetries = maxRetries,
      initialDelay = JavaDuration.ZERO,
      maxDelay = JavaDuration.ZERO,
    )

  private fun row(id: Int): Map<String, Any?> = mapOf("event_id" to "event-$id")

  /** Returns the records the processor logs while [block] runs. */
  private fun logRecordsDuring(block: () -> Unit): List<LogRecord> {
    val records = CopyOnWriteArrayList<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord?) {
          if (record != null) records += record
        }

        override fun flush() {}

        override fun close() {}
      }
    val julLogger = Logger.getLogger(BatchProcessor::class.java.name)
    julLogger.addHandler(handler)
    try {
      block()
    } finally {
      julLogger.removeHandler(handler)
    }
    return records
  }

  private fun Map<String, Long>.nonZero(): Map<String, Long> = filterValues { it > 0 }

  private suspend fun eventually(condition: () -> Boolean): Unit =
    withTimeout(5.seconds) { while (!condition()) delay(5) }

  private companion object {
    // Long enough for a shutdown to write every queued row.
    val DRAIN_TIMEOUT = 5.seconds
  }
}

/**
 * A [RowWriter] that records each batch and answers with [results], then with success. Each write
 * waits for [gate], if given; the first then throws [failure], if given.
 */
private class FakeRowWriter(
  vararg results: WriteResult,
  private val gate: CompletableDeferred<Unit>? = null,
  private val failure: Throwable? = null,
) : RowWriter {
  val batches = CopyOnWriteArrayList<List<Map<String, Any?>>>()
  val releases = AtomicInteger()
  private val script = ArrayDeque(results.toList())

  override suspend fun write(rows: List<Map<String, Any?>>): WriteResult {
    batches += rows
    gate?.await()
    if (failure != null && batches.size == 1) throw failure
    return synchronized(script) { script.removeFirstOrNull() } ?: WriteResult.Written
  }

  override fun release() {
    releases.incrementAndGet()
  }

  override fun close() {}
}

/** A [Random] whose [nextDouble] always returns [value]. */
private class FixedRandom(private val value: Double) : Random() {
  override fun nextBits(bitCount: Int): Int = 0

  override fun nextDouble(): Double = value
}
