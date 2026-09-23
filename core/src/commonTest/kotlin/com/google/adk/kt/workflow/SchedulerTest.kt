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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
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

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
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
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    counter.enter()
    delay(10)
    counter.exit()
    emit(null)
  }
}

/** Records the input it was handed, so what a predecessor passed downstream is observable. */
private class InputRecorder(override val name: String) : Node {
  var received: Any? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
  }
}

/** Waits for an output and produces one only from its [readyOn]-th activation. */
private class LateProducer(override val name: String, private val readyOn: Int) : Node {
  override val waitForOutput: Boolean = true

  var activations: Int = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    activations++
    if (activations >= readyOn) emit("ready")
  }
}

/** Selects [route] and produces no output, so only the routing decision drives the graph. */
private class Router(override val name: String, private val route: Route) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.routes = listOf(route)
  }
}

/** Emits a value that changes each activation, so a loop's iterations are distinguishable. */
private class CountingEmitter(override val name: String) : Node {
  private var activations = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    activations++
    emit("$name$activations")
  }
}

/** A fan-in node that routes back to its predecessors once, recording every aggregate it sees. */
private class LoopingJoin(override val name: String) : Node {
  override val requiresAllPredecessors: Boolean = true

  val seen = mutableListOf<Any?>()

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    seen.add(nodeInput)
    if (seen.size == 1) context.routes = listOf(yes()) else emit(nodeInput)
  }
}

/** A minimal agent, so an agent in a graph can be exercised without a model. */
private class EchoAgent(name: String, private val value: String) : BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    emit(Event(author = name, output = value))
  }
}

class SchedulerTest {

  /** Drives [workflow]'s scheduler on a root context branched at [branch]. */
  private fun runScheduler(workflow: Workflow, branch: String?): Pair<Context, List<Event>> =
    runBlocking {
      val events = mutableListOf<Event>()
      val invocationContext = testInvocationContext(branch = branch)
      val root =
        Context(
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
    val unused = runScheduler(workflow, branch = "root")

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
    val unused = runScheduler(workflow, branch = "root")

    // Assert
    assertEquals(3, counter.peak)
  }

  @Test
  fun aNestedWorkflowHandsItsTerminalOutputToTheOuterSuccessor() {
    // Arrange: an inner workflow sits in the outer graph as an ordinary node.
    val inner = Workflow(name = "inner", edges = listOf(Edge(Start, Emitter("leaf", "INNER"))))
    val downstream = InputRecorder("downstream")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, inner), Edge(inner, downstream)))

    // Act
    val unused = runScheduler(workflow, branch = "root")

    // Assert: the inner graph's terminal output becomes the nested node's own.
    assertEquals("INNER", downstream.received)
  }

  @Test
  fun aWaitForOutputNodeParksUntilARetriggerProducesItsOutput() {
    // Arrange: two predecessors trigger a node that only answers on its second activation.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val late = LateProducer("late", readyOn = 2)
    val downstream = InputRecorder("downstream")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(Start, b),
            Edge(a, late),
            Edge(b, late),
            Edge(late, downstream),
          ),
      )

    // Act
    val unused = runScheduler(workflow, branch = "root")

    // Assert: the first activation parks instead of completing, so the successor waits for the
    // second one to produce an output.
    assertEquals(2, late.activations)
    assertEquals("ready", downstream.received)
  }

  @Test
  fun anEmittedRouteDrivesOnlyTheMatchingSuccessor() {
    // Arrange
    val taken = Emitter("taken", "T")
    val skipped = Emitter("skipped", "S")
    val router = Router("router", yes())
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, router),
            Edge(router, taken, listOf(yes())),
            Edge(router, skipped, listOf(no())),
          ),
      )

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert: the route reaches the graph through the scheduler, not only through edge matching.
    assertEquals(1, events.count { it.nodeInfo?.path == "wf@1/taken@1" })
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/skipped@1" })
  }

  @Test
  fun aStateChangeRidesOnExactlyOneEmittedEvent() {
    // Arrange: a successor follows the writer, so a delta repeated on later events would show up.
    val writer = StateWriter("writer", "k")
    val after = Emitter("after", "A")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, writer), Edge(writer, after)))

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert
    assertEquals(1, events.count { it.actions.stateDelta["k"] == "v" })
  }

  @Test
  fun aJoinUnderAConcurrencyCapFiresOnceWithEveryPredecessorOutput() {
    // Arrange: the cap serializes the predecessors, so the barrier sees them complete one at a
    // time rather than together.
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val c = Emitter("c", "C")
    val join = JoinAll("join")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(Start, b),
            Edge(Start, c),
            Edge(a, join),
            Edge(b, join),
            Edge(c, join),
          ),
        maxConcurrency = 1,
      )

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert
    assertEquals(1, events.count { it.nodeInfo?.path == "wf@1/join@1" })
    assertEquals(mapOf("a" to "A", "b" to "B", "c" to "C"), events.outputOf("wf@1/join@1"))
  }

  @Test
  fun anAgentRunsAsAGraphNodeAndFeedsItsSuccessor() {
    // Arrange: every agent is a node, so one drops into a graph without an adapter.
    val agent = EchoAgent("worker", "FROM_AGENT")
    val downstream = InputRecorder("downstream")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, agent), Edge(agent, downstream)))

    // Act
    val events = schedule(workflow, branch = "root")

    // Assert: the agent keeps authorship of its own event, and its output flows on.
    assertEquals("worker", events.single { it.nodeInfo?.path == "wf@1/worker@1" }.author)
    assertEquals("FROM_AGENT", downstream.received)
  }

  @Test
  fun aLoopRetriggeringAJoinPredecessorDoesNotFireTheBarrierOnItsStaleOutput() {
    // Arrange: a routed loop sends the join back to both predecessors, and the concurrency cap
    // leaves one of them queued while the other re-runs.
    val a = CountingEmitter("a")
    val b = CountingEmitter("b")
    val join = LoopingJoin("join")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, a),
            Edge(Start, b),
            Edge(a, join),
            Edge(b, join),
            Edge(join, a, listOf(yes())),
            Edge(join, b, listOf(yes())),
          ),
        maxConcurrency = 1,
      )

    // Act
    val unused = runScheduler(workflow, branch = "root")

    // Assert: one activation per iteration, each pairing outputs from the same iteration. Gating on
    // status alone adds a third activation carrying a's second output beside b's first.
    assertEquals<List<Any?>>(
      listOf(mapOf("a" to "a1", "b" to "b1"), mapOf("a" to "a2", "b" to "b2")),
      join.seen,
    )
  }
}
