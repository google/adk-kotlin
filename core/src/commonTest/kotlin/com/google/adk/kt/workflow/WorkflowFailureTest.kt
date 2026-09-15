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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Raises on every attempt. */
private class AlwaysRaises(override val name: String, private val error: () -> Throwable) : Node {
  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow { throw error() }
}

/** Sleeps, so a cancellation has something to interrupt. */
private class Sleeper(override val name: String, private val nap: kotlin.time.Duration) : Node {
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
}
