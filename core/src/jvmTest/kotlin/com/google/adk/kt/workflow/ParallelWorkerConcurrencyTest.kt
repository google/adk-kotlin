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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.testing.testInvocationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/**
 * Records the threads that ran item bodies and holds each body until [overlapCount] of them are in
 * flight at once, which only happens when the items really run on several threads.
 */
private class ItemOverlapProbe(overlapCount: Int) {
  /** Distinct names of the threads that ran an item body. */
  val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** The most item bodies seen running at the same time. */
  val maxConcurrent = AtomicInteger(0)

  private val active = AtomicInteger(0)
  private val overlap = CountDownLatch(overlapCount)

  /**
   * Records the running thread, then blocks it until enough bodies overlap. Fails if they never do;
   * cancellation interrupts the wait.
   */
  suspend fun enter() {
    threads.add(Thread.currentThread().name)
    val current = active.incrementAndGet()
    maxConcurrent.getAndUpdate { maxOf(it, current) }
    overlap.countDown()
    check(runInterruptible { overlap.await(30, TimeUnit.SECONDS) }) { "items never overlapped" }
  }

  fun leave() {
    active.decrementAndGet()
  }
}

/** Doubles each item while overlapping with the other items. */
private class OverlappingDoubler(name: String, private val probe: ItemOverlapProbe) :
  BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    probe.enter()
    try {
      emit((nodeInput as Long) * 2)
    } finally {
      probe.leave()
    }
  }
}

/** Fails every item at the same moment, once [count] of them are running. */
private class SimultaneousFailures(name: String, count: Int) : BaseNode(name = name) {
  private val allRunning = CountDownLatch(count)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    allRunning.countDown()
    check(runInterruptible { allRunning.await(30, TimeUnit.SECONDS) }) { "items never ran at once" }
    throw TestNodeException("item $nodeInput failed")
  }
}

/** Records the order in which items start. */
private class StartOrderRecorder(name: String) : BaseNode(name = name) {
  val started = ConcurrentLinkedQueue<Long>()

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    started.add(nodeInput as Long)
    emit(nodeInput)
  }
}

/**
 * Runs parallel workers on the multi-threaded [Dispatchers.Default], where a race in the fan-out or
 * the shared event sink would surface; the other workflow tests run on single-threaded
 * `runBlocking`, where items interleave but never truly run at once.
 */
class ParallelWorkerConcurrencyTest {

  @Test
  fun run_parallelNode_runsItemsConcurrentlyInInputOrder() {
    // Arrange
    val probe = ItemOverlapProbe(overlapCount = 2)
    val source = Emitter("source", (1..8).map { it.toLong() })
    val worker = parallelNode(OverlappingDoubler("worker", probe))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events =
      runBlocking(Dispatchers.Default) { workflow.runAsync(testInvocationContext()).toList() }

    // Assert: the items overlapped on several threads, and the results still follow input order.
    assertEquals((1..8).map { it.toLong() * 2 }, events.last().output)
    assertTrue(
      probe.maxConcurrent.get() >= 2,
      "expected items to overlap but max concurrent was ${probe.maxConcurrent.get()}",
    )
    assertTrue(probe.threads.size >= 2, "expected items on >= 2 threads but saw ${probe.threads}")
  }

  @Test
  fun run_itemsFailAtOnce_alwaysFailsTheNode() {
    repeat(50) {
      // Arrange: both items fail on their own threads at once, so each sees the other still
      // running.
      val source = Emitter("source", listOf(1L, 2L))
      val worker = parallelNode(SimultaneousFailures("worker", count = 2))
      val workflow =
        Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

      // Act
      val error =
        assertFailsWith<TestNodeException> {
          runBlocking(Dispatchers.Default) { workflow.runAsync(testInvocationContext()).toList() }
        }

      // Assert: a failure always surfaces; the items never cancel each other into a short list.
      assertContains(error.message!!, "failed")
    }
  }

  @Test
  fun run_oneWorkerAtATime_startsItemsInInputOrder() {
    repeat(10) {
      // Arrange
      val items = (1..20).map { it.toLong() }
      val source = Emitter("source", items)
      val recorder = StartOrderRecorder("worker")
      val worker = parallelNode(recorder, maxParallelWorkers = 1)
      val workflow =
        Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

      // Act
      runBlocking(Dispatchers.Default) { workflow.runAsync(testInvocationContext()).toList() }

      // Assert
      assertEquals(items, recorder.started.toList())
    }
  }
}
