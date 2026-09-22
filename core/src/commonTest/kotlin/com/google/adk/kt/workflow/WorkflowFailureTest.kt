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
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testInvocationContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
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
  override val config: NodeConfig = NodeConfig(retryConfig = retry)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { throw error() }
}

/** Raises until the given attempt, then succeeds. Attempt count comes from the context. */
private class FailsUntilAttempt(
  override val name: String,
  private val succeedFrom: Int,
  retry: RetryConfig? = null,
) : Node {
  override val config: NodeConfig = NodeConfig(retryConfig = retry)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (context.attemptCount < succeedFrom) throw NodeExecutionException("RuntimeError", "boom")
    emit("B")
  }
}

/** Sleeps, so a timeout or a cancellation has something to interrupt. */
private class Sleeper(
  override val name: String,
  private val nap: Duration,
  timeout: Duration? = null,
) : Node {
  override val config: NodeConfig = NodeConfig(timeout = timeout)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    delay(nap)
    emit("slept")
  }
}

/** Sleeps and records whether it was cancelled before it could finish. */
private class CancellationProbe(override val name: String, private val nap: Duration) : Node {
  var cancelled: Boolean = false
    private set

  var finished: Boolean = false
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    try {
      delay(nap)
      finished = true
      emit("slept")
    } catch (e: CancellationException) {
      cancelled = true
      throw e
    }
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
    val events = mutableListOf<Event>()
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: all three attempts ran and failed before the run gave up.
    assertContains(error.message!!, "boom")
    assertEquals(3, events.count { it.errorCode == "RuntimeError" })
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
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
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
        override val config: NodeConfig =
          NodeConfig(retryConfig = instantRetry(2), timeout = 50.milliseconds)

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          delay(30.seconds)
          emit("never")
        }
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, retried)))

    // Act
    val events = mutableListOf<Event>()
    assertFailsWith<NodeTimeoutException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
    }

    // Assert
    assertEquals(2, events.count { it.errorCode == "NodeTimeoutError" })
  }

  @Test
  fun anOuterTimeoutIsNotRelabeledOrRetriedAsAnInnerNodesTimeout() {
    // Arrange: the inner node hangs and carries its own (longer) timeout and a retry, inside an
    // outer workflow whose timeout is far shorter, so the outer fires first and cancels the inner.
    var innerRuns = 0
    val inner =
      object : Node {
        override val name: String = "inner"
        override val config: NodeConfig =
          NodeConfig(retryConfig = instantRetry(3), timeout = 10.seconds)

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          innerRuns++
          delay(30.seconds)
          emit("never")
        }
      }
    val workflow =
      Workflow(
        name = "outer",
        edges = listOf(Edge(Start, inner)),
        config = NodeConfig(timeout = 50.milliseconds),
      )

    // Act
    val error = assertFailsWith<NodeTimeoutException> { runWorkflow(workflow) }

    // Assert: the outer's timeout is reported, not relabeled as the inner's. The inner's
    // cancellation (from the outer) is rethrown unchanged, so it is neither renamed nor retried --
    // it ran exactly once. Without the currentCoroutineContext().isActive guard the inner would
    // relabel the outer cancellation as its own NodeTimeoutException and retry it.
    assertEquals("outer", error.nodeName)
    assertEquals(1, innerRuns)
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
    val slow = CancellationProbe("slow", nap = 30.seconds)
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, fork), Edge(fork, boom), Edge(fork, slow)))

    // Act
    val start = TimeSource.Monotonic.markNow()
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }
    val elapsed = start.elapsedNow()

    // Assert: the failure ends the run instead of waiting out the sleeping sibling, and the
    // sibling is really cancelled rather than merely abandoned.
    assertContains(error.message!!, "boom")
    assertTrue(slow.cancelled, "the sibling was not cancelled")
    assertFalse(slow.finished, "the sibling ran to completion")
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
      runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
    }

    // Assert: an empty list matches nothing, so the node runs once and is not retried.
    assertEquals(1, events.count { it.errorCode == "RuntimeError" })
  }

  @Test
  fun aNonPositiveTimeoutIsRejectedAtConstruction() {
    // A node's timeout is validated centrally in NodeConfig, so any node built with one is
    // rejected.
    assertFailsWith<IllegalArgumentException> { NodeConfig(timeout = Duration.ZERO) }
    assertFailsWith<IllegalArgumentException> { NodeConfig(timeout = (-1).seconds) }
    assertFailsWith<IllegalArgumentException> {
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, Emitter("a", "A"))),
        config = NodeConfig(timeout = Duration.ZERO),
      )
    }
  }

  @Test
  fun aFailureInsideANestedWorkflowFailsTheOuterRun() {
    // Arrange: the inner workflow reports its failure on the shared context rather than by
    // raising, so this guards the path the outer scheduler reads it back on.
    val boom = AlwaysRaises("boom", { NodeExecutionException("RuntimeError", "inner boom") })
    val inner = Workflow(name = "inner", edges = listOf(Edge(Start, boom)))
    val after = Emitter("after", "never")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, inner), Edge(inner, after)))

    // Act
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "inner boom")
  }

  @Test
  fun aWorkflowRetriesWhenANestedChildFails() {
    // Arrange: a nested workflow whose child always fails. The workflow reports that failure rather
    // than raising, so only a retry policy on the workflow itself can re-run its graph.
    var childRuns = 0
    val failing =
      object : Node {
        override val name: String = "failing"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          childRuns += 1
          throw NodeExecutionException("RuntimeError", "child boom")
        }
      }
    val inner =
      Workflow(
        name = "inner",
        edges = listOf(Edge(Start, failing)),
        config = NodeConfig(retryConfig = instantRetry(3)),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, inner)))

    // Act
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }

    // Assert: the workflow retried its whole graph, so the child ran once per attempt.
    assertContains(error.message!!, "child boom")
    assertEquals(3, childRuns)
  }

  @Test
  fun aRootWorkflowRetriesWhenAChildFails() {
    // Arrange: the retry policy lives on the ROOT workflow. A child failure sets the workflow's
    // failure rather than raising, so only running the root through the node runner lets its policy
    // re-run the graph. Mirrors Python test_retry_config_on_outer_workflow_retries_nested_failure.
    var childRuns = 0
    val failing =
      object : Node {
        override val name: String = "failing"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          childRuns += 1
          throw NodeExecutionException("RuntimeError", "boom")
        }
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, failing)),
        config = NodeConfig(retryConfig = instantRetry(3)),
      )

    // Act
    val error = assertFailsWith<NodeExecutionException> { runWorkflow(workflow) }

    // Assert: the root workflow re-ran its whole graph, so the child ran once per attempt.
    assertContains(error.message!!, "boom")
    assertEquals(3, childRuns)
  }

  @Test
  fun aRootWorkflowAppliesItsOwnTimeout() {
    // Arrange: the timeout lives on the ROOT workflow, and its only child hangs well past it.
    val slow = Sleeper("slow", nap = 30.seconds)
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, slow)),
        config = NodeConfig(timeout = 50.milliseconds),
      )

    // Act
    val error = assertFailsWith<NodeTimeoutException> { runWorkflow(workflow) }

    // Assert: the workflow's own timeout ended the run and the failure names the workflow.
    assertEquals("wf", error.nodeName)
  }
}
