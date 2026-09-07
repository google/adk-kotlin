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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testInvocationContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** Retries with no delay, so a test never waits on backoff. */
private fun instantRetry(maxAttempts: Int, exceptions: List<String>? = null) =
  RetryConfig(
    maxAttempts = maxAttempts,
    initialDelay = Duration.ZERO,
    jitter = 0.0,
    exceptions = exceptions,
  )

/** Raises on every attempt. */
private class AlwaysRaises(
  override val name: String,
  private val error: () -> Throwable,
  retry: RetryConfig? = null,
) : Node {
  override val retryConfig: RetryConfig? = retry

  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow { throw error() }
}

/** Raises until the given attempt, then succeeds. Attempt count comes from the context. */
private class FailsUntilAttempt(
  override val name: String,
  private val succeedFrom: Int,
  retry: RetryConfig? = null,
) : Node {
  override val retryConfig: RetryConfig? = retry

  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
    if (context.attemptCount < succeedFrom) throw NodeExecutionException("RuntimeError", "boom")
    emit("B")
  }
}

/** Sleeps, so a timeout or a cancellation has something to interrupt. */
private class Sleeper(
  override val name: String,
  private val nap: Duration,
  override val timeout: Duration? = null,
) : Node {
  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
    delay(nap)
    emit("slept")
  }
}

class WorkflowFailureTest {

  @Test
  fun aNodeThatRaisesWithoutRetryShutsTheWorkflowDown() {
    // Arrange
    val a = Emitter("a", "A")
    val boom = AlwaysRaises("boom", { NodeExecutionException("RuntimeError", "boom") })
    val after = Emitter("after", "never")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, boom), Edge(boom, after)))

    // Act
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "boom")
  }

  @Test
  fun aRetriedNodeReportsEveryFailedAttemptAndThenSucceeds() {
    // Arrange
    val a = Emitter("a", "A")
    val flaky = FailsUntilAttempt("flaky", succeedFrom = 3, retry = instantRetry(5))
    val c = Emitter("c", "C")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, flaky), Edge(flaky, c)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(
      listOf(
        Triple("wf@1/a@1", null, "A"),
        Triple("wf@1/flaky@1", "RuntimeError", null),
        Triple("wf@1/flaky@1", "RuntimeError", null),
        Triple("wf@1/flaky@1", null, "B"),
        Triple("wf@1/c@1", null, "C"),
      ),
      events.map { Triple(it.nodeInfo?.path, it.errorCode, it.output) },
    )
  }

  @Test
  fun theRetryBudgetCountsTheFirstAttempt() {
    // Arrange
    val flaky = FailsUntilAttempt("flaky", succeedFrom = 3, retry = instantRetry(3))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, flaky)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(2, events.count { it.errorCode != null })
    assertEquals("B", events.last().output)
  }

  @Test
  fun anExhaustedRetryBudgetFailsTheRun() {
    // Arrange
    val doomed =
      AlwaysRaises("doomed", { NodeExecutionException("RuntimeError", "boom") }, instantRetry(3))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, doomed)))

    // Act
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "boom")
  }

  @Test
  fun retryAppliesOnlyToTheNamedExceptions() {
    // Arrange
    val unmatched =
      AlwaysRaises(
        "unmatched",
        { NodeExecutionException("RuntimeError", "boom") },
        instantRetry(5, exceptions = listOf("ValueError")),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, unmatched)))

    // Act
    val events = mutableListOf<Event>()
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking {
          workflow.runAsync(testInvocationContext(agent = workflow)).collect { events.add(it) }
        }
      }

    // Assert
    assertContains(error.message!!, "boom")
    assertEquals(1, events.count { it.errorCode == "RuntimeError" })
  }

  @Test
  fun aNodeThatOverrunsItsTimeoutFailsTheRunAndNamesItself() {
    // Arrange
    val slow = Sleeper("slow", nap = 30.seconds, timeout = 50.milliseconds)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, slow)))

    // Act
    val error = assertFailsWith<NodeTimeoutException> { runWorkflow(workflow) }

    // Assert
    assertEquals("slow", error.nodeName)
    assertContains(error.message!!, "slow")
  }

  @Test
  fun aTimedOutNodeIsStillRetried() {
    // Arrange
    val retried =
      object : Node {
        override val name: String = "slow_retried"
        override val timeout: Duration = 50.milliseconds
        override val retryConfig: RetryConfig = instantRetry(2)

        override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
          delay(30.seconds)
          emit("never")
        }
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, retried)))

    // Act
    val events = mutableListOf<Event>()
    assertFailsWith<NodeTimeoutException> {
      runBlocking {
        workflow.runAsync(testInvocationContext(agent = workflow)).collect { events.add(it) }
      }
    }

    // Assert
    assertEquals(2, events.count { it.errorCode == "NodeTimeoutError" })
  }

  @Test
  fun aSlowNodeStillOrdersTheChain() {
    // Arrange
    val slow = Sleeper("slow", nap = 30.milliseconds)
    val after = Emitter("after", "after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, slow), Edge(slow, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("wf@1/slow@1", "wf@1/after@1"), events.map { it.nodeInfo?.path })
  }

  @Test
  fun aFailingBranchCancelsAStillRunningSibling() {
    // Arrange
    val fork = Emitter("fork", "F")
    val boom = AlwaysRaises("boom", { NodeExecutionException("RuntimeError", "boom") })
    val slow = Sleeper("slow", nap = 30.seconds)
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, fork), Edge(fork, boom), Edge(fork, slow)))

    // Act
    val start = TimeSource.Monotonic.markNow()
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }
    val elapsed = start.elapsedNow()

    // Assert: the failure ends the run instead of waiting out the sleeping sibling.
    assertContains(error.message!!, "boom")
    assertTrue(elapsed < 10.seconds, "run took $elapsed, so the sibling was not cancelled")
  }

  @Test
  fun retryConfigRejectsNegativeValues() {
    assertFailsWith<IllegalArgumentException> { RetryConfig(maxAttempts = -1) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(initialDelay = (-1).seconds) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(maxDelay = (-1).seconds) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(backoffFactor = -1.0) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(jitter = -0.5) }
  }

  @Test
  fun anEmptyExceptionsListRetriesNothing() {
    // Arrange
    val doomed =
      AlwaysRaises(
        "doomed",
        { NodeExecutionException("RuntimeError", "boom") },
        instantRetry(5, exceptions = emptyList()),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, doomed)))

    // Act
    val events = mutableListOf<Event>()
    assertFailsWith<NodeExecutionException> {
      runBlocking {
        workflow.runAsync(testInvocationContext(agent = workflow)).collect { events.add(it) }
      }
    }

    // Assert: an empty list matches nothing, so the node runs once and is not retried.
    assertEquals(1, events.count { it.errorCode == "RuntimeError" })
  }

  @Test
  fun aWorkflowAppliesItsOwnTimeoutAndRetryPolicyWhenItIsTheRootAgent() {
    // Arrange
    var runs = 0
    val slow =
      object : Node {
        override val name: String = "slow"

        override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
          runs += 1
          delay(30.seconds)
          emit("never")
        }
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, slow)),
        retryConfig = instantRetry(2),
        timeout = 50.milliseconds,
      )

    // Act
    val error = assertFailsWith<NodeTimeoutException> { runWorkflow(workflow) }

    // Assert: the workflow's own timeout ends the attempt, and its own policy runs the graph again.
    assertEquals("wf", error.nodeName)
    assertEquals(2, runs)
  }

  @Test
  fun aNonPositiveTimeoutIsRejectedAtConstruction() {
    assertFailsWith<IllegalArgumentException> {
      Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))), timeout = Duration.ZERO)
    }
    assertFailsWith<IllegalArgumentException> {
      Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))), timeout = (-1).seconds)
    }
  }
}
