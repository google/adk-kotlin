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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
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

/** A model that records the request contents it receives, so a test can see what the agent saw. */
private class RecordingModel(
  private val captured: MutableList<Content>,
  private val responseContent: Content =
    Content(role = Role.MODEL, parts = listOf(Part(text = "done"))),
) : Model {
  constructor(
    captured: MutableList<Content>,
    responseText: String,
  ) : this(captured, Content(role = Role.MODEL, parts = listOf(Part(text = responseText))))

  override val name = "recording"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    captured.clear()
    captured.addAll(request.contents)
    return flowOf(LlmResponse(content = responseContent))
  }
}

private data class NodeResult(val output: Any?)

/** Asks the user for input, which pauses the workflow until the answer arrives. */
private class AskForInputNode(override val name: String, private val interruptId: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(RequestInput(interruptId = interruptId, message = "name?"))
  }
}

/** The user's answer to the input request [interruptId]. */
private fun inputAnswer(interruptId: String, response: Map<String, Any?>) =
  Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              id = interruptId,
              name = RequestInput.FUNCTION_CALL_NAME,
              response = response,
            )
        )
      ),
  )

/** Runs `ask -> agent` resumably: the first turn pauses on the request, the second answers it. */
private suspend fun runAgentAfterAnsweredRequest(agent: LlmAgent): List<Event> {
  val ask = AskForInputNode("ask", "ask_1")
  val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, agent)))
  val runner =
    InMemoryRunner(
      app =
        App(
          appName = "app",
          rootNode = workflow,
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
    )
  val paused = runner.runAsync("u", "s", newMessage = Content.fromText(Role.USER, "go")).toList()
  check(paused.isNotEmpty()) { "the first turn produced no events to resume from" }
  return runner
    .runAsync("u", "s", newMessage = inputAnswer("ask_1", mapOf("name" to "John")))
    .toList()
}

/**
 * A minimal non-[LlmAgent] agent that emits one model turn, to exercise base [BaseAgent.runNode].
 */
private class BaseNodeEchoAgent(name: String, private val text: String) : BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    emit(
      Event(author = name, content = Content(role = Role.MODEL, parts = listOf(Part(text = text))))
    )
  }
}

/** Records whether it ran, so a test can tell whether its predecessor triggered it. */
private class RanFlagNode(override val name: String) : Node {
  var ran = false
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { ran = true }
}

/** Records the input handed to it by its predecessor. */
private class CaptureNode(override val name: String) : Node {
  var captured: Any? = null
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    captured = nodeInput
  }
}

/**
 * Runs [node] inside a workflow, chained to a capture node, and returns the output the node handed
 * downstream. A message-as-output node delivers its value off the wire (on the node context, not on
 * the emitted event), so its output is observed through the successor it feeds.
 */
private suspend fun runNodeAsRoot(
  node: Node,
  context: InvocationContext,
  eventSink: EventSink,
  nodeInput: Any? = null,
): NodeResult {
  val capture = CaptureNode(name = "capture")
  val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, node), Edge(node, capture)))
  val inputContext =
    if (nodeInput != null) {
      val content =
        when (nodeInput) {
          is Content -> nodeInput
          is String -> Content(role = Role.USER, parts = listOf(Part(text = nodeInput)))
          else -> null
        }
      context.copy(userContent = content)
    } else {
      context
    }
  workflow.runAsync(inputContext).collect { event -> eventSink.send(event, null) }
  return NodeResult(capture.captured)
}

/** A model turn calling [toolName] under [id] with `{"request": "go"}`. */
private fun adapterToolCall(toolName: String, id: String) =
  LlmResponse(
    content =
      Content(
        role = Role.MODEL,
        parts =
          listOf(Part(functionCall = FunctionCall(toolName, mapOf("request" to "go"), id = id))),
      )
  )

class AdapterNodesTest {

  @Test
  fun nodeFlags_llmAgent_matchPythonSingleTurnDefaults() {
    // Arrange
    val agent = LlmAgent(name = "a", model = RecordingModel(mutableListOf()))

    // Assert
    assertTrue(agent.rerunOnResume)
    assertFalse(agent.waitForOutput)
  }

  @Test
  fun runNode_stringOutputSchema_returnsPlainTextVerbatim() = runBlocking {
    // Arrange: plain text that is not JSON is kept as-is under a string schema.
    val agent =
      LlmAgent(
        name = "a",
        model = RecordingModel(mutableListOf(), responseText = "The answer is 42"),
        outputSchema = Schema(type = Type.STRING),
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> })

    // Assert
    assertEquals("The answer is 42", result.output)
  }

  @Test
  fun runNode_quotedStringAnswer_decodesTheJsonString() = runBlocking {
    // Arrange: in JSON mode the model wraps a string answer in quotes.
    val agent =
      LlmAgent(
        name = "a",
        model = RecordingModel(mutableListOf(), responseText = "\"Paris\""),
        outputSchema = Schema(type = Type.STRING),
        outputKey = "city",
      )
    val events = mutableListOf<Event>()

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { event, _ -> events.add(event) })

    // Assert
    assertEquals("Paris", result.output)
    assertEquals("Paris", events.single { it.content != null }.actions.stateDelta["city"])
  }

  @Test
  fun runNode_numberAnswerWithStringSchema_keepsRawText() = runBlocking {
    // Arrange: the answer decodes to a number, which fails the string schema.
    val agent =
      LlmAgent(
        name = "a",
        model = RecordingModel(mutableListOf(), responseText = "42"),
        outputSchema = Schema(type = Type.STRING),
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> })

    // Assert
    assertEquals("42", result.output)
  }

  @Test
  fun runNode_fencedJsonAnswer_stripsFenceBeforeValidating() = runBlocking {
    // Arrange
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val agent =
      LlmAgent(
        name = "a",
        model = RecordingModel(mutableListOf(), responseText = "```json\n{\"n\": 7}\n```"),
        outputSchema = schema,
      )

    // Act: a fenced JSON answer is stripped and validated against the node's output schema.
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> })

    // Assert
    assertEquals(mapOf("n" to 7L), result.output)
  }

  @Test
  fun runNode_textInput_isFedAsUserTurn() = runBlocking {
    // Arrange: node output uses `output` not `content`, reaching an agent only via a user turn.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))

    // Act
    val unused =
      runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = "translate this")

    // Assert: the agent saw its input as a user turn.
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("translate this"), texts)
  }

  @Test
  fun runNode_contentInput_isRetaggedAsUserTurn() = runBlocking {
    // Arrange
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val input = Content(role = Role.MODEL, parts = listOf(Part(text = "from upstream")))

    // Act
    val unused = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = input)

    // Assert: a Content input is re-tagged as the user's turn and reaches the agent.
    val roles = captured.map { it.role }
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf(Role.USER), roles)
    assertEquals(listOf("from upstream"), texts)
  }

  @Test
  fun runNode_mapInput_rendersAsJson() = runBlocking {
    // Arrange: a node that receives a map feeds its JSON text as the user turn.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val context = testInvocationContext()
    val nodeCtx = Context(invocationContext = context, node = agent, eventSink = { _, _ -> })

    // Act
    val unused = agent.runNode(nodeCtx, mapOf("city" to "Kraków", "days" to 3)).toList()

    // Assert: non-ASCII text is preserved.
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("{\"city\":\"Kraków\",\"days\":3}"), texts)
  }

  @Test
  fun runNode_listAndNestedMapInput_rendersAsJson() = runBlocking {
    // Arrange
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val nodeCtx =
      Context(invocationContext = testInvocationContext(), node = agent, eventSink = { _, _ -> })

    // Act
    val unused = agent.runNode(nodeCtx, listOf(1, mapOf("tags" to listOf("a", "b")))).toList()

    // Assert
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("[1,{\"tags\":[\"a\",\"b\"]}]"), texts)
  }

  @Test
  fun runNode_secondAgentNode_receivesFirstAnswerAsUserTurn() = runBlocking {
    // Arrange
    val firstRequest = mutableListOf<Content>()
    val secondRequest = mutableListOf<Content>()
    val first = LlmAgent(name = "first", model = RecordingModel(firstRequest, "Paris"))
    val second = LlmAgent(name = "second", model = RecordingModel(secondRequest, "done"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, first), Edge(first, second)))
    val context =
      testInvocationContext(
        userContent = Content(role = Role.USER, parts = listOf(Part(text = "Capital of France?")))
      )

    // Act
    val unused = workflow.runAsync(context).toList()

    // Assert: the second agent sees only the first agent's answer, not the original question.
    assertEquals(listOf("Paris"), secondRequest.flatMap { it.parts }.mapNotNull { it.text })
  }

  @Test
  fun runNode_nullInput_injectsNoUserTurn() = runBlocking {
    // Arrange
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))

    // Act
    val unused = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = null)

    // Assert: nothing is injected, so the agent sees no user turn.
    assertTrue(captured.isEmpty())
  }

  @Test
  fun runAsync_firstNodeAgent_seesUserMessageOnce() = runBlocking {
    // Arrange: runner and node both supply the user input, so a history-free agent sees it once.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "process_request", model = RecordingModel(captured))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, agent)))
    val runner = InMemoryRunner(app = App("app", rootNode = workflow))

    // Act
    val unused =
      runner
        .runAsync("u", "s", newMessage = Content.fromText(Role.USER, "I want 3 days off"))
        .toList()

    // Assert
    assertEquals(1, captured.size)
    assertEquals(Role.USER, captured.single().role)
    assertEquals(listOf("I want 3 days off"), captured.single().parts.mapNotNull { it.text })
  }

  @Test
  fun runNode_defaultIncludeContents_runsHistoryFree() = runBlocking {
    // Arrange: an agent added directly as a history-free node must not see prior root history.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val context = testInvocationContext()
    context.session.events.add(
      Event(
        author = "user",
        content = Content(role = Role.USER, parts = listOf(Part(text = "earlier question"))),
      )
    )
    context.session.events.add(
      Event(
        author = "root",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "earlier answer"))),
      )
    )

    // Act: the LlmAgent runs as the node itself.
    val unused = runNodeAsRoot(agent, context, { _, _ -> }, nodeInput = "current task")

    // Assert: it saw only its injected node_input, not the prior root history.
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("current task"), texts)
  }

  @Test
  fun runNode_agentWithParent_keepsHistoryWithoutInjectingInput() = runBlocking {
    // Arrange: an agent with a parent converses with the session, as a sub-agent does.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val unusedParent =
      LlmAgent(name = "lead", model = RecordingModel(mutableListOf()), subAgents = listOf(agent))
    val context = testInvocationContext()
    context.session.events.add(
      Event(
        author = "user",
        content = Content(role = Role.USER, parts = listOf(Part(text = "earlier question"))),
      )
    )

    // Act
    val unused = runNodeAsRoot(agent, context, { _, _ -> }, nodeInput = "current task")

    // Assert: the history reaches the model, and the node input is not added as a turn.
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("earlier question"), texts)
  }

  @Test
  fun runNode_explicitIncludeContentsDefault_preservesHistory() = runBlocking {
    // Arrange: explicit includeContents DEFAULT keeps prior history rather than forcing NONE.
    val captured = mutableListOf<Content>()
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(captured),
        includeContents = LlmAgent.IncludeContents.DEFAULT,
      )
    val context = testInvocationContext()
    context.session.events.add(
      Event(
        author = "user",
        content = Content(role = Role.USER, parts = listOf(Part(text = "earlier question"))),
      )
    )
    context.session.events.add(
      Event(
        author = "worker",
        content = Content(role = Role.MODEL, parts = listOf(Part(text = "earlier answer"))),
      )
    )

    // Act
    val unused = runNodeAsRoot(agent, context, { _, _ -> }, nodeInput = "current task")

    // Assert: explicit DEFAULT preserves prior history alongside the injected user turn.
    val texts = captured.flatMap { it.parts }.mapNotNull { it.text }
    assertEquals(listOf("earlier question", "earlier answer", "current task"), texts)
  }

  @Test
  fun runNode_resumingWithResumeInputs_skipsInputInjection() = runBlocking {
    // Arrange: resuming with resumeInputs avoids appending a duplicate user event to session.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val ic = testInvocationContext(branch = "parent.worker@1")
    ic.session.events.add(
      Event(
        author = "user",
        branch = "parent.worker@1",
        content = Content(role = Role.USER, parts = listOf(Part(text = "turn 1 initial input"))),
      )
    )
    val nodeCtx =
      Context(
        invocationContext = ic,
        node = agent,
        eventSink = { _, _ -> },
        resumeInputs = mapOf("worker@1" to mapOf("confirmed" to true)),
      )
    val initialEventCount = ic.session.events.size

    // Act
    val unused = agent.runNode(nodeCtx, "duplicate input").toList()

    // Assert: session.events has no duplicate user event appended, and the model saw turn 1 input.
    val userEvents = ic.session.events.filter { it.author == Role.USER }
    assertEquals(initialEventCount, userEvents.size)
    assertEquals(
      listOf("turn 1 initial input"),
      captured.flatMap { it.parts }.mapNotNull { it.text },
    )
  }

  @Test
  fun runAsync_nodeAfterAnsweredRequest_seesOnlyAnswerAsUserTurn() = runBlocking {
    // Arrange: the resuming answer is a function response a history-free agent cannot see alone.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "writer", model = RecordingModel(captured, "hello John"))

    // Act
    val events = runAgentAfterAnsweredRequest(agent)

    // Assert
    val replies = events.filter { it.author == "writer" }.map { it.contentText() }
    assertEquals(listOf("hello John"), replies.filter { it.isNotEmpty() })
    assertEquals(
      listOf("{\"name\":\"John\"}"),
      captured.flatMap { it.parts }.mapNotNull { it.text },
    )
  }

  @Test
  fun runAsync_nodeWithHistoryAfterAnsweredRequest_endsOnAnswerAsUserTurn() = runBlocking {
    // Arrange: with history, injected input stays last so the answer is not a dangling response.
    val captured = mutableListOf<Content>()
    val agent =
      LlmAgent(
        name = "writer",
        model = RecordingModel(captured, "hello John"),
        includeContents = LlmAgent.IncludeContents.DEFAULT,
      )

    // Act
    val events = runAgentAfterAnsweredRequest(agent)

    // Assert
    val replies = events.filter { it.author == "writer" }.map { it.contentText() }
    assertEquals(listOf("hello John"), replies.filter { it.isNotEmpty() })
    assertEquals(Role.USER, captured.last().role)
    assertEquals(listOf("{\"name\":\"John\"}"), captured.last().parts.mapNotNull { it.text })
  }

  @Test
  fun runNode_inputEvent_inheritsBranch() = runBlocking {
    // Arrange: the injected user turn inherits the node's branch so parallel branches stay apart.
    val ic = testInvocationContext(branch = "parent.worker@1")
    var branchSeen: String? = null
    val model =
      DummyModel("m") {
        branchSeen = ic.session.events.last { it.author == Role.USER }.branch
        flowOf(LlmResponse(content = Content.fromText(Role.MODEL, "done")))
      }
    val agent = LlmAgent(name = "worker", model = model)
    val nodeCtx = Context(invocationContext = ic, node = agent, eventSink = { _, _ -> })

    // Act
    val unused = agent.runNode(nodeCtx, "scoped input").toList()

    // Assert
    assertEquals("parent.worker@1", branchSeen)
  }

  @Test
  fun runNode_injectedInput_isRemovedAfterTheRun() = runBlocking {
    // Arrange: a later node on the same branch must not see this node's input.
    val captured = mutableListOf<Content>()
    val agent = LlmAgent(name = "worker", model = RecordingModel(captured))
    val ic = testInvocationContext()
    val before = ic.session.events.toList()
    val nodeCtx = Context(invocationContext = ic, node = agent, eventSink = { _, _ -> })

    // Act
    val unused = agent.runNode(nodeCtx, "one-off input").toList()

    // Assert
    assertEquals(listOf("one-off input"), captured.flatMap { it.parts }.mapNotNull { it.text })
    assertEquals(before, ic.session.events.toList())
  }

  @Test
  fun runNode_modelFails_stillRemovesInjectedInput() = runBlocking {
    // Arrange
    val model = DummyModel("m") { throw IllegalStateException("503 unavailable") }
    val agent = LlmAgent(name = "worker", model = model)
    val ic = testInvocationContext()
    val before = ic.session.events.toList()
    val nodeCtx = Context(invocationContext = ic, node = agent, eventSink = { _, _ -> })

    // Act
    assertFailsWith<IllegalStateException> { agent.runNode(nodeCtx, "one-off input").toList() }

    // Assert
    assertEquals(before, ic.session.events.toList())
  }

  @Test
  fun runNode_thoughtParts_areFilteredFromOutputAndStateDelta() = runBlocking {
    // Arrange: thought parts are excluded from both the node's output and outputKey state delta.
    val responseWithThought =
      Content(
        role = Role.MODEL,
        parts =
          listOf(Part(text = "internal reasoning", thought = true), Part(text = "visible answer")),
      )
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(mutableListOf(), responseContent = responseWithThought),
        outputKey = "summary",
      )
    val events = mutableListOf<Event>()

    // Act
    val result =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "go",
      )

    // Assert
    assertEquals("visible answer", result.output)
    assertEquals(
      "visible answer",
      events.single { it.content != null }.actions.stateDelta["summary"],
    )
  }

  @Test
  fun runNode_toolCall_finishesBeforeFinalOutput() = runBlocking {
    // Arrange: tool events carry no output; only the final text turn is marked messageAsOutput.
    val toolCall =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall("lookup", emptyMap(), id = "call_1"))),
      )
    val finalReply = Content(role = Role.MODEL, parts = listOf(Part(text = "It is 72")))
    val agent =
      LlmAgent(
        name = "worker",
        model =
          DummyModel.createSequential(
            "m",
            listOf(LlmResponse(content = toolCall), LlmResponse(content = finalReply)),
          ),
        tools = listOf(DummyTool("lookup", onRun = { _, _ -> mapOf("temp" to 72) })),
      )
    val events = mutableListOf<Event>()

    // Act
    val result =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "go",
      )

    // Assert
    assertEquals("It is 72", result.output)
    val contentEvents = events.filter { it.content != null }
    assertEquals(3, contentEvents.size)
    assertFalse(contentEvents[0].nodeInfo?.messageAsOutput == true)
    assertFalse(contentEvents[1].nodeInfo?.messageAsOutput == true)
    assertTrue(contentEvents[2].nodeInfo?.messageAsOutput == true)
  }

  @Test
  fun runNode_finalTurn_isMarkedAsOutput() = runBlocking {
    // Arrange: a string output schema takes the model's answer verbatim as the node's output.
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(mutableListOf()),
        outputSchema = Schema(type = Type.STRING),
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = "hi")

    // Assert: the agent's final model turn becomes the node's output.
    assertEquals("done", result.output)
  }

  @Test
  fun runNode_output_isDeliveredOnExactlyOneEvent() = runBlocking {
    // Arrange: a message-as-output node emits its answer once on the content event, not twice.
    val agent =
      LlmAgent(name = "worker", model = RecordingModel(mutableListOf(), responseText = "hi"))
    val events = mutableListOf<Event>()
    val sink = EventSink { event, _ -> events.add(event) }

    // Act
    val unused = runNodeAsRoot(agent, testInvocationContext(), sink, nodeInput = "go")

    // Assert: one event carries the answer on message-as-output content with no `output` field.
    val outputBearing = events.filter {
      it.output != null || it.nodeInfo?.outputFor?.isNotEmpty() == true
    }
    assertEquals(1, outputBearing.size)
    val outputEvent = outputBearing.single()
    assertNull(outputEvent.output)
    assertTrue(outputEvent.nodeInfo?.messageAsOutput == true)
    assertEquals("hi", outputEvent.content?.parts?.mapNotNull { it.text }?.joinToString(""))
  }

  @Test
  fun runNode_answerNotMatchingSchema_failsWithoutQuotingModelText() = runBlocking {
    // Arrange: the schema requires an object while the model returns prose, failing validation.
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val answer = "sorry, I do not know"
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(mutableListOf(), responseText = answer),
        outputSchema = schema,
      )
    val events = mutableListOf<Event>()
    val sink = EventSink { event, _ -> events.add(event) }

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runNodeAsRoot(agent, testInvocationContext(), sink)
      }

    // Assert: the engine rejects the output without repeating the model text in events or errors.
    assertContains(error.message.orEmpty(), "validation error")
    assertFalse(error.message.orEmpty().contains(answer))
    assertTrue(events.none { it.errorMessage.orEmpty().contains(answer) })
  }

  @Test
  fun runNode_noModelTurn_stillTriggersSuccessor() = runBlocking {
    // Arrange: capped at one step, the agent's only turn is a tool call, so it produces no output.
    val toolCall =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(functionCall = FunctionCall("dummy_tool", emptyMap(), id = "call_1"))),
      )
    val agent =
      LlmAgent(
        name = "worker",
        model = DummyModel.createSequential("m", listOf(LlmResponse(content = toolCall))),
        tools = listOf(DummyTool()),
        maxSteps = 1,
      )
    val successor = RanFlagNode("after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, agent), Edge(agent, successor)))

    // Act
    workflow.runAsync(testInvocationContext()).collect {}

    // Assert: the node finishes instead of waiting for missing output so the graph continues.
    assertTrue(successor.ran)
  }

  @Test
  fun runNode_nonLlmAgent_forwardsItsEvents() = runBlocking {
    // Arrange: a non-LlmAgent uses base runNode to drive runAsync and stamp author and node path.
    val agent = BaseNodeEchoAgent(name = "echo", text = "hello from base")
    val events = mutableListOf<Event>()
    val sink = EventSink { event, _ -> events.add(event) }

    // Act
    val unused = runNodeAsRoot(agent, testInvocationContext(), sink)

    // Assert: the agent's event is forwarded, attributed to the agent, and placed at its node path.
    val agentEvents = events.filter { it.content != null }
    val texts = agentEvents.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text }
    assertEquals(listOf("hello from base"), texts)
    assertTrue(agentEvents.all { it.author == "echo" })
    assertTrue(agentEvents.all { it.nodeInfo?.path?.contains("echo") == true })
  }

  @Test
  fun runNode_outputSchemaAndOutputKey_writesParsedValueToBoth() = runBlocking {
    // Arrange
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("title" to Schema(type = Type.STRING), "count" to Schema(type = Type.INTEGER)),
        required = listOf("title", "count"),
      )
    val agent =
      LlmAgent(
        name = "writer",
        model =
          RecordingModel(mutableListOf(), responseText = "{\"title\": \"Story\", \"count\": 3}"),
        outputSchema = schema,
        outputKey = "story_meta",
      )
    val events = mutableListOf<Event>()

    // Act
    val result =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "write",
      )

    // Assert
    val expected = mapOf("title" to "Story", "count" to 3L)
    assertEquals(expected, result.output)
    assertEquals(expected, events.single { it.content != null }.actions.stateDelta["story_meta"])
  }

  @Test
  fun runNode_undeclaredKeyInAnswer_failsNode() = runBlocking {
    // Arrange: an object answer may only carry the keys its schema declares.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("title" to Schema(type = Type.STRING)))
    val agent =
      LlmAgent(
        name = "writer",
        model =
          RecordingModel(
            mutableListOf(),
            responseText = "{\"title\": \"Story\", \"extra\": \"x\"}",
          ),
        outputSchema = schema,
        outputKey = "story_meta",
      )

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = "write")
      }

    // Assert
    assertContains(error.message.orEmpty(), "validation error")
  }

  @Test
  fun runNode_fencedAnswerWithOutputKey_storesParsedValue() = runBlocking {
    // Arrange: the node parses fenced JSON, storing that parsed value in outputKey, not raw text.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("title" to Schema(type = Type.STRING)))
    val agent =
      LlmAgent(
        name = "writer",
        model =
          RecordingModel(mutableListOf(), responseText = "```json\n{\"title\": \"Story\"}\n```"),
        outputSchema = schema,
        outputKey = "story_meta",
      )
    val events = mutableListOf<Event>()

    // Act
    val result =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "write",
      )

    // Assert
    val expected = mapOf("title" to "Story")
    assertEquals(expected, result.output)
    assertEquals(expected, events.single { it.content != null }.actions.stateDelta["story_meta"])
  }

  @Test
  fun runNode_textWithLongRunningCall_savesTextToOutputKey() = runBlocking {
    // Arrange: text alongside a long-running call is a final response, though not the node output.
    val text = "Checking with the user."
    val turn =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(text = text),
            Part(functionCall = FunctionCall("ask", emptyMap(), id = "call_1")),
          ),
      )
    val agent =
      LlmAgent(
        name = "worker",
        model = DummyModel.createSequential("m", listOf(LlmResponse(content = turn))),
        tools = listOf(DummyTool(name = "ask", isLongRunning = true, onRun = { _, _ -> Unit })),
        outputKey = "answer",
        maxSteps = 1,
      )
    val events = mutableListOf<Event>()

    // Act
    val unused =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "go",
      )

    // Assert
    val turnEvent = events.single { event ->
      event.content?.parts.orEmpty().any { it.text == text }
    }
    assertEquals(text, turnEvent.actions.stateDelta["answer"])
    assertFalse(turnEvent.nodeInfo?.messageAsOutput == true)
  }

  @Test
  fun runNode_agentCallingATool_answerIsNodeOutput() = runBlocking {
    // Arrange
    val tool = DummyTool(name = "talker", onRun = { _, _ -> mapOf("result" to "inner done") })
    val agent =
      LlmAgent(
        name = "worker",
        model =
          DummyModel.createSequential(
            "m",
            listOf(
              adapterToolCall("talker", "fc_1"),
              LlmResponse(content = Content.fromText(Role.MODEL, "Outer answer.")),
            ),
          ),
        tools = listOf(tool),
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = "go")

    // Assert
    assertEquals("Outer answer.", result.output)
  }

  @Test
  fun runNode_outputSchema_carriesParsedValueWithoutMessageFlag() = runBlocking {
    // Arrange
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(mutableListOf(), responseText = """{"n": 7}"""),
        outputSchema = schema,
      )
    val events = mutableListOf<Event>()

    // Act
    val result =
      runNodeAsRoot(
        agent,
        testInvocationContext(),
        { event, _ -> events.add(event) },
        nodeInput = "go",
      )

    // Assert
    val answer = events.single { it.contentText() == """{"n": 7}""" }
    assertEquals(mapOf("n" to 7L), answer.output)
    assertFalse(answer.nodeInfo?.messageAsOutput == true)
    assertEquals(mapOf("n" to 7L), result.output)
  }

  @Test
  fun runNode_blankAnswerWithOutputSchema_producesNoOutput() = runBlocking {
    // Arrange
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val agent =
      LlmAgent(
        name = "worker",
        model = RecordingModel(mutableListOf(), responseText = "   "),
        outputSchema = schema,
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> }, nodeInput = "go")

    // Assert
    assertNull(result.output)
  }

  @Test
  fun runNode_quotedNumberForIntegerProperty_failsNode() = runBlocking {
    // Arrange: validation is strict, so the text "7" is not an integer.
    val schema = Schema(type = Type.OBJECT, properties = mapOf("n" to Schema(type = Type.INTEGER)))
    val agent =
      LlmAgent(
        name = "counter",
        model = RecordingModel(mutableListOf(), responseText = """{"n": "7"}"""),
        outputSchema = schema,
      )

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runNodeAsRoot(agent, testInvocationContext(), { _, _ -> })
      }

    // Assert
    assertContains(error.message.orEmpty(), "validation error")
  }

  @Test
  fun runNode_arrayOutputSchema_parsesJsonArray() = runBlocking {
    // Arrange
    val schema = Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER))
    val agent =
      LlmAgent(
        name = "numbers",
        model = RecordingModel(mutableListOf(), responseText = "[1, 2, 3]"),
        outputSchema = schema,
      )

    // Act
    val result = runNodeAsRoot(agent, testInvocationContext(), { _, _ -> })

    // Assert
    assertEquals(listOf(1L, 2L, 3L), result.output)
  }
}
