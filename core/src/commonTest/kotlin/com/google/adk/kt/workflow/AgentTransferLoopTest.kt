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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
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

private fun callResponse(name: String, args: Map<String, Any?>) =
  LlmResponse(content = Content(Role.MODEL, listOf(Part(functionCall = FunctionCall(name, args)))))

private fun transferResponse(target: String) =
  callResponse("transfer_to_agent", mapOf("agent_name" to target))

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

private fun List<Event>.textBy(author: String): List<String> =
  filter { it.author == author }.map { it.contentText() }.filter { it.isNotEmpty() }

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
    assertEquals("Transfer target agent 'nobody' not found.", unknown.message)
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
            listOf(callResponse("approve", emptyMap()), transferResponse("child")),
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
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

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
              callResponse("approve", emptyMap()),
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
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the child picks up the answer and finishes under its node path.
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
              callResponse("approve", emptyMap()),
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
        model = DummyModel.createSequential("m", listOf(callResponse("hold", emptyMap()))),
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
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

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
              callResponse("approve", emptyMap()),
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
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

    // Act
    val events = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: the peer target picks up the answer and finishes under its node path.
    val reply = events.single { it.author == "second" && it.contentText() == "approved by peer" }
    assertEquals(paused.first().invocationId, reply.invocationId)
    assertEquals("wf@1/second@1", reply.nodeInfo?.path)
  }

  @Test
  fun runAsync_parentTransferTargetPausedThenAnswered_resumesTarget() = runBlocking {
    // Arrange: the coordinator hands off to the child, which hands back to the coordinator, which
    // then waits on a long-running approval.
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
              callResponse("approve", emptyMap()),
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
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

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
  fun runNode_dispatchedAgentTransferToSibling_returnsSiblingRun() = runBlocking {
    // Arrange
    val first = chatAgent("first", transferModel("second"))
    val second = LlmAgent("second", replyModel("from second"), mode = AgentMode.SINGLE_TURN)
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val capture = ModeCaptureNodeForTransfer()
    val driver = TransferDriverNode(first)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, driver), Edge(driver, capture)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: the sibling ran under the driver and its answer is what runNode returned.
    assertEquals(listOf("from second"), events.textBy("second"))
    val secondEvent = events.first { it.author == "second" && it.content != null }
    assertEquals("wf@1/driver@1/second@1", secondEvent.nodeInfo?.path)
    assertEquals("from second", capture.received)
  }

  @Test
  fun runNode_sameRunIdTwiceWithATransfer_runsTheTargetOnce() = runBlocking {
    // Arrange
    val first = chatAgent("first", transferModel("second"))
    val second = LlmAgent("second", replyModel("from second"), mode = AgentMode.SINGLE_TURN)
    val unusedRoot = chatAgent("root", replyModel("unused"), subAgents = listOf(first, second))
    val driver = SameRunIdTransferDriverNode(first)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, driver)))

    // Act
    val events = workflow.runAsync(testInvocationContext()).toList()

    // Assert: both calls share one run of the handoff, so the target answers once for both.
    assertEquals(listOf("from second"), events.textBy("second"))
    val output = events.single { it.nodeInfo?.path == "wf@1/driver@1" && it.output != null }.output
    assertEquals(listOf("from second", "from second"), output)
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

    // Act
    val error =
      runCatching { workflow.runAsync(testInvocationContext()).toList() }.exceptionOrNull()

    // Assert
    assertContains(error?.message.orEmpty(), "unrelated agent 'elsewhere'")
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
      runCatching { workflow.runAsync(testInvocationContext()).toList(events) }.exceptionOrNull()

    // Assert
    assertContains(error?.message.orEmpty(), "'billing.desk'")
    assertTrue(events.none { it.author == "billing.desk" })
  }
}

/** Records the input it receives. */
private class ModeCaptureNodeForTransfer(override val name: String = "capture") : Node {
  var received: Any? = null

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
  }
}
