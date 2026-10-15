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
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.tools.BaseTool
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Answers each request with the next scripted turn, repeating the last, and records the requests.
 */
private class ModeScriptedModel(private vararg val turns: Content) : Model {
  override val name = "mode-scripted"
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

private fun runnerFor(root: BaseAgent, resumable: Boolean = false) =
  InMemoryRunner(
    app =
      App(
        "app",
        rootAgent = root,
        resumabilityConfig = if (resumable) ResumabilityConfig(isResumable = true) else null,
      )
  )

private suspend fun InMemoryRunner.send(message: String): List<Event> =
  runAsync("u", "s", newMessage = Content.fromText(Role.USER, message)).toList()

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
    assertFalse(instruction.contains("task_sub`"))
    assertFalse(instruction.contains("single_sub`"))
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
    assertFalse(request.config.systemInstruction?.text().orEmpty().contains("`peer`"))
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
  fun runAsync_singleTurnRoot_isRejectedBeforeTouchingSession() {
    // Arrange
    val runner =
      runnerFor(
        LlmAgent("root_agent", ModeScriptedModel(text("unused")), mode = AgentMode.SINGLE_TURN)
      )

    // Act
    val error = assertFailsWith<IllegalArgumentException> { runBlocking { runner.send("go") } }

    // Assert
    assertContains(error.message.orEmpty(), "must have mode='chat' or 'task'")
    assertNull(runBlocking { runner.sessionService.getSession(SessionKey("app", "u", "s")) })
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

    // Assert
    assertTrue(events.responseNames().containsAll(listOf("note_a", "note_b", "worker")))
    assertEquals("Combined turn complete.", events.contentful().last().contentText())
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
    assertFalse(events.any { it.contentText().contains("Resume complete.") })
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
    val second = runner.send("the second one").contentful()

    // Assert
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
    assertContains(error.message.orEmpty(), "mode='chat' following node 'capture'")
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
    assertContains(error.message.orEmpty(), "mode='chat' following node 'capture'")
  }
}
