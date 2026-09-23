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
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** Emits a single content event flagged as the node's output, optionally carrying [output] too. */
private class MessageNode(
  override val name: String,
  private val message: Content,
  private val output: Any? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
        author = "",
        content = message,
        output = output,
        nodeInfo = NodeInfo(messageAsOutput = true),
      )
    )
  }
}

/** Emits one native event carrying a transfer-to-agent directive. */
private class TransferNode(override val name: String, private val target: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(author = "", actions = EventActions(transferToAgent = target)))
  }
}

/** Sets an output, a route and a state change without emitting them, then throws. */
private class UnflushedResultsThenFailNode(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.output = "unflushed"
    context.routes = listOf(Route.Tag("unflushed"))
    context.updateState("k", "v")
    throw IllegalStateException("node failed")
  }
}

/** Emits a native event selecting [route], so inline route recording can be observed. */
private class RoutingNode(override val name: String, private val route: Route) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(author = "", actions = EventActions(route = listOf(route))))
  }
}

/** Selects [route] without emitting, so the deferred-route path can be observed. */
private class DeferredRouteNode(override val name: String, private val route: Route) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.routes = listOf(route)
  }
}

/** Sets [value] as the output without emitting, so the deferred-output path can be observed. */
private class DeferredOutputNode(override val name: String, private val value: Any?) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.output = value
  }
}

class NodeRunnerTest {

  /** Runs [node] under a root parent whose branch is [parentBranch], collecting emitted events. */
  private fun runNode(
    node: Node,
    parentBranch: String?,
    useSubBranch: Boolean = false,
    overrideBranch: String? = null,
  ): Pair<Context, List<Event>> = runBlocking {
    val events = mutableListOf<Event>()
    val invocationContext = testInvocationContext(branch = parentBranch)
    val root =
      Context(
        invocationContext = invocationContext,
        node = StubNode("root"),
        eventSink = { events.add(it) },
        nodePath = "",
      )
    val context =
      NodeRunner(
          node = node,
          parent = root,
          runId = "1",
          useSubBranch = useSubBranch,
          overrideBranch = overrideBranch,
        )
        .run(nodeInput = null)
    context to events.toList()
  }

  @Test
  fun aFannedOutNodeRunsOnASubBranchDerivedFromItsParentBranch() {
    // Act
    val (context, events) =
      runNode(Emitter("greet", "hi"), parentBranch = "root", useSubBranch = true)

    // Assert: the branch descends from the parent's, named for the node and its run id.
    assertEquals("root.greet@1", context.invocationContext.branch)
    assertEquals("root.greet@1", events.single().branch)
  }

  @Test
  fun anOverrideBranchReplacesTheInheritedBranch() {
    // Act: a join re-merge or single-successor inheritance arrives as an override branch.
    val (context, events) =
      runNode(Emitter("greet", "hi"), parentBranch = "root", overrideBranch = "merged")

    // Assert
    assertEquals("merged", context.invocationContext.branch)
    assertEquals("merged", events.single().branch)
  }

  @Test
  fun withNoBranchDirectiveTheNodeInheritsItsParentBranch() {
    // Act
    val (context, events) = runNode(Emitter("greet", "hi"), parentBranch = "root")

    // Assert
    assertEquals("root", context.invocationContext.branch)
    assertEquals("root", events.single().branch)
  }

  @Test
  fun aMessageAsOutputEventCapturesItsContentAsTheNodeOutput() {
    // Arrange
    val message = Content(parts = listOf(Part(text = "hi")))
    val node = MessageNode("greet", message)

    // Act
    val (context, events) = runNode(node, parentBranch = "root")

    // Assert: the content becomes the output, and only the content event is emitted (no duplicate).
    assertEquals(message, context.output)
    assertEquals(1, events.size)
    assertEquals(message, events.single().content)
    assertNull(events.single().output)
  }

  @Test
  fun aMessageAsOutputEventThatAlsoCarriesOutputIsEmittedOnce() {
    // Arrange
    val message = Content(parts = listOf(Part(text = "hi")))
    val node = MessageNode("greet", message, output = "hi")

    // Act
    val (_, events) = runNode(node, parentBranch = "root")

    // Assert: the content event delivers the output; the deferred output event is suppressed.
    assertEquals(1, events.size)
    assertEquals(message, events.single().content)
    assertNull(events.single().output)
  }

  @Test
  fun everyEmittedEventIsStampedWithTheNodePathAndInvocationId() {
    // Act
    val (_, events) = runNode(Emitter("greet", "hi"), parentBranch = "root")

    // Assert: the path roots at the node under the empty-path parent, and the id is the run's.
    val event = events.single()
    assertEquals("greet", event.author)
    assertEquals("greet@1", event.nodeInfo?.path)
    assertEquals("test-invocation-id", event.invocationId)
    assertEquals("hi", event.output)
  }

  @Test
  fun anExplicitEventAuthorOnTheParentIsInheritedByChildNodes() {
    // Arrange: parent explicitly overrides eventAuthor.
    val events = mutableListOf<Event>()
    val root =
      Context(
        invocationContext = testInvocationContext(),
        node = StubNode("root"),
        eventSink = { events.add(it) },
        nodePath = "",
      )
    root.eventAuthor = "custom-agent"

    // Act
    val (_, childEvents) =
      runBlocking {
        val context =
          NodeRunner(node = Emitter("worker", "done"), parent = root).run(nodeInput = null)
        context to events.toList()
      }

    // Assert: child inherits the explicit eventAuthor.
    assertEquals("custom-agent", childEvents.single().author)
  }

  @Test
  fun aFailureReportsTheDeclaredCrossImplementationTypeName() {
    // Arrange: the declared type name is what another ADK implementation matches on.
    val node = ThrowingNode("boom") { NodeExecutionException("RuntimeError", "node failed") }

    // Act
    val (context, events) = runNode(node, parentBranch = "root")

    // Assert
    assertEquals("RuntimeError", events.single().errorCode)
    assertEquals("node failed", events.single().errorMessage)
    assertEquals("boom@1", context.requireNodeState().failure?.nodePath)
  }

  @Test
  fun aFailureWithoutADeclaredTypeNameReportsTheExceptionClassName() {
    // Arrange
    val node = ThrowingNode("boom") { IllegalStateException("node failed") }

    // Act
    val (_, events) = runNode(node, parentBranch = "root")

    // Assert
    assertEquals("IllegalStateException", events.single().errorCode)
  }

  @Test
  fun aNativeEventsTransferToAgentIsRecordedOnTheNodeActions() {
    // Act: the node emits its own event requesting a transfer.
    val (context, _) = runNode(TransferNode("router", "specialist"), parentBranch = "root")

    // Assert: the directive is aggregated onto the node's actions for the scheduler to read.
    assertEquals("specialist", context.actions.transferToAgent)
  }

  @Test
  fun aStateChangeMadeBeforeAFailureRidesTheErrorEvent() {
    // Arrange
    val node = StateThenFailNode("boom", key = "k", value = "v")

    // Act
    val (_, events) = runNode(node, parentBranch = "root")

    // Assert: the single error event carries the pending delta rather than dropping it.
    val errorEvent = events.single()
    assertEquals("IllegalStateException", errorEvent.errorCode)
    assertEquals("v", errorEvent.actions.stateDelta["k"])
  }

  @Test
  fun aCancellationExceptionPropagatesInsteadOfBecomingAFailureEvent() {
    // Arrange
    val node = ThrowingNode("boom") { CancellationException("cancelled") }

    // Act + Assert: cancellation is rethrown so structured concurrency is honored.
    assertFailsWith<CancellationException> {
      runBlocking {
        val root =
          Context(
            invocationContext = testInvocationContext(),
            node = StubNode("root"),
            eventSink = {},
            nodePath = "",
          )
        NodeRunner(node = node, parent = root).run(nodeInput = null)
      }
    }
  }

  @Test
  fun aJvmErrorPropagatesInsteadOfBecomingAFailureEvent() {
    // Arrange: run() catches Exception, not Throwable, so an Error stays fatal.
    val node = ThrowingNode("boom") { AssertionError("fatal") }

    // Act + Assert
    assertFailsWith<AssertionError> {
      runBlocking {
        val root =
          Context(
            invocationContext = testInvocationContext(),
            node = StubNode("root"),
            eventSink = {},
            nodePath = "",
          )
        NodeRunner(node = node, parent = root).run(nodeInput = null)
      }
    }
  }

  @Test
  fun anEmittedRouteIsRecordedAndRidesItsOwnEvent() {
    // Act
    val (context, events) = runNode(RoutingNode("router", Route.Tag("next")), parentBranch = "root")

    // Assert: the route is recorded on the context and carried on its event; no extra event
    // follows.
    assertEquals(listOf(Route.Tag("next")), context.routes)
    assertEquals(listOf(Route.Tag("next")), events.single().actions.route)
  }

  @Test
  fun aRouteSetWithoutAnEventIsEmittedAsADeferredEvent() {
    // Act
    val (_, events) = runNode(DeferredRouteNode("router", Route.Tag("next")), parentBranch = "root")

    // Assert: the runner emits a final event carrying the pending route.
    assertEquals(listOf(Route.Tag("next")), events.single().actions.route)
  }

  @Test
  fun anOutputSetWithoutAnEventIsEmittedAsADeferredEvent() {
    // Act
    val (_, events) = runNode(DeferredOutputNode("worker", "result"), parentBranch = "root")

    // Assert: the runner emits a final event carrying the deferred output.
    assertEquals("result", events.single().output)
  }

  @Test
  fun anAgentRunAsANodeStampsEventsWithTheAgentsAuthor() {
    // Arrange: the node's name differs from the author its agent emits, so the assertion proves the
    // author is propagated from the emitted event (BaseAgent.runNode) rather than supplied by the
    // node-name fallback in stamp.
    val agent =
      DummyAgent(name = "router") {
        emit(Event(author = "worker", content = Content(parts = listOf(Part(text = "hi")))))
      }

    // Act
    val (_, events) = runNode(agent, parentBranch = "root")

    // Assert: the event keeps the agent's own author "worker", not the node name "router", and is
    // stamped with the node path.
    val contentEvent = events.first { it.content != null }
    assertEquals("worker", contentEvent.author)
    assertEquals("router@1", contentEvent.nodeInfo?.path)
    assertTrue(events.all { it.nodeInfo?.path == "router@1" }, "every event is path-stamped")
  }

  @Test
  fun runRootEmitsTheErrorEventBeforeRethrowingTheFailure() = runBlocking {
    // Arrange
    val node = UnflushedResultsThenFailNode("worker")
    val collected = mutableListOf<Event>()

    // Act
    assertFailsWith<IllegalStateException> {
      NodeRunner.runRoot(node, testInvocationContext()).collect { collected.add(it) }
    }

    // Assert: the error event carries the state delta, but not the unflushed output or routes.
    val errorEvent = collected.single()
    assertEquals("worker", errorEvent.author)
    assertEquals("worker@1", errorEvent.nodeInfo?.path)
    assertNull(errorEvent.nodeInfo?.outputFor)
    assertNull(errorEvent.output)
    assertNull(errorEvent.actions.route)
    assertEquals("IllegalStateException", errorEvent.errorCode)
    assertEquals("v", errorEvent.actions.stateDelta["k"])
  }
}
