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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.tools

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.InvocationEventSink
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.EventSink
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.parallelNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable private data class UserProfileInput(val userId: String)

@Serializable private data class UserProfile(val id: String, val role: String)

@Serializable private data class TypedInput(val x: Int)

@Serializable private data class Query(val query: String)

@Serializable private data class Note(val text: String, val tag: String? = null)

/**
 * A tool context on the node path, as an agent run by a node runner gives its tools, whose nodes
 * save their events to its session and apply their state changes.
 */
private fun runnerToolContext(
  invocationContext: InvocationContext = testInvocationContext(),
  functionCallId: String? = null,
): ToolContext {
  val session = invocationContext.session
  val sink = EventSink { event, _ ->
    if (!event.partial) {
      session.state.applyDelta(event.actions.stateDelta)
      session.events.add(event)
    }
  }
  val frameworkData = invocationContext.frameworkData.copy(eventSink = InvocationEventSink(sink))
  return testToolContext(
    invocationContext.copy(frameworkData = frameworkData),
    functionCallId = functionCallId,
  )
}

/** A model that records each request in [requests] and answers the n-th with [responses]'s n-th. */
private fun recordingModel(requests: MutableList<LlmRequest>, responses: List<LlmResponse>) =
  DummyModel("model") { request ->
    requests += request
    flowOf(responses[requests.size - 1])
  }

/** The parts of every content in this request. */
private fun LlmRequest.parts() = contents.flatMap { it.parts }

/** A custom node that checks its input against [inputSchema] and outputs it as text. */
private class EchoNode(
  override val name: String,
  override val inputSchema: Schema = Schema(type = Type.STRING),
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flowOf("$nodeInput")
}

/** A custom node whose output is what [output] returns for its input. */
private class OutputNode(override val name: String, private val output: (Any?) -> Any?) : Node {
  override val inputSchema: Schema = Schema(type = Type.STRING)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flowOf(output(nodeInput))
}

/** Asserts that [result] is the error answer for arguments that fail validation. */
private fun assertValidationError(result: Any) {
  val answer = assertIs<Map<*, *>>(result)
  assertEquals(setOf("error"), answer.keys)
  assertContains(answer["error"] as String, "argument validation errors")
}

class NodeToolTest {

  /** Takes a `query` string, as most workflows here do. */
  private val querySchema =
    Schema(
      type = Type.OBJECT,
      properties = mapOf("query" to Schema(type = Type.STRING)),
      required = listOf("query"),
    )

  /** A union that Vertex rejects in a function's response schema. */
  private val stringOrInteger =
    Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))

  /** The JSON form of a model message saying "hi", as a tool answers with it. */
  private val helloJson = mapOf("parts" to listOf(mapOf("text" to "hi")), "role" to "model")

  /** An object schema that lets any arguments through, leaving the check to the entry nodes. */
  private val anyObject = Schema(type = Type.OBJECT)

  private val double = node<TypedInput, Int>("double") { _, input -> 2 * input.x }

  /** Answers with the branch its node runs on. */
  private val branchReporter =
    NodeTool(
      node<String, String>("where") { context, _ -> context.invocationContext.branch.orEmpty() }
    )

  /** A workflow named [name] over [edges] that takes [querySchema] unless given another schema. */
  private fun testWorkflow(
    name: String,
    edges: List<Edge> = emptyList(),
    description: String = "",
    inputSchema: Schema? = querySchema,
    outputSchema: Schema? = null,
  ): Workflow =
    Workflow(
      name = name,
      edges = edges,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
    )

  // -- Construction --

  @Test
  fun constructor_agent_isRejected() {
    val error =
      assertFailsWith<IllegalArgumentException> { NodeTool(DummyAgent(name = "my_agent")) }

    assertContains(error.message.orEmpty(), "cannot be wrapped as a NodeTool")
  }

  @Test
  fun constructor_workflowWithoutInputSchema_isRejected() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        NodeTool(testWorkflow("sub_workflow", inputSchema = null))
      }

    assertContains(error.message.orEmpty(), "does not have an inputSchema defined")
  }

  @Test
  fun constructor_nodeNameThatCorruptsPaths_isRejected() {
    for (name in listOf("a.b", "a/b", "a@b")) {
      val error = assertFailsWith<IllegalArgumentException>(name) { NodeTool(EchoNode(name)) }

      assertContains(error.message.orEmpty(), "must not contain '/', '@', or '.'")
    }
  }

  @Test
  fun constructor_parallelFunctionNodeWithItemSchema_isRejected() {
    val itemSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("x" to Schema(type = Type.INTEGER)),
        required = listOf("x"),
      )
    val parallel =
      parallelNode<TypedInput, Int>("double", inputSchema = itemSchema) { _, input -> 2 * input.x }

    val error = assertFailsWith<IllegalArgumentException> { NodeTool(parallel) }

    assertContains(error.message.orEmpty(), "it is a parallel node")
  }

  @Test
  fun constructor_parallelFunctionNode_isRejected() {
    val error = assertFailsWith<IllegalArgumentException> { NodeTool(parallelNode(double)) }

    assertContains(error.message.orEmpty(), "it is a parallel node")
  }

  @Test
  fun constructor_parallelNodeOverWorkflow_isRejected() {
    val inner =
      testWorkflow("inner", edges = listOf(Edge(Start, node<Any?, String>("s") { _, _ -> "" })))

    val error = assertFailsWith<IllegalArgumentException> { NodeTool(parallelNode(inner)) }

    assertContains(error.message.orEmpty(), "it is a parallel node")
  }

  @Test
  fun constructor_functionNodeTakingNoArguments_declaresNoParameters() {
    val tool = NodeTool(node<Unit, String>("get_constant") { _, _ -> "constant_val" })

    assertEquals("get_constant", tool.declaration().name)
    assertNull(tool.declaration().parameters)
  }

  @Test
  fun constructor_functionNodeWhoseInputTypeHasNoSchema_isRejected() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        NodeTool(node<Any?, String>("untyped") { _, input -> input.toString() })
      }

    assertContains(error.message.orEmpty(), "does not have an inputSchema defined")
  }

  @Test
  fun constructor_functionNodeTakingAMessage_isRejected() {
    val echo =
      node<Content, String>("echo", inputSchema = Schema(type = Type.OBJECT)) { _, message ->
        message.text()
      }

    val error = assertFailsWith<IllegalArgumentException> { NodeTool(echo) }

    assertContains(error.message.orEmpty(), "its input type is Content or Event")
  }

  @Test
  fun nameAndDescription_emptyOverrides_fallBackToNode() {
    val tool =
      NodeTool(testWorkflow("collect", description = "node desc"), name = "", description = "")

    assertEquals("collect", tool.name)
    assertEquals("node desc", tool.description)
  }

  @Test
  fun nameAndDescription_notGiven_defaultToNode() {
    val tool = NodeTool(testWorkflow("collect", description = "Collects info."))

    assertEquals("collect", tool.name)
    assertEquals("Collects info.", tool.description)
  }

  @Test
  fun description_nodeHasNone_fallsBackToGenerated() {
    assertEquals("Executes the node: collect", NodeTool(testWorkflow("collect")).description)
  }

  @Test
  fun nameAndDescription_given_overrideNode() {
    val tool =
      NodeTool(
        testWorkflow("collect", description = "node desc"),
        name = "alias",
        description = "tool desc",
      )

    assertEquals("alias", tool.name)
    assertEquals("tool desc", tool.description)
  }

  // -- Declaration --

  @Test
  fun declaration_objectInputSchema_isParametersAsIs() {
    assertEquals(querySchema, NodeTool(testWorkflow("sub_workflow")).declaration().parameters)
  }

  @Test
  fun declaration_primitiveInputSchema_isWrappedUnderRequest() {
    val parameters =
      NodeTool(testWorkflow("sub_workflow", inputSchema = Schema(type = Type.STRING)))
        .declaration()
        .parameters

    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
    assertEquals(listOf("request"), parameters.required)
  }

  @Test
  fun declaration_typedFunctionNode_infersParameters() {
    val tool = NodeTool(node<UserProfileInput, String>("lookup") { _, input -> input.userId })

    val parameters = tool.declaration().parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(setOf("userId"), parameters.properties?.keys)
  }

  @Test
  fun declaration_outputSchema_isResponse() {
    val outputSchema =
      Schema(type = Type.OBJECT, properties = mapOf("result" to Schema(type = Type.STRING)))

    assertEquals(
      outputSchema,
      NodeTool(testWorkflow("sub_workflow", outputSchema = outputSchema)).declaration().response,
    )
  }

  @Test
  fun declaration_outputSchemaIsAnyOf_hasNoResponse() {
    val tool = NodeTool(testWorkflow("sub_workflow", outputSchema = stringOrInteger))

    assertNull(tool.declaration().response)
  }

  @Test
  fun declaration_outputPropertyIsAnyOf_hasNoResponse() {
    val outputSchema = Schema(type = Type.OBJECT, properties = mapOf("value" to stringOrInteger))

    assertNull(
      NodeTool(testWorkflow("sub_workflow", outputSchema = outputSchema)).declaration().response
    )
  }

  @Test
  fun declaration_outputItemsAreAnyOf_hasNoResponse() {
    val outputSchema = Schema(type = Type.ARRAY, items = stringOrInteger)

    assertNull(
      NodeTool(testWorkflow("sub_workflow", outputSchema = outputSchema)).declaration().response
    )
  }

  @Test
  fun declaration_nodeHasNoOutputSchema_hasNoResponse() {
    assertNull(NodeTool(testWorkflow("sub_workflow")).declaration().response)
  }

  // -- Running --

  @Test
  fun run_primitiveInput_unwrapsRequestArgument() = runBlocking {
    val tool = NodeTool(node<String, String>("greet") { _, request -> "Hello, $request!" })

    assertEquals("Hello, world!", tool.run(runnerToolContext(), mapOf("request" to "world")))
  }

  @Test
  fun run_typedNode_decodesArgumentsAndEncodesOutput() = runBlocking {
    val tool =
      NodeTool(
        node<UserProfileInput, UserProfile>("get_user_profile") { _, input ->
          UserProfile(id = input.userId, role = "admin")
        }
      )

    val result = tool.run(runnerToolContext(), mapOf("userId" to "user_123"))

    assertEquals(mapOf("id" to "user_123", "role" to "admin"), result)
  }

  @Test
  fun run_explicitSchemaLeavesOutOptionalField_runsWithItsDefault() = runBlocking {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("text" to Schema(type = Type.STRING), "tag" to Schema(type = Type.STRING)),
        required = listOf("text"),
      )
    val tool =
      NodeTool(
        node<Note, String>("annotate", inputSchema = schema) { _, note ->
          "${note.text}:${note.tag}"
        }
      )

    assertEquals("hi:null", tool.run(runnerToolContext(), mapOf("text" to "hi")))
  }

  @Test
  fun run_mapOutput_answersUnchanged() = runBlocking {
    val tool =
      NodeTool(
        node<UserProfileInput, Map<String, Any?>>("get_user_profile") { _, input ->
          mapOf("id" to input.userId, "role" to "admin")
        }
      )

    val result = tool.run(runnerToolContext(), mapOf("userId" to "user_123"))

    assertEquals(mapOf("id" to "user_123", "role" to "admin"), result)
  }

  @Test
  fun run_noOutput_answersWithNullResult() = runBlocking {
    val tool = NodeTool(node<String, String?>("no_output") { _, _ -> null })

    assertEquals(mapOf("result" to null), tool.run(runnerToolContext(), mapOf("request" to "x")))
  }

  @Test
  fun run_functionNodeThatTakesNoArguments_ignoresArguments() = runBlocking {
    val tool = NodeTool(node<Unit, String>("get_constant") { _, _ -> "constant_val" })

    assertEquals("constant_val", tool.run(runnerToolContext(), emptyMap()))
    assertEquals("constant_val", tool.run(runnerToolContext(), mapOf("stray" to 1)))
  }

  @Test
  fun run_jsonNumbers_decodeIntoTypedCollections() = runBlocking {
    val tool = NodeTool(node<List<Int>, Int>("sum") { _, numbers -> numbers.sum() })

    // Model arguments carry JSON numbers as Long, and the Int output comes back as a JSON Long.
    assertEquals(3L, tool.run(runnerToolContext(), mapOf("request" to listOf(1L, 2L))))
  }

  @Test
  fun run_untypedMapInput_receivesArgumentsUnchanged() = runBlocking {
    val typeOfN =
      node<Map<String, Any?>, String>("type_of_n", inputSchema = anyObject) { _, input ->
        input["n"]?.let { it::class.simpleName }.orEmpty()
      }

    assertEquals("Int", NodeTool(typeOfN).run(runnerToolContext(), mapOf("n" to 5)))
  }

  @Test
  fun run_nullInNonNullSlot_returnsValidationError() = runBlocking {
    val size =
      node<Map<String, Any>, Int>("size", inputSchema = anyObject) { _, input -> input.size }

    assertValidationError(NodeTool(size).run(runnerToolContext(), mapOf("a" to null)))
  }

  @Test
  fun run_artifactAndStateChanges_areEmittedToSession() = runBlocking {
    val tool =
      NodeTool(
        node<String, String>("writer") { context, _ ->
          context.actions.artifactDelta["report.txt"] = 2
          context.updateState("written", true)
          "ok"
        }
      )
    val toolContext = runnerToolContext()

    val unused = tool.run(toolContext, mapOf("request" to "x"))

    val emitted = toolContext.invocationContext.session.events.single()
    assertEquals(2, emitted.actions.artifactDelta["report.txt"])
    assertEquals(true, emitted.actions.stateDelta["written"])
    assertEquals(true, toolContext.invocationContext.session.state["written"])
  }

  @Test
  fun run_skipSummarization_reachesTheToolContext() = runBlocking {
    val tool =
      NodeTool(
        node<String, String>("direct") { context, _ ->
          context.actions.skipSummarization = true
          "ok"
        }
      )
    val toolContext = runnerToolContext()

    val unused = tool.run(toolContext, mapOf("request" to "x"))

    assertTrue(toolContext.actions.skipSummarization)
  }

  @Test
  fun run_nodeThatRequestsInput_failsBecauseItWouldPause() = runBlocking {
    val tool =
      NodeTool(
        node<String, String?>("asks") { _, _ ->
          emit(Event(author = "", longRunningToolIds = setOf("ask_user")))
          null
        }
      )

    val error =
      assertFailsWith<IllegalStateException> {
        tool.run(runnerToolContext(), mapOf("request" to "x"))
      }
    assertContains(error.message.orEmpty(), "cannot pause yet")
  }

  @Test
  fun run_argumentsOfWrongType_returnValidationError() = runBlocking {
    val tool = NodeTool(node<TypedInput, Int>("typed") { _, input -> input.x })

    val result = assertIs<Map<*, *>>(tool.run(runnerToolContext(), mapOf("x" to "abc")))

    assertEquals(setOf("error"), result.keys)
    assertContains(
      result["error"] as String,
      "Invoking `typed()` failed due to argument validation errors",
    )
  }

  @Test
  fun run_argumentsOutsideSchema_returnValidationError() = runBlocking {
    val echo = node<Map<String, Any?>, String>("echo") { _, input -> input.toString() }
    val tool = NodeTool(testWorkflow("sub_workflow", edges = listOf(Edge(Start, echo))))

    assertValidationError(tool.run(runnerToolContext(), mapOf("query" to 5)))
  }

  @Test
  fun run_argumentsTheEntryNodeRejects_returnValidationError() = runBlocking {
    val tool = NodeTool(testWorkflow("one", listOf(Edge(Start, double)), inputSchema = anyObject))

    assertValidationError(tool.run(runnerToolContext(), mapOf("x" to "bad")))
  }

  @Test
  fun run_argumentsOneOfTwoEntryNodesRejects_returnValidationError() = runBlocking {
    val greet = node<UserProfileInput, String>("greet") { _, input -> "Hi ${input.userId}" }
    val edges = listOf(Edge(Start, double), Edge(Start, greet))
    val tool = NodeTool(testWorkflow("two", edges, inputSchema = anyObject))

    assertValidationError(tool.run(runnerToolContext(), mapOf("x" to 1L)))
  }

  @Test
  fun run_argumentsANestedWorkflowsEntryNodeRejects_returnValidationError() = runBlocking {
    val inner = testWorkflow("inner", listOf(Edge(Start, double)), inputSchema = anyObject)
    val tool = NodeTool(testWorkflow("nested", listOf(Edge(Start, inner)), inputSchema = anyObject))

    assertValidationError(tool.run(runnerToolContext(), mapOf("x" to "bad")))
  }

  @Test
  fun run_itemAParallelFunctionNodeRejects_returnsValidationError() = runBlocking {
    val edges = listOf(Edge(Start, parallelNode(double)))
    val tool = NodeTool(testWorkflow("each", edges, inputSchema = Schema(type = Type.ARRAY)))
    val items = listOf(mapOf("x" to 1L), mapOf("x" to "bad"))

    assertValidationError(tool.run(runnerToolContext(), mapOf("request" to items)))
  }

  @Test
  fun run_itemAParallelWorkflowsEntryNodeRejects_returnsValidationError() = runBlocking {
    val inner = testWorkflow("inner", listOf(Edge(Start, double)), inputSchema = anyObject)
    val edges = listOf(Edge(Start, parallelNode(inner)))
    val tool = NodeTool(testWorkflow("each", edges, inputSchema = Schema(type = Type.ARRAY)))
    val items = listOf(mapOf("x" to 1L), mapOf("x" to "bad"))

    assertValidationError(tool.run(runnerToolContext(), mapOf("request" to items)))
  }

  @Test
  fun run_nullAnEntryNodeRejects_returnsValidationError() = runBlocking {
    val edges = listOf(Edge(Start, node<Int, Int>("increment") { _, n -> n + 1 }))
    val nullableInteger = Schema(type = Type.INTEGER, nullable = true)
    val tool = NodeTool(testWorkflow("nullable", edges, inputSchema = nullableInteger))

    assertValidationError(tool.run(runnerToolContext(), mapOf("request" to null)))
  }

  @Test
  fun run_nullForNonNullableWorkflowSchema_returnsValidationError() = runBlocking {
    val edges = listOf(Edge(Start, node<Any?, String>("echo") { _, input -> "$input" }))
    val tool = NodeTool(testWorkflow("echo_wf", edges, inputSchema = Schema(type = Type.STRING)))

    assertValidationError(tool.run(runnerToolContext(), mapOf("request" to null)))
  }

  @Test
  fun run_nullForNonNullableNodeSchema_returnsValidationError() = runBlocking {
    val echo =
      node<String?, String>("echo", inputSchema = Schema(type = Type.STRING)) { _, input ->
        input ?: "none"
      }

    assertValidationError(NodeTool(echo).run(runnerToolContext(), mapOf("request" to null)))
  }

  @Test
  fun run_nullForCustomNodesNonNullableSchema_returnsValidationError() = runBlocking {
    assertValidationError(
      NodeTool(EchoNode("custom")).run(runnerToolContext(), mapOf("request" to null))
    )
  }

  @Test
  fun run_nullForAnyOfWithNull_runsTheNode() = runBlocking {
    val schema = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.NULL)))

    assertEquals(
      "null",
      NodeTool(EchoNode("echo", schema)).run(runnerToolContext(), mapOf("request" to null)),
    )
  }

  @Test
  fun run_nullForNullTypeSchema_runsTheNode() = runBlocking {
    val schema = Schema(type = Type.NULL)

    assertEquals(
      "null",
      NodeTool(EchoNode("echo", schema)).run(runnerToolContext(), mapOf("request" to null)),
    )
  }

  @Test
  fun run_nullForUntypedSchema_runsTheNode() = runBlocking {
    assertEquals(
      "null",
      NodeTool(EchoNode("echo", Schema())).run(runnerToolContext(), mapOf("request" to null)),
    )
  }

  @Test
  fun run_nullItemForParallelCustomNode_returnsValidationError() = runBlocking {
    val edges = listOf(Edge(Start, parallelNode(EchoNode("echo"))))
    val tool = NodeTool(testWorkflow("each", edges, inputSchema = Schema(type = Type.ARRAY)))

    assertValidationError(tool.run(runnerToolContext(), mapOf("request" to listOf(null))))
  }

  @Test
  fun run_nullForNullableSchema_runsTheNode() = runBlocking {
    val schema = Schema(type = Type.STRING, nullable = true)
    val echo = node<String?, String>("echo", inputSchema = schema) { _, input -> input ?: "none" }

    assertEquals("none", NodeTool(echo).run(runnerToolContext(), mapOf("request" to null)))
  }

  @Test
  fun run_argumentsEveryEntryNodeAccepts_runTheWorkflow() = runBlocking {
    val tool =
      NodeTool(
        testWorkflow(
          "each",
          listOf(Edge(Start, parallelNode(double))),
          inputSchema = Schema(type = Type.ARRAY),
        )
      )

    val result =
      tool.run(runnerToolContext(), mapOf("request" to listOf(mapOf("x" to 1L), mapOf("x" to 2L))))

    assertEquals(listOf(2L, 4L), result)
  }

  @Test
  fun run_joinNodeFedByStart_isNotCheckedAsAnEntryNode() = runBlocking {
    val doubled =
      node<TypedInput, Map<String, Int>>("double") { _, input -> mapOf("x" to 2 * input.x) }
    // Each predecessor's output must be an object; the raw arguments' values are not.
    val join = JoinNode("join", inputSchema = anyObject)
    val edges = listOf(Edge(Start, doubled), Edge(Start, join), Edge(doubled, join))
    val tool = NodeTool(testWorkflow("joined", edges, inputSchema = anyObject))

    val result = assertIs<Map<*, *>>(tool.run(runnerToolContext(), mapOf("x" to 5L)))

    assertEquals(mapOf("x" to 10L), result["double"])
  }

  @Test
  fun run_joinNodeFedByStart_leavesTheCheckToTheOtherEntryNode() = runBlocking {
    val join = JoinNode("join")
    val edges = listOf(Edge(Start, double), Edge(Start, join), Edge(double, join))
    val tool = NodeTool(testWorkflow("joined", edges, inputSchema = anyObject))

    assertValidationError(tool.run(runnerToolContext(), mapOf("x" to "bad")))
  }

  @Test
  fun run_contentOutput_answersWithItsJsonForm() = runBlocking {
    val tool = NodeTool(OutputNode("reply") { Content.fromText(Role.MODEL, "$it") })

    assertEquals(helloJson, tool.run(runnerToolContext(), mapOf("request" to "hi")))
  }

  @Test
  fun run_contentInMapOutput_answersWithItsJsonForm() = runBlocking {
    val tool =
      NodeTool(OutputNode("reply") { mapOf("reply" to Content.fromText(Role.MODEL, "$it")) })

    assertEquals(
      mapOf("reply" to helloJson),
      tool.run(runnerToolContext(), mapOf("request" to "hi")),
    )
  }

  @Test
  fun run_contentInListOutput_answersWithItsJsonForm() = runBlocking {
    val tool = NodeTool(OutputNode("reply") { listOf(Content.fromText(Role.MODEL, "$it")) })

    assertEquals(listOf(helloJson), tool.run(runnerToolContext(), mapOf("request" to "hi")))
  }

  @Test
  fun run_jsonElementOutput_answersWithPlainValues() = runBlocking {
    val tool =
      NodeTool(
        OutputNode("json") { JsonObject(mapOf("n" to JsonPrimitive(1), "s" to JsonPrimitive("a"))) }
      )

    assertEquals(
      mapOf("n" to 1L, "s" to "a"),
      tool.run(runnerToolContext(), mapOf("request" to "x")),
    )
  }

  @Test
  fun run_jsonNullOutput_answersWithNullResult() = runBlocking {
    val tool = NodeTool(OutputNode("json") { JsonNull })

    assertEquals(mapOf("result" to null), tool.run(runnerToolContext(), mapOf("request" to "x")))
  }

  @Test
  fun run_intOutput_answersWithJsonLong() = runBlocking {
    val tool = NodeTool(OutputNode("count") { mapOf("count" to 3) })

    assertEquals(mapOf("count" to 3L), tool.run(runnerToolContext(), mapOf("request" to "x")))
  }

  @Test
  fun run_nodeFailure_throwsTheNodesOwnError() = runBlocking {
    val tool =
      NodeTool(node<String, String>("fails") { _, _ -> throw IllegalStateException("boom") })

    val error =
      assertFailsWith<IllegalStateException> {
        tool.run(runnerToolContext(), mapOf("request" to "x"))
      }
    assertEquals("boom", error.message)
  }

  @Test
  fun run_functionCallId_keysTheBranch() = runBlocking {
    val agentContext = testInvocationContext(branch = "agent")

    assertEquals(
      "agent.where@fc_1",
      branchReporter.run(runnerToolContext(agentContext, "fc_1"), mapOf("request" to "x")),
    )
  }

  @Test
  fun run_noFunctionCallId_runsOnABranchOfItsOwn() = runBlocking {
    val branch = assertIs<String>(branchReporter.run(runnerToolContext(), mapOf("request" to "x")))

    assertTrue(branch.startsWith("where@"))
  }

  @Test
  fun run_callIdWithPathCharacters_runsUnderAFreshRunId() = runBlocking {
    val tool = NodeTool(node<String, String>("echo") { _, input -> input })
    val runIds = mutableSetOf<String>()

    for (callId in listOf("a.b", "a@b", "a/b")) {
      val toolContext = runnerToolContext(functionCallId = callId)

      assertEquals("x", tool.run(toolContext, mapOf("request" to "x")))
      val path = toolContext.invocationContext.session.events.single().nodeInfo?.path.orEmpty()
      val runId = path.removePrefix("echo@")
      assertTrue(runId.isNotEmpty() && runId.none { it in "./@" }, "$callId ran as $path")
      runIds += runId
    }
    // Distinct run IDs, so calls never share a path and replay each other's workflow steps.
    assertEquals(3, runIds.size)
  }

  @Test
  fun run_missingRequestArgument_returnsValidationError() = runBlocking {
    val tool = NodeTool(node<String?, String>("echo") { _, input -> input ?: "none" })

    val result = assertIs<Map<*, *>>(tool.run(runnerToolContext(), emptyMap()))

    assertContains(result["error"] as String, "Missing required argument \"request\".")
  }

  @Test
  fun run_stateChanges_reachLaterStepsAndSession() = runBlocking {
    val write = node<Any?, Unit>("write") { context, _ -> context.updateState("k", "v") }
    val read = node<Any?, String>("read") { context, _ -> context.state["k"].toString() }
    val tool =
      NodeTool(testWorkflow("sub_workflow", edges = listOf(Edge(Start, write), Edge(write, read))))
    val toolContext = runnerToolContext()

    val result = tool.run(toolContext, mapOf("query" to "q"))

    assertEquals("v", result)
    assertEquals("v", toolContext.invocationContext.session.state["k"])
  }

  @Test
  fun run_workflowWithJoinNode_returnsTerminalOutput() = runBlocking {
    val nodeA = node<Any?, Map<String, Int>>("NodeA") { _, _ -> mapOf("a" to 1) }
    val nodeB = node<Any?, Map<String, Int>>("NodeB") { _, _ -> mapOf("b" to 2) }
    val join = JoinNode("NodeJoin")
    val format =
      node<Map<String, Map<String, Int>>, String>("format") { _, input ->
        "A is ${input.getValue("NodeA")["a"]} and B is ${input.getValue("NodeB")["b"]}."
      }
    val edges =
      listOf(
        Edge(Start, nodeA),
        Edge(Start, nodeB),
        Edge(nodeA, join),
        Edge(nodeB, join),
        Edge(join, format),
      )
    val tool = NodeTool(testWorkflow("sub_workflow", edges = edges))

    assertEquals("A is 1 and B is 2.", tool.run(runnerToolContext(), mapOf("query" to "q")))
  }

  @Test
  fun run_nestedWorkflow_returnsInnerOutput() = runBlocking {
    val inner = node<Any?, String>("inner_node") { _, _ -> "inner_output" }
    val innerWorkflow = Workflow(name = "inner_wf", edges = listOf(Edge(Start, inner)))
    val outer = node<Any?, String>("outer_node") { _, _ -> "outer_output" }
    val tool =
      NodeTool(
        testWorkflow("sub_workflow", edges = listOf(Edge(Start, outer), Edge(outer, innerWorkflow)))
      )

    assertEquals("inner_output", tool.run(runnerToolContext(), mapOf("query" to "q")))
  }

  @Test
  fun run_workflowWithDynamicChild_returnsParentOutput() = runBlocking {
    val child = node<String, String>("child") { _, input -> "child got: $input" }
    val parent =
      node<Any?, String>("parent_node", rerunOnResume = true) { context, _ ->
        "parent got: ${context.runNode(child, nodeInput = "hello")}"
      }
    val tool = NodeTool(testWorkflow("sub_workflow", edges = listOf(Edge(Start, parent))))

    assertEquals(
      "parent got: child got: hello",
      tool.run(runnerToolContext(), mapOf("query" to "q")),
    )
  }

  @Test
  fun run_workflowWithoutOutput_failsBecauseItWouldWait() = runBlocking {
    val silent = node<Any?, Unit>("silent") { _, _ -> }
    val tool = NodeTool(testWorkflow("sub_workflow", edges = listOf(Edge(Start, silent))))

    val error =
      assertFailsWith<IllegalStateException> {
        tool.run(runnerToolContext(), mapOf("query" to "q"))
      }
    assertContains(error.message.orEmpty(), "cannot pause yet")
  }

  // -- Through an agent --

  @Test
  fun agentCall_nodeAnswers_agentContinues() = runBlocking {
    val greet = NodeTool(node<String, String>("greet") { _, request -> "Hello, $request!" })

    val events =
      runAgent(greet, modelFunctionCallResponse("greet", mapOf("request" to "world"), id = "fc_1"))

    val call = events.first { event -> event.functionCalls().any { it.id == "fc_1" } }
    assertTrue(call.longRunningToolIds.isEmpty())
    assertEquals(
      listOf<Map<String, Any?>>(mapOf("result" to "Hello, world!")),
      events.flatMap { it.functionResponses() }.map { it.response },
    )
    assertTrue(events.any { it.content?.text() == DONE })
  }

  @Test
  fun agentCall_classicRoot_failsNamingTheNodePath() = runBlocking {
    val greet = NodeTool(node<String, String>("greet") { _, request -> "Hello, $request!" })
    val agent =
      agent(greet, modelFunctionCallResponse("greet", mapOf("request" to "world"), id = "fc_1"))
    val runner = InMemoryRunner(App(appName = "app", rootAgent = agent))

    val error =
      assertFailsWith<IllegalStateException> {
        runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
      }

    assertContains(error.message.orEmpty(), "App(rootNode = agent)")
  }

  @Test
  fun agentCall_parallelCallsOfOneTool_runOnSeparateBranches() = runBlocking {
    val itemSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("item_id" to Schema(type = Type.STRING)),
        required = listOf("item_id"),
      )
    val process =
      NodeTool(
        node<Map<String, Any?>, String>("process_tool", inputSchema = itemSchema) { context, input
          ->
          "${input["item_id"]} on ${context.invocationContext.branch}"
        }
      )

    val events =
      runAgent(
        process,
        modelParallelFunctionCallsResponse(
          FunctionCall(name = "process_tool", args = mapOf("item_id" to "alpha"), id = "fc_alpha"),
          FunctionCall(name = "process_tool", args = mapOf("item_id" to "beta"), id = "fc_beta"),
        ),
      )

    val nodeEvents = events.filter { it.output != null }
    assertEquals(
      mapOf<String?, Any?>(
        "process_tool@fc_alpha" to "alpha on process_tool@fc_alpha",
        "process_tool@fc_beta" to "beta on process_tool@fc_beta",
      ),
      nodeEvents.associate { it.branch to it.output },
    )
    val responses = events.flatMap { it.functionResponses() }.associate { it.id to it.response }
    assertEquals(mapOf("result" to "alpha on process_tool@fc_alpha"), responses["fc_alpha"])
    assertEquals(mapOf("result" to "beta on process_tool@fc_beta"), responses["fc_beta"])
  }

  @Test
  fun agentCall_workflowSteps_areSavedBeforeFunctionResponse() = runBlocking {
    val step1 =
      node<Any?, String>("step1") { context, _ ->
        context.updateState("step1_done", true)
        "step1_out"
      }
    val step2 =
      node<String, String>("step2") { context, input -> "$input:${context.state["step1_done"]}" }
    val wf = testWorkflow("sub_workflow", edges = listOf(Edge(Start, step1), Edge(step1, step2)))
    val tool = NodeTool(wf, name = "wf_tool")

    val events =
      runAgent(tool, modelFunctionCallResponse("wf_tool", mapOf("query" to "q"), id = "fc_1"))

    // The workflow runs under the calling agent's path, keyed by the call ID.
    val step1Index = events.indexOfFirst {
      it.nodeInfo?.path == "parent_agent@1/sub_workflow@fc_1/step1@1"
    }
    val step2Index = events.indexOfFirst {
      it.nodeInfo?.path == "parent_agent@1/sub_workflow@fc_1/step2@1"
    }
    val responseIndex = events.indexOfFirst { it.functionResponses().isNotEmpty() }
    assertTrue(step1Index in 0..<step2Index)
    assertTrue(step2Index < responseIndex)
    assertEquals("wf_tool@fc_1", events[step1Index].branch)
    assertEquals(
      mapOf<String, Any?>("result" to "step1_out:true"),
      events[responseIndex].functionResponses().single().response,
    )
  }

  @Test
  fun agentCall_workflowStepSkipsSummarization_endsTurnWithFunctionResponse() = runBlocking {
    val report =
      node<Query, String>("report") { context, input ->
        context.actions.skipSummarization = true
        "Exact workflow report for ${input.query}"
      }
    val tool = NodeTool(testWorkflow("report_workflow", edges = listOf(Edge(Start, report))))
    val modelRequests = mutableListOf<LlmRequest>()
    val model =
      recordingModel(
        modelRequests,
        listOf(
          modelFunctionCallResponse("report_workflow", mapOf("query" to "Project X"), id = "fc_1"),
          LlmResponse(content = modelMessage("Never sent, because summarization is skipped.")),
        ),
      )
    val agent = LlmAgent(name = "parent_agent", model = model, tools = listOf(tool))

    val events = runRoot(agent, "Report X")

    assertEquals(1, modelRequests.size)
    val last = events.last()
    assertTrue(last.actions.skipSummarization)
    assertEquals(
      listOf<Map<String, Any?>>(mapOf("result" to "Exact workflow report for Project X")),
      last.functionResponses().map { it.response },
    )
    assertEquals(listOf(last), events.filter { it.author == "parent_agent" && it.isFinalResponse })
  }

  @Test
  fun agentCall_callIdWithSlash_keepsNodeMessagesOutOfLaterRequests() = runBlocking {
    val chatty =
      node<String, String>("chatty") { _, input ->
        emit(Event(author = "chatty", content = modelMessage("Working on $input")))
        "done"
      }
    val modelRequests = mutableListOf<LlmRequest>()
    val model =
      recordingModel(
        modelRequests,
        listOf(
          modelFunctionCallResponse("chatty", mapOf("request" to "it"), id = "call/1"),
          LlmResponse(content = modelMessage(DONE)),
          LlmResponse(content = modelMessage("Still done.")),
        ),
      )
    val agent = LlmAgent(name = "parent_agent", model = model, tools = listOf(NodeTool(chatty)))
    val runner = InMemoryRunner(App(appName = "app", rootNode = agent))

    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go")).toList()
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("and now?")).toList()

    // The caller still sees the message; only the model is spared it.
    assertTrue(events.any { it.content?.text() == "Working on it" })
    assertEquals(3, modelRequests.size)
    for (request in modelRequests.drop(1)) {
      assertTrue(request.parts().none { it.text.orEmpty().contains("Working on it") })
      assertTrue(request.parts().any { it.functionResponse?.response == mapOf("result" to "done") })
    }
  }

  @Test
  fun agentStepInWorkflow_toolMessages_stayOutOfNextRequest() = runBlocking {
    val analyze =
      node<Query, Map<String, String>>("analyze") { _, input ->
        emit(Event(author = "analyze", content = modelMessage("Starting ${input.query}...")))
        emit(Event(author = "analyze", content = modelMessage("Finished ${input.query}.")))
        mapOf("rows" to "42")
      }
    val modelRequests = mutableListOf<LlmRequest>()
    val worker =
      LlmAgent(
        name = "worker",
        model =
          recordingModel(
            modelRequests,
            listOf(
              modelFunctionCallResponse("analyze", mapOf("query" to "sales"), id = "fc_1"),
              LlmResponse(content = modelMessage("All done.")),
            ),
          ),
        tools = listOf(NodeTool(analyze)),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, worker)))

    val unused = runRoot(workflow, "run sales")

    assertEquals(2, modelRequests.size)
    val parts = modelRequests[1].parts()
    assertTrue(parts.any { it.text.orEmpty().contains("run sales") })
    assertTrue(parts.any { it.functionCall?.name == "analyze" })
    assertTrue(parts.any { it.functionResponse?.response == mapOf("rows" to "42") })
    assertTrue(parts.none { it.text.orEmpty().contains("] said:") })
    assertTrue(parts.none { it.text.orEmpty().contains("Finished sales") })
  }

  @Test
  fun agentCall_fanOutWorkflow_keepsBranchMessagesOutOfTheNextTurn() = runBlocking {
    val branchA =
      node<Any?, Map<String, Int>>("branch_a") { _, _ ->
        emit(Event(author = "branch_a", content = modelMessage("Branch A progress")))
        mapOf("a" to 1)
      }
    val branchB =
      node<Any?, Map<String, Int>>("branch_b") { _, _ ->
        emit(Event(author = "branch_b", content = modelMessage("Branch B progress")))
        mapOf("b" to 2)
      }
    val join = JoinNode("join")
    val combine =
      node<Map<String, Map<String, Int>>, Int>("combine") { _, input ->
        input.getValue("branch_a").getValue("a") + input.getValue("branch_b").getValue("b")
      }
    val edges =
      listOf(
        Edge(Start, branchA),
        Edge(Start, branchB),
        Edge(branchA, join),
        Edge(branchB, join),
        Edge(join, combine),
      )
    val fanOut =
      NodeTool(
        testWorkflow("sub_wf", edges = edges, inputSchema = Schema(type = Type.OBJECT)),
        name = "fan_out_tool",
        description = "Runs two branches.",
      )
    val modelRequests = mutableListOf<LlmRequest>()
    val model =
      recordingModel(
        modelRequests,
        listOf(
          modelFunctionCallResponse("fan_out_tool", emptyMap(), id = "fc_1"),
          LlmResponse(content = modelMessage(DONE)),
          LlmResponse(content = modelMessage("Still done.")),
        ),
      )
    val agent = LlmAgent(name = "parent", model = model, tools = listOf(fanOut))
    val runner = InMemoryRunner(App(appName = "app", rootNode = agent))

    val events =
      runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("run it")).toList()
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("and now?")).toList()

    val nestedLeaves = events.mapNotNull { it.branch?.substringAfterLast('.', "") }.toSet()
    assertTrue(nestedLeaves.containsAll(setOf("branch_a@1", "branch_b@1")), "$nestedLeaves")
    assertEquals(3, modelRequests.size)
    val parts = modelRequests[2].parts()
    assertTrue(parts.none { it.text.orEmpty().contains("progress") })
    assertTrue(
      parts.any { (it.functionResponse?.response?.get("result") as? Number)?.toInt() == 3 }
    )
  }

  @Test
  fun agentStepInsideNodeTool_ownToolCalls_stayInItsHistory() = runBlocking {
    val add =
      DummyTool(name = "add", declares = true) { _, args ->
        (args.getValue("a") as Number).toLong() + (args.getValue("b") as Number).toLong()
      }
    val innerRequests = mutableListOf<LlmRequest>()
    val inner =
      LlmAgent(
        name = "inner",
        model =
          recordingModel(
            innerRequests,
            listOf(
              modelFunctionCallResponse("add", mapOf("a" to 1L, "b" to 2L), id = "fc_add"),
              LlmResponse(content = modelMessage("The sum is 3.")),
            ),
          ),
        tools = listOf(add),
      )
    val requestSchema =
      Schema(type = Type.OBJECT, properties = mapOf("request" to Schema(type = Type.STRING)))
    val subWorkflow =
      testWorkflow("sub_wf", listOf(Edge(Start, inner)), inputSchema = requestSchema)
    val parent =
      LlmAgent(
        name = "parent",
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              modelFunctionCallResponse(
                "sub_wf_tool",
                mapOf("request" to "add 1 and 2"),
                id = "fc_1",
              ),
              LlmResponse(content = modelMessage(DONE)),
            ),
          ),
        tools = listOf(NodeTool(subWorkflow, name = "sub_wf_tool", description = "Adds numbers.")),
      )

    val unused = runRoot(parent, "add 1 and 2")

    assertEquals(2, innerRequests.size)
    val firstContent = innerRequests[0].contents.first()
    assertEquals(Role.USER, firstContent.role)
    assertContains(firstContent.text(), "add 1 and 2")
    assertTrue("] said:" !in firstContent.text())
    val secondParts = innerRequests[1].parts()
    assertTrue(secondParts.any { it.functionCall?.name == "add" })
    assertTrue(
      secondParts.any { (it.functionResponse?.response?.get("result") as? Number)?.toInt() == 3 }
    )
  }

  @Test
  fun agentCall_stepFailure_reachesOnToolErrorCallbacksAsTheStepsOwnError() = runBlocking {
    val step1 = node<Any?, String>("step1") { _, _ -> "ok" }
    val step2 =
      node<String, String>("step2") { _, _ -> throw IllegalStateException("step2 failed") }
    val tool =
      NodeTool(testWorkflow("two_steps", edges = listOf(Edge(Start, step1), Edge(step1, step2))))
    val seenErrors = mutableListOf<Throwable>()
    val agent =
      LlmAgent(
        name = "parent_agent",
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              modelFunctionCallResponse("two_steps", mapOf("query" to "q"), id = "fc_1"),
              LlmResponse(content = modelMessage(DONE)),
            ),
          ),
        tools = listOf(tool),
        onToolErrorCallbacks =
          listOf(
            OnToolErrorCallback { _, _, _, error ->
              seenErrors += error
              CallbackChoice.Break(mapOf("handled" to true))
            }
          ),
      )

    val events = runRoot(agent)

    assertEquals("step2 failed", assertIs<IllegalStateException>(seenErrors.single()).message)
    assertEquals(
      listOf<Map<String, Any?>>(mapOf("handled" to true)),
      events.flatMap { it.functionResponses() }.map { it.response },
    )
  }

  @Test
  fun agentStepInWorkflow_multiStepNodeTool_answersWithLastStep() = runBlocking {
    val step1 = node<Any?, String>("step1") { _, _ -> "step1_out" }
    val step2 = node<String, String>("step2") { _, input -> "$input:step2" }
    val tool =
      NodeTool(testWorkflow("sub_workflow", edges = listOf(Edge(Start, step1), Edge(step1, step2))))
    val agent =
      agent(tool, modelFunctionCallResponse("sub_workflow", mapOf("query" to "q"), id = "fc_1"))
    val outer = Workflow(name = "outer", edges = listOf(Edge(Start, agent)))

    val events = runRoot(outer)

    assertTrue(events.none { it.errorCode != null })
    assertEquals(
      "step1_out:step2",
      events
        .single { it.nodeInfo?.path == "outer@1/parent_agent@1/sub_workflow@fc_1/step2@1" }
        .output,
    )
    assertEquals(
      mapOf<String, Any?>("result" to "step1_out:step2"),
      events.single { it.functionResponses().isNotEmpty() }.functionResponses().single().response,
    )
  }

  /** Runs one turn of an agent whose model first returns [firstResponse], then [DONE]. */
  private suspend fun runAgent(tool: NodeTool, firstResponse: LlmResponse): List<Event> =
    runRoot(agent(tool, firstResponse))

  /** Runs one turn of an app whose root node is [root] and returns its events. */
  private suspend fun runRoot(root: Node, message: String = "go"): List<Event> =
    InMemoryRunner(App(appName = "app", rootNode = root))
      .runAsync(userId = "u", sessionId = "s", newMessage = userMessage(message))
      .toList()

  /** An agent with [tool] whose model first returns [firstResponse], then [DONE]. */
  private fun agent(tool: NodeTool, firstResponse: LlmResponse) =
    LlmAgent(
      name = "parent_agent",
      model =
        DummyModel.createSequential(
          "model",
          listOf(firstResponse, LlmResponse(content = modelMessage(DONE))),
        ),
      tools = listOf(tool),
    )

  companion object {
    private const val DONE = "Done."
  }
}
