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

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Dispatches [target] through [Context.runNode] and emits what it returned. */
private class TransferDriverNode(private val target: Node) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(target))
  }
}

/** Dispatches [target] twice at once under one run ID and emits both results. */
private class SameRunIdTransferDriverNode(private val target: Node) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val results = coroutineScope {
      List(2) { async { context.runNode(target, runId = "t") } }.awaitAll()
    }
    emit(results)
  }
}

/** Records the input it receives. */
private class TransferCaptureNode(override val name: String = "capture") : Node {
  var received: Any? = null

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
  }
}

/** An agent whose node run outputs [value], and counts its runs. */
private class OutputAgent(name: String, private val value: String) : BaseAgent(name = name) {
  var runs = 0
    private set

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs++
    emit(value)
  }
}

/** Asks for a transfer to [target] on every call and counts the calls. */
private class CountingTransferModel(private val target: String) : Model {
  var calls = 0
    private set

  override val name = "counting-transfer"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    calls++
    return flowOf(transferResponse(target))
  }
}

/** An agent that asks for input on its first run and outputs [value] once answered. */
private class AskingAgent(name: String, private val value: String) : BaseAgent(name = name) {
  override val rerunOnResume: Boolean = true

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (context.resumeInputs.isEmpty()) emit(RequestInput(interruptId = "ask_$name"))
    else emit(value)
  }
}

/** Answers every call with [text] and counts the calls. */
private class CountingReplyModel(private val text: String) : Model {
  var calls = 0
    private set

  override val name = "counting"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    calls++
    return flowOf(LlmResponse(content = Content.fromText(Role.MODEL, text)))
  }
}

/** A node that is not an agent, and pauses on a call whose event also asks for a transfer. */
private class PlainTransferNode : Node {
  override val name = "plain"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "approve", id = "t_1"))),
          ),
        longRunningToolIds = setOf("t_1"),
        actions = EventActions(transferToAgent = "other"),
      )
    )
  }
}

/** Dispatches [target] twice in a row under one run ID and emits both results. */
private class SequentialSameRunIdDriverNode(private val target: Node) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(List(2) { context.runNode(target, runId = "t") })
  }
}

private fun transferResponse(target: String) =
  modelFunctionCallResponse("transfer_to_agent", mapOf("agent_name" to target))

private fun transferModel(target: String): Model =
  DummyModel.createSequential("m", listOf(transferResponse(target)))

private fun replyModel(text: String): Model =
  DummyModel.createSequential(
    "m",
    listOf(LlmResponse(content = Content.fromText(Role.MODEL, text))),
  )

private fun chatAgent(
  name: String,
  model: Model,
  subAgents: List<BaseAgent> = emptyList(),
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
) =
  LlmAgent(
    name = name,
    model = model,
    subAgents = subAgents,
    disallowTransferToParent = disallowTransferToParent,
    disallowTransferToPeers = disallowTransferToPeers,
    mode = AgentMode.CHAT,
  )

/** A node activation of [node] under [parent], for resolving transfers without running anything. */
private fun activation(node: Node, parent: Context? = null): Context =
  Context(
    invocationContext = parent?.invocationContext ?: testInvocationContext(),
    node = node,
    eventSink = { _, _ -> },
    parent = parent,
  )

class AgentTransferLoopTest {

  @Test
  fun resolveTransferTarget_child_runsUnderTransferringAgent() {
    // Arrange
    val child = chatAgent("child", replyModel("unused"))
    val coordinator = chatAgent("coordinator", replyModel("unused"), subAgents = listOf(child))
    val workflowContext = activation(StubNode("wf"))
    val coordinatorContext = activation(coordinator, workflowContext)

    // Act
    val target = resolveTransferTarget("child", coordinator, coordinatorContext, workflowContext)

    // Assert
    assertSame(child, target.agent)
    assertSame(coordinatorContext, target.parent)
  }

  @Test
  fun resolveTransferTarget_sibling_runsUnderSameParent() {
    // Arrange
    val first = chatAgent("first", replyModel("unused"))
    val second = chatAgent("second", replyModel("unused"))
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val driverContext = activation(StubNode("driver"))
    val firstContext = activation(first, driverContext)

    // Act
    val target = resolveTransferTarget("second", first, firstContext, driverContext)

    // Assert
    assertSame(second, target.agent)
    assertSame(driverContext, target.parent)
  }

  @Test
  fun resolveTransferTarget_parent_runsWhereItsEarlierRunRan() {
    // Arrange
    val child = chatAgent("child", replyModel("unused"))
    val coordinator = chatAgent("coordinator", replyModel("unused"), subAgents = listOf(child))
    val workflowContext = activation(StubNode("wf"))
    val coordinatorContext = activation(coordinator, workflowContext)
    val childContext = activation(child, coordinatorContext)

    // Act
    val target = resolveTransferTarget("coordinator", child, childContext, coordinatorContext)

    // Assert
    assertSame(coordinator, target.agent)
    assertSame(workflowContext, target.parent)
  }

  @Test
  fun resolveTransferTarget_forbiddenByFlags_isRejected() {
    // Arrange
    val toParent = chatAgent("to_parent", replyModel("unused"), disallowTransferToParent = true)
    val toPeer = chatAgent("to_peer", replyModel("unused"), disallowTransferToPeers = true)
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(toParent, toPeer))
    val rootContext = activation(StubNode("wf"))

    // Act
    val parentError =
      assertFailsWith<IllegalArgumentException> {
        resolveTransferTarget("root", toParent, activation(toParent, rootContext), rootContext)
      }
    val peerError =
      assertFailsWith<IllegalArgumentException> {
        resolveTransferTarget("to_parent", toPeer, activation(toPeer, rootContext), rootContext)
      }

    // Assert
    assertContains(parentError.message.orEmpty(), "disallow_transfer_to_parent is set")
    assertContains(peerError.message.orEmpty(), "disallow_transfer_to_peers is set")
  }

  @Test
  fun resolveTransferTarget_selfUnknownOrUnrelated_isRejected() {
    // Arrange
    val leaf = chatAgent("leaf", replyModel("unused"))
    val cousin = chatAgent("cousin", replyModel("unused"))
    val left = chatAgent("left", replyModel("unused"), subAgents = listOf(leaf))
    val right = chatAgent("right", replyModel("unused"), subAgents = listOf(cousin))
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(left, right))
    val parentContext = activation(StubNode("wf"))
    val leafContext = activation(leaf, parentContext)

    // Act
    val self =
      assertFailsWith<IllegalArgumentException> {
        resolveTransferTarget("leaf", leaf, leafContext, parentContext)
      }
    val unknown =
      assertFailsWith<IllegalArgumentException> {
        resolveTransferTarget("nobody", leaf, leafContext, parentContext)
      }
    val unrelated =
      assertFailsWith<IllegalArgumentException> {
        resolveTransferTarget("cousin", leaf, leafContext, parentContext)
      }

    // Assert
    assertEquals("Agent 'leaf' cannot transfer to itself.", self.message)
    assertEquals(
      "Agent 'leaf' requested a transfer to an agent that is not in the agent tree.",
      unknown.message,
    )
    assertEquals(
      "Cannot transfer from 'leaf' to unrelated agent 'cousin'.\n" +
        "Available agents: root, left, leaf, right, cousin",
      unrelated.message,
    )
  }

  @Test
  fun runNode_chatNodeTransfer_runsSubAgentAsChildNode() = runBlocking {
    // Arrange
    val child = chatAgent("child", replyModel("from child"))
    val coordinator = chatAgent("coordinator", transferModel("child"), subAgents = listOf(child))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the child ran once, as a node below the coordinator, not inline in it.
    val childEvents = events.filter { it.author == "child" && it.content != null }
    assertEquals(listOf("from child"), childEvents.map { it.contentText() })
    assertEquals("wf@1/coordinator@1/child@1", childEvents.single().nodeInfo?.path)
  }

  @Test
  fun runNode_transferBackToParent_runsParentUnderFreshPath() = runBlocking {
    // Arrange: the coordinator hands off to the child, which hands straight back.
    val child = chatAgent("child", transferModel("coordinator"))
    val coordinatorModel =
      DummyModel.createSequential(
        "m",
        listOf(
          transferResponse("child"),
          LlmResponse(content = Content.fromText(Role.MODEL, "back again")),
        ),
      )
    val coordinator = chatAgent("coordinator", coordinatorModel, subAgents = listOf(child))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the coordinator answers from a second workflow run numbered after the first run.
    val answer = events.single { it.author == "coordinator" && it.contentText() == "back again" }
    assertEquals("wf@1/coordinator@2", answer.nodeInfo?.path)
    val childRun = events.indexOfFirst { it.author == "child" }
    assertTrue(
      childRun in 0 until events.indexOf(answer),
      "the coordinator answered before the child ran",
    )
  }

  @Test
  fun runAsync_graphNodeResumedWithAnswer_followsTransfer() = runBlocking {
    // Arrange: the coordinator waits on a long-running approval, then hands off once it arrives.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val child = chatAgent("child", replyModel("from child"))
    val coordinator =
      LlmAgent(
        name = "coordinator",
        model =
          DummyModel.createSequential(
            "m",
            listOf(modelFunctionCallResponse("approve"), transferResponse("child")),
          ),
        subAgents = listOf(child),
        tools = listOf(approve),
        mode = AgentMode.CHAT,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approvalId = paused.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the resumed coordinator ran the child as its own child node.
    val childEvent = events.single { it.author == "child" && it.content != null }
    assertEquals("from child", childEvent.contentText())
    assertEquals("wf@1/coordinator@1/child@1", childEvent.nodeInfo?.path)
  }

  @Test
  fun runAsync_transferTargetPausedThenAnswered_resumesTarget() = runBlocking {
    // Arrange: the coordinator hands off to the child, which then waits on a long-running approval.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val child =
      LlmAgent(
        name = "child",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              modelFunctionCallResponse("approve"),
              LlmResponse(content = Content.fromText(Role.MODEL, "approved")),
            ),
          ),
        tools = listOf(approve),
        mode = AgentMode.CHAT,
      )
    val coordinator = chatAgent("coordinator", transferModel("child"), subAgents = listOf(child))
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approvalId = paused.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the coordinator ended at its handoff, and the child picks up the answer and finishes
    // under its node path.
    assertTrue(paused.any { it.author == "coordinator" && it.actions.endOfAgent == true })
    val reply = events.single { it.author == "child" && it.contentText() == "approved" }
    assertEquals("wf@1/coordinator@1/child@1", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_childTargetPausedBesideOtherPausedBranch_resumesTarget() = runBlocking {
    // Arrange: two start edges put each agent on its own branch; both pause on long-running calls.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val child =
      LlmAgent(
        name = "child",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              modelFunctionCallResponse("approve"),
              LlmResponse(content = Content.fromText(Role.MODEL, "approved")),
            ),
          ),
        tools = listOf(approve),
        mode = AgentMode.CHAT,
      )
    val coordinator = chatAgent("coordinator", transferModel("child"), subAgents = listOf(child))
    val other =
      LlmAgent(
        name = "other",
        model = DummyModel.createSequential("m", listOf(modelFunctionCallResponse("hold"))),
        tools = listOf(DummyTool("hold", isLongRunning = true, onRun = { _, _ -> Unit })),
        mode = AgentMode.CHAT,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode =
              Workflow(name = "wf", edges = listOf(Edge(Start, coordinator), Edge(Start, other))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approval = paused.single { e -> e.functionCalls().any { it.name == "approve" } }
    val approvalId = approval.functionCalls().single().id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the child resumes on the branch it paused on.
    val reply = events.single { it.author == "child" && it.contentText() == "approved" }
    assertEquals("wf@1/coordinator@1/child@1", reply.nodeInfo?.path)
    assertEquals(approval.branch, reply.branch)
  }

  @Test
  fun runAsync_peerTransferTargetPausedThenAnswered_resumesTarget() = runBlocking {
    // Arrange: the first sibling hands off to the second, which waits on a long-running approval.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val first = chatAgent("first", transferModel("second"))
    val second =
      LlmAgent(
        name = "second",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              modelFunctionCallResponse("approve"),
              LlmResponse(content = Content.fromText(Role.MODEL, "approved by peer")),
            ),
          ),
        tools = listOf(approve),
        mode = AgentMode.CHAT,
      )
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, first))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approvalId = paused.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the peer target picks up the answer and finishes under its node path.
    val reply = events.single { it.author == "second" && it.contentText() == "approved by peer" }
    assertEquals(paused.first().invocationId, reply.invocationId)
    assertEquals("wf@1/second@1", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_peerTargetAnsweredAfterPausing_outputsOnce() = runBlocking {
    // Arrange: the first sibling hands off to `asker`, which asks for input and then outputs.
    val first = chatAgent("first", transferModel("asker"))
    val asker = AskingAgent("asker", "answered")
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, asker))
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, first))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer =
      userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_asker", mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the target's run on resume is not a replay, so its output is not re-emitted.
    assertEquals(1, events.count { it.output == "answered" }, "${events.map { it.author }}")
  }

  @Test
  fun runAsync_parentTransferTargetPausedThenAnswered_resumesTarget() = runBlocking {
    // Arrange: coordinator -> child -> coordinator again, which then waits on an approval.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val child = chatAgent("child", transferModel("coordinator"))
    val coordinator =
      LlmAgent(
        name = "coordinator",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              transferResponse("child"),
              modelFunctionCallResponse("approve"),
              LlmResponse(content = Content.fromText(Role.MODEL, "approved by parent")),
            ),
          ),
        subAgents = listOf(child),
        tools = listOf(approve),
        mode = AgentMode.CHAT,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approvalId = paused.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the second coordinator run picks up the answer and finishes under its node path.
    val reply = events.single {
      it.author == "coordinator" && it.contentText() == "approved by parent"
    }
    assertEquals(paused.first().invocationId, reply.invocationId)
    assertEquals("wf@1/coordinator@2", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_finishedTransferTargetBeforeAPausedNode_letsThatNodeResume() = runBlocking {
    // Arrange: `first` hands off to `second`, which finishes; the next node, `ask`, then pauses.
    val first = chatAgent("first", transferModel("second"))
    val second = OutputAgent("second", "X")
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val ask =
      LlmAgent(
        name = "ask",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              modelFunctionCallResponse("approve"),
              LlmResponse(content = Content.fromText(Role.MODEL, "approved")),
            ),
          ),
        tools = listOf(approve),
        mode = AgentMode.TASK,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, first), Edge(first, ask))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val approvalId = paused.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer = userFunctionResponse("approve", id = approvalId, response = mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: `first` gets its target's output back, so `ask` resumes, and `second` runs once.
    assertTrue(events.any { it.author == "ask" && it.contentText() == "approved" })
    assertEquals(1, second.runs)
  }

  @Test
  fun runAsync_peerTargetThatRepliedBesideAPausedBranch_runsOnceOnResume() = runBlocking {
    // Arrange: `first` hands off to `second`, which replies; `other`, on its own branch, asks.
    val secondModel = CountingReplyModel("from second")
    val first = chatAgent("first", transferModel("second"))
    val unusedRoot =
      chatAgent(
        "root",
        replyModel("unused"),
        subAgents = listOf(first, chatAgent("second", secondModel)),
      )

    // Act
    val events = answerOtherBesides(first)

    // Assert: `second` keeps its one reply, and `other` finishes.
    assertEquals(1, secondModel.calls)
    assertTrue(events.any { it.output == "answered" })
  }

  @Test
  fun runAsync_childTargetThatRepliedBesideAPausedBranch_runsOnceOnResume() = runBlocking {
    // Arrange: the coordinator hands off to its child, which replies; `other` asks.
    val childModel = CountingReplyModel("from child")
    val coordinator =
      chatAgent(
        "coordinator",
        transferModel("child"),
        subAgents = listOf(chatAgent("child", childModel)),
      )

    // Act
    val events = answerOtherBesides(coordinator)

    // Assert
    assertEquals(1, childModel.calls)
    assertTrue(events.any { it.output == "answered" })
  }

  @Test
  fun runAsync_chatNodeThatRepliedBesideAPausedBranch_runsOnceOnResume() = runBlocking {
    // Arrange: a chat node replies without an output, so it waits; `other` asks.
    val chatModel = CountingReplyModel("from chat")

    // Act
    val events = answerOtherBesides(chatAgent("chat", chatModel))

    // Assert: answering `other` is not a message to the chat node, so it does not reply again.
    assertEquals(1, chatModel.calls)
    assertTrue(events.any { it.output == "answered" })
  }

  @Test
  fun runAsync_peerTargetThatRepliedBesideATaskThatAsked_runsOnceOnTheReply() = runBlocking {
    // Arrange: `first` hands off to `second`, which replies; a task on its own branch asks in text.
    val secondModel = CountingReplyModel("from second")
    val first = chatAgent("first", transferModel("second"))
    val unusedRoot =
      chatAgent(
        "root",
        replyModel("unused"),
        subAgents = listOf(first, chatAgent("second", secondModel)),
      )
    val task =
      LlmAgent(
        name = "task",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              LlmResponse(content = Content.fromText(Role.MODEL, "which one?")),
              modelFunctionCallResponse("finish_task", mapOf("result" to "done")),
            ),
          ),
        mode = AgentMode.TASK,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, first), Edge(Start, task))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "b")).toList()

    // Assert: the reply is for the task, so `second` does not reply again.
    assertEquals(1, secondModel.calls)
    assertTrue(events.any { it.output == mapOf("result" to "done") })
  }

  /**
   * Runs [node] beside an `other` node that asks for input on its own branch, answers `other`, and
   * returns the events of that resumed turn.
   */
  private suspend fun answerOtherBesides(node: Node): List<Event> {
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode =
              Workflow(
                name = "wf",
                edges = listOf(Edge(Start, node), Edge(Start, AskingAgent("other", "answered"))),
              ),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer =
      userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_other", mapOf("ok" to true))
    return runner.runAsync("u", "s", newMessage = answer).toList()
  }

  @Test
  fun runNode_dispatchedAgentTransferToSibling_returnsSiblingRun() = runBlocking {
    // Arrange
    val first = chatAgent("first", transferModel("second"))
    val second = OutputAgent("second", "from second")
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val capture = TransferCaptureNode()
    val driver = TransferDriverNode(first)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, driver), Edge(driver, capture)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the sibling ran under the driver and its output is what runNode returned.
    assertEquals(1, second.runs)
    val secondEvent = events.first { it.output == "from second" }
    assertEquals("wf@1/driver@1/second@1", secondEvent.nodeInfo?.path)
    assertEquals("from second", capture.received)
  }

  @Test
  fun runNode_sameRunIdTwiceWithATransfer_runsTheTargetOnce() = runBlocking {
    // Arrange
    val first = chatAgent("first", transferModel("second"))
    val second = OutputAgent("second", "from second")
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val driver = SameRunIdTransferDriverNode(first)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, driver)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: both calls share one run of the handoff, so the target answers once for both.
    assertEquals(1, second.runs)
    val output = events.single { it.nodeInfo?.path == "wf@1/driver@1" && it.output != null }.output
    assertEquals(listOf("from second", "from second"), output)
  }

  @Test
  fun runNode_sameRunIdAgainAfterAHandoffToAChatTarget_runsNeitherAgain() = runBlocking {
    // Arrange: the chat target gives no output, yet the first agent's run is shared.
    val firstModel = CountingTransferModel("second")
    val secondModel = CountingReplyModel("from second")
    val first = chatAgent("first", firstModel)
    val second = chatAgent("second", secondModel)
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, SequentialSameRunIdDriverNode(first))))

    // Act
    val unused = workflow.runAsync(testInvocationContext()).toList()

    // Assert: as in Python, the second call reuses the first one's run of the handoff.
    assertEquals(listOf(1, 1), listOf(firstModel.calls, secondModel.calls))
  }

  @Test
  fun runAsync_topLevelHopNamedLikeTheRootWorkflow_getsItsOwnRunId() = runBlocking {
    // Arrange: the root workflow and the parent agent `child` hands off to share a name.
    val child = chatAgent("child", transferModel("coordinator"))
    val unusedCoordinator =
      chatAgent("coordinator", replyModel("coordinator here"), subAgents = listOf(child))
    val runner =
      InMemoryRunner(
        app = App(appName = "app", rootNode = Workflow("coordinator", listOf(Edge(Start, child))))
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert: the parent runs beside the root under a new run ID, not the root's own path.
    val reply = events.single {
      it.author == "coordinator" && it.contentText() == "coordinator here"
    }
    assertEquals("coordinator@2", reply.nodeInfo?.path)
  }

  @Test
  fun runNode_transferToUnrelatedAgent_failsDispatchingNode() = runBlocking {
    // Arrange
    val stray = chatAgent("stray", transferModel("elsewhere"))
    val unusedOther =
      chatAgent(
        "other",
        replyModel("unused"),
        subAgents = listOf(chatAgent("elsewhere", replyModel("x"))),
      )
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(stray, unusedOther))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, TransferDriverNode(stray))))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        workflow.runAsync(testInvocationContext()).toList(events)
      }

    // Assert: the driver node failed with the transfer error.
    assertContains(error.message.orEmpty(), "unrelated agent 'elsewhere'")
    val failure = events.single { it.nodeInfo?.path == "wf@1/driver@1" && it.errorMessage != null }
    assertContains(failure.errorMessage.orEmpty(), "unrelated agent 'elsewhere'")
  }

  @Test
  fun runAsync_taskWorkerTransferToChatRoot_rerunsTheRootAtTheTopLevel() = runBlocking {
    // Arrange: a task worker under a chat root hands off to the root.
    val worker =
      LlmAgent(
        name = "worker",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              transferResponse("root"),
              LlmResponse(content = Content.fromText(Role.MODEL, "again")),
            ),
          ),
        mode = AgentMode.TASK,
      )
    val root =
      LlmAgent(
        name = "root",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              modelFunctionCallResponse("worker", mapOf("request" to "do it")),
              LlmResponse(content = Content.fromText(Role.MODEL, "root done")),
            ),
          ),
        subAgents = listOf(worker),
        mode = AgentMode.CHAT,
      )
    val runner = InMemoryRunner(app = App(appName = "app", rootNode = root))

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert: as in ADK Python, the root runs again beside its first run, not nested in it.
    val paths = events.mapNotNull { it.nodeInfo?.path }
    assertTrue(paths.any { it.startsWith("root@2") }, "$paths")
    assertTrue(paths.none { it.startsWith("root@1/root@") }, "$paths")
  }

  @Test
  fun runAsync_taskTransferBackToItsParentOnAFollowUp_rerunsTheParentUnderANewRunId() =
    runBlocking {
      // Arrange: turn 1 hands off from root to helper; turn 2 starts at helper, whose task hands
      // back.
      val worker =
        LlmAgent(
          name = "worker",
          model =
            DummyModel.createSequential(
              "m",
              listOf(
                transferResponse("helper"),
                LlmResponse(content = Content.fromText(Role.MODEL, "again")),
              ),
            ),
          mode = AgentMode.TASK,
        )
      val helper =
        LlmAgent(
          name = "helper",
          model =
            DummyModel.createSequential(
              "m",
              listOf(
                LlmResponse(content = Content.fromText(Role.MODEL, "hello")),
                modelFunctionCallResponse("worker", mapOf("request" to "x")),
                LlmResponse(content = Content.fromText(Role.MODEL, "helper done")),
              ),
            ),
          subAgents = listOf(worker),
          mode = AgentMode.CHAT,
        )
      val root = chatAgent("root", transferModel("helper"), subAgents = listOf(helper))
      val runner = InMemoryRunner(app = App(appName = "app", rootNode = root))
      val unused =
        runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "hi")).toList()

      // Act
      val events =
        runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go on")).toList()

      // Assert: the helper's rerun takes the next run ID instead of repeating `root@1/helper@1`.
      val paths = events.mapNotNull { it.nodeInfo?.path }
      assertTrue(paths.any { it.startsWith("root@1/helper@1") }, "$paths")
      assertTrue(paths.any { it.startsWith("root@1/helper@2") }, "$paths")
    }

  @Test
  fun runNode_transferToTaskSubAgent_failsDispatchingNode() = runBlocking {
    // Arrange: the tool offers only `helper`, so a model that names the task agent is rejected.
    val worker = LlmAgent(name = "worker", model = replyModel("unused"), mode = AgentMode.TASK)
    val helper = chatAgent("helper", replyModel("unused"))
    val coordinator =
      chatAgent("coordinator", transferModel("worker"), subAgents = listOf(worker, helper))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, coordinator)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        workflow.runAsync(testInvocationContext()).toList(events)
      }

    // Assert
    assertContains(error.message.orEmpty(), "Cannot transfer to 'worker'")
    assertTrue(events.none { it.author == "worker" })
  }

  @Test
  fun runAsync_chatNodeInWorkflowTransfer_runsItsAfterAgentCallbacks() = runBlocking {
    // Arrange: a chat node inside a workflow hands off to its helper.
    var afterCallbacks = 0
    val helper = chatAgent("helper", replyModel("from helper"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.CHAT,
        afterAgentCallbacks =
          listOf(
            AfterAgentCallback {
              afterCallbacks += 1
              CallbackChoice.Continue(Unit)
            }
          ),
      )
    val runner =
      InMemoryRunner(
        app = App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, router))))
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert: the router's turn finishes at the transfer, so its after-agent callbacks run once.
    assertEquals(1, afterCallbacks)
    assertEquals(1, events.count { it.author == "helper" && it.contentText() == "from helper" })
  }

  @Test
  fun runAsync_resumableSingleTurnNodeTransfer_endsTheAgentThatHandsOff() = runBlocking {
    // Arrange: a single-turn router hands off to its helper in a resumable app.
    val helper = chatAgent("helper", replyModel("from helper"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, router))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert
    assertTrue(events.any { it.author == "router" && it.actions.endOfAgent == true })
  }

  @Test
  fun runAsync_transferToAParentThatNeverRan_runsItAtTheTopLevel() = runBlocking {
    // Arrange: `child` is the only node, and its parent agent is not part of the workflow.
    val child = chatAgent("child", transferModel("coordinator"))
    val unusedCoordinator =
      chatAgent("coordinator", replyModel("coordinator here"), subAgents = listOf(child))
    val runner =
      InMemoryRunner(
        app = App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, child))))
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert: as in ADK Python, the parent runs beside the root node, not inside it.
    val reply = events.single { it.author == "coordinator" }
    assertEquals("coordinator@1", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_topLevelHopTransfer_runsTheTargetOnce() = runBlocking {
    // Arrange: `child` hands off to its parent, which runs at the top level and hands back.
    val childModel =
      DummyModel.createSequential(
        "m",
        listOf(
          transferResponse("coordinator"),
          LlmResponse(content = Content.fromText(Role.MODEL, "child again")),
          LlmResponse(content = Content.fromText(Role.MODEL, "child again")),
        ),
      )
    val child = chatAgent("child", childModel)
    val unusedCoordinator =
      chatAgent("coordinator", transferModel("child"), subAgents = listOf(child))
    val runner =
      InMemoryRunner(
        app = App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, child))))
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()

    // Assert: the top-level hop stops at its transfer, so `child` runs once more, as a node.
    val replies = events.filter { it.author == "child" && it.contentText() == "child again" }
    assertEquals(listOf("coordinator@1/child@1"), replies.map { it.nodeInfo?.path })
  }

  @Test
  fun runNode_singleTurnNodeTransfer_runsTheTargetOnce() = runBlocking {
    // Arrange: a single-turn router keeps the transfer tool for its helper.
    val helperModel = CountingReplyModel("from helper")
    val helper = chatAgent("helper", helperModel)
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, router)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the helper ran once, as a node below the router, not also inline in it.
    assertEquals(1, helperModel.calls)
    val reply = events.single { it.author == "helper" && it.contentText() == "from helper" }
    assertEquals("wf@1/router@1/helper@1", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_singleTurnNodeTransfer_passesTheTargetsReplyToTheNextNode() = runBlocking {
    // Arrange: Prep -> a single-turn router that hands off to its chat helper -> Capture.
    val helper = chatAgent("helper", CountingReplyModel("from helper"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
        outputKey = "routed",
      )
    val prep = node<Any?, String>("prep") { _, _ -> "question" }
    var captured: Any? = null
    val capture = node<Any?, String>("capture") { _, input -> "done".also { captured = input } }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, prep), Edge(prep, router), Edge(router, capture)),
      )

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: as in ADK Python, the helper's reply is the router's output and fills its output key.
    assertEquals("from helper", captured)
    assertEquals(listOf("from helper"), events.mapNotNull { it.actions.stateDelta["routed"] })
  }

  @Test
  fun runAsync_singleTurnNodeTransferToASequentialAgent_passesItsStepsReply() = runBlocking {
    // Arrange: a single-turn router hands off to a sequential agent whose inner agent replies.
    val team =
      SequentialAgent("team", subAgents = listOf(LlmAgent("writer", replyModel("written"))))
    val router =
      LlmAgent(
        "router",
        transferModel("team"),
        subAgents = listOf(team),
        mode = AgentMode.SINGLE_TURN,
        outputKey = "routed",
      )
    val capture = TransferCaptureNode()
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, router), Edge(router, capture)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: as in ADK Python, the inner agent's reply is the router's output.
    assertEquals("written", capture.received)
    assertEquals(listOf("written"), events.mapNotNull { it.actions.stateDelta["routed"] })
  }

  @Test
  fun runAsync_singleTurnNodeTransferToASequentialAgentThatRepliesTwice_failsTheNode() =
    runBlocking {
      // Arrange: a single-turn router hands off to a sequential agent with two replying steps.
      val steps = listOf(LlmAgent("writer", replyModel("written")), LlmAgent("b", replyModel("b")))
      val team = SequentialAgent("team", subAgents = steps)
      val router =
        LlmAgent(
          "router",
          transferModel("team"),
          subAgents = listOf(team),
          mode = AgentMode.SINGLE_TURN,
        )
      val capture = TransferCaptureNode()
      val workflow =
        Workflow(name = "wf", edges = listOf(Edge(Start, router), Edge(router, capture)))

      // Act
      val failure =
        assertFailsWith<IllegalStateException> {
          workflow.runAsync(testInvocationContext()).toList()
        }

      // Assert: as in ADK Python, the second reply fails as a second output of the node.
      assertContains(failure.message.orEmpty(), "produced a second output")
      assertEquals(null, capture.received)
    }

  @Test
  fun runAsync_resumedSingleTurnNodeTransfer_readsTheReplyWithTheRoutersSchema() = runBlocking {
    // Arrange: a router without a schema hands off to a helper with one; a later node pauses.
    val helper =
      LlmAgent(
        "helper",
        replyModel("from helper"),
        mode = AgentMode.CHAT,
        outputSchema = Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(Type.STRING))),
      )
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val asker = AskingAgent("asker", "answered")
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow("wf", listOf(Edge(Start, router), Edge(router, asker))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer =
      userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_asker", mapOf("ok" to true))

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the replayed reply is plain text, as the router read it on the first run.
    assertEquals(emptyList(), events.mapNotNull { it.errorMessage })
    assertTrue(events.any { it.output == "answered" })
  }

  @Test
  fun runNode_singleTurnNodeTransferAsTheCallersOutput_emitsTheOutputOnce() = runBlocking {
    // Arrange: a function node hands its output to a single-turn router whose helper hands off.
    val helper2 = chatAgent("helper2", CountingReplyModel("from helper"))
    val helper = chatAgent("helper", transferModel("helper2"), subAgents = listOf(helper2))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
        outputKey = "",
      )
    val caller =
      node<Any?, Any?>("caller", rerunOnResume = true) { context, _ ->
        context.runNode(router, useAsOutput = true)
      }
    var captured: Any? = null
    val capture = node<Any?, String>("capture") { _, input -> "done".also { captured = input } }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller), Edge(caller, capture)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: one output event, the last hop's reply, for every level; no state written.
    assertEquals("from helper", captured)
    val r = "wf@1/caller@1/router@1"
    val outputs = events.filter {
      it.nodeInfo?.path?.startsWith("wf@1/caller@1") == true &&
        (it.output != null || it.nodeInfo.messageAsOutput == true)
    }
    assertEquals(
      listOf("$r/helper@1/helper2@1", "$r/helper@1", r, "wf@1/caller@1"),
      outputs.single().nodeInfo?.outputFor,
    )
    assertEquals(emptyList(), events.mapNotNull { it.actions.stateDelta[""] })
  }

  @Test
  fun runAsync_resumedPlainNodeTransfer_failsAsOnAFreshRun() = runBlocking {
    // Arrange: a node that is not an agent pauses on a call whose event also asks for a transfer.
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "app",
            rootNode = Workflow(name = "wf", edges = listOf(Edge(Start, PlainTransferNode()))),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )
    val unused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
    val answer = userFunctionResponse("approve", id = "t_1", response = mapOf("ok" to true))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runner.runAsync("u", "s", newMessage = answer).toList()
      }

    // Assert
    assertContains(error.message.orEmpty(), "Only agents can request an agent transfer.")
  }

  @Test
  fun runNode_transferToDottedAgentName_failsBeforeTheTargetRuns() = runBlocking {
    // Arrange: an agent name may contain '.', which would corrupt the hop's node path. The agent is
    // dispatched dynamically, so graph validation does not see its sub-agents.
    val target = chatAgent("billing.desk", replyModel("unused"))
    val coordinator =
      chatAgent("coordinator", transferModel("billing.desk"), subAgents = listOf(target))
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, TransferDriverNode(coordinator))))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        workflow.runAsync(testInvocationContext()).toList(events)
      }

    // Assert
    assertContains(error.message.orEmpty(), "'billing.desk'")
    assertTrue(events.none { it.author == "billing.desk" })
  }

  @Test
  fun runAsync_singleTurnNodeTransferWithAnEnumSchema_failsOnAReplyOutsideTheEnum() = runBlocking {
    // Arrange: a single-turn router whose output must be YES or NO hands off to its helper.
    val helper = chatAgent("helper", replyModel("MAYBE"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
        outputSchema = Schema(type = Type.STRING, enum = listOf("YES", "NO")),
        outputKey = "answer",
      )
    val capture = TransferCaptureNode()
    val context = testInvocationContext()
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, router), Edge(router, capture)))

    // Act
    val failure = assertFailsWith<IllegalArgumentException> { workflow.runAsync(context).toList() }

    // Assert: the helper's reply is checked as the router's own reply would be.
    assertContains(failure.message.orEmpty(), "output of node 'router'")
    assertEquals(null, capture.received)
    assertEquals(null, context.session.state["answer"])
  }

  @Test
  fun runAsync_singleTurnNodeTransferToATopLevelParent_passesItsReplyToTheNextNode() = runBlocking {
    // Arrange: a single-turn router hands off to its parent, which is not part of the workflow.
    val router = LlmAgent("router", transferModel("coordinator"), mode = AgentMode.SINGLE_TURN)
    val unusedCoordinator =
      chatAgent("coordinator", replyModel("coordinator here"), subAgents = listOf(router))
    val capture = TransferCaptureNode()
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, router), Edge(router, capture)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the parent runs at the top level, and its reply is the router's output.
    val reply = events.single { it.author == "coordinator" }
    assertEquals("coordinator@1", reply.nodeInfo?.path)
    assertEquals("coordinator here", capture.received)
  }

  @Test
  fun runAsync_resumedSingleTurnNodeTransferToATopLevelParent_readsTheReplyWithTheRoutersSchema() =
    runBlocking {
      // Arrange: a router with a schema hands off to a schemaless single-turn parent; asker pauses.
      val parentModel = CountingReplyModel("""{"a": "x"}""")
      val router =
        LlmAgent(
          "router",
          transferModel("coordinator"),
          mode = AgentMode.SINGLE_TURN,
          outputSchema = Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(Type.STRING))),
        )
      val unusedCoordinator =
        LlmAgent(
          "coordinator",
          parentModel,
          subAgents = listOf(router),
          mode = AgentMode.SINGLE_TURN,
        )
      val asker = AskingAgent("asker", "answered")
      val runner =
        InMemoryRunner(
          app =
            App(
              appName = "app",
              rootNode = Workflow("wf", listOf(Edge(Start, router), Edge(router, asker))),
              resumabilityConfig = ResumabilityConfig(isResumable = true),
            )
        )
      val unused =
        runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
      val answer =
        userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_asker", mapOf("ok" to true))

      // Act
      val events = runner.runAsync("u", "s", newMessage = answer).toList()

      // Assert: the replayed reply reads with the router's schema; the parent does not rerun.
      val replayed = events.single { it.author == "coordinator" && it.output != null }
      assertEquals(mapOf("a" to "x"), replayed.output)
      assertEquals(1, parentModel.calls)
      assertTrue(events.any { it.output == "answered" })
    }

  @Test
  fun runAsync_singleTurnNodeTransferToASingleTurnParentWithTheRoutersSchema_passesTheParsedReply() =
    runBlocking {
      // Arrange: a router with a schema hands off to a single-turn parent without one.
      val router =
        LlmAgent(
          "router",
          transferModel("coordinator"),
          mode = AgentMode.SINGLE_TURN,
          outputSchema = Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(Type.STRING))),
        )
      val unusedCoordinator =
        LlmAgent(
          "coordinator",
          replyModel("""{"a": "x"}"""),
          subAgents = listOf(router),
          mode = AgentMode.SINGLE_TURN,
        )
      val capture = TransferCaptureNode()
      val workflow =
        Workflow(name = "wf", edges = listOf(Edge(Start, router), Edge(router, capture)))

      // Act
      val events = workflow.runAsync(testInvocationContext()).toList()

      // Assert: the reply reads with the router's schema, not as the parent's message.
      assertEquals(mapOf("a" to "x"), capture.received)
      assertEquals(mapOf("a" to "x"), events.single { it.author == "coordinator" }.output)
    }

  @Test
  fun runAsync_singleTurnNodeTransfer_showsTheNodeInputToTheTarget() = runBlocking {
    // Arrange: Prep -> a single-turn router that hands off to its helper -> Capture.
    val requests = mutableListOf<LlmRequest>()
    val helper = chatAgent("helper", recordingModel(requests, "from helper"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val capture = TransferCaptureNode()
    val context = testInvocationContext()

    // Act
    val unused = prepWorkflow(router, capture).runAsync(context).toList()

    // Assert: the helper sees the router's input, which leaves the session after the handoff.
    assertContains(requestTexts(requests.single()), "prep out")
    assertEquals("from helper", capture.received)
    assertTrue(
      context.session.events.none { it.author == Role.USER && it.contentText() == "prep out" }
    )
  }

  @Test
  fun runAsync_singleTurnNodeTransferToAPeer_showsTheNodeInputToThePeer() = runBlocking {
    // Arrange: a router hands off to a history-free peer, which runs beside it, not below it.
    val requests = mutableListOf<LlmRequest>()
    val router = LlmAgent("router", transferModel("peer"), mode = AgentMode.SINGLE_TURN)
    val peer =
      LlmAgent(
        "peer",
        recordingModel(requests, "from peer"),
        mode = AgentMode.CHAT,
        includeContents = IncludeContents.NONE,
      )
    val unusedParent =
      chatAgent("coordinator", replyModel("unused"), subAgents = listOf(router, peer))
    val capture = TransferCaptureNode()

    // Act
    val unused = prepWorkflow(router, capture).runAsync(testInvocationContext()).toList()

    // Assert
    assertContains(requestTexts(requests.single()), "prep out")
    assertEquals("from peer", capture.received)
  }

  @Test
  fun runAsync_singleTurnNodeTransferToATopLevelParent_showsTheNodeInputToTheParent() =
    runBlocking {
      // Arrange: a router hands off to its single-turn parent, outside the workflow.
      val requests = mutableListOf<LlmRequest>()
      val router = LlmAgent("router", transferModel("coordinator"), mode = AgentMode.SINGLE_TURN)
      val unusedCoordinator =
        LlmAgent(
          "coordinator",
          recordingModel(requests, "coordinator here"),
          subAgents = listOf(router),
          mode = AgentMode.SINGLE_TURN,
        )
      val capture = TransferCaptureNode()

      // Act
      val unused = prepWorkflow(router, capture).runAsync(testInvocationContext()).toList()

      // Assert
      assertContains(requestTexts(requests.single()), "prep out")
      assertEquals("coordinator here", capture.received)
    }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun runAsync_nodeThatASingleTurnNodesToolStarts_doesNotSeeTheNodeInput() = runBlocking {
    // Arrange: a single-turn router whose tool runs another agent as a node during its turn.
    val requests = mutableListOf<LlmRequest>()
    val inner = chatAgent("inner", recordingModel(requests, "from inner"))
    val startInner =
      DummyTool(
        "start_inner",
        onRun = { context, _ ->
          context.invocationContext.frameworkData.nodeContext!!.runNode(inner)
          mapOf("ok" to true)
        },
      )
    val router =
      LlmAgent(
        "router",
        DummyModel.createSequential(
          "m",
          listOf(
            modelFunctionCallResponse("start_inner"),
            LlmResponse(content = Content.fromText(Role.MODEL, "done")),
          ),
        ),
        tools = listOf(startInner),
        mode = AgentMode.SINGLE_TURN,
      )
    val capture = TransferCaptureNode()

    // Act
    val unused = prepWorkflow(router, capture).runAsync(testInvocationContext()).toList()

    // Assert: as in ADK Python, only the node the input was for reads it.
    assertTrue("prep out" !in requestTexts(requests.single()))
    assertEquals("done", capture.received)
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun runAsync_nodeThatASingleTurnNodesTargetStarts_doesNotSeeTheNodeInput() = runBlocking {
    // Arrange: the helper's tool runs an agent, and a single-turn node that hands off, as nodes.
    val innerRequests = mutableListOf<LlmRequest>()
    val inner = chatAgent("inner", recordingModel(innerRequests, "from inner"))
    val nestedRequests = mutableListOf<LlmRequest>()
    val nested = chatAgent("nested", recordingModel(nestedRequests, "from nested"))
    val nestedRouter =
      LlmAgent(
        "nested_router",
        transferModel("nested"),
        subAgents = listOf(nested),
        mode = AgentMode.SINGLE_TURN,
      )
    val startInner =
      DummyTool(
        "start_inner",
        onRun = { context, _ ->
          val nodeContext = context.invocationContext.frameworkData.nodeContext!!
          nodeContext.runNode(inner)
          nodeContext.runNode(nestedRouter)
          mapOf("ok" to true)
        },
      )
    val helperRequests = mutableListOf<LlmRequest>()
    val helperModel =
      DummyModel("m") { request ->
        helperRequests += request
        if (helperRequests.size == 1) flowOf(modelFunctionCallResponse("start_inner"))
        else flowOf(LlmResponse(content = Content.fromText(Role.MODEL, "from helper")))
      }
    val helper = LlmAgent("helper", helperModel, tools = listOf(startInner), mode = AgentMode.CHAT)
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val capture = TransferCaptureNode()

    // Act
    val unused = prepWorkflow(router, capture).runAsync(testInvocationContext()).toList()

    // Assert: the helper reads the router's input; the nodes its tool starts do not.
    assertContains(requestTexts(helperRequests.first()), "prep out")
    assertTrue("prep out" !in requestTexts(innerRequests.single()))
    assertTrue("prep out" !in requestTexts(nestedRequests.single()))
    assertEquals("from helper", capture.received)
  }

  @Test
  fun runAsync_resumedSingleTurnNodeTransfer_doesNotShowTheNodeInputToTheResumedTarget() =
    runBlocking {
      // Arrange: Prep -> router -> helper waiting on a long-running call; then a single-turn node.
      val helperRequests = mutableListOf<LlmRequest>()
      val helperModel =
        DummyModel("m") { request ->
          helperRequests += request
          if (helperRequests.size == 1) flowOf(modelFunctionCallResponse("approve"))
          else flowOf(LlmResponse(content = Content.fromText(Role.MODEL, "approved")))
        }
      val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
      val helper = LlmAgent("helper", helperModel, tools = listOf(approve), mode = AgentMode.CHAT)
      val router =
        LlmAgent(
          "router",
          transferModel("helper"),
          subAgents = listOf(helper),
          mode = AgentMode.SINGLE_TURN,
        )
      val laterRequests = mutableListOf<LlmRequest>()
      val later =
        LlmAgent("later", recordingModel(laterRequests, "later"), mode = AgentMode.SINGLE_TURN)
      val runner =
        InMemoryRunner(
          app =
            App(
              appName = "app",
              rootNode = prepWorkflow(router, later),
              resumabilityConfig = ResumabilityConfig(isResumable = true),
            )
        )
      val first = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
      val callId = first.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
      val answer = userFunctionResponse("approve", id = callId, response = mapOf("ok" to true))

      // Act
      val unused = runner.runAsync("u", "s", newMessage = answer).toList()

      // Assert: as in ADK Python, a resumed turn injects no input, so only the first turn sees it.
      assertEquals(2, helperRequests.size)
      assertContains(requestTexts(helperRequests.first()), "prep out")
      assertTrue("prep out" !in requestTexts(helperRequests.last()))
      val laterTexts = requestTexts(laterRequests.single())
      assertContains(laterTexts, "approved")
      assertTrue("prep out" !in laterTexts)
    }

  @Test
  fun runAsync_singleTurnNodeTransfer_keepsTheNodeInputFromLaterNodes() = runBlocking {
    // Arrange: Prep -> a single-turn router that hands off to its helper -> a single-turn node.
    val requests = mutableListOf<LlmRequest>()
    val helper = chatAgent("helper", replyModel("from helper"))
    val router =
      LlmAgent(
        "router",
        transferModel("helper"),
        subAgents = listOf(helper),
        mode = AgentMode.SINGLE_TURN,
      )
    val later = LlmAgent("later", recordingModel(requests, "later"), mode = AgentMode.SINGLE_TURN)

    // Act
    val unused = prepWorkflow(router, later).runAsync(testInvocationContext()).toList()

    // Assert: the next node sees the router's output, not the router's own input.
    val texts = requestTexts(requests.single())
    assertContains(texts, "from helper")
    assertTrue("prep out" !in texts)
  }

  /** A model that records each request in [requests] and answers [reply]. */
  private fun recordingModel(requests: MutableList<LlmRequest>, reply: String) =
    DummyModel("m") { request ->
      requests += request
      flowOf(LlmResponse(content = Content.fromText(Role.MODEL, reply)))
    }

  private fun requestTexts(request: LlmRequest): List<String> =
    request.contents.flatMap { it.parts }.mapNotNull { it.text }

  /** Prep, which outputs `prep out` -> [router] -> [next]. */
  private fun prepWorkflow(router: Node, next: Node): Workflow {
    val prep = node<Any?, String>("prep") { _, _ -> "prep out" }
    return Workflow(
      name = "wf",
      edges = listOf(Edge(Start, prep), Edge(prep, router), Edge(router, next)),
    )
  }
}
