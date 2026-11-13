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
import com.google.adk.kt.agents.FinishTaskTool
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.agents.unresolvedTaskDelegations
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Answers each request with the next scripted turn, repeating the last, and records the requests.
 */
private class ModeScriptedModel(
  private vararg val turns: Content,
  override val name: String = "mode-scripted",
) : Model {
  val requests = mutableListOf<LlmRequest>()

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    val turn = turns.getOrElse(requests.size) { turns.last() }
    requests.add(request)
    return flowOf(LlmResponse(content = turn))
  }

  fun declaredToolNames(): List<String> =
    requests
      .first()
      .config
      .tools
      .orEmpty()
      .flatMap { it.functionDeclarations.orEmpty() }
      .map { it.name }
}

/** Records the input it receives and emits it back as its output. */
private class ModeCaptureNode(override val name: String = "capture") : Node {
  var received: Any? = null

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
    emit(nodeInput)
  }
}

/** Records the input it receives and produces no output. */
private class ModeSinkNode(override val name: String) : Node {
  var received: Any? = null

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
  }
}

/** Emits its input once [ready] holds for the session's events, so it can wait for a sibling. */
private class ModeWaitingNode(
  override val name: String,
  private val ready: (List<Event>) -> Boolean,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    repeat(MAX_CHECKS) {
      if (ready(context.session.events)) {
        emit(nodeInput)
        return@flow
      }
      delay(5)
    }
    error("Node $name waited too long for its condition.")
  }

  private companion object {
    const val MAX_CHECKS = 2000
  }
}

/** Dispatches [target] with [input] through [Context.runNode] and emits what it returned. */
private class ModeDriverNode(private val target: Node, private val input: Any?) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(target, nodeInput = input))
  }
}

/** Runs [target] as a child node in the isolation scope [scope]. */
private class ModeScopedDriverNode(private val target: Node, private val scope: String) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(target, overrideIsolationScope = scope))
  }
}

private fun call(name: String, args: Map<String, Any?>): Content =
  Content(Role.MODEL, listOf(Part(functionCall = FunctionCall(name, args))))

private fun calls(vararg calls: Pair<String, Map<String, Any?>>): Content =
  Content(Role.MODEL, calls.map { (name, args) -> Part(functionCall = FunctionCall(name, args)) })

private fun text(text: String): Content = Content(Role.MODEL, listOf(Part(text = text)))

private fun taskAgent(
  name: String,
  model: Model,
  outputSchema: Schema? = null,
  outputKey: String? = null,
) =
  LlmAgent(
    name = name,
    model = model,
    description = "Does $name.",
    outputSchema = outputSchema,
    outputKey = outputKey,
    mode = AgentMode.TASK,
  )

private fun coordinator(
  model: Model,
  subAgents: List<BaseAgent>,
  tools: List<BaseTool> = emptyList(),
) = LlmAgent(name = "root_agent", model = model, subAgents = subAgents, tools = tools)

/** Runs [root] passed as a node, so an agent may use agent modes. */
private fun runnerFor(root: Node, resumable: Boolean = false) =
  InMemoryRunner(
    app =
      App(
        "app",
        rootNode = root,
        resumabilityConfig = if (resumable) ResumabilityConfig(isResumable = true) else null,
      )
  )

private suspend fun InMemoryRunner.send(message: String): List<Event> =
  runAsync("u", "s", newMessage = Content.fromText(Role.USER, message)).toList()

/** Counts its runs and emits a fixed output. */
private class ModeCountingNode(override val name: String) : Node {
  var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs++
    emit("$name ran")
  }
}

/** Asks for input on its first run and emits `answered` once it has an answer. */
private class ModeAskingNode(override val name: String) : Node {
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (context.resumeInputs.isEmpty()) emit(RequestInput(interruptId = "ask_$name"))
    else emit("answered")
  }
}

/** A coordinator with a long-running `approve` tool and the task sub-agent `worker`. */
private fun approvingCoordinator(model: Model, workerModel: Model, name: String = "root_agent") =
  LlmAgent(
    name = name,
    model = model,
    description = "Coordinates.",
    subAgents = listOf(taskAgent("worker", workerModel)),
    tools = listOf(DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })),
  )

/** A coordinator turn that calls `approve` and delegates to `worker`, then a closing reply. */
private fun approveAndDelegateModel() =
  ModeScriptedModel(
    calls("approve" to emptyMap(), "worker" to mapOf("request" to "do it")),
    text("all done"),
  )

private suspend fun runWorkflow(message: String, vararg edges: Edge): List<Event> =
  InMemoryRunner(app = App("app", rootNode = Workflow("wf", edges.toList())))
    .runAsync("u", "s", newMessage = Content.fromText(Role.USER, message))
    .toList()

private fun List<Event>.contentful() = filter { !it.content?.parts.isNullOrEmpty() }

private fun List<Event>.responseNames() = flatMap { it.functionResponses() }.map { it.name }

private val LABEL_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf("label" to Schema(type = Type.STRING), "score" to Schema(type = Type.INTEGER)),
    required = listOf("label", "score"),
  )

class LlmAgentModeTest {

  @Test
  fun defaultMode_parentlessAgentWithSubAgents_isChat() {
    // Arrange: as in ADK Python, sub-agents make a parentless agent a chat coordinator.
    val helper = LlmAgent("helper", ModeScriptedModel(), mode = AgentMode.SINGLE_TURN)
    val coordinator = LlmAgent("coordinator", ModeScriptedModel(), subAgents = listOf(helper))

    // Act
    val mode = coordinator.defaultMode()

    // Assert
    assertEquals(AgentMode.CHAT, mode)
  }

  @Test
  fun defaultMode_parentlessAgentWithoutSubAgents_isSingleTurn() {
    // Arrange
    val agent = LlmAgent("solo", ModeScriptedModel())

    // Act
    val mode = agent.defaultMode()

    // Assert
    assertEquals(AgentMode.SINGLE_TURN, mode)
  }

  @Test
  fun taskMode_agent_gainsFinishTaskAndIsParentTool() {
    // Arrange
    val worker = taskAgent("worker", ModeScriptedModel(text("unused")))

    // Act
    val root = coordinator(ModeScriptedModel(text("unused")), listOf(worker))

    // Assert
    assertEquals(listOf("finish_task"), worker.tools.map { it.name })
    assertEquals(listOf("worker"), root.tools.map { it.name })
    assertTrue(worker.waitForOutput)
  }

  @Test
  fun subAgentMode_decidesToolOrTransferTarget() = runBlocking {
    // Arrange
    val model = ModeScriptedModel(text("done"))
    val chat = LlmAgent("chat_sub", model, description = "Chats.", mode = AgentMode.CHAT)
    val task = taskAgent("task_sub", model)
    val single =
      LlmAgent("single_sub", model, description = "Answers.", mode = AgentMode.SINGLE_TURN)
    val root = coordinator(model, listOf(chat, task, single))

    // Act
    val unused = runnerFor(root).send("hi")

    // Assert
    assertEquals(listOf("transfer_to_agent", "task_sub", "single_sub"), model.declaredToolNames())
    val instruction = model.requests.first().config.systemInstruction?.text().orEmpty()
    assertContains(instruction, "`chat_sub`.")
    assertFalse("task_sub`" in instruction)
    assertFalse("single_sub`" in instruction)
  }

  @Test
  fun taskMode_request_hasNoResponseSchemaOrTransferInstructions() = runBlocking {
    // Arrange: the task agent has an output schema and a peer it could otherwise transfer to.
    val model = ModeScriptedModel(call("finish_task", mapOf("title" to "Story")))
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("title" to Schema(type = Type.STRING)))
    val writer = taskAgent("writer", model, outputSchema = schema)
    val peer = LlmAgent("peer", ModeScriptedModel(text("unused")), description = "Chats.")
    val unusedRoot = coordinator(ModeScriptedModel(text("unused")), listOf(writer, peer))

    // Act
    val unused = runWorkflow("write", Edge(Start, writer))

    // Assert: finish_task carries the schema; as in Python, the transfer tool stays but untold.
    val request = model.requests.first()
    assertNull(request.config.responseSchema)
    assertEquals(listOf("transfer_to_agent", "finish_task"), model.declaredToolNames())
    assertFalse("`peer`" in request.config.systemInstruction?.text().orEmpty())
  }

  @Test
  fun taskMode_gemini2Request_offersNoSetModelResponseTool() = runBlocking {
    // Arrange: Gemini 2.x takes no response schema beside tools, which adds `set_model_response`.
    val model =
      ModeScriptedModel(call("finish_task", mapOf("title" to "Story")), name = "gemini-2.5-flash")
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("title" to Schema(type = Type.STRING)))
    val writer = taskAgent("writer", model, outputSchema = schema)

    // Act
    val unused = runWorkflow("write", Edge(Start, writer))

    // Assert: `finish_task` carries the schema instead.
    assertEquals(listOf("finish_task"), model.declaredToolNames())
  }

  @Test
  fun chatMode_runInScope_seesItsOwnToolTurns() = runBlocking {
    // Arrange: a chat agent run in a scope calls a tool, then answers.
    val model = ModeScriptedModel(call("lookup", emptyMap()), text("done"))
    val lookup = DummyTool("lookup", onRun = { _, _ -> mapOf("ok" to true) })
    val chat = LlmAgent("chat_agent", model, mode = AgentMode.CHAT, tools = listOf(lookup))

    // Act
    val unused = runWorkflow("go", Edge(Start, ModeScopedDriverNode(chat, scope = "scope-1")))

    // Assert: its scoped tool call and response reach its next request.
    val second = model.requests[1].contents.flatMap { it.parts }
    assertTrue(second.any { it.functionCall?.name == "lookup" })
    assertTrue(second.any { it.functionResponse?.name == "lookup" })
  }

  @Test
  fun singleTurnRoot_isRejectedWhenTheRunnerIsBuilt() {
    // Arrange
    val root =
      LlmAgent("root_agent", ModeScriptedModel(text("unused")), mode = AgentMode.SINGLE_TURN)

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runnerFor(root) }

    // Assert
    assertContains(error.message.orEmpty(), "must have mode='chat' or 'task'")
  }

  @Test
  fun taskMode_taskWithTaskSubAgent_runsNestedDelegation() = runBlocking {
    // Arrange: the root delegates to a task agent, which delegates to its own task sub-agent.
    val grandchild =
      taskAgent("grandchild", ModeScriptedModel(call("finish_task", mapOf("result" to "leaf"))))
    val child =
      LlmAgent(
        name = "child",
        model =
          ModeScriptedModel(
            call("grandchild", mapOf("request" to "leaf work")),
            call("finish_task", mapOf("result" to "middle wraps leaf")),
          ),
        description = "Does child.",
        mode = AgentMode.TASK,
        subAgents = listOf(grandchild),
      )
    val root =
      coordinator(
        ModeScriptedModel(
          call("child", mapOf("request" to "do the thing")),
          text("Top-level done."),
        ),
        listOf(child),
      )

    // Act
    val events = runnerFor(root).send("hi")

    // Assert: the grandchild finishes first, then the child, then the root answers.
    val finishArgs = events.flatMap { it.functionCalls() }.filter { it.name == "finish_task" }
    assertEquals(
      listOf(mapOf("result" to "leaf"), mapOf("result" to "middle wraps leaf")),
      finishArgs.map { it.args },
    )
    assertEquals("Top-level done.", events.contentful().last().contentText())
  }

  @Test
  fun taskMode_turnDelegatingAndFinishing_runsTheDelegationBeforeFinishing() = runBlocking {
    // Arrange: as in ADK Python, a finish_task call beside a delegation is answered with an error.
    val grandchild =
      taskAgent("grandchild", ModeScriptedModel(call("finish_task", mapOf("result" to "leaf"))))
    val childModel =
      ModeScriptedModel(
        calls(
          "grandchild" to mapOf("request" to "leaf work"),
          "finish_task" to mapOf("result" to "too early"),
        ),
        call("finish_task", mapOf("result" to "after leaf")),
      )
    val child =
      LlmAgent(
        name = "child",
        model = childModel,
        description = "Does child.",
        mode = AgentMode.TASK,
        subAgents = listOf(grandchild),
      )
    val root =
      coordinator(
        ModeScriptedModel(call("child", mapOf("request" to "do the thing")), text("done")),
        listOf(child),
      )

    // Act
    val events = runnerFor(root).send("hi")

    // Assert
    val childResponse =
      events.flatMap { it.functionResponses() }.single { it.name == "child" }.response
    assertEquals(mapOf("result" to "after leaf"), childResponse)
    val earlyFinish =
      childModel.requests[1]
        .contents
        .flatMap { it.parts }
        .mapNotNull { it.functionResponse?.takeIf { response -> response.name == "finish_task" } }
    assertEquals(listOf(FinishTaskTool.REJECTED_RESPONSE), earlyFinish.map { it.response })
  }

  @Test
  fun runAsync_coordinatorOfTaskAgent_followUpGoesToChatSiblingItTransferredTo() = runBlocking {
    // Arrange: a coordinator of a task agent hands off to a chat sibling.
    val rootModel =
      ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper")), text("root"))
    val helper = LlmAgent("helper", ModeScriptedModel(text("helper here"), text("helper again")))
    val runner =
      runnerFor(
        coordinator(
          rootModel,
          listOf(taskAgent("worker", ModeScriptedModel(text("unused"))), helper),
        )
      )
    val unused = runner.send("hi")

    // Act
    val followUp = runner.send("more").contentful()

    // Assert: as in Python, the follow-up goes to the sibling, not back to the coordinator.
    assertEquals(listOf("helper again"), followUp.map { it.contentText() })
    assertEquals(1, rootModel.requests.size)
  }

  @Test
  fun taskMode_graphTaskPausingAgainAfterFinishing_replyJoinsItsScope() = runBlocking {
    // Arrange: a graph task node asks, finishes on the reply, then asks again on the next message.
    val model =
      ModeScriptedModel(
        text("which one?"),
        call("finish_task", mapOf("result" to "first")),
        text("which one now?"),
        call("finish_task", mapOf("result" to "second")),
      )
    val runner =
      InMemoryRunner(
        app = App("app", rootNode = Workflow("wf", listOf(Edge(Start, taskAgent("worker", model)))))
      )
    for (message in listOf("go", "this one", "again")) runner.send(message)

    // Act
    val unused = runner.send("that one")

    // Assert: the earlier finish does not close the scope for the new run.
    val session = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s")))
    val reply = session.events.single { it.content?.text() == "that one" }
    assertEquals("wf@1/worker@1", reply.isolationScope)
  }

  @Test
  fun taskMode_graphTaskWaitingOnUser_holdsBackOtherNodes() = runBlocking {
    // Arrange: `Start -> worker`, `Start -> b`, `b -> c`; `b` ends only after the checkpoint that
    // records the task waiting on its question.
    val model =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val b =
      ModeWaitingNode("b") { events ->
        val asked = events.indexOfFirst {
          it.author == "worker" && it.contentText() == "which one?"
        }
        asked >= 0 && events.drop(asked + 1).any { it.actions.agentState != null }
      }
    val c = ModeSinkNode("c")
    val edges = listOf(Edge(Start, taskAgent("worker", model)), Edge(Start, b), Edge(b, c))
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode = Workflow("wf", edges),
            resumabilityConfig = ResumabilityConfig(isResumable = true),
          )
      )

    // Act
    val unused = runner.send("go")
    val cRanWhileTaskWaited = c.received != null
    val unusedReply = runner.send("the second one")

    // Assert: as in Python, `c` starts only once the task is answered.
    assertFalse(cRanWhileTaskWaited)
    assertTrue(c.received != null)
  }

  @Test
  fun chatMode_singleTurnHelperTurns_stayOutOfLaterRequests() = runBlocking {
    // Arrange: the root calls a single-turn helper as a tool, then answers.
    val rootModel =
      ModeScriptedModel(call("helper", mapOf("request" to "look it up")), text("done"), text("ok"))
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(text("helper says hi")),
        description = "Does helper.",
        mode = AgentMode.SINGLE_TURN,
      )
    val runner = runnerFor(coordinator(rootModel, listOf(helper)))
    val unused = runner.send("go")

    // Act
    val unusedFollowUp = runner.send("again")

    // Assert: as in Python, the root sees the helper's function response but not its own turns.
    val parts = rootModel.requests.last().contents.flatMap { it.parts }
    assertTrue(parts.any { it.functionResponse?.name == "helper" })
    assertTrue(parts.mapNotNull { it.text }.none { "helper says hi" in it })
  }

  @Test
  fun unresolvedTaskDelegations_withScope_ignoresCallsFromOtherScopes() {
    // Arrange: one task agent left a delegation open in each of two scopes.
    val child =
      LlmAgent(
        name = "child",
        model = ModeScriptedModel(text("unused")),
        mode = AgentMode.TASK,
        subAgents = listOf(taskAgent("grandchild", ModeScriptedModel(text("unused")))),
      )
    fun delegation(id: String, scope: String) =
      Event(
        author = "child",
        content =
          Content(Role.MODEL, listOf(Part(functionCall = FunctionCall("grandchild", id = id)))),
        isolationScope = scope,
      )
    val events = listOf(delegation("a", "s1"), delegation("b", "s2"))
    val tools = child.tools.associateBy { it.name }

    // Act
    val scoped = unresolvedTaskDelegations(events, "child", tools, scope = "s1")
    val unscoped = unresolvedTaskDelegations(events, "child", tools)

    // Assert
    assertEquals(listOf("a"), scoped.map { it.id })
    assertEquals(listOf("a", "b"), unscoped.map { it.id })
  }

  @Test
  fun taskMode_root_runsInScopeNamedByNodePath() = runBlocking {
    // Arrange
    val model =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val runner = runnerFor(taskAgent("root_task", model))

    // Act
    val first = runner.send("go")
    val second = runner.send("the second one")

    // Assert: the task, and the reply that continues it, share the task's scope, and the reply
    // finishes the task.
    assertTrue(first.filter { it.author == "root_task" }.all { it.isolationScope == "root_task@1" })
    assertTrue(second.any { it.output == mapOf("result" to "done") })
    val session = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s")))
    val reply = session.events.single { it.content?.text() == "the second one" }
    assertEquals("root_task@1", reply.isolationScope)
    assertEquals("go", model.requests.first().contents.first().text())
  }

  @Test
  fun chatMode_turnMixingToolTaskAndTransfer_runsTaskThenTransfers() = runBlocking {
    // Arrange
    val lookup = DummyTool("lookup", onRun = { _, _ -> mapOf("ok" to true) })
    val workerModel = ModeScriptedModel(call("finish_task", mapOf("result" to "done")))
    val helper = LlmAgent("helper", ModeScriptedModel(text("helper here")))
    val root =
      coordinator(
        ModeScriptedModel(
          calls(
            "lookup" to emptyMap(),
            "worker" to mapOf("request" to "do"),
            "transfer_to_agent" to mapOf("agent_name" to "helper"),
          ),
          text("after task"),
        ),
        listOf(taskAgent("worker", workerModel), helper),
        tools = listOf(lookup),
      )

    // Act
    val events = runnerFor(root).send("go")

    // Assert: as in Python, the delegation runs before the node follows the transfer.
    assertEquals(1, workerModel.requests.size)
    assertTrue("worker" in events.responseNames())
    assertEquals("helper", events.contentful().last().author)
  }

  @Test
  fun chatMode_resumableTurnMixingTaskAndTransfer_runsEachAgentOnce() = runBlocking {
    // Arrange: a resumable chat root delegates a task and hands off in the same turn.
    val workerModel = ModeScriptedModel(call("finish_task", mapOf("result" to "done")))
    val helperModel = ModeScriptedModel(text("helper here"), text("helper again"))
    val root =
      coordinator(
        ModeScriptedModel(
          calls(
            "worker" to mapOf("request" to "do"),
            "transfer_to_agent" to mapOf("agent_name" to "helper"),
          ),
          text("after task"),
        ),
        listOf(taskAgent("worker", workerModel), LlmAgent("helper", helperModel)),
      )

    // Act
    val events = runnerFor(root, resumable = true).send("go")

    // Assert: the task runs once and the helper answers once, as a node, not also inline.
    assertEquals(1, workerModel.requests.size)
    assertEquals(1, helperModel.requests.size, "${events.map { it.author }}")
  }

  @Test
  fun chatMode_turnWhoseTaskAsksTheUserAndTransfers_endsTheTurnAtTheQuestion() = runBlocking {
    // Arrange: one turn delegates to a task that asks a question, and also transfers.
    val workerModel = ModeScriptedModel(text("Which city?"))
    val helperModel = ModeScriptedModel(text("helper here"))
    val root =
      coordinator(
        ModeScriptedModel(
          calls(
            "worker" to mapOf("request" to "book a trip"),
            "transfer_to_agent" to mapOf("agent_name" to "helper"),
          )
        ),
        listOf(taskAgent("worker", workerModel), LlmAgent("helper", helperModel)),
      )

    // Act
    val events = runnerFor(root).send("go")

    // Assert: as in Python, the turn ends at the question and the helper does not run yet.
    assertEquals("Which city?", events.contentful().last().contentText())
    assertTrue(helperModel.requests.isEmpty())
  }

  @Test
  fun chatMode_replyToTaskThatAskedBesideATransfer_finishesTheTaskThenTransfers() = runBlocking {
    // Arrange: one turn delegates to a task that asks a question, and also transfers.
    val workerModel =
      ModeScriptedModel(text("Which city?"), call("finish_task", mapOf("result" to "Paris")))
    val helperModel = ModeScriptedModel(text("helper here"))
    val rootModel =
      ModeScriptedModel(
        calls(
          "worker" to mapOf("request" to "book a trip"),
          "transfer_to_agent" to mapOf("agent_name" to "helper"),
        ),
        text("root again"),
      )
    val runner =
      runnerFor(
        coordinator(
          rootModel,
          listOf(taskAgent("worker", workerModel), LlmAgent("helper", helperModel)),
        )
      )
    val unused = runner.send("go")

    // Act
    val reply = runner.send("Paris")

    // Assert: as in Python, the task finishes first, and then the transfer runs without another
    // model call by the root.
    assertEquals(
      listOf("worker", "transfer_to_agent"),
      reply.responseNames().filter { it != FinishTaskTool.NAME },
    )
    assertEquals(
      listOf("helper here"),
      reply.filter { it.author == "helper" }.map { it.contentText() },
    )
    assertEquals(1, rootModel.requests.size)
  }

  @Test
  fun taskMode_taskWhoseInnerTaskAsksBesideATransfer_waitsThenTransfers() = runBlocking {
    // Arrange: a delegated task delegates to an inner task that asks, and also transfers.
    val innerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "b")))
    val helperModel = ModeScriptedModel(text("helper here"))
    val outerModel =
      ModeScriptedModel(
        calls(
          "inner_task" to mapOf("request" to "pick"),
          "transfer_to_agent" to mapOf("agent_name" to "helper"),
        )
      )
    val outer =
      LlmAgent(
        name = "outer_task",
        description = "Does outer.",
        model = outerModel,
        mode = AgentMode.TASK,
        subAgents = listOf(taskAgent("inner_task", innerModel), LlmAgent("helper", helperModel)),
      )
    val rootModel =
      ModeScriptedModel(call("outer_task", mapOf("request" to "go")), text("root done"))
    val runner = runnerFor(coordinator(rootModel, listOf(outer)))

    // Act
    val first = runner.send("start")
    val rootRequestsAfterQuestion = rootModel.requests.size
    val reply = runner.send("b")

    // Assert: as in Python, the first turn ends at the question; the reply finishes the inner task,
    // then the outer task transfers, and only then does the root go on.
    assertEquals("which one?", first.contentful().last().contentText())
    assertEquals(1, rootRequestsAfterQuestion)
    assertContains(innerModel.requests[1].contents.map { it.text() }, "b")
    assertEquals(
      listOf("inner_task", "transfer_to_agent", "outer_task"),
      reply.responseNames().filter { it != FinishTaskTool.NAME },
    )
    assertEquals(1, helperModel.requests.size)
    assertEquals(1, outerModel.requests.size)
    assertEquals("root done", reply.contentful().last().contentText())
  }

  @Test
  fun taskMode_taskHandingOffToAHelperWhoseTaskAsks_endsTheTurnAtTheQuestion() = runBlocking {
    // Arrange: a delegated task hands off to a helper, whose own task asks the user.
    val innerModel = ModeScriptedModel(text("which one?"))
    val helper =
      LlmAgent(
        name = "helper",
        model = ModeScriptedModel(call("inner_task", mapOf("request" to "pick")), text("ok")),
        subAgents = listOf(taskAgent("inner_task", innerModel)),
      )
    val outer =
      LlmAgent(
        name = "outer_task",
        description = "Does outer.",
        model = ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper"))),
        mode = AgentMode.TASK,
        subAgents = listOf(helper),
      )
    val rootModel =
      ModeScriptedModel(call("outer_task", mapOf("request" to "go")), text("root done"))

    // Act
    val events = runnerFor(coordinator(rootModel, listOf(outer))).send("start")

    // Assert: the waiting inner task holds the root back, so the turn ends at its question.
    assertEquals("which one?", events.contentful().last().contentText())
    assertEquals(1, rootModel.requests.size)
  }

  @Test
  fun singleTurnTool_handoffBackToADottedRoot_runsTheRootAgain() = runBlocking {
    // Arrange: a root whose name has a dot calls a single-turn tool that hands back to it.
    val finder =
      LlmAgent(
        "finder",
        ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "support.bot"))),
        description = "Finds things.",
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      LlmAgent(
        "support.bot",
        ModeScriptedModel(call("finder", mapOf("request" to "look")), text("root again")),
        subAgents = listOf(finder),
        mode = AgentMode.CHAT,
      )

    // Act
    val events = runnerFor(root).send("go")

    // Assert: the handoff runs the root at the top level instead of failing the tool.
    val paths = events.filter { it.author == "support.bot" }.mapNotNull { it.nodeInfo?.path }
    assertEquals(listOf("support.bot@1", "support.bot@2"), paths.distinct(), "$paths")
    assertTrue(events.none { it.errorCode != null }, "${events.mapNotNull { it.errorMessage }}")
  }

  @Test
  fun singleTurnTool_handoffBackToTheAgentAFollowUpStartedAt_getsANewRunId() = runBlocking {
    // Arrange: turn 1 hands off to `helper`; turn 2 starts at `helper`, whose tool hands back.
    val finder =
      LlmAgent(
        "finder",
        ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper"))),
        description = "Finds things.",
        mode = AgentMode.SINGLE_TURN,
      )
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(
          text("helper here"),
          call("finder", mapOf("request" to "look")),
          text("helper again"),
          text("helper done"),
        ),
        subAgents = listOf(finder),
      )
    val root =
      coordinator(
        ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper"))),
        listOf(helper),
      )
    val runner = runnerFor(root)
    val unused = runner.send("hi")

    // Act
    val events = runner.send("find it")

    // Assert: the handoff back runs `helper` under a new run ID, not its first one again.
    val paths = events.filter { it.author == "helper" }.mapNotNull { it.nodeInfo?.path }.distinct()
    assertEquals(2, paths.size, "$paths")
  }

  @Test
  fun runAsync_replyToTaskUnderChatSubAgent_resumesThroughItsDelegator() = runBlocking {
    // Arrange: the root hands off to a chat sub-agent, whose task agent asks a question.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val chatSubModel =
      ModeScriptedModel(call("task_worker", mapOf("request" to "do it")), text("sub done"))
    val chatSub =
      LlmAgent("chat_sub", chatSubModel, subAgents = listOf(taskAgent("task_worker", workerModel)))
    val root =
      coordinator(
        ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "chat_sub"))),
        listOf(chatSub),
      )
    val runner = runnerFor(root)
    val unused = runner.send("go")

    // Act
    val second = runner.send("the second one")

    // Assert: as in Python, the chat sub-agent resumes its delegation and goes on with the result.
    assertTrue("task_worker" in second.responseNames())
    assertEquals("sub done", second.contentful().last().contentText())
  }

  @Test
  fun runAsync_answerToTaskAgentsLongRunningCall_resumesThroughItsDelegator() = runBlocking {
    // Arrange: the root delegates to a task agent, which pauses on a long-running approval.
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val worker =
      LlmAgent(
        name = "worker",
        model =
          ModeScriptedModel(
            call("approve", emptyMap()),
            call("finish_task", mapOf("result" to "approved")),
          ),
        description = "Does worker.",
        tools = listOf(approve),
        mode = AgentMode.TASK,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "get approval")), text("all done")),
        listOf(worker),
      )
    val runner = runnerFor(root)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val second = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: as in Python, the answer resumes the task through the agent that delegated it.
    assertTrue("worker" in second.responseNames())
    assertEquals("all done", second.contentful().last().contentText())
  }

  @Test
  fun taskMode_rootWhoseTaskAsksTheUser_resumesThroughTheRootOnTheReply() = runBlocking {
    // Arrange: a task root delegates to a task agent, which asks the user a question.
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(
          text("Which city?"),
          call("finish_task", mapOf("result" to "Paris booked")),
        ),
      )
    val rootModel =
      ModeScriptedModel(
        call("worker", mapOf("request" to "book a trip")),
        call("finish_task", mapOf("result" to "trip done")),
      )
    val root =
      LlmAgent(
        name = "root_task",
        model = rootModel,
        mode = AgentMode.TASK,
        subAgents = listOf(worker),
      )
    val runner = runnerFor(root)
    val unused = runner.send("plan my trip")

    // Act
    val reply = runner.send("Paris")

    // Assert: the reply reaches the task through the root, which then finishes its own task.
    assertTrue("worker" in reply.responseNames())
    val rootFinish =
      reply
        .flatMap { it.functionCalls() }
        .single { it.name == "finish_task" && it.args["result"] == "trip done" }
    assertEquals(mapOf("result" to "trip done"), rootFinish.args)
    assertEquals(2, rootModel.requests.size)
  }

  @Test
  fun taskMode_resumableRootReplyByInvocationId_finishesTheTask() = runBlocking {
    // Arrange: a resumable task root asks the user a question, which ends its turn.
    val rootModel =
      ModeScriptedModel(text("Which city?"), call("finish_task", mapOf("result" to "Paris")))
    val root = LlmAgent(name = "root_task", model = rootModel, mode = AgentMode.TASK)
    val runner = runnerFor(root, resumable = true)
    val question = runner.send("plan my trip")
    val invocationId = question.first { it.author == "root_task" }.invocationId

    // Act: the reply resumes that invocation by its ID.
    val reply =
      runner
        .runAsync(
          "u",
          "s",
          invocationId = invocationId,
          newMessage = Content.fromText(Role.USER, "Paris"),
        )
        .toList()

    // Assert: as in Python, the resumed task root runs and finishes its task.
    assertEquals(2, rootModel.requests.size)
    assertTrue(reply.flatMap { it.functionCalls() }.any { it.name == "finish_task" })
  }

  @Test
  fun taskMode_rootTransfer_runsTargetOnce() = runBlocking {
    // Arrange: a task root hands off to its chat sub-agent.
    val helperModel = ModeScriptedModel(text("helper here"))
    val root =
      LlmAgent(
        name = "root_task",
        model = ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper"))),
        mode = AgentMode.TASK,
        subAgents = listOf(LlmAgent("helper", helperModel)),
      )

    // Act
    val events = runnerFor(root).send("go")

    // Assert: as in Python, the task stops at its transfer and only the node loop runs the target.
    assertEquals(1, helperModel.requests.size)
    assertEquals(1, events.count { it.author == "helper" && it.contentText() == "helper here" })
  }

  @Test
  fun taskMode_followUpAfterRootTransfer_reachesTheTargetWithItsHistory() = runBlocking {
    // Arrange: a task root hands off to its chat sub-agent on the first turn.
    val helperModel = ModeScriptedModel(text("helper here"))
    val root =
      LlmAgent(
        name = "root_task",
        model = ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "helper"))),
        mode = AgentMode.TASK,
        subAgents = listOf(LlmAgent("helper", helperModel)),
      )
    val runner = runnerFor(root)
    val unused = runner.send("first question")

    // Act
    val unusedFollowUp = runner.send("second question")

    // Assert: as in Python, the helper answers in the scope it ran in and sees the follow-up.
    val texts = helperModel.requests.last().contents.map { it.text() }
    assertContains(texts, "second question")
    assertContains(texts, "helper here")
  }

  @Test
  fun chatMode_taskDelegation_answersCallWithTaskOutput() = runBlocking {
    // Arrange
    val worker =
      taskAgent("worker", ModeScriptedModel(call("finish_task", mapOf("result" to "done"))))
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "do it")), text("all done")),
        listOf(worker),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert
    assertEquals(
      listOf("root_agent", "worker", "worker", "user", "root_agent"),
      events.map { it.author },
    )
    assertEquals(mapOf("result" to "done"), events[2].output)
    assertEquals(mapOf("result" to "done"), events[3].functionResponses().single().response)
    assertEquals("all done", events.last().contentText())
  }

  @Test
  fun chatMode_twoTaskDelegations_runInTurn() = runBlocking {
    // Arrange
    val collector =
      taskAgent("collector", ModeScriptedModel(call("finish_task", mapOf("result" to "collected"))))
    val payer =
      taskAgent("payer", ModeScriptedModel(call("finish_task", mapOf("result" to "paid"))))
    val root =
      coordinator(
        ModeScriptedModel(
          call("collector", mapOf("request" to "collect")),
          call("payer", mapOf("request" to "pay")),
          text("Order placed."),
        ),
        listOf(collector, payer),
      )

    // Act
    val events = runnerFor(root).send("place order").contentful()

    // Assert
    assertEquals(
      listOf(
        "root_agent",
        "collector",
        "collector",
        "user",
        "root_agent",
        "payer",
        "payer",
        "user",
        "root_agent",
      ),
      events.map { it.author },
    )
    assertEquals("Order placed.", events.last().contentText())
  }

  @Test
  fun chatMode_turnMixingToolsAndTask_keepsEveryToolResponse() = runBlocking {
    // Arrange
    val worker =
      taskAgent("worker", ModeScriptedModel(call("finish_task", mapOf("result" to "worked"))))
    val tools =
      listOf(
        DummyTool("note_a", onRun = { _, _ -> mapOf("note" to "one") }),
        DummyTool("note_b", onRun = { _, _ -> mapOf("note" to "two") }),
      )
    val model =
      ModeScriptedModel(
        calls(
          "note_a" to mapOf("value" to "one"),
          "note_b" to mapOf("value" to "two"),
          "worker" to mapOf("request" to "run"),
        ),
        text("Combined turn complete."),
      )

    // Act
    val events = runnerFor(coordinator(model, listOf(worker), tools)).send("go")

    // Assert: the second request answers every call of the first turn.
    assertTrue(events.responseNames().containsAll(listOf("note_a", "note_b", "worker")))
    assertEquals("Combined turn complete.", events.contentful().last().contentText())
    assertEquals(2, model.requests.size)
    val answered =
      model.requests[1].contents.flatMap { it.parts }.mapNotNull { it.functionResponse?.name }
    assertEquals(listOf("note_a", "note_b", "worker"), answered.sorted())
  }

  @Test
  fun chatMode_toolsetCallBesideTask_isAnsweredBeforeTheNextRequest() = runBlocking {
    // Arrange: one turn calls a toolset tool and a task agent together.
    val worker =
      taskAgent("worker", ModeScriptedModel(call("finish_task", mapOf("result" to "worked"))))
    val note = DummyTool("note", onRun = { _, _ -> mapOf("ok" to true) })
    val toolset =
      object : Toolset {
        override suspend fun getTools(readonlyContext: ReadonlyContext?) = listOf(note)
      }
    val model =
      ModeScriptedModel(
        calls("note" to emptyMap(), "worker" to mapOf("request" to "run")),
        text("done"),
      )
    val root =
      LlmAgent(
        name = "root_agent",
        model = model,
        subAgents = listOf(worker),
        toolsets = listOf(toolset),
        mode = AgentMode.CHAT,
      )

    // Act
    val unused = runnerFor(root).send("go")

    // Assert: the toolset call runs, so the next request answers both calls.
    assertEquals(2, model.requests.size)
    val answered =
      model.requests[1].contents.flatMap { it.parts }.mapNotNull { it.functionResponse?.name }
    assertEquals(listOf("note", "worker"), answered.sorted())
  }

  @Test
  fun chatMode_maxSteps_holdsAcrossDelegationRounds() = runBlocking {
    // Arrange: a one-step coordinator whose model would delegate twice.
    val worker =
      taskAgent("worker", ModeScriptedModel(call("finish_task", mapOf("result" to "done"))))
    val model =
      ModeScriptedModel(
        call("worker", mapOf("request" to "first")),
        call("worker", mapOf("request" to "second")),
        text("all done"),
      )
    val root =
      LlmAgent(
        name = "root_agent",
        model = model,
        subAgents = listOf(worker),
        maxSteps = 1,
        mode = AgentMode.CHAT,
      )

    // Act
    val unused = runnerFor(root).send("go")

    // Assert: the one allowed step's delegation runs, and no second step starts.
    assertEquals(1, model.requests.size)
  }

  @Test
  fun chatMode_turnWithLongRunningToolAndTask_pausesAfterTask() = runBlocking {
    // Arrange
    val specialist =
      taskAgent("specialist", ModeScriptedModel(call("finish_task", mapOf("result" to "analysed"))))
    val longRunning = DummyTool("my_long_run", isLongRunning = true, onRun = { _, _ -> Unit })
    val model =
      ModeScriptedModel(
        calls("my_long_run" to mapOf("value" to "hello"), "specialist" to mapOf("request" to "go")),
        text("Resume complete."),
      )

    // Act
    val events =
      runnerFor(coordinator(model, listOf(specialist), listOf(longRunning)), resumable = true)
        .send("go")

    // Assert
    assertTrue("specialist" in events.responseNames())
    assertFalse("my_long_run" in events.responseNames())
    assertFalse(events.any { "Resume complete." in it.contentText() })
    assertTrue(events.any { it.author == "root_agent" && it.longRunningToolIds.isNotEmpty() })
  }

  @Test
  fun finishTask_invalidCall_returnsValidationErrorAndModelRetries() = runBlocking {
    // Arrange
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(
          call("finish_task", mapOf("label" to "spam")),
          call("finish_task", mapOf("label" to "spam", "score" to 9L)),
        ),
        outputSchema = LABEL_SCHEMA,
        outputKey = "answer",
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "classify")), text("all done")),
        listOf(worker),
      )
    val runner = runnerFor(root)

    // Act
    val events = runner.send("go").contentful()

    // Assert
    val rejection = events[2].functionResponses().single().response["error"] as String
    assertContains(rejection, "validation errors")
    assertEquals(mapOf("label" to "spam", "score" to 9L), events[4].output)
    val state = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s"))).state
    assertEquals(mapOf("label" to "spam", "score" to 9L), state["answer"])
  }

  @Test
  fun finishTask_wholeNumberDoubleForInteger_savesALong() = runBlocking {
    // Arrange: the score arrives as 9.0, the way Gson decodes the JSON number 9.
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(call("finish_task", mapOf("label" to "spam", "score" to 9.0))),
        outputSchema = LABEL_SCHEMA,
        outputKey = "answer",
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "classify")), text("all done")),
        listOf(worker),
      )
    val runner = runnerFor(root)

    // Act
    val events = runner.send("go").contentful()

    // Assert
    val expected = mapOf("label" to "spam", "score" to 9L)
    assertEquals(expected, events.single { it.output != null }.output)
    val state = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s"))).state
    assertEquals(expected, state["answer"])
  }

  @Test
  fun finishTask_wholeNumberDoubleInOneOfTheseFieldsSchema_savesALong() = runBlocking {
    // Arrange: the schema's own properties type `score`; its `anyOf` only says which field to send.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("score" to Schema(type = Type.INTEGER), "error" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("score")), Schema(required = listOf("error"))),
      )
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(call("finish_task", mapOf("score" to 9.0))),
        outputSchema = schema,
        outputKey = "answer",
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "classify")), text("all done")),
        listOf(worker),
      )
    val runner = runnerFor(root)

    // Act
    val unused = runner.send("go")

    // Assert
    val state = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s"))).state
    assertEquals(mapOf("score" to 9L), state["answer"])
  }

  @Test
  fun taskMode_delegatedAgent_seesOnlyDelegationAsFirstTurn() = runBlocking {
    // Arrange
    val workerModel = ModeScriptedModel(call("finish_task", mapOf("result" to "done")))
    val root =
      coordinator(
        ModeScriptedModel(
          text("let me delegate"),
          call("worker", mapOf("request" to "do the thing")),
          text("all done"),
        ),
        listOf(taskAgent("worker", workerModel)),
      )
    val runner = runnerFor(root)

    // Act
    val unusedFirst = runner.send("first turn")
    val unusedSecond = runner.send("second turn")

    // Assert
    val contents = workerModel.requests.single().contents
    assertEquals(1, contents.size)
    assertEquals(Role.USER, contents.single().role)
    assertEquals("{\"request\":\"do the thing\"}", contents.single().text())
  }

  @Test
  fun taskMode_textReply_resumesOnNextUserMessage() = runBlocking {
    // Arrange
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(text("which thing?"), call("finish_task", mapOf("result" to "done"))),
        outputKey = "answer",
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "do the thing")), text("all done")),
        listOf(worker),
      )
    val runner = runnerFor(root)

    // Act
    val first = runner.send("go").contentful()
    val answerAfterQuestion =
      checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s"))).state["answer"]
    val second = runner.send("the second one").contentful()

    // Assert: the question is not the task's output; only `finish_task` sets it.
    assertNull(answerAfterQuestion)
    assertEquals(listOf("root_agent", "worker"), first.map { it.author })
    assertEquals("which thing?", first.last().contentText())
    assertEquals(listOf("worker", "worker", "user", "root_agent"), second.map { it.author })
    val state = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s"))).state
    assertEquals(mapOf("result" to "done"), state["answer"])
  }

  @Test
  fun taskMode_rewoundDelegation_isNotResumed() = runBlocking {
    // Arrange: the first turn leaves a task waiting, then a rewind removes that whole invocation.
    val worker = taskAgent("worker", ModeScriptedModel(text("which thing?")))
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "do the thing")), text("fresh start")),
        listOf(worker),
      )
    val runner = runnerFor(root)
    val first = runner.send("go")
    runner.rewindAsync("u", "s", checkNotNull(first.first().invocationId))

    // Act
    val second = runner.send("something else")

    // Assert: the rewound task neither resumes nor claims the new message.
    assertTrue(second.none { it.author == "worker" })
    val session = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s")))
    assertNull(session.events.last { it.contentText() == "something else" }.isolationScope)
  }

  @Test
  fun singleTurnTool_parentCall_isAnsweredByChildNode() = runBlocking {
    // Arrange
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(text("helper result")),
        description = "A helper.",
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("helper", mapOf("request" to "hi")), text("all done")),
        listOf(helper),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert
    assertEquals(
      listOf("root_agent", "helper", "root_agent", "root_agent"),
      events.map { it.author },
    )
    assertEquals(
      mapOf("result" to "helper result"),
      events[2].functionResponses().single().response,
    )
  }

  @Test
  fun singleTurnTool_helperPausedOnLongRunningCall_resumesThroughTheParentsCall() = runBlocking {
    // Arrange: the helper pauses inside its parent's tool call, with and without resumability.
    for (resumable in listOf(false, true)) {
      val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
      val helper =
        LlmAgent(
          "helper",
          ModeScriptedModel(call("approve", emptyMap()), text("approved")),
          description = "A helper.",
          tools = listOf(approve),
          mode = AgentMode.SINGLE_TURN,
        )
      val runner =
        runnerFor(
          coordinator(
            ModeScriptedModel(call("helper", mapOf("request" to "hi")), text("all done")),
            listOf(helper),
          ),
          resumable = resumable,
        )
      val approvalId =
        runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id
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
      val second = runner.runAsync("u", "s", newMessage = answer).toList()

      // Assert: as in Python, the parent replays its helper call, which finishes and answers it.
      assertEquals(
        mapOf("result" to "approved"),
        second.flatMap { it.functionResponses() }.single { it.name == "helper" }.response,
        "resumable=$resumable",
      )
      assertEquals("all done", second.contentful().last().content?.parts?.single()?.text)
    }
  }

  @Test
  fun singleTurnTool_helperPausedBesideAToolCall_answersBothCallsOnResume() = runBlocking {
    // Arrange: one model turn calls a regular tool and the helper, which pauses on an approval.
    val lookup = DummyTool("lookup", onRun = { _, _ -> mapOf("ok" to true) })
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(call("approve", emptyMap()), text("approved")),
        description = "A helper.",
        tools = listOf(approve),
        mode = AgentMode.SINGLE_TURN,
      )
    val bothCalls =
      Content(
        Role.MODEL,
        listOf(
          Part(functionCall = FunctionCall("lookup", emptyMap())),
          Part(functionCall = FunctionCall("helper", mapOf("request" to "hi"))),
        ),
      )
    val rootModel = ModeScriptedModel(bothCalls, text("all done"))
    val runner = runnerFor(coordinator(rootModel, listOf(helper), tools = listOf(lookup)))
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id
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
    val unused = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: as in Python, the next request answers both calls, not only the helper's.
    val answered =
      rootModel.requests
        .last()
        .contents
        .flatMap { it.parts }
        .mapNotNull { it.functionResponse?.name }
    assertTrue("lookup" in answered && "helper" in answered, "$answered")
  }

  @Test
  fun singleTurnAgent_outsideANode_getsNoIdentityOrTransferInstructions() = runBlocking {
    // Arrange: a single-turn agent runs as a sequential step, so no node sets its mode.
    val model = ModeScriptedModel(text("done"))
    val step = LlmAgent("step", model, description = "A step.", mode = AgentMode.SINGLE_TURN)
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode =
              Workflow(
                "wf",
                listOf(Edge(Start, SequentialAgent("steps", subAgents = listOf(step)))),
              ),
          )
      )

    // Act
    val unused = runner.send("go")

    // Assert: as in Python, its own mode decides both, so it gets neither.
    val instruction = model.requests.single().config.systemInstruction?.text().orEmpty()
    assertTrue("Your internal name is" !in instruction, instruction)
    assertTrue("other agents to transfer to" !in instruction, instruction)
  }

  @Test
  fun singleTurnAgent_outsideANode_keepsItsHistory() = runBlocking {
    // Arrange: a single-turn agent runs as a sequential step, so no node makes it history-free.
    val model = ModeScriptedModel(text("one"), text("two"))
    val step = LlmAgent("step", model, mode = AgentMode.SINGLE_TURN)
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode =
              Workflow(
                "wf",
                listOf(Edge(Start, SequentialAgent("steps", subAgents = listOf(step)))),
              ),
          )
      )
    val unused = runner.send("first")

    // Act
    val unusedSecond = runner.send("second")

    // Assert: as in Python, only a node placement drops history, so the step sees the first turn.
    val texts = model.requests.last().contents.flatMap { it.parts }.mapNotNull { it.text }
    assertTrue("first" in texts, "$texts")
  }

  @Test
  fun chatAgentMadeByAParentBuiltAfterTheWorkflow_isRejectedWhenTheWorkflowRuns() {
    // Arrange: the agent has no parent while the graph is built, so it passes as single-turn.
    val model = ModeScriptedModel(text("unused"))
    val agent = LlmAgent("late_chat", model)
    val capture = ModeCaptureNode()
    val workflow = Workflow("wf", listOf(Edge(Start, capture), Edge(capture, agent)))
    val unusedParent =
      LlmAgent("parent", ModeScriptedModel(text("unused")), subAgents = listOf(agent))
    val runner = InMemoryRunner(app = App("app", rootNode = workflow))

    // Act
    val error = assertFailsWith<GraphValidationException> { runBlocking { runner.send("go") } }

    // Assert
    assertContains(error.message.orEmpty(), "'late_chat'")
    assertTrue(model.requests.isEmpty())
  }

  @Test
  fun finishTask_missingScalarResult_isRejected() = runBlocking {
    // Arrange: a string schema wraps the answer in `result`, which the first call omits.
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(
          call("finish_task", emptyMap()),
          call("finish_task", mapOf("result" to "done")),
        ),
        outputSchema = Schema(type = Type.STRING),
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "summarize")), text("all done")),
        listOf(worker),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert
    val rejection = events[2].functionResponses().single().response["error"] as String
    assertContains(rejection, "validation errors")
    assertEquals("done", events[4].output)
    val parameters = worker.tools.single { it.name == "finish_task" }.declaration()?.parameters
    assertEquals(listOf("result"), parameters?.required)
    assertEquals(Type.STRING, parameters?.properties?.get("result")?.type)
  }

  @Test
  fun finishTask_quotedNumberForIntegerProperty_isRejected() = runBlocking {
    // Arrange: the arguments are an object, whose integer property does not accept the text "9".
    val worker =
      taskAgent(
        "worker",
        ModeScriptedModel(
          call("finish_task", mapOf("label" to "spam", "score" to "9")),
          call("finish_task", mapOf("label" to "spam", "score" to 9L)),
        ),
        outputSchema = LABEL_SCHEMA,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "classify")), text("all done")),
        listOf(worker),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert
    val rejection = events[2].functionResponses().single().response["error"] as String
    assertContains(rejection, "validation errors")
    assertEquals(mapOf("label" to "spam", "score" to 9L), events[4].output)
  }

  @Test
  fun singleTurnTool_inputBreakingSchema_answersWithError() = runBlocking {
    // Arrange: the helper takes an integer count, and the parent passes the count as text.
    val helperModel = ModeScriptedModel(text("unused"))
    val helper =
      LlmAgent(
        "helper",
        helperModel,
        description = "A helper.",
        inputSchema =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("count" to Schema(type = Type.INTEGER)),
            required = listOf("count"),
          ),
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("helper", mapOf("count" to "3")), text("all done")),
        listOf(helper),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert: the call is answered with the validation error, and the helper never runs.
    val response = events.flatMap { it.functionResponses() }.single { it.name == "helper" }
    assertContains(response.response["result"].toString(), "Error validating input")
    assertTrue(helperModel.requests.isEmpty())
  }

  @Test
  fun modeSubAgentsUnderAgentRoot_areRejectedWhenTheRunnerIsBuilt() {
    // Arrange: a sequential root passed as an agent would run the coordinator in the classic flow.
    val helper = LlmAgent("helper", ModeScriptedModel(text("unused")), mode = AgentMode.SINGLE_TURN)
    val coordinatorModel = ModeScriptedModel(text("unused"))
    val coordinator =
      LlmAgent(
        name = "root_agent",
        model = coordinatorModel,
        subAgents = listOf(helper, taskAgent("worker", ModeScriptedModel(text("unused")))),
      )
    val app =
      App("app", rootAgent = SequentialAgent(name = "steps", subAgents = listOf(coordinator)))

    // Act
    val error = assertFailsWith<IllegalArgumentException> { InMemoryRunner(app = app) }

    // Assert
    assertContains(error.message.orEmpty(), "Agents [helper, worker] set a mode")
    assertTrue(coordinatorModel.requests.isEmpty())
  }

  @Test
  fun runAsync_plainReplyToPausedTask_continuesInvocation() = runBlocking {
    // Arrange: a task node asks a question in plain text, which pauses the workflow.
    val workerModel =
      ModeScriptedModel(
        text("Please provide the secret code:"),
        call("finish_task", mapOf("result" to "Success with code")),
      )
    val worker = taskAgent("my_task_agent", workerModel)
    val runner =
      InMemoryRunner(app = App("app", rootNode = Workflow("wf", listOf(Edge(Start, worker)))))
    val first = runner.send("start workflow")
    val paused = first.single { it.contentText() == "Please provide the secret code:" }

    // Act
    val second = runner.send("my_secret_code_123")
    val third = runner.send("another request")

    // Assert: the reply finishes the paused invocation, and the next message starts a fresh one.
    val finished = second.single { it.output == mapOf("result" to "Success with code") }
    assertEquals(paused.invocationId, finished.invocationId)
    assertContains(workerModel.requests[1].contents.map { it.text() }, "my_secret_code_123")
    assertTrue(third.isNotEmpty())
    assertTrue(third.all { it.invocationId != paused.invocationId })
  }

  @Test
  fun runAsync_coordinatorRootPlainReplyToPausedTask_continuesInvocation() = runBlocking {
    // Arrange: an LlmAgent root delegates to a task agent, which asks a question.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val root =
      coordinator(
        ModeScriptedModel(call("worker", mapOf("request" to "do it")), text("all done")),
        listOf(taskAgent("worker", workerModel)),
      )
    val runner = runnerFor(root)
    val paused = runner.send("go").single { it.contentText() == "which one?" }

    // Act
    val second = runner.send("the second one")

    // Assert: the reply joins the paused task's invocation.
    assertTrue(second.isNotEmpty())
    assertTrue(second.all { it.invocationId == paused.invocationId })
  }

  @Test
  fun runAsync_coordinatorRootAnswerToOtherCallWhileTaskPaused_reachesTheCoordinator() =
    runBlocking {
      // Arrange: an approval is left open, then a later delegation pauses on a question.
      val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
      val coordinatorModel =
        ModeScriptedModel(
          call("approve", emptyMap()),
          call("worker", mapOf("request" to "do it")),
          text("all done"),
        )
      val workerModel =
        ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
      val root =
        coordinator(
          coordinatorModel,
          listOf(taskAgent("worker", workerModel)),
          tools = listOf(approve),
        )
      val runner = runnerFor(root)
      val approvalCall =
        runner.send("approve it").single { e -> e.functionCalls().any { it.name == "approve" } }
      val approvalId = approvalCall.functionCalls().single().id
      val unused = runner.send("now the task")
      val coordinatorRequestsBeforeAnswer = coordinatorModel.requests.size

      // Act
      val unusedAnswer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()
      val coordinatorRequestsAfterAnswer = coordinatorModel.requests.size
      val workerRequestsAfterAnswer = workerModel.requests.size
      val reply = runner.send("the second one")

      // Assert: the answer keeps the unscoped call's scope and waits for the paused task without a
      // model call; the reply then finishes the task, and the coordinator sees both answers.
      val session = checkNotNull(runner.sessionService.getSession(SessionKey("app", "u", "s")))
      val answer = session.events.single { e -> e.functionResponses().any { it.id == approvalId } }
      assertNull(answer.isolationScope)
      assertEquals(approvalCall.invocationId, answer.invocationId)
      assertEquals(coordinatorRequestsBeforeAnswer, coordinatorRequestsAfterAnswer)
      assertEquals(1, workerRequestsAfterAnswer)
      assertTrue(reply.any { it.contentText() == "all done" })
      val coordinatorResponses =
        coordinatorModel.requests
          .last()
          .contents
          .flatMap { it.parts }
          .mapNotNull { it.functionResponse?.name }
      assertEquals(listOf("approve", "worker"), coordinatorResponses)
    }

  @Test
  fun runAsync_replyToTaskDelegatedBesideALongRunningCall_reachesTheTask() = runBlocking {
    // Arrange: one coordinator turn calls a long-running tool and delegates a task that asks.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val root = approvingCoordinator(approveAndDelegateModel(), workerModel)
    val runner = runnerFor(root, resumable = true)
    val first = runner.send("go")
    val paused = first.single { it.contentText() == "which one?" }
    val approvalId = first.flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val reply = runner.send("the second one")
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the reply finishes the task while the approval stays open, and the answer to the
    // approval then lets the coordinator go on.
    val taskAnswer = reply.single { e -> e.functionResponses().any { it.name == "worker" } }
    assertEquals(paused.invocationId, taskAnswer.invocationId)
    assertContains(workerModel.requests[1].contents.map { it.text() }, "the second one")
    assertFalse(reply.any { it.contentText() == "all done" })
    assertTrue(answer.any { it.contentText() == "all done" })
  }

  @Test
  fun runAsync_answerToACallBesideATaskThatAsked_leavesTheTaskWaiting() = runBlocking {
    // Arrange: one coordinator turn calls a long-running tool and delegates a task that asks.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val root = approvingCoordinator(approveAndDelegateModel(), workerModel)
    val runner = runnerFor(root, resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()
    val reply = runner.send("the second one")

    // Assert: the answer does not make the task ask again; the reply then finishes it.
    assertTrue(answer.none { it.author == "worker" })
    assertEquals(2, workerModel.requests.size)
    assertTrue(reply.any { it.contentText() == "all done" })
  }

  @Test
  fun runAsync_graphCoordinatorReplyToTaskBesideALongRunningCall_reachesTheTask() = runBlocking {
    // Arrange: a coordinator graph node calls a long-running tool and delegates a task that asks.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val coordinator = approvingCoordinator(approveAndDelegateModel(), workerModel)
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, coordinator))), resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val reply = runner.send("the second one")
    val workerRequestsAfterReply = workerModel.requests.size
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the reply reaches the task first while the workflow keeps waiting on the approval;
    // the approval answer then lets the coordinator go on.
    assertEquals(2, workerRequestsAfterReply)
    assertContains(workerModel.requests[1].contents.map { it.text() }, "the second one")
    assertFalse(reply.any { it.contentText() == "all done" })
    assertTrue(reply.none { it.author == "wf" && it.actions.endOfAgent })
    assertTrue(answer.any { it.contentText() == "all done" })
  }

  @Test
  fun runAsync_nestedGraphCoordinatorReplyToTask_keepsTheOuterWorkflowWaiting() = runBlocking {
    // Arrange: the coordinator runs inside `inner`, which `after` follows in the outer workflow.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val coordinator = approvingCoordinator(approveAndDelegateModel(), workerModel)
    val inner = Workflow("inner", listOf(Edge(Start, coordinator)))
    val after = ModeCountingNode("after")
    val runner =
      runnerFor(Workflow("outer", listOf(Edge(Start, inner), Edge(inner, after))), resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val unusedReply = runner.send("the second one")
    val afterRunsAfterReply = after.runs
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the reply finishes the task, but `after` waits for the approval.
    assertEquals(2, workerModel.requests.size)
    assertEquals(0, afterRunsAfterReply)
    assertEquals(1, after.runs)
    assertTrue(answer.any { it.contentText() == "all done" })
  }

  @Test
  fun runAsync_graphCoordinatorTaskAsksAgain_answerToAnotherBranchLeavesItWaiting() = runBlocking {
    // Arrange: the task asks twice, and `other` asks on its own branch.
    val workerModel =
      ModeScriptedModel(
        text("which one?"),
        text("which color?"),
        call("finish_task", mapOf("result" to "done")),
      )
    val coordinator = approvingCoordinator(approveAndDelegateModel(), workerModel)
    val runner =
      runnerFor(
        Workflow("wf", listOf(Edge(Start, coordinator), Edge(Start, ModeAskingNode("other")))),
        resumable = true,
      )
    val unusedGo = runner.send("go")
    val unusedReply = runner.send("the second one")
    val otherAnswer = answerTo(RequestInput.FUNCTION_CALL_NAME, "ask_other")

    // Act
    val answer = runner.runAsync("u", "s", newMessage = otherAnswer).toList()

    // Assert: `other` finishes, while the task keeps its question and the workflow keeps waiting.
    assertTrue(answer.any { it.output == "answered" })
    assertEquals(2, workerModel.requests.size)
    assertTrue(answer.none { it.author == "wf" && it.actions.endOfAgent })
  }

  @Test
  fun runAsync_taskNodeWithAHelperMessagedWhileACallIsOpen_keepsTheWorkflowWaiting() = runBlocking {
    // Arrange: a task node asks a single-turn helper, then calls the long-running `approve`.
    val helper =
      LlmAgent(
        name = "helper",
        model = ModeScriptedModel(text("a hint")),
        description = "Helps.",
        mode = AgentMode.SINGLE_TURN,
      )
    val taskModel =
      ModeScriptedModel(
        call("helper", mapOf("request" to "hint?")),
        call("approve", emptyMap()),
        call("finish_task", mapOf("result" to "done")),
      )
    val task =
      LlmAgent(
        name = "task",
        model = taskModel,
        description = "Does task.",
        subAgents = listOf(helper),
        tools = listOf(DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })),
        mode = AgentMode.TASK,
      )
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, task))), resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val message = runner.send("any news?")
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the message is not a reply to the helper, so the task waits without running again,
    // and the workflow does not end while the approval is open.
    assertTrue(message.none { it.author == "task" })
    assertTrue(message.none { it.author == "wf" && it.actions.endOfAgent })
    assertTrue(answer.any { it.output == mapOf("result" to "done") })
  }

  @Test
  fun runAsync_graphNodeWithTwoOpenCallsOneAnswered_keepsWaitingOnTheOther() = runBlocking {
    // Arrange: a graph node calls two long-running tools in one turn, and `after` follows it.
    val agent =
      LlmAgent(
        name = "agent",
        model =
          ModeScriptedModel(
            calls("approve_a" to emptyMap(), "approve_b" to emptyMap()),
            text("all done"),
          ),
        tools =
          listOf(
            DummyTool("approve_a", isLongRunning = true, onRun = { _, _ -> Unit }),
            DummyTool("approve_b", isLongRunning = true, onRun = { _, _ -> Unit }),
          ),
      )
    val after = ModeCountingNode("after")
    val runner =
      runnerFor(Workflow("wf", listOf(Edge(Start, agent), Edge(agent, after))), resumable = true)
    val callIds = runner.send("go").flatMap { it.functionCalls() }.associate { it.name to it.id }

    // Act
    val first =
      runner.runAsync("u", "s", newMessage = answerTo("approve_a", callIds["approve_a"])).toList()
    val afterRunsAfterFirst = after.runs
    val second =
      runner.runAsync("u", "s", newMessage = answerTo("approve_b", callIds["approve_b"])).toList()

    // Assert: the first answer leaves the node waiting on the second call; the second lets it go
    // on.
    assertEquals(0, afterRunsAfterFirst)
    assertTrue(first.none { it.author == "wf" && it.actions.endOfAgent })
    assertTrue(second.any { it.contentText() == "all done" })
    assertEquals(1, after.runs)
  }

  @Test
  fun runAsync_coordinatorReachedByAHandoffReplyToTask_reachesTheTask() = runBlocking {
    // Arrange: graph node `first` hands off to a child coordinator, whose task asks.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val child = approvingCoordinator(approveAndDelegateModel(), workerModel, name = "child")
    val first =
      LlmAgent(
        name = "first",
        model = ModeScriptedModel(call("transfer_to_agent", mapOf("agent_name" to "child"))),
        subAgents = listOf(child),
      )
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, first))), resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val reply = runner.send("the second one")
    val workerRequestsAfterReply = workerModel.requests.size
    val answer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the reply reaches the task while the workflow keeps waiting on the approval.
    assertEquals(2, workerRequestsAfterReply)
    assertContains(workerModel.requests[1].contents.map { it.text() }, "the second one")
    assertTrue(reply.none { it.author == "wf" && it.actions.endOfAgent })
    assertTrue(answer.any { it.contentText() == "all done" })
  }

  @Test
  fun runAsync_handoffBesideTwoOwnCallsOneAnswered_keepsWaitingOnTheOther() = runBlocking {
    // Arrange: graph node `first` calls two long-running tools and hands off in one turn.
    val childModel = ModeScriptedModel(text("from child"))
    val first =
      LlmAgent(
        name = "first",
        model =
          ModeScriptedModel(
            calls(
              "approve_a" to emptyMap(),
              "approve_b" to emptyMap(),
              "transfer_to_agent" to mapOf("agent_name" to "child"),
            )
          ),
        subAgents = listOf(LlmAgent(name = "child", model = childModel, description = "Helps.")),
        tools =
          listOf(
            DummyTool("approve_a", isLongRunning = true, onRun = { _, _ -> Unit }),
            DummyTool("approve_b", isLongRunning = true, onRun = { _, _ -> Unit }),
          ),
      )
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, first))), resumable = true)
    val callIds = runner.send("go").flatMap { it.functionCalls() }.associate { it.name to it.id }

    // Act
    val answer =
      runner.runAsync("u", "s", newMessage = answerTo("approve_a", callIds["approve_a"])).toList()

    // Assert: `first` still waits on `approve_b`, so the handoff waits too.
    assertTrue(childModel.requests.isEmpty())
    assertTrue(answer.none { it.author == "wf" && it.actions.endOfAgent })
  }

  @Test
  fun runAsync_deferredHandoffAfterAReplyToTask_waitsForTheOpenCall() = runBlocking {
    // Arrange: one coordinator turn calls `approve`, delegates a task that asks, and hands off.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val childModel = ModeScriptedModel(text("from child"))
    val coordinator =
      LlmAgent(
        name = "root_agent",
        model =
          ModeScriptedModel(
            calls(
              "approve" to emptyMap(),
              "worker" to mapOf("request" to "do it"),
              "transfer_to_agent" to mapOf("agent_name" to "child"),
            )
          ),
        subAgents =
          listOf(
            taskAgent("worker", workerModel),
            LlmAgent(name = "child", model = childModel, description = "Helps."),
          ),
        tools = listOf(DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, coordinator))), resumable = true)
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id

    // Act
    val reply = runner.send("the second one")
    val childRequestsAfterReply = childModel.requests.size
    val unusedAnswer = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the reply finishes the task, and the handoff waits for the approval.
    assertEquals(2, workerModel.requests.size)
    assertEquals(0, childRequestsAfterReply)
    assertTrue(reply.none { it.author == "wf" && it.actions.endOfAgent })
    assertEquals(1, childModel.requests.size)
  }

  @Test
  fun runAsync_waitForOutputNodeThatWroteStateBeforeATask_replyReachesTheTask() = runBlocking {
    // Arrange: a custom node writes state, then runs a task that asks.
    val workerModel =
      ModeScriptedModel(text("which one?"), call("finish_task", mapOf("result" to "done")))
    val worker = taskAgent("worker", workerModel)
    val driver =
      object : Node {
        override val name = "driver"
        override val rerunOnResume = true
        override val waitForOutput = true

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          context.updateState("touched", true)
          emit(context.runNode(worker, nodeInput = "do it", raiseOnWait = true))
        }
      }
    val runner = runnerFor(Workflow("wf", listOf(Edge(Start, driver))), resumable = true)
    val unusedGo = runner.send("go")

    // Act
    val reply = runner.send("the second one")

    // Assert: the node runs again, so the task gets the reply and finishes.
    assertEquals(2, workerModel.requests.size)
    val driverOutput = reply.single { it.nodeInfo?.path == "wf@1/driver@1" && it.output != null }
    assertEquals(mapOf("result" to "done"), driverOutput.output)
  }

  @Test
  fun runAsync_functionResponseWhileTaskPaused_doesNotBorrowTaskInvocation() = runBlocking {
    // Arrange
    val (runner, approvalId, paused) = pausedTaskWithEarlierApproval()

    // Act
    val third = runner.runAsync("u", "s", newMessage = approvalAnswer(approvalId)).toList()

    // Assert: the response belongs to the call's invocation, not to the paused task's.
    assertTrue(third.isNotEmpty())
    assertTrue(third.all { it.invocationId != paused.invocationId })
  }

  @Test
  fun runAsync_functionResponseWithoutIdWhileTaskPaused_borrowsTaskInvocation() = runBlocking {
    // Arrange
    val (runner, _, paused, workerModel) = pausedTaskWithEarlierApproval()

    // Act
    val third = runner.runAsync("u", "s", newMessage = approvalAnswer(id = null)).toList()

    // Assert: without an id nothing ties the response to a call, so it joins the paused task, and
    // the task sees it.
    assertTrue(third.isNotEmpty())
    assertTrue(third.all { it.invocationId == paused.invocationId })
    val lastRequest = workerModel.requests.last().contents.flatMap { it.parts }
    assertTrue(lastRequest.any { it.functionResponse?.name == "approve" }, "$lastRequest")
  }

  /** A runner whose task node paused on [paused] while an earlier `approve` call is still open. */
  private data class PausedTaskFixture(
    val runner: InMemoryRunner,
    val approvalId: String?,
    val paused: Event,
    val workerModel: ModeScriptedModel,
  )

  /**
   * Runs one invocation that leaves an `approve` call open, then a second whose task node pauses on
   * a plain question.
   */
  private suspend fun pausedTaskWithEarlierApproval(): PausedTaskFixture {
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val workerModel =
      ModeScriptedModel(
        call("approve", emptyMap()),
        text("Please provide the secret code:"),
        call("finish_task", mapOf("result" to "done")),
      )
    val worker =
      LlmAgent(
        name = "my_task_agent",
        model = workerModel,
        tools = listOf(approve),
        mode = AgentMode.TASK,
      )
    val runner =
      InMemoryRunner(app = App("app", rootNode = Workflow("wf", listOf(Edge(Start, worker)))))
    val first = runner.send("start first invocation")
    val approvalId = first.flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val second =
      runner
        .runAsync(
          "u",
          "s",
          invocationId = "paused_task_invocation",
          newMessage = Content.fromText(Role.USER, "start second invocation"),
        )
        .toList()
    val paused = second.single { it.contentText() == "Please provide the secret code:" }
    return PausedTaskFixture(runner, approvalId, paused, workerModel)
  }

  private fun answerTo(name: String, id: String?) =
    Content(
      Role.USER,
      listOf(
        Part(
          functionResponse = FunctionResponse(id = id, name = name, response = mapOf("ok" to true))
        )
      ),
    )

  private fun approvalAnswer(id: String?) = answerTo("approve", id)

  @Test
  fun taskMode_node_handsFinishArgumentsToNextNode() = runBlocking {
    // Arrange
    val story = mapOf("title" to "Story", "content" to "Once upon a time")
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("title" to Schema(type = Type.STRING), "content" to Schema(type = Type.STRING)),
      )
    val writer =
      taskAgent("writer", ModeScriptedModel(call("finish_task", story)), outputSchema = schema)
    val capture = ModeCaptureNode()

    // Act
    val events = runWorkflow("write", Edge(Start, writer), Edge(writer, capture))

    // Assert
    assertEquals(story, capture.received)
    val scope = events.first { it.author == "writer" }.isolationScope
    assertEquals("wf@1/writer@1", scope)
  }

  @Test
  fun taskMode_nodeWithoutDelegatingCall_startsFromNodeInput() = runBlocking {
    // Arrange
    val model = ModeScriptedModel(call("finish_task", mapOf("result" to "ok")))
    val writer = taskAgent("writer", model)

    // Act
    val unused = runWorkflow("draft the memo", Edge(Start, writer))

    // Assert: the node input stands in for the missing delegating call, and no nudge is added.
    val contents = model.requests.first().contents
    assertEquals(listOf("draft the memo"), contents.first().parts.mapNotNull { it.text })
  }

  @Test
  fun taskMode_dynamicDispatch_runsInOwnScopeAndReturnsFinishOutput() = runBlocking {
    // Arrange
    val worker =
      taskAgent("task_agent", ModeScriptedModel(call("finish_task", mapOf("result" to "dynamic"))))

    // Act
    val events = runWorkflow("start", Edge(Start, ModeDriverNode(worker, "go")))

    // Assert: the task runs in a scope of its own, not the driver's.
    assertTrue(events.any { (it.output as? Map<*, *>)?.get("result") == "dynamic" })
    val scope = events.first { it.author == "task_agent" }.isolationScope
    assertEquals("wf@1/driver@1/task_agent@1", scope)
  }

  @Test
  fun graphOf_subAgentWithoutModeAfterNonStartNode_isRejected() {
    // Arrange: a sub-agent without a mode runs as a chat agent.
    val helper = LlmAgent("helper", ModeScriptedModel(text("unused")))
    val unusedParent = coordinator(ModeScriptedModel(text("unused")), listOf(helper))
    val capture = ModeCaptureNode()

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow("bad", listOf(Edge(Start, capture), Edge(capture, helper)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "AgentMode.CHAT following node 'capture'")
  }

  @Test
  fun construction_dottedTaskSubAgent_throwsNamingTheAgent() {
    // Arrange: a task sub-agent runs as a child node, so its name becomes part of a node path.
    val worker = taskAgent("billing.desk", ModeScriptedModel(text("unused")))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        coordinator(ModeScriptedModel(text("unused")), listOf(worker))
      }

    // Assert
    assertContains(error.message.orEmpty(), "'billing.desk'")
  }

  @Test
  fun construction_dottedSingleTurnSubAgent_throwsNamingTheAgent() {
    // Arrange
    val helper =
      LlmAgent("notes.writer", ModeScriptedModel(text("unused")), mode = AgentMode.SINGLE_TURN)

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        coordinator(ModeScriptedModel(text("unused")), listOf(helper))
      }

    // Assert
    assertContains(error.message.orEmpty(), "'notes.writer'")
  }

  @Test
  fun construction_rejectedParent_leavesSiblingSubAgentFreeToReuse() {
    // Arrange: the valid sibling comes first, so a late check would already have linked it.
    val sibling = taskAgent("valid_worker", ModeScriptedModel(text("unused")))
    val dotted = taskAgent("billing.desk", ModeScriptedModel(text("unused")))
    val unused =
      assertFailsWith<IllegalArgumentException> {
        coordinator(ModeScriptedModel(text("unused")), listOf(sibling, dotted))
      }

    // Act
    val parent = coordinator(ModeScriptedModel(text("unused")), listOf(sibling))

    // Assert
    assertEquals(listOf("valid_worker"), parent.subAgents.map { it.name })
  }

  @Test
  fun construction_dottedChatOrUnsetModeSubAgent_isAllowed() {
    // Arrange: transfer targets of a plain root agent run inline, so any agent name works.
    val chat = LlmAgent("billing.desk", ModeScriptedModel(text("unused")), mode = AgentMode.CHAT)
    val unset = LlmAgent("notes.writer", ModeScriptedModel(text("unused")))

    // Act
    val parent = coordinator(ModeScriptedModel(text("unused")), listOf(chat, unset))

    // Assert
    assertEquals(listOf("billing.desk", "notes.writer"), parent.subAgents.map { it.name })
  }

  @Test
  fun graphOf_dottedTransferTargetUnderAgentNode_isRejected() {
    // Arrange: inside a workflow, a transfer target runs as a child node of the agent node.
    val target = LlmAgent("billing.desk", ModeScriptedModel(text("unused")))
    val lead = coordinator(ModeScriptedModel(text("unused")), listOf(target))

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Workflow("wf", listOf(Edge(Start, lead))) }

    // Assert
    assertContains(error.message.orEmpty(), "[billing.desk]")
  }

  @Test
  fun graphOf_dottedSubAgentUnderSequentialAgentNode_isAllowed() {
    // Arrange: a sequential agent runs its sub-agents inline, so their names stay off node paths.
    val step = LlmAgent("billing.desk", ModeScriptedModel(text("unused")))
    val pipeline = SequentialAgent(name = "pipeline", subAgents = listOf(step))

    // Act: building the workflow does not throw.
    val unused = Workflow("wf", listOf(Edge(Start, pipeline)))
  }

  @Test
  fun graphOf_dottedGrandchildThroughLlmAgents_isRejected() {
    // Arrange: a transfer target's own sub-agents are transfer targets too.
    val grandchild = LlmAgent("billing.desk", ModeScriptedModel(text("unused")))
    val child =
      LlmAgent("support", ModeScriptedModel(text("unused")), subAgents = listOf(grandchild))
    val lead = coordinator(ModeScriptedModel(text("unused")), listOf(child))

    // Act
    val error =
      assertFailsWith<GraphValidationException> { Workflow("wf", listOf(Edge(Start, lead))) }

    // Assert
    assertContains(error.message.orEmpty(), "[billing.desk]")
  }

  @Test
  fun graphOf_dottedSubAgentOfParallelWrappedLlmAgent_isRejected() {
    // Arrange: each item of a parallel node runs the wrapped agent, which runs transfers as nodes.
    val target = LlmAgent("billing.desk", ModeScriptedModel(text("unused")))
    val lead = coordinator(ModeScriptedModel(text("unused")), listOf(target))

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow("wf", listOf(Edge(Start, parallelNode(lead))))
      }

    // Assert
    assertContains(error.message.orEmpty(), "[billing.desk]")
  }

  @Test
  fun graphOf_dottedChildOfSequentialTransferTarget_isAllowed() {
    // Arrange: the sequential target runs as a node, but runs its own sub-agents inline.
    val step = LlmAgent("billing.desk", ModeScriptedModel(text("unused")))
    val pipeline = SequentialAgent(name = "pipeline", subAgents = listOf(step))
    val lead = coordinator(ModeScriptedModel(text("unused")), listOf(pipeline))

    // Act: building the workflow does not throw.
    val unused = Workflow("wf", listOf(Edge(Start, lead)))
  }

  @Test
  fun graphOf_chatAgentAfterNonStartNode_isRejected() {
    // Arrange
    val chatter = LlmAgent("chatter", ModeScriptedModel(text("unused")), mode = AgentMode.CHAT)
    val capture = ModeCaptureNode()

    // Act
    val unused = Workflow("ok", listOf(Edge(Start, chatter)))
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow("bad", listOf(Edge(Start, capture), Edge(capture, chatter)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "AgentMode.CHAT following node 'capture'")
  }
}
