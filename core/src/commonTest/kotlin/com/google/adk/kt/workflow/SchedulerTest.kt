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
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** A fan-in node that waits for every predecessor and emits their aggregated input. */
private class JoinAll(override val name: String) : Node {
  override val requiresAllPredecessors: Boolean = true

  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** Counts how many node activations overlap, so a concurrency limit is observable. */
private class OverlapCounter {
  var peak: Int = 0
    private set

  private var active: Int = 0

  fun enter() {
    active++
    if (active > peak) peak = active
  }

  fun exit() {
    active--
  }
}

/** Suspends while running, so overlapping activations are visible to [counter]. */
private class Overlapping(override val name: String, private val counter: OverlapCounter) : Node {
  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
    counter.enter()
    delay(10)
    counter.exit()
    emit(null)
  }
}

class SchedulerTest {

  /** Drives [workflow]'s scheduler on a root context branched at [branch]. */
  private fun runScheduler(workflow: Workflow, branch: String?): Pair<NodeContext, List<Event>> =
    runBlocking {
      val events = mutableListOf<Event>()
      val invocationContext = testInvocationContext(agent = workflow, branch = branch)
      val root =
        NodeContext(
          invocationContext = invocationContext,
          node = workflow,
          eventSink = { events.add(it) },
        )
      root.eventAuthor = workflow.name
      Scheduler(workflow, workflow.graph!!, root).run(nodeInput = null)
      root to events.toList()
    }

  /** The events [workflow]'s scheduler emitted on a root context branched at [branch]. */
  private fun schedule(workflow: Workflow, branch: String?): List<Event> =
    runScheduler(workflow, branch).second

  /** The branch stamped on the event a node emitted, found by that node's path. */
  private fun List<Event>.branchOf(path: String): String? =
    single { it.nodeInfo?.path == path }.branch

  /** The output carried by the event a node emitted, found by that node's path. */
  private fun List<Event>.outputOf(path: String): Any? = single { it.nodeInfo?.path == path }.output

  @Test
  fun aSingleSuccessorInheritsItsPredecessorBranch() {
    // Arrange: a straight chain never forks, so the branch never descends.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, b)))

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert
    assertEquals("root", events.branchOf("wf@1/a@1"))
    assertEquals("root", events.branchOf("wf@1/b@1"))
  }

  @Test
  fun aJoinReceivesEveryPredecessorOutputKeyedByNodeName() {
    // Arrange
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val join = JoinAll("join")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, a), Edge(Start, b), Edge(a, join), Edge(b, join)),
      )

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert: the barrier hands the join both outputs, keyed by the node that produced them.
    assertEquals(mapOf("a" to "A", "b" to "B"), events.outputOf("wf@1/join@1"))
  }

  @Test
  fun aJoinReMergesItsPredecessorsToTheirCommonBranchPrefix() {
    // Arrange: START forks to two sub-branches that both feed a wait-for-all join.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val join = JoinAll("join")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, a), Edge(Start, b), Edge(a, join), Edge(b, join)),
      )

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert: the predecessors fork onto distinct sub-branches, and the join re-merges to their
    // common prefix.
    assertEquals("root.a@1", events.branchOf("wf@1/a@1"))
    assertEquals("root.b@1", events.branchOf("wf@1/b@1"))
    assertEquals("root", events.branchOf("wf@1/join@1"))
  }

  @Test
  fun aJoinReMergesToAMultiSegmentPrefixRatherThanTheParentBranch() {
    // Arrange: START forks, then one fork forks again, so the join's predecessors share a prefix
    // deeper than the workflow's own branch.
    val fork = Emitter("fork", "F")
    val idle = Emitter("idle", null)
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val join = JoinAll("join")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, fork),
            Edge(Start, idle),
            Edge(fork, a),
            Edge(fork, b),
            Edge(a, join),
            Edge(b, join),
          ),
      )

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert
    assertEquals("root.fork@1.a@1", events.branchOf("wf@1/a@1"))
    assertEquals("root.fork@1.b@1", events.branchOf("wf@1/b@1"))
    assertEquals("root.fork@1", events.branchOf("wf@1/join@1"))
  }

  @Test
  fun aJoinOnAnUnbranchedRootReMergesToNoBranchRatherThanTheEmptyString() {
    // Arrange: with no root branch the forked predecessors share no prefix at all.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val join = JoinAll("join")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, a), Edge(Start, b), Edge(a, join), Edge(b, join)),
      )

    // Act
    val events = schedule(workflow, branch = null)

    // Assert
    assertEquals("a@1", events.branchOf("wf@1/a@1"))
    assertEquals("b@1", events.branchOf("wf@1/b@1"))
    assertNull(events.branchOf("wf@1/join@1"))
  }

  @Test
  fun theSoleTerminalNodeOutputBecomesTheWorkflowOutput() {
    // Arrange
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, b)))

    // Act
    val (root, _) = runScheduler(workflow, branch = "root")

    // Assert
    assertEquals("B", root.output)
  }

  @Test
  fun twoTerminalNodesProducingOutputIsRejected() {
    // Arrange: both successors of START are terminal and both produce an output.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(Start, b)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runScheduler(workflow, branch = "root") }

    // Assert
    assertContains(error.message!!, "multiple terminal nodes produced output")
  }

  @Test
  fun maxConcurrencyCapsHowManyNodesRunAtOnce() {
    // Arrange
    val counter = OverlapCounter()
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, Overlapping("a", counter)),
            Edge(Start, Overlapping("b", counter)),
            Edge(Start, Overlapping("c", counter)),
          ),
        maxConcurrency = 1,
      )

    // Act
    runScheduler(workflow, branch = "root")

    // Assert
    assertEquals(1, counter.peak)
  }

  @Test
  fun withoutAMaxConcurrencyForkedNodesRunTogether() {
    // Arrange: the same graph without the cap, so the cap above is shown to be what limits it.
    val counter = OverlapCounter()
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, Overlapping("a", counter)),
            Edge(Start, Overlapping("b", counter)),
            Edge(Start, Overlapping("c", counter)),
          ),
      )

    // Act
    runScheduler(workflow, branch = "root")

    // Assert
    assertEquals(3, counter.peak)
  }
}
