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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.testing.testInvocationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Records the threads that ran branch bodies and holds each body until [latchCount] of them are in
 * flight at once, which only happens when the runtime dispatches them across real threads.
 */
private class JoinOverlapProbe(latchCount: Int) {
  /** Distinct names of the threads that ran a probed body. */
  val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** The most bodies observed running at the same time. */
  val maxConcurrent = AtomicInteger(0)

  private val active = AtomicInteger(0)
  private val overlap = CountDownLatch(latchCount)

  /** Records the running thread, then blocks until enough bodies overlap or 30 seconds pass. */
  fun enter() {
    threads.add(Thread.currentThread().name)
    val current = active.incrementAndGet()
    maxConcurrent.getAndUpdate { maxOf(it, current) }
    overlap.countDown()
    overlap.await(30, TimeUnit.SECONDS)
  }

  fun leave() {
    active.decrementAndGet()
  }
}

/** A branch that overlaps with its siblings before emitting [value], so parallelism shows. */
private class JoinProbedBranch(
  override val name: String,
  private val value: Any?,
  private val probe: JoinOverlapProbe,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    probe.enter()
    try {
      emit(value)
    } finally {
      probe.leave()
    }
  }
}

/**
 * Runs a join on a real multi-thread dispatcher, so a race in the scheduler's barrier or the shared
 * event sink would surface. The other workflow tests run on single-thread `runBlocking`, where
 * branches interleave but never run at once.
 */
class JoinNodeConcurrencyTest {

  @Test
  fun run_sixBranchesOnSeveralThreads_firesOnceWithEveryOutput() {
    // Arrange
    val probe = JoinOverlapProbe(latchCount = 2)
    val branches = (1..6).map { JoinProbedBranch("b$it", it.toLong(), probe) }
    val fork = Emitter("fork", "go")
    val join = JoinNode("join")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(Edge(Start, fork)) +
            branches.map { Edge(fork, it) } +
            branches.map { Edge(it, join) },
      )

    // Act
    val events =
      runBlocking(Dispatchers.Default) { workflow.runAsync(testInvocationContext()).toList() }

    // Assert: every run of the join is matched, so a second firing (`join@2`) fails `single()`.
    val joinEvent = events.single { it.nodeInfo?.path?.substringBeforeLast('@') == "wf@1/join" }
    assertEquals((1..6).associate { "b$it" to it.toLong() }, joinEvent.output)
    assertTrue(probe.maxConcurrent.get() >= 2, "branches never overlapped")
    assertTrue(probe.threads.size >= 2, "branches ran on ${probe.threads.size} thread(s)")
  }
}
