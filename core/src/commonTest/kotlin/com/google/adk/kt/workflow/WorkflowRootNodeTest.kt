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
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Records its input and parent, writes a durable and a transient state key, and emits a marker. */
private class StandaloneRootNode(override val name: String) : Node {
  var receivedInput: Any? = null
    private set

  var observedParent: Context? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    receivedInput = nodeInput
    observedParent = context.parent
    context.updateState("durable", "saved")
    context.updateState("temp:scratch", "transient")
    emit("echoed")
  }
}

/** Reads [key] from [Context.state] and emits whatever value it observed. */
private class SessionStateReader(override val name: String, private val key: String) : Node {
  var observedValue: Any? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    observedValue = context.state[key]
    emit(observedValue)
  }
}

/** Emits one non-partial content event and no output, so several can run as parallel branches. */
private class ContentEmitter(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(author = "", content = modelMessage(name)))
  }
}

/** Emits a content event, then records whether the session already held it once emit returned. */
private class PersistenceProbeNode(override val name: String) : Node {
  var sawOwnEventPersisted: Boolean? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val content = modelMessage("probe")
    emit(Event(author = "", content = content))
    sawOwnEventPersisted = context.invocationContext.session.events.any { it.content == content }
  }
}

/** Emits one output and records anything thrown back at it from that emission. */
private class EmissionFailureRecorder(override val name: String) : Node {
  var thrownAtEmit: Throwable? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    try {
      emit("value")
    } catch (e: Throwable) {
      thrownAtEmit = e
      throw e
    }
  }
}

/**
 * Suspends in every append, as a remote session service does, so a node that does not wait for its
 * events to be persisted runs ahead of the session.
 */
private class SlowAppendSessionService(
  private val delegate: SessionService = InMemorySessionService()
) : SessionService by delegate {
  override suspend fun appendEvent(session: Session, event: Event): Event {
    delay(20.milliseconds)
    return delegate.appendEvent(session, event)
  }
}

/** Records events and run-error notifications seen by the runner's plugin pipeline. */
private class RecordingPlugin(private val onEventFailure: Throwable? = null) : Plugin {
  override val name: String = "recording"

  val seenEvents = mutableListOf<Event>()
  val seenErrors = mutableListOf<Throwable>()
  var afterRunCount = 0
    private set

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
    onEventFailure?.let { throw it }
    seenEvents.add(event)
    return event
  }

  override suspend fun afterRun(invocationContext: InvocationContext) {
    afterRunCount++
  }

  override suspend fun onRunError(invocationContext: InvocationContext, error: Throwable) {
    seenErrors.add(error)
  }
}

/** A graph or standalone node, rather than an agent tree, as an application's entry point. */
class WorkflowRootNodeTest {

  @Test
  fun aWorkflowRunsAsAnApplicationRootNode() {
    // Arrange
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))))
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Act
    val events = runBlocking {
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
    }

    // Assert: the graph ran under the runner, and its node is stamped on the event.
    assertEquals(listOf("wf@1/a@1"), events.mapNotNull { it.nodeInfo?.path })
    assertTrue(events.any { it.output == "A" }, "the node's output never reached the caller")
  }

  @Test
  fun aRunnerRootedOnAGraphExposesTheWorkflowAsItsNode() {
    // Arrange + Act
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))))
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Assert: an app rooted on a graph exposes the workflow as the runner's running node.
    assertSame(workflow, runner.node)
  }

  @Test
  fun aStandaloneNodeRunsAsAnApplicationRootNode() = runBlocking {
    // Arrange
    val node = StandaloneRootNode("solo")
    val runner = InMemoryRunner(app = App(appName = "node_app", rootNode = node))
    val greeting = userMessage("hello")

    // Act
    val events = runner.runAsync(userId = "u", sessionId = "s", newMessage = greeting).toList()
    val session = assertNotNull(runner.sessionService.getSession(SessionKey("node_app", "u", "s")))

    // Assert: the node ran at the root with no parent, and its temp: key was not persisted.
    assertSame(node, runner.node)
    assertEquals(greeting, node.receivedInput)
    assertNull(node.observedParent)
    val event = events.single()
    assertEquals("solo", event.author)
    assertEquals("solo@1", event.nodeInfo?.path)
    assertEquals(listOf("solo@1"), event.nodeInfo?.outputFor)
    assertEquals("echoed", event.output)
    assertEquals("saved", session.state["durable"])
    assertNull(session.state["temp:scratch"])
  }

  @Test
  fun aStandaloneRootNodeWithANameThatCorruptsPathsIsRejected() = runBlocking {
    // Arrange: '.' is legal in an agent name but separates branch segments in a node path.
    val runner =
      InMemoryRunner(app = App(appName = "node_app", rootNode = StandaloneRootNode("a.b")))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }

    // Assert
    assertContains(error.message!!, "must not contain")
  }

  @Test
  fun aFailingStandaloneRootNodePersistsItsErrorEventAndRethrows() = runBlocking {
    // Arrange
    val plugin = RecordingPlugin()
    val node = StateThenFailNode("boom", key = "partial_step", value = "done")
    val runner =
      InMemoryRunner(app = App(appName = "node_app", rootNode = node, plugins = listOf(plugin)))

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }
    val session = assertNotNull(runner.sessionService.getSession(SessionKey("node_app", "u", "s")))

    // Assert: onEvent saw the error event and its state delta before the exception surfaced.
    assertEquals("node failed", error.message)
    val errorEvent = session.events.last()
    assertEquals("boom", errorEvent.author)
    assertEquals("boom@1", errorEvent.nodeInfo?.path)
    assertEquals("IllegalStateException", errorEvent.errorCode)
    assertEquals("done", session.state["partial_step"])
    assertEquals(listOf("boom@1"), plugin.seenEvents.mapNotNull { it.nodeInfo?.path })
    assertEquals("node failed", plugin.seenErrors.single().message)
    assertEquals(0, plugin.afterRunCount)
  }

  @Test
  fun aFailingWorkflowRootPersistsCompletedAndErrorEventsInSessionBeforeRethrowing() = runBlocking {
    // Arrange: slow appends keep the collector busy while the workflow fails.
    val plugin = RecordingPlugin()
    val a = Emitter("a", "A")
    val boom = ThrowingNode("boom") { NodeExecutionException("RuntimeError", "workflow boom") }
    val after = Emitter("after", "never")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, a), Edge(a, boom), Edge(boom, after)))
    val runner =
      InMemoryRunner(
        app = App(appName = "graph_app", rootNode = workflow, plugins = listOf(plugin)),
        sessionService = SlowAppendSessionService(),
      )

    // Act
    val error =
      assertFailsWith<NodeExecutionException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }
    val session = assertNotNull(runner.sessionService.getSession(SessionKey("graph_app", "u", "s")))

    // Assert: both events were persisted once, the successor never ran, and afterRun was skipped.
    assertContains(error.message!!, "workflow boom")
    assertEquals(listOf("wf@1/a@1", "wf@1/boom@1"), session.events.mapNotNull { it.nodeInfo?.path })
    assertEquals("wf", session.events.last().author)
    assertEquals("RuntimeError", session.events.last().errorCode)
    assertSame(error, plugin.seenErrors.single())
    assertEquals(0, plugin.afterRunCount)
  }

  @Test
  fun aFailingOnEventCallbackCancelsTheRootNodeAndSurfacesTheCallbackError() = runBlocking {
    // Arrange: mirrors Python, where a failing on_event cancels the root task.
    val callbackError = IllegalStateException("onEvent boom")
    val plugin = RecordingPlugin(onEventFailure = callbackError)
    val node = EmissionFailureRecorder("emitter")
    val runner =
      InMemoryRunner(app = App(appName = "node_app", rootNode = node, plugins = listOf(plugin)))

    // Act
    val error =
      assertFailsWith<IllegalStateException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }
    val session = assertNotNull(runner.sessionService.getSession(SessionKey("node_app", "u", "s")))

    // Assert: messages are compared because stack-trace recovery may copy the exception.
    assertEquals(callbackError.message, error.message)
    assertIs<CancellationException>(node.thrownAtEmit)
    assertEquals(callbackError.message, plugin.seenErrors.single().message)
    assertEquals(0, plugin.afterRunCount)
    assertTrue(session.events.none { it.author == "emitter" }, "the node's event was persisted")
  }

  @Test
  fun aWorkflowSuccessorNodeObservesStateWrittenByItsPredecessorUnderARunner() = runBlocking {
    // Arrange: mirrors Python test_non_partial_event_blocks_until_processed, with slow appends.
    val writer = StateWriter("writer", "shared_key")
    val reader = SessionStateReader("reader", "shared_key")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, writer), Edge(writer, reader)))
    val runner =
      InMemoryRunner(
        app = App(appName = "graph_app", rootNode = workflow),
        sessionService = SlowAppendSessionService(),
      )

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert: the writer's event was persisted before the scheduler started the reader.
    assertEquals("v", reader.observedValue)
    assertEquals("v", events.single { it.nodeInfo?.path == "wf@1/reader@1" }.output)
  }

  @Test
  fun aNodeNestedInAWorkflowRootResumesOnlyAfterTheRunnerPersistsItsEvent() = runBlocking {
    // Arrange: the probe runs one level below the root, inside the workflow's scheduler.
    val probe = PersistenceProbeNode("probe")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, probe)))
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert: the nested node's emit returned only after the runner appended the event.
    assertEquals(true, probe.sawOwnEventPersisted)
    assertEquals("wf@1/probe@1", events.single().nodeInfo?.path)
  }

  @Test
  fun onUserMessageCallbackUpdatesNodeInputPassedToARootNode() = runBlocking {
    // Arrange: mirrors Python test_node_runner_passes_modified_user_message_as_node_input.
    val node = StandaloneRootNode("recorder")
    val rewritten = userMessage("modified text")
    val plugin =
      object : Plugin {
        override val name: String = "rewrite_user_message"

        override suspend fun onUserMessage(
          invocationContext: InvocationContext,
          userMessage: Content,
        ): Content = rewritten
      }
    val runner =
      InMemoryRunner(app = App(appName = "node_app", rootNode = node, plugins = listOf(plugin)))

    // Act
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("original")).toList()

    // Assert: the root node received the rewritten Content as its nodeInput.
    assertEquals(rewritten, node.receivedInput)
  }

  @Test
  fun beforeRunBreakHaltsARootNodeAndDispatchesAfterRun() = runBlocking {
    // Arrange: mirrors Python test_run_node_async_halts_on_early_exit_from_plugin.
    var nodeRan = false
    val node =
      object : Node {
        override val name: String = "should_not_run"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          nodeRan = true
          emit("unreachable")
        }
      }
    val haltedContent = modelMessage("halted by plugin")
    var afterRunCalled = false
    val plugin =
      object : Plugin {
        override val name: String = "halt_plugin"

        override suspend fun beforeRun(
          invocationContext: InvocationContext
        ): CallbackChoice<Unit, Content> = CallbackChoice.Break(haltedContent)

        override suspend fun afterRun(invocationContext: InvocationContext) {
          afterRunCalled = true
        }
      }
    val runner =
      InMemoryRunner(app = App(appName = "node_app", rootNode = node, plugins = listOf(plugin)))

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()

    // Assert: the node never ran, the early-exit event was emitted, and afterRun still executed.
    assertFalse(nodeRan)
    assertTrue(afterRunCalled)
    assertEquals(haltedContent, events.single().content)
  }

  @Test
  fun onEventModificationIsStreamedAndPersistedAndAfterRunFiresOnRootNodeSuccess() = runBlocking {
    // Arrange: mirrors Python test_after_run_callback_dispatched_on_workflow_root.
    var afterRunCount = 0
    val plugin =
      object : Plugin {
        override val name: String = "enrich_plugin"

        override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event =
          event.copy(customMetadata = mapOf("enriched" to "true"))

        override suspend fun afterRun(invocationContext: InvocationContext) {
          afterRunCount++
        }
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, Emitter("a", "A"))))
    val runner =
      InMemoryRunner(
        app = App(appName = "graph_app", rootNode = workflow, plugins = listOf(plugin))
      )

    // Act
    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
    val session = assertNotNull(runner.sessionService.getSession(SessionKey("graph_app", "u", "s")))

    // Assert: the streamed and the persisted event both carry the onEvent change.
    assertEquals(1, afterRunCount)
    assertEquals(mapOf("enriched" to "true"), events.single().customMetadata)
    assertEquals(mapOf("enriched" to "true"), session.events.last().customMetadata)
  }

  @Test
  fun partialEventsFlowThroughWithoutBlockingAndOnlyFinalEventIsPersisted() = runBlocking {
    // Arrange: mirrors Python test_partial_event_does_not_block.
    var advancedPastPartial = false
    var observedAdvancedWhenPartialCollected = false
    val node =
      object : Node {
        override val name: String = "streamer"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          emit(Event(author = "", content = modelMessage("part"), partial = true))
          advancedPastPartial = true
          emit(Event(author = "", content = modelMessage("final"), partial = false))
        }
      }
    val runner = InMemoryRunner(app = App(appName = "stream_app", rootNode = node))

    // Act
    val collected = mutableListOf<Event>()
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).collect { event
      ->
      if (event.partial) observedAdvancedWhenPartialCollected = advancedPastPartial
      collected.add(event)
    }
    val session =
      assertNotNull(runner.sessionService.getSession(SessionKey("stream_app", "u", "s")))

    // Assert: the partial emit did not wait for the collector, and only the final event persisted.
    assertTrue(observedAdvancedWhenPartialCollected)
    assertEquals(listOf(true, false), collected.map { it.partial })
    assertEquals(
      listOf("final"),
      session.events
        .filter { it.author == "streamer" }
        .mapNotNull { it.content?.parts?.firstOrNull()?.text },
    )
  }

  @Test
  fun earlyCollectorStopViaTakeCancelsParkedSiblingsWithoutHanging() = runBlocking {
    // Arrange: both branches park on the ack, so stopping early must cancel the other one.
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ContentEmitter("a")), Edge(Start, ContentEmitter("b"))),
      )
    val runner = InMemoryRunner(app = App(appName = "graph_app", rootNode = workflow))

    // Act
    val taken =
      withTimeout(10.seconds) {
        runner
          .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go"))
          .take(1)
          .toList()
      }

    // Assert
    assertEquals(1, taken.size)
  }
}
