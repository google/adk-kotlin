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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userEvent
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Echoes its arguments, declaring [required] of its string [params] as required. */
private class ToolNodeEchoTool(
  name: String,
  private val params: List<String> = emptyList(),
  private val required: List<String> = emptyList(),
) : BaseTool(name = name, description = "Echoes its arguments.") {
  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties = params.associateWith { Schema(type = Type.STRING) },
          required = required,
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = args
}

/** Asks for user confirmation before every call, and records whether its body ran. */
private class ToolNodeConfirmingTool(name: String) :
  FunctionTool(name = name, description = "", requiresConfirmation = { true }) {
  var executed = false

  override fun declaration(): FunctionDeclaration? = null

  override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
    executed = true
    return "ran"
  }
}

/** A tool named "writer" that writes `args["name"] = args["val"]` to state and returns [result]. */
private fun stateWriterTool(result: Any = mapOf("status" to "ok")) =
  DummyTool(name = "writer") { context, args ->
    context.updateState(args.getValue("name") as String, args.getValue("val")!!)
    result
  }

/** Outputs the input it received, so a test can see what a tool node handed downstream. */
private class ToolNodeCapture(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(mapOf("received" to nodeInput))
  }
}

/** Runs `START -> source -> tool` with [input] as the source's output, and returns every event. */
private fun runToolAfter(
  input: Any?,
  tool: Node,
  context: InvocationContext = testInvocationContext(),
): List<Event> = runBlocking {
  val source = Emitter("source", input)
  val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, tool)))
  workflow.runAsync(context).toList()
}

/** The session the artifact tests run in. */
private val REPORT_SESSION = SessionKey("report_app", "u", "s")

/** A tool named "save_report" that saves `report.txt` = "v1" and reports the saved version. */
private fun saveReportTool(onSaved: (Int) -> Unit) =
  DummyTool(name = "save_report") { context, _ ->
    onSaved(context.saveArtifact("report.txt", Part(text = "v1")))
    "saved"
  }

/** Creates the report session, appending an unrelated first invocation when [withPriorRun]. */
private fun InMemoryRunner.createReportSession(withPriorRun: Boolean) = runBlocking {
  val session = sessionService.createSession(REPORT_SESSION)
  if (withPriorRun) {
    val unused = sessionService.appendEvent(session, userEvent("hi", invocationId = "invocation1"))
  }
}

/** A runner whose workflow runs [saveReportTool] as its only node, with the report session made. */
private fun reportRunner(
  withPriorRun: Boolean = false,
  onSaved: (Int) -> Unit = {},
): InMemoryRunner {
  val node = saveReportTool(onSaved).asNode()
  val runner =
    InMemoryRunner(
      App(appName = "report_app", rootNode = Workflow("wf", listOf(Edge(Start, node))))
    )
  runner.createReportSession(withPriorRun)
  return runner
}

/** Runs one invocation in the report session; `{}` means no tool arguments. */
private fun InMemoryRunner.runReportTurn(): List<Event> = runBlocking {
  runAsync(userId = "u", sessionId = "s", newMessage = userMessage("{}")).toList()
}

/** Returns the latest version of `report.txt` in the report session. */
private fun InMemoryRunner.loadReport(): Part? = runBlocking {
  artifactService!!.loadArtifact(REPORT_SESSION, "report.txt", null)
}

/** Returns the report session's persisted events. */
private fun InMemoryRunner.reportSessionEvents(): List<Event> = runBlocking {
  sessionService.getSession(REPORT_SESSION)!!.events
}

/** Returns the output of the event the node at [path] emitted. */
private fun List<Event>.outputAt(path: String): Any? = single { it.nodeInfo?.path == path }.output

class ToolNodeTest {

  @Test
  fun run_mapInput_passesItAsToolArguments() {
    // Arrange
    val args = mapOf("param_a" to 1L, "param_b" to "value")

    // Act
    val events = runToolAfter(args, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(args, events.outputAt("wf@1/mock_tool@1"))
    assertEquals("wf", events.single { it.nodeInfo?.path == "wf@1/mock_tool@1" }.author)
  }

  @Test
  fun run_jsonObjectString_parsesItIntoArguments() {
    // Arrange
    val input = """  {"param_a": 1, "param_b": "value"}  """

    // Act
    val events = runToolAfter(input, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(mapOf("param_a" to 1L, "param_b" to "value"), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_blankString_callsToolWithNoArguments() {
    // Act
    val events = runToolAfter("  ", ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(emptyMap<String, Any?>(), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_nullInput_callsToolWithNoArguments() {
    // Act
    val events = runToolAfter(null, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(emptyMap<String, Any?>(), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_jsonObjectContent_readsItsNonThoughtText() {
    // Arrange
    val content =
      Content(
        role = Role.MODEL,
        parts = listOf(Part(text = "reasoning", thought = true), Part(text = """{"x": 1}""")),
      )

    // Act
    val events = runToolAfter(content, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(mapOf("x" to 1L), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_blankContent_callsToolWithNoArguments() {
    // Arrange
    val content = Content(role = Role.MODEL, parts = listOf(Part(text = "  ")))

    // Act
    val events = runToolAfter(content, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(emptyMap<String, Any?>(), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_contentWithOnlyThoughts_callsToolWithNoArguments() {
    // Arrange: thought parts are not text for the node, so the input reads as blank.
    val content =
      Content(role = Role.MODEL, parts = listOf(Part(text = "{\"x\": 1}", thought = true)))

    // Act
    val events = runToolAfter(content, ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(emptyMap<String, Any?>(), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_jsonNullText_callsToolWithNoArguments() {
    // Act
    val events = runToolAfter(" null ", ToolNodeEchoTool("mock_tool").asNode())

    // Assert
    assertEquals(emptyMap<String, Any?>(), events.outputAt("wf@1/mock_tool@1"))
  }

  @Test
  fun run_jsonArrayText_failsTheNode() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter("[1, 2, 3]", ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "must be a dictionary")
    assertContains(error.message.orEmpty(), "but got text that is not a JSON object")
  }

  @Test
  fun run_jsonScalarText_failsTheNode() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter("42", ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "but got text that is not a JSON object")
  }

  @Test
  fun run_plainText_failsTheNode() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter("not a json", ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "input to tool node 'mock_tool' must be a dictionary")
    assertContains(error.message.orEmpty(), "but got text that is not a JSON object")
  }

  @Test
  fun run_nonJsonContent_failsTheNode() {
    // Arrange
    val content = Content(role = Role.MODEL, parts = listOf(Part(text = "not a json")))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter(content, ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "must be a dictionary")
    assertContains(error.message.orEmpty(), "but got text that is not a JSON object")
  }

  @Test
  fun run_objectTextWithUnquotedValue_failsTheNode() {
    // Act: a lenient parser would read `Paris` as a string; strict JSON does not, nor does Python.
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter("""{"city": Paris}""", ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "but got text that is not a JSON object")
  }

  @Test
  fun run_nonMapValue_failsTheNode() {
    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runToolAfter(42L, ToolNodeEchoTool("mock_tool").asNode())
      }

    // Assert
    assertContains(error.message.orEmpty(), "but got Long")
  }

  @Test
  fun run_parametersMissing_readsOnlyRequiredOnesFromState() {
    // Arrange
    val tool =
      ToolNodeEchoTool("weather", params = listOf("city", "units"), required = listOf("city"))
    val context = testInvocationContext()
    context.session.state["city"] = "Paris"
    context.session.state["units"] = "fahrenheit"

    // Act
    val events = runToolAfter(null, tool.asNode(), context)

    // Assert
    assertEquals(mapOf("city" to "Paris"), events.outputAt("wf@1/weather@1"))
  }

  @Test
  fun run_requiredParameterInNeitherInputNorState_isLeftOut() {
    // Arrange
    val tool = ToolNodeEchoTool("weather", params = listOf("city"), required = listOf("city"))

    // Act
    val events = runToolAfter(null, tool.asNode())

    // Assert: the tool gets no `city` key at all, not one mapped to null.
    val args = events.outputAt("wf@1/weather@1") as Map<*, *>
    assertFalse("city" in args, "the tool got a city key")
  }

  @Test
  fun run_argumentInInputAndState_usesTheInput() {
    // Arrange
    val tool = ToolNodeEchoTool("weather", params = listOf("city"), required = listOf("city"))
    val context = testInvocationContext()
    context.session.state["city"] = "Paris"

    // Act
    val events = runToolAfter(mapOf("city" to "Rome"), tool.asNode(), context)

    // Assert
    assertEquals(mapOf("city" to "Rome"), events.outputAt("wf@1/weather@1"))
  }

  @Test
  fun run_toolWritesState_putsDeltaOnOutputEventForLaterNodes() {
    // Arrange
    val seed = Emitter("seed", mapOf("name" to "color", "val" to "blue"))
    val writer = stateWriterTool().asNode()
    val reader =
      object : Node {
        override val name = "reader"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          emit(context.state["color"])
        }
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, seed), Edge(seed, writer), Edge(writer, reader)),
      )
    val runner = InMemoryRunner(App(appName = "tool_node_app", rootNode = workflow))

    // Act
    val events = runBlocking {
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = Content.fromText(Role.USER, "go"))
        .toList()
    }

    // Assert
    val writerEvent = events.single { it.nodeInfo?.path == "wf@1/writer@1" }
    assertEquals(mapOf("status" to "ok"), writerEvent.output)
    assertEquals(mapOf<String, Any>("color" to "blue"), writerEvent.actions.stateDelta.toMap())
    assertEquals("blue", events.outputAt("wf@1/reader@1"))
  }

  @Test
  fun run_toolReturnsNothing_stillRecordsStateWrite() {
    // Arrange
    val writer = stateWriterTool(result = Unit).asNode()

    // Act
    val events = runToolAfter(mapOf("name" to "color", "val" to "blue"), writer)

    // Assert
    val writerEvent = events.single { it.nodeInfo?.path == "wf@1/writer@1" }
    assertNull(writerEvent.output)
    assertEquals(mapOf<String, Any>("color" to "blue"), writerEvent.actions.stateDelta.toMap())
  }

  @Test
  fun run_toolSavesArtifact_putsArtifactDeltaOnOutputEvent() {
    // Arrange
    val saver =
      DummyTool(name = "saver") { context, _ ->
        context.actions.artifactDelta["report.txt"] = 3
        "saved"
      }

    // Act
    val events = runToolAfter(null, saver.asNode())

    // Assert
    val event = events.single { it.nodeInfo?.path == "wf@1/saver@1" }
    assertEquals("saved", event.output)
    assertEquals(mapOf("report.txt" to 3), event.actions.artifactDelta.toMap())
  }

  @Test
  fun run_toolCalledInTwoRuns_getsAFreshFunctionCallIdEachTime() {
    // Arrange
    val seenIds = mutableListOf<String?>()
    val node =
      DummyTool(name = "record") { context, _ ->
          seenIds += context.functionCallId
          "ok"
        }
        .asNode()

    // Act
    repeat(2) {
      val unused = runToolAfter(null, node)
    }

    // Assert
    assertEquals(2, seenIds.size)
    assertTrue(seenIds.all { !it.isNullOrEmpty() }, "a run had no function call id")
    assertNotEquals(seenIds[0], seenIds[1])
  }

  @Test
  fun run_agentTool_runsTheAgentOnTheRequestAndOutputsItsReply() {
    // Arrange
    var modelInput: String? = null
    val model =
      DummyModel("model") { request ->
        modelInput = request.contents.last().text()
        flowOf(LlmResponse(content = Content.fromText(Role.MODEL, "Paris is sunny.")))
      }
    val node = AgentTool(LlmAgent(name = "forecaster", model = model)).asNode()

    // Act
    val events = runToolAfter(mapOf("request" to "Weather in Paris?"), node)

    // Assert
    assertEquals("Weather in Paris?", modelInput)
    assertEquals("Paris is sunny.", events.outputAt("wf@1/forecaster@1"))
  }

  @Test
  fun run_toolReturnsErrorMap_emitsItAsOutputForTheNextNode() {
    // Arrange
    val tool =
      DummyTool(name = "lookup") { _, _ ->
          mapOf(FunctionTool.ERROR_KEY to "Missing required parameter city")
        }
        .asNode()
    val capture = ToolNodeCapture("capture")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, tool), Edge(tool, capture)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: no model reads the error map, so it flows on as ordinary output.
    val errorMap = mapOf(FunctionTool.ERROR_KEY to "Missing required parameter city")
    assertEquals(errorMap, events.outputAt("wf@1/lookup@1"))
    assertEquals(mapOf("received" to errorMap), events.outputAt("wf@1/capture@1"))
  }

  @Test
  fun run_toolRequiresConfirmation_dropsRequestAndOutputsPlaceholderError() {
    // Arrange
    val tool = ToolNodeConfirmingTool("confirming")

    // Act
    val events = runToolAfter(null, tool.asNode())

    // Assert
    val event = events.single { it.nodeInfo?.path == "wf@1/confirming@1" }
    assertContains((event.output as Map<*, *>).keys, FunctionTool.ERROR_KEY)
    assertTrue(event.actions.requestedToolConfirmations.isEmpty())
    assertFalse(event.actions.skipSummarization)
    assertFalse(tool.executed)
  }

  @Test
  fun run_toolRequestsTransfer_dropsItAndRunsTheNextNode() {
    // Arrange
    val tool =
      DummyTool(name = "handoff") { context, _ ->
          context.actions.transferToAgent = "elsewhere"
          "done"
        }
        .asNode()
    val capture = ToolNodeCapture("capture")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, tool), Edge(tool, capture)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(mapOf("received" to "done"), events.outputAt("wf@1/capture@1"))
    assertTrue(events.none { it.actions.transferToAgent != null })
  }

  @Test
  fun run_retryConfigSet_retriesFailedToolCall() {
    // Arrange
    var calls = 0
    val tool =
      DummyTool(name = "flaky") { _, _ ->
        calls += 1
        check(calls > 1) { "transient failure" }
        "ok"
      }
    val config =
      NodeConfig(retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO))

    // Act
    val events = runToolAfter(null, tool.asNode(config = config))

    // Assert
    assertEquals(2, calls)
    assertEquals("ok", events.last { it.nodeInfo?.path == "wf@1/flaky@1" }.output)
  }

  @Test
  fun run_toolThrowsWithoutRetryConfig_failsTheNode() {
    // Arrange
    var calls = 0
    val tool =
      DummyTool(name = "flaky") { _, _ ->
        calls += 1
        check(calls > 1) { "transient failure" }
        "ok"
      }

    // Act
    val error = assertFailsWith<IllegalStateException> { runToolAfter(null, tool.asNode()) }

    // Assert
    assertEquals(1, calls)
    assertContains(error.message.orEmpty(), "transient failure")
  }

  @Test
  fun asNode_defaults_usesToolNameAndDescription() {
    // Act
    val node = ToolNodeEchoTool("lookup").asNode()

    // Assert
    assertEquals("lookup", node.name)
    assertEquals("Echoes its arguments.", node.description)
  }

  @Test
  fun asNode_explicitName_overridesToolName() {
    // Act
    val node = ToolNodeEchoTool("lookup").asNode(name = "custom")

    // Assert
    assertEquals("custom", node.name)
    assertEquals("Echoes its arguments.", node.description)
  }

  @Test
  fun asNode_sameToolConvertedTwice_isRejectedByTheGraph() {
    // Arrange
    val tool = ToolNodeEchoTool("lookup")
    val first = tool.asNode()
    val second = tool.asNode()

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow(name = "wf", edges = listOf(Edge(Start, first), Edge(first, second)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "Duplicate node names")
  }

  @Test
  fun asNode_toolNameInvalidAsNodeName_requiresExplicitName() {
    // Arrange
    val tool = ToolNodeEchoTool("ns.lookup")

    // Act
    val error = assertFailsWith<IllegalArgumentException> { tool.asNode() }

    // Assert
    assertContains(error.message.orEmpty(), "must not contain")
    assertEquals("lookup", tool.asNode(name = "lookup").name)
  }

  @Test
  fun run_toolSavesArtifact_recordsArtifactDeltaOnSessionEvent() {
    // Arrange
    var savedVersion: Int? = null
    val runner = reportRunner { savedVersion = it }

    // Act
    val unused = runner.runReportTurn()

    // Assert
    assertEquals("v1", runner.loadReport()?.text)
    val version = assertNotNull(savedVersion)
    assertTrue(
      runner.reportSessionEvents().any { it.actions.artifactDelta["report.txt"] == version },
      "no persisted event records report.txt version $version",
    )
  }

  @Test
  fun rewind_beforeToolNodeRun_removesSavedArtifact() {
    // Arrange
    val runner = reportRunner(withPriorRun = true)
    val toolNodeRun = assertNotNull(runner.runReportTurn().first().invocationId)

    // Act
    runBlocking {
      runner.rewindAsync(userId = "u", sessionId = "s", rewindBeforeInvocationId = toolNodeRun)
    }

    // Assert: an artifact that did not exist at the rewind point becomes an empty placeholder.
    val report = runner.loadReport()
    assertNotEquals("v1", report?.text)
    assertEquals("application/octet-stream", report?.inlineData?.mimeType)
  }
}
