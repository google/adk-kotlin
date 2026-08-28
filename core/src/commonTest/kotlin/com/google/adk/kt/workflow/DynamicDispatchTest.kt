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
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** A leaf node that interrupts by surfacing a long-running tool id instead of an output. */
private class InterruptingChild(override val name: String, private val interruptId: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(author = "", longRunningToolIds = setOf(interruptId)))
  }
}

/**
 * A wait-for-output leaf that runs but produces nothing, so a raiseOnWait dispatcher waits on it.
 */
private class WaitingChild(override val name: String) : Node {
  override val waitForOutput: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {}
}

/** A leaf node that echoes the input it received, prefixed with a label. */
private class EchoChild(override val name: String, private val prefix: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit("$prefix:$nodeInput")
  }
}

/**
 * A node that dynamically dispatches [child] via [Context.runNode], then emits [ownOutput] if
 * given, else whatever the child returned, unless [emitResult] is false. With [useAsOutput] the
 * child's output is this node's, so emitting one of its own fails the node.
 */
private class Dispatcher(
  override val name: String,
  private val child: Node,
  private val childInput: Any? = null,
  private val childRunId: String? = null,
  private val useAsOutput: Boolean = false,
  private val useSubBranch: Boolean = false,
  private val overrideBranch: String? = null,
  private val raiseOnWait: Boolean = false,
  private val ownOutput: Any? = null,
  private val emitResult: Boolean = true,
  override val rerunOnResume: Boolean = true,
  override val config: NodeConfig = NodeConfig(),
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val result =
      context.runNode(
        child,
        nodeInput = childInput ?: nodeInput,
        runId = childRunId,
        useAsOutput = useAsOutput,
        useSubBranch = useSubBranch,
        overrideBranch = overrideBranch,
        raiseOnWait = raiseOnWait,
      )
    if (emitResult) emit(ownOutput ?: result)
  }
}

/** A node that attempts to delegate its output to two dynamic children in one activation. */
private class DoubleDelegatingDispatcher(
  override val name: String,
  private val first: Node,
  private val second: Node,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNode(first, useAsOutput = true)
    context.runNode(second, useAsOutput = true)
  }
}

/** A node that dispatches [children] one after another and emits their outputs as a list. */
private class SequentialDispatcher(override val name: String, private val children: List<Node>) :
  Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(children.map { context.runNode(it) })
  }
}

/** A node that dispatches [count] copies of [child] at once and emits their outputs as a list. */
private class ConcurrentDispatcher(
  override val name: String,
  private val child: Node,
  private val count: Int,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val outputs = coroutineScope {
      (1..count).map { async { context.runNode(child, nodeInput = it) } }.awaitAll()
    }
    emit(outputs)
  }
}

/** Dispatches [child], then fails on its first attempt only; its retry policy runs it again. */
private class FailsFirstAttemptDispatcher(
  override val name: String,
  private val child: Node,
  private val childInput: Any? = null,
) : Node {
  override val rerunOnResume: Boolean = true
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val result = context.runNode(child, nodeInput = childInput)
    if (context.attemptCount == 1) throw NodeExecutionException("RuntimeError", "first attempt")
    emit(result)
  }
}

/**
 * Dispatches [child] through [Context.runNodeUnchecked] with [childRunId] on a sub-branch, as a
 * tool running a node per function call does; the node itself does not rerun on resume.
 */
private class UncheckedDispatcher(
  override val name: String,
  private val child: Node,
  private val childRunId: String,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNodeUnchecked(child, nodeInput = "in", runId = childRunId, useSubBranch = true))
  }
}

/** Delegates its output to [child], then emits [ownEvent]. */
private class DelegatingEventEmitter(
  override val name: String,
  private val child: Node,
  private val ownEvent: Event,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNode(child, useAsOutput = true)
    emit(ownEvent)
  }
}

/** Delegates its output to [child], then assigns an output of its own. */
private class DelegatingAssigner(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNode(child, useAsOutput = true)
    context.output = "OWN"
  }
}

/** Emits [value] as its output, then fails. */
private class EmitThenFailChild(override val name: String, private val value: Any) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(value)
    throw NodeExecutionException("RuntimeError", "failed after its output")
  }
}

/** Emits [value] as its output, then cancels. */
private class EmitThenCancelChild(override val name: String, private val value: Any) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(value)
    throw CancellationException("cancelled after its output")
  }
}

/** Assigns [value] as its output, then fails before the output is emitted. */
private class AssignThenFailChild(override val name: String, private val value: Any) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.output = value
    throw NodeExecutionException("RuntimeError", "failed before emitting its output")
  }
}

/** Assigns an output of its own, then delegates its output to [child]. */
private class AssignThenDelegate(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.output = "OWN"
    context.runNode(child, useAsOutput = true)
  }
}

/** Dispatches [child] from its parent's context rather than its own, as an agent transfer does. */
private class ParentScopeDispatcher(override val name: String, private val child: Node) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(checkNotNull(context.parent).runNode(child))
  }
}

/** From its parent workflow's context, runs [first] and then [second], each with useAsOutput. */
private class WorkflowScopeDelegator(
  override val name: String,
  private val first: Node,
  private val second: Node,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val workflowContext = checkNotNull(context.parent)
    val firstOutput = workflowContext.runNode(first, useAsOutput = true)
    val secondOutput = workflowContext.runNode(second, useAsOutput = true)
    emit(listOf(firstOutput, secondOutput))
  }
}

/** Fails the first time it runs in a test and succeeds on every later run. */
private class FailsOnFirstCall(override val name: String) : Node {
  private var calls = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    calls += 1
    if (calls == 1) throw NodeExecutionException("RuntimeError", "first call")
    emit("ok")
  }
}

/** Delegates its output to [first]; if that fails, delegates it to [second] instead. */
private class RedelegatingDispatcher(
  override val name: String,
  private val first: Node,
  private val second: Node,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    try {
      context.runNode(first, useAsOutput = true)
    } catch (e: RuntimeException) {
      context.runNode(second, useAsOutput = true)
    }
  }
}

/** Emits the session state value under [key] as its output. */
private class StateValueReader(override val name: String, private val key: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.state[key])
  }
}

/** A node that catches its child's failure and emits [fallback] instead. */
private class FailureCatchingDispatcher(
  override val name: String,
  private val child: Node,
  private val fallback: Any?,
  private val useAsOutput: Boolean = false,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val result =
      try {
        context.runNode(child, useAsOutput = useAsOutput)
      } catch (e: RuntimeException) {
        fallback
      }
    emit(result)
  }
}

class DynamicDispatchTest {

  @Test
  fun runNode_callerDoesNotRerunOnResume_isRejected() {
    // Arrange: a dynamically scheduled child may be interrupted, so the workflow re-runs the parent
    // to collect its answer - which only works if the dispatching node reruns on resume.
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "C"), rerunOnResume = false)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "must set rerunOnResume = true")
  }

  @Test
  fun runNode_childNameThatCorruptsPaths_isRejected() {
    for (name in listOf("", "a/b", "a@b", "a.b")) {
      // Arrange: a name graph nodes may not have, because it would corrupt the child's paths.
      val workflow =
        Workflow(
          name = "wf",
          edges = listOf(Edge(Start, Dispatcher("dispatcher", Emitter(name, "C")))),
        )

      // Act
      val error =
        assertFailsWith<IllegalArgumentException>("name \"$name\"") { runWorkflow(workflow) }

      // Assert: the same check a graph node's name gets.
      assertContains(error.message!!, "A node name must", message = "name \"$name\"")
    }
  }

  @Test
  fun runNode_allDigitRunId_isRejected() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "C"), childRunId = "1")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(
      error.message!!,
      "runId \"1\" for node 'child' must contain a non-digit character",
    )
  }

  @Test
  fun runNode_runIdThatCorruptsPaths_isRejected() {
    for (runId in listOf("a/b", "a@b", "a.b")) {
      // Arrange: the run id becomes part of the child's node path and branch, like its name.
      val dispatcher = Dispatcher("dispatcher", Emitter("child", "C"), childRunId = runId)
      val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

      // Act
      val error =
        assertFailsWith<IllegalArgumentException>("runId \"$runId\"") { runWorkflow(workflow) }

      // Assert
      assertContains(error.message!!, "runId \"$runId\" for node 'child' must not contain")
    }
  }

  @Test
  fun runNode_secondUseAsOutputOnSameNode_isRejected() {
    // Arrange
    val dispatcher =
      DoubleDelegatingDispatcher("dispatcher", Emitter("first", "A"), Emitter("second", "B"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "already delegated its output")
  }

  @Test
  fun runNode_childProducesOutput_returnsItToCaller() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "CHILD_OUT"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals("CHILD_OUT", outputEvent.output)
    assertEquals(listOf("wf@1/dispatcher@1", "wf@1"), outputEvent.nodeInfo?.outputFor)
  }

  @Test
  fun runNode_customRunId_namesChildRunInPath() {
    // Arrange
    val dispatcher =
      Dispatcher("dispatcher", EchoChild("child", "echo"), childInput = "x", childRunId = "custom")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@custom" }
    assertEquals("echo:x", childEvent.output)
  }

  @Test
  fun runNode_useAsOutput_childOutputReplacesParentOutput() {
    // Arrange
    val dispatcher =
      Dispatcher(
        "dispatcher",
        Emitter("child", "CHILD_OUT"),
        useAsOutput = true,
        emitResult = false,
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the child's output event counts for the parent's and workflow's paths too, and the
    // parent emits no output event of its own.
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@1" }
    assertEquals("CHILD_OUT", childEvent.output)
    assertEquals(
      listOf("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1", "wf@1"),
      childEvent.nodeInfo?.outputFor,
    )
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null })
  }

  @Test
  fun runNode_useAsOutput_forwardsChildOutputDownstream() {
    // Arrange
    val dispatcher =
      Dispatcher(
        "dispatcher",
        Emitter("child", "CHILD_OUT"),
        useAsOutput = true,
        emitResult = false,
      )
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val afterEvent = events.single { it.nodeInfo?.path == "wf@1/after@1" }
    assertEquals("after:CHILD_OUT", afterEvent.output)
  }

  @Test
  fun runNode_useAsOutputThenParentReturnsChildOutput_failsParent() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "CHILD_OUT"), useAsOutput = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_useAsOutputThenParentEmitsOutput_failsParent() {
    // Arrange: the dispatcher delegates its output, then emits one of its own anyway.
    val dispatcher =
      Dispatcher(
        "dispatcher",
        Emitter("child", "CHILD_OUT"),
        useAsOutput = true,
        ownOutput = "DISPATCHER_OWN",
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_useAsOutput_deferredOutputCarriesDispatcherPath() {
    // Arrange: the child assigns its output instead of emitting it, so the runtime emits it on the
    // deferred path at the end of the child's run rather than stamping an event the child sent.
    val dispatcher =
      Dispatcher(
        "dispatcher",
        OutputAssigner("child", "CHILD_OUT"),
        useAsOutput = true,
        emitResult = false,
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the deferred event counts for the child's own path, the dispatcher, and the workflow.
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@1" }
    assertEquals("CHILD_OUT", childEvent.output)
    assertEquals(
      listOf("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1", "wf@1"),
      childEvent.nodeInfo?.outputFor,
    )
  }

  @Test
  fun runNode_useAsOutputThroughNestedWorkflow_reachesDownstreamNode() {
    // Arrange: a silent delegator runs an inner workflow with useAsOutput = true and emits nothing
    // itself; the inner workflow's terminal node output attributes up through both and feeds the
    // downstream node.
    val step1 = Emitter("step_1", "step_1_done")
    val step2 = EchoChild("step_2", "final")
    val innerWf =
      Workflow(name = "inner_wf", edges = listOf(Edge(Start, step1), Edge(step1, step2)))
    val nodeA = Dispatcher("node_a", innerWf, useAsOutput = true, emitResult = false)
    val after = EchoChild("after", "after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, nodeA), Edge(nodeA, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val step2Event = events.single { it.nodeInfo?.path == "wf@1/node_a@1/inner_wf@1/step_2@1" }
    assertEquals("final:step_1_done", step2Event.output)
    assertEquals(
      listOf("wf@1/node_a@1/inner_wf@1/step_2@1", "wf@1/node_a@1/inner_wf@1", "wf@1/node_a@1"),
      step2Event.nodeInfo?.outputFor,
    )
    val afterEvent = events.single { it.nodeInfo?.path == "wf@1/after@1" }
    assertEquals("after:final:step_1_done", afterEvent.output)
    assertEquals(listOf("wf@1/after@1", "wf@1"), afterEvent.nodeInfo?.outputFor)
  }

  @Test
  fun runNode_useAsOutputWorkflowWithOutputSchema_callerGetsValidatedOutput() {
    // Arrange: the terminal node emits Content, which the inner workflow's schema reads as a
    // string.
    val leaf = Emitter("leaf", Content(parts = listOf(Part(text = "hi"))))
    val innerWf =
      Workflow(
        name = "inner_wf",
        edges = listOf(Edge(Start, leaf)),
        outputSchema = Schema(type = Type.STRING),
      )
    val nodeA = Dispatcher("node_a", innerWf, useAsOutput = true, emitResult = false)
    val after = EchoChild("after", "after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, nodeA), Edge(nodeA, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val afterEvent = events.single { it.nodeInfo?.path == "wf@1/after@1" }
    assertEquals("after:hi", afterEvent.output)
  }

  @Test
  fun runNode_childFails_failsDispatcher() {
    // Arrange
    val child = ThrowingNode("child") { NodeExecutionException("RuntimeError", "child boom") }
    val dispatcher = Dispatcher("dispatcher", child)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))
    val events = mutableListOf<Event>()

    // Act: the child's failure is wrapped and carried up, failing the dispatcher and the run.
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the run fails with the child's own error, reported once, at the child's path; the
    // dispatcher carries the failure up without an error event of its own.
    assertContains(error.message!!, "child boom")
    val errorEvents = events.filter { it.errorCode != null }
    assertEquals(listOf("wf@1/dispatcher@1/child@1"), errorEvents.map { it.nodeInfo?.path })
  }

  @Test
  fun runNode_dispatcherCatchesChildFailure_carriesOn() {
    // Arrange
    val child = ThrowingNode("child") { NodeExecutionException("RuntimeError", "child boom") }
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, "RECOVERED")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the child still reports its failure, and the dispatcher completes with its fallback.
    assertEquals(
      1,
      events.count { it.nodeInfo?.path == "wf@1/dispatcher@1/child@1" && it.errorCode != null },
    )
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals("RECOVERED", outputEvent.output)
  }

  @Test
  fun runNode_childInterrupts_leavesDispatcherWaiting() {
    // Arrange: the child asks for input, so the dispatcher inherits the interrupt and its successor
    // must not run.
    val dispatcher = Dispatcher("dispatcher", InterruptingChild("child", "ask"))
    val after = Emitter("after", "AFTER")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the child's interrupt surfaces, and the waiting dispatcher never triggers its
    // successor.
    assertContains(events.flatMap { it.longRunningToolIds }, "ask")
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/after@1" })
  }

  @Test
  fun runNode_dispatcherCatchesExceptionsAndChildInterrupts_stillWaits() {
    // Arrange
    val dispatcher = FailureCatchingDispatcher("dispatcher", InterruptingChild("child", "ask"), "X")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertContains(events.flatMap { it.longRunningToolIds }, "ask")
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null })
  }

  @Test
  fun runNode_useSubBranch_childGetsBranchBelowDispatcher() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "C"), useSubBranch = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the child's events sit on a sub-branch derived from its name and run id.
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@1" }
    assertEquals("child@1", childEvent.branch)
  }

  @Test
  fun runNode_raiseOnWaitAndNoChildOutput_abortsDispatcher() {
    // Arrange: a wait-for-output child produces nothing, and raiseOnWait makes the dispatcher treat
    // that as itself waiting, so it aborts before emitting its own output.
    val dispatcher =
      Dispatcher("dispatcher", WaitingChild("child"), raiseOnWait = true, ownOutput = "AFTER_WAIT")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the dispatcher never reached its own emit.
    assertEquals(0, events.count { it.output == "AFTER_WAIT" })
  }

  @Test
  fun runNode_raiseOnWaitAndChildAssignsNull_abortsDispatcher() {
    // Arrange
    val dispatcher =
      Dispatcher(
        "dispatcher",
        OutputAssigner("child", null, waitForOutput = true),
        raiseOnWait = true,
        ownOutput = "AFTER_WAIT",
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(0, events.count { it.output == "AFTER_WAIT" })
  }

  @Test
  fun runNode_raiseOnWaitAndWorkflowChildWithoutOutput_abortsDispatcher() {
    // Arrange: the inner workflow's only node produces no output, so the workflow has none either.
    val silent = StubNode("silent")
    val innerWf = Workflow(name = "inner_wf", edges = listOf(Edge(Start, silent)))
    val dispatcher = Dispatcher("dispatcher", innerWf, raiseOnWait = true, ownOutput = "AFTER_WAIT")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(0, events.count { it.output == "AFTER_WAIT" })
  }

  @Test
  fun runNode_waitingChildWithoutRaiseOnWait_returnsNullAndCarriesOn() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", WaitingChild("child"), ownOutput = "AFTER_WAIT")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals("AFTER_WAIT", outputEvent.output)
  }

  @Test
  fun runNode_overrideBranch_replacesChildBranch() {
    // Arrange
    val plain = Dispatcher("plain", Emitter("child", "C"), overrideBranch = "tool.call@1")
    val sub =
      Dispatcher("sub", Emitter("child", "C"), overrideBranch = "tool.call@1", useSubBranch = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, plain), Edge(plain, sub)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the override replaces the dispatcher's branch, and a sub-branch derives from it.
    assertEquals(
      "tool.call@1",
      events.single { it.nodeInfo?.path == "wf@1/plain@1/child@1" }.branch,
    )
    assertEquals(
      "tool.call@1.child@1",
      events.single { it.nodeInfo?.path == "wf@1/sub@1/child@1" }.branch,
    )
  }

  @Test
  fun runNode_autoRunIds_countEachChildNameSeparately() {
    // Arrange
    val a = EchoChild("a", "a")
    val b = EchoChild("b", "b")
    val dispatcher = SequentialDispatcher("dispatcher", listOf(a, b, a))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val childPaths =
      events.mapNotNull { it.nodeInfo?.path }.filter { it.count { c -> c == '/' } == 2 }
    assertEquals(
      listOf("wf@1/dispatcher@1/a@1", "wf@1/dispatcher@1/b@1", "wf@1/dispatcher@1/a@2"),
      childPaths,
    )
  }

  @Test
  fun runNode_concurrentDispatches_getDistinctRunIds() {
    // Arrange: many children dispatched at once on a multi-threaded dispatcher all allocate from
    // the same node's run counter.
    val count = 64
    val dispatcher = ConcurrentDispatcher("dispatcher", EchoChild("child", "echo"), count)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events =
      runBlocking(Dispatchers.Default) { workflow.runAsync(testInvocationContext()).toList() }

    // Assert: every child ran under a distinct path, and each call got its own child's output back.
    val childPaths = events.mapNotNull { it.nodeInfo?.path }.filter { "/child@" in it }
    assertEquals((1..count).map { "wf@1/dispatcher@1/child@$it" }.toSet(), childPaths.toSet())
    assertEquals(count, childPaths.size)
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals((1..count).map { "echo:$it" }, outputEvent.output)
  }

  @Test
  fun runNode_useAsOutputOnTerminalNode_attributesOutputToEveryLevel() {
    // Arrange: node_a is terminal, so the delegated output also counts for the outer workflow.
    val step1 = Emitter("step_1", "step_1_done")
    val step2 = EchoChild("step_2", "final")
    val innerWf =
      Workflow(name = "inner_wf", edges = listOf(Edge(Start, step1), Edge(step1, step2)))
    val nodeA = Dispatcher("node_a", innerWf, useAsOutput = true, emitResult = false)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, nodeA)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: step_1 stays inside the inner graph; step_2 is the only output node_a and wf report.
    val outputEvents = events.filter { it.output != null }
    assertEquals(
      listOf("wf@1/node_a@1/inner_wf@1/step_1@1", "wf@1/node_a@1/inner_wf@1/step_2@1"),
      outputEvents.map { it.nodeInfo?.path },
    )
    assertEquals(listOf("wf@1/node_a@1/inner_wf@1/step_1@1"), outputEvents[0].nodeInfo?.outputFor)
    assertEquals(
      listOf(
        "wf@1/node_a@1/inner_wf@1/step_2@1",
        "wf@1/node_a@1/inner_wf@1",
        "wf@1/node_a@1",
        "wf@1",
      ),
      outputEvents[1].nodeInfo?.outputFor,
    )
  }

  @Test
  fun runNode_childFailsUnderDispatcherRetryPolicy_isNotRetried() {
    // Arrange: the dispatcher may retry, but a dynamic child's failure is carried up, not retried.
    val child = ThrowingNode("child") { NodeExecutionException("RuntimeError", "child boom") }
    val retry = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    val dispatcher = Dispatcher("dispatcher", child, config = NodeConfig(retryConfig = retry))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<NodeExecutionException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the child ran once, and the run failed with its error.
    assertContains(error.message!!, "child boom")
    val childPaths = events.mapNotNull { it.nodeInfo?.path }.filter { "/child@" in it }.toSet()
    assertEquals(setOf("wf@1/dispatcher@1/child@1"), childPaths)
  }

  @Test
  fun runNode_useAsOutputThenParentEmitsMessageAsOutput_failsParent() {
    // Arrange: a message-as-output event's content is the node's output.
    val content = Content(parts = listOf(Part(text = "own text")))
    val ownEvent =
      Event(author = "", content = content, nodeInfo = NodeInfo(messageAsOutput = true))
    val dispatcher = DelegatingEventEmitter("dispatcher", Emitter("child", "CHILD_OUT"), ownEvent)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_useAsOutputThenParentWritesState_keepsStateWrite() {
    // Arrange: after delegating its output, the caller may still write state.
    val stateWrite = EventActions(stateDelta = mutableMapOf("written" to "yes"))
    val ownEvent = Event(author = "", actions = stateWrite)
    val dispatcher = DelegatingEventEmitter("dispatcher", Emitter("child", "CHILD_OUT"), ownEvent)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val dispatcherEvents = events.filter { it.nodeInfo?.path == "wf@1/dispatcher@1" }
    val stateEvent = dispatcherEvents.single { it.actions.stateDelta.isNotEmpty() }
    assertEquals(mapOf<String, Any>("written" to "yes"), stateEvent.actions.stateDelta.toMap())
    assertEquals(0, dispatcherEvents.count { it.output != null })
  }

  @Test
  fun runNode_useAsOutputThenCallerAssignsOutput_failsCaller() {
    // Arrange: the caller delegates its output, then assigns one of its own anyway.
    val dispatcher = DelegatingAssigner("dispatcher", Emitter("child", "CHILD_OUT"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_callerAssignsOutputThenDelegates_failsCaller() {
    // Arrange: the caller already has an output when it asks a child for one.
    val dispatcher = AssignThenDelegate("dispatcher", Emitter("child", "CHILD_OUT"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert: the caller can't hand over an output it already has.
    assertContains(error.message!!, "already produced an output and cannot delegate")
  }

  @Test
  fun runNode_delegateFails_callerFallsBackToItsOwnOutput() {
    // Arrange: the delegate fails, and the caller catches that and produces its own output.
    val child = ThrowingNode("child") { NodeExecutionException("RuntimeError", "child boom") }
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, "FALLBACK", useAsOutput = true)
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the failed delegation was released, so the fallback is the caller's output.
    assertEquals(
      "after:FALLBACK",
      events.single { it.nodeInfo?.path == "wf@1/after@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_delegateNameRejected_callerFallsBackToItsOwnOutput() {
    // Arrange: the delegate never runs, since its name would corrupt the child's paths.
    val dispatcher =
      FailureCatchingDispatcher("dispatcher", Emitter("a/b", "C"), "FALLBACK", useAsOutput = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val output = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null }
    assertEquals("FALLBACK", output.output)
  }

  @Test
  fun runNode_delegateAssignsOutputThenFails_callerFallsBackToItsOwnOutput() {
    // Arrange: the delegate's assigned output was never emitted.
    val child = AssignThenFailChild("child", "CHILD_OUT")
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, "FALLBACK", useAsOutput = true)
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(
      "after:FALLBACK",
      events.single { it.nodeInfo?.path == "wf@1/after@1" && it.output != null }.output,
    )
    assertEquals(0, events.count { it.output == "CHILD_OUT" })
  }

  @Test
  fun runNode_delegateFailsAfterEmittingOutput_callerCannotFallBack() {
    // Arrange: the delegate already emitted its output for the caller when it fails.
    val child = EmitThenFailChild("child", "CHILD_OUT")
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, "FALLBACK", useAsOutput = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert: the caller's output is already the one the delegate emitted.
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_delegateFailsAfterEmittingOutput_successorGetsThatOutput() {
    // Arrange: the caller catches the delegate's failure and produces nothing itself.
    val child = EmitThenFailChild("child", "CHILD_OUT")
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, null, useAsOutput = true)
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the successor sees the output the event stream attributes to the caller, as a
    // resume would.
    assertEquals(
      "after:CHILD_OUT",
      events.single { it.nodeInfo?.path == "wf@1/after@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_sameNameAsGraphNode_sharesRunCounter() {
    // Arrange: coordinator runs as a graph node, then helper runs another coordinator from the
    // workflow's own context, the way an agent transferring back re-enters its parent.
    val coordinator = EchoChild("coordinator", "graph")
    val helper = ParentScopeDispatcher("helper", EchoChild("coordinator", "dynamic"))
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, coordinator), Edge(coordinator, helper)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the dynamic run continues the graph node's numbering instead of reusing its path.
    val coordinatorPaths =
      events.mapNotNull { it.nodeInfo?.path }.filter { "/coordinator@" in it }.distinct()
    assertEquals(listOf("wf@1/coordinator@1", "wf@1/coordinator@2"), coordinatorPaths)
  }

  @Test
  fun runNode_useAsOutputFromWorkflowContext_doesNotClaimDelegation() {
    // Arrange: two useAsOutput dispatches from the workflow's own context. A workflow keeps its own
    // output, so neither claims delegation; from any other node the second would be rejected.
    val helper = WorkflowScopeDelegator("helper", Emitter("first", "A"), Emitter("second", "B"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, helper)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: both dispatches ran and returned their outputs.
    val helperOutput = events.single { it.nodeInfo?.path == "wf@1/helper@1" && it.output != null }
    assertEquals(listOf("A", "B"), helperOutput.output)
  }

  @Test
  fun workflowRetry_innerNodeFails_rerunsNodesUnderSamePaths() {
    // Arrange: the inner workflow's only node fails once, and the inner workflow's own retry
    // policy runs the whole graph again.
    val flaky = FailsOnFirstCall("flaky")
    val inner =
      Workflow(
        name = "inner",
        edges = listOf(Edge(Start, flaky)),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, inner)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: each run of the workflow numbers its nodes from 1 again.
    val flakyPaths = events.mapNotNull { it.nodeInfo?.path }.filter { "/flaky@" in it }
    assertEquals(listOf("wf@1/inner@1/flaky@1", "wf@1/inner@1/flaky@1"), flakyPaths)
    assertEquals(
      "ok",
      events.single { it.nodeInfo?.path == "wf@1/inner@1/flaky@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_dispatcherRetried_childrenContinueRunNumbering() {
    // Arrange: the dispatcher runs its child on both attempts and fails only the first.
    val dispatcher =
      FailsFirstAttemptDispatcher("dispatcher", EchoChild("child", "echo"), childInput = "in")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the second attempt's child is a new run, not a replay of the first attempt's path.
    val childPaths = events.mapNotNull { it.nodeInfo?.path }.filter { "/child@" in it }
    assertEquals(listOf("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1/child@2"), childPaths)
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals("echo:in", outputEvent.output)
  }

  @Test
  fun runNodeUnchecked_callerFailsRunNodeChecks_stillRunsChild() {
    // Arrange: the dispatcher does not rerun on resume and passes an all-digit run id, both of
    // which the public runNode rejects.
    val dispatcher = UncheckedDispatcher("dispatcher", EchoChild("child", "echo"), childRunId = "7")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the child ran under the given id on its own sub-branch, and its output came back.
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@7" }
    assertEquals("child@7", childEvent.branch)
    val outputEvent = events.single {
      it.nodeInfo?.path == "wf@1/dispatcher@1" && it.output != null
    }
    assertEquals("echo:in", outputEvent.output)
  }

  @Test
  fun runNodeUnchecked_runIdThatCorruptsPaths_isRejected() {
    // Arrange: the unchecked path skips caller policy, not path safety.
    val dispatcher =
      UncheckedDispatcher("dispatcher", EchoChild("child", "echo"), childRunId = "a/b")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "runId \"a/b\" for node 'child' must not contain")
  }

  @Test
  fun runNode_useAsOutputWorkflowFailsAfterTerminalEmits_callerCannotFallBack() {
    // Arrange: the inner workflow's terminal node already emitted its output for the caller before
    // failing.
    val innerWf =
      Workflow(
        name = "inner_wf",
        edges = listOf(Edge(Start, EmitThenFailChild("term", "CHILD_OUT"))),
      )
    val dispatcher =
      FailureCatchingDispatcher("dispatcher", innerWf, "FALLBACK", useAsOutput = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_useAsOutputWorkflowFailsAfterTerminalEmits_successorGetsThatOutput() {
    // Arrange: the caller catches the inner workflow's failure and produces nothing itself.
    val innerWf =
      Workflow(
        name = "inner_wf",
        edges = listOf(Edge(Start, EmitThenFailChild("term", "CHILD_OUT"))),
      )
    val dispatcher =
      FailureCatchingDispatcher("dispatcher", innerWf, fallback = null, useAsOutput = true)
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the successor sees the output the terminal node emitted for the caller.
    assertEquals(
      "after:CHILD_OUT",
      events.single { it.nodeInfo?.path == "wf@1/after@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_delegateCancelledAfterEmittingOutput_callerCannotFallBack() {
    // Arrange: the delegate cancels itself after emitting its output; the caller catches that
    // CancellationException (a RuntimeException) and tries to fall back.
    val child = EmitThenCancelChild("child", "CHILD_OUT")
    val dispatcher = FailureCatchingDispatcher("dispatcher", child, "FALLBACK", useAsOutput = true)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_useAsOutputChildProducesNoOutputThenParentEmits_failsParent() {
    // Arrange
    val dispatcher =
      Dispatcher("dispatcher", StubNode("child"), useAsOutput = true, ownOutput = "OWN")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val error = assertFailsWith<IllegalStateException> { runWorkflow(workflow) }

    // Assert
    assertContains(error.message!!, "delegated its output")
  }

  @Test
  fun runNode_notANodeActivation_isRejected() {
    // Arrange
    val context = Context(testInvocationContext())

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        runBlocking { context.runNode(Emitter("child", "C")) }
      }

    // Assert
    assertContains(error.message!!, "node activation")
  }

  @Test
  fun runNode_emptyRunId_autoGeneratesRunId() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", Emitter("child", "C"), childRunId = "")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val childEvent = events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/child@1" }
    assertEquals("C", childEvent.output)
  }

  @Test
  fun runNode_delegateFailsBeforeEmitting_callerCanDelegateAgain() {
    // Arrange
    val first = ThrowingNode("first") { NodeExecutionException("RuntimeError", "first failed") }
    val dispatcher = RedelegatingDispatcher("dispatcher", first, Emitter("second", "SECOND"))
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals("after:SECOND", events.single { it.nodeInfo?.path == "wf@1/after@1" }.output)
  }

  @Test
  fun runner_useAsOutputChild_storesItsOutputForTheCallerAndSuccessor() {
    // Arrange
    val dispatcher =
      Dispatcher(
        "dispatcher",
        EchoChild("child", "echo"),
        childInput = "in",
        useAsOutput = true,
        emitResult = false,
      )
    val after = EchoChild("after", "after")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, after)))

    // Act
    val session = runWorkflowAsApp(workflow)

    // Assert: the dispatcher stores no output event of its own; the child's counts for it.
    val outputEvents = session.events.filter { it.output != null }
    assertEquals(
      listOf("wf@1/dispatcher@1/child@1", "wf@1/after@1"),
      outputEvents.map { it.nodeInfo?.path },
    )
    assertEquals(
      listOf("wf@1/dispatcher@1/child@1", "wf@1/dispatcher@1"),
      outputEvents[0].nodeInfo?.outputFor,
    )
    assertEquals("after:echo:in", outputEvents[1].output)
  }

  @Test
  fun runner_childUpdatesState_stateIsStoredAndReachesSuccessor() {
    // Arrange
    val dispatcher = Dispatcher("dispatcher", StateWriter("writer", key = "color"))
    val reader = StateValueReader("reader", key = "color")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, reader)))

    // Act
    val session = runWorkflowAsApp(workflow)

    // Assert
    assertEquals("v", session.state["color"])
    val writerEvent = session.events.single { it.nodeInfo?.path == "wf@1/dispatcher@1/writer@1" }
    assertEquals("v", writerEvent.actions.stateDelta["color"])
    val readerEvent = session.events.single { it.nodeInfo?.path == "wf@1/reader@1" }
    assertEquals("v", readerEvent.output)
  }

  @Test
  fun runner_childIsWorkflow_innerEventsAreStoredUnderTheDispatcher() {
    // Arrange
    val step1 = Emitter("step_1", "step_1_done")
    val step2 = EchoChild("step_2", "final")
    val innerWf =
      Workflow(name = "inner_wf", edges = listOf(Edge(Start, step1), Edge(step1, step2)))
    val nodeA = Dispatcher("node_a", innerWf, useAsOutput = true, emitResult = false)
    val after = EchoChild("after", "after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, nodeA), Edge(nodeA, after)))

    // Act
    val session = runWorkflowAsApp(workflow)

    // Assert
    val outputEvents = session.events.filter { it.output != null }
    assertEquals(
      listOf(
        "wf@1/node_a@1/inner_wf@1/step_1@1",
        "wf@1/node_a@1/inner_wf@1/step_2@1",
        "wf@1/after@1",
      ),
      outputEvents.map { it.nodeInfo?.path },
    )
    assertEquals("after:final:step_1_done", outputEvents.last().output)
  }
}
