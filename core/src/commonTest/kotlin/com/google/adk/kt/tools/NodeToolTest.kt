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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable private data class UserProfileInput(val userId: String)

@Serializable private data class UserProfile(val id: String, val role: String)

@Serializable private data class TypedInput(val x: Int)

class NodeToolTest {

  private val objectSchema =
    Schema(
      type = Type.OBJECT,
      properties = mapOf("query" to Schema(type = Type.STRING)),
      required = listOf("query"),
    )

  private fun workflow(
    name: String = "sub_workflow",
    edges: List<Edge> = emptyList(),
    description: String = "",
    inputSchema: Schema? = objectSchema,
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
  fun constructor_rejectsAgent() {
    val error =
      assertFailsWith<IllegalArgumentException> { NodeTool(DummyAgent(name = "my_agent")) }

    assertContains(error.message.orEmpty(), "cannot be wrapped as a NodeTool")
  }

  @Test
  fun constructor_requiresInputSchemaOnNonFunctionNode() {
    val error = assertFailsWith<IllegalArgumentException> { NodeTool(workflow(inputSchema = null)) }

    assertContains(error.message.orEmpty(), "does not have an inputSchema defined")
  }

  @Test
  fun constructor_acceptsFunctionNodeThatTakesNoArguments() {
    val tool = NodeTool(node<Unit, String>("get_constant") { _, _ -> "constant_val" })

    assertEquals("get_constant", tool.declaration().name)
    assertNull(tool.declaration().parameters)
  }

  @Test
  fun constructor_rejectsFunctionNodeWhoseInputTypeHasNoSchema() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        NodeTool(node<Any?, String>("untyped") { _, input -> input.toString() })
      }

    assertContains(error.message.orEmpty(), "does not have an inputSchema defined")
  }

  @Test
  fun constructor_rejectsFunctionNodeThatTakesAMessage() {
    val echo =
      node<Content, String>("echo", inputSchema = Schema(type = Type.OBJECT)) { _, message ->
        message.text()
      }

    val error = assertFailsWith<IllegalArgumentException> { NodeTool(echo) }

    assertContains(error.message.orEmpty(), "its input type is Content or Event")
  }

  @Test
  fun nameAndDescription_emptyOverridesFallBackToNode() {
    val tool =
      NodeTool(workflow(name = "collect", description = "node desc"), name = "", description = "")

    assertEquals("collect", tool.name)
    assertEquals("node desc", tool.description)
  }

  @Test
  fun nameAndDescription_defaultToNode() {
    val tool = NodeTool(workflow(name = "collect", description = "Collects info."))

    assertEquals("collect", tool.name)
    assertEquals("Collects info.", tool.description)
  }

  @Test
  fun description_fallsBackToGenerated_whenNodeHasNone() {
    assertEquals("Executes the node: collect", NodeTool(workflow(name = "collect")).description)
  }

  @Test
  fun nameAndDescription_overridesWin() {
    val tool =
      NodeTool(
        workflow(name = "collect", description = "node desc"),
        name = "alias",
        description = "tool desc",
      )

    assertEquals("alias", tool.name)
    assertEquals("tool desc", tool.description)
  }

  // -- Declaration --

  @Test
  fun declaration_usesObjectInputSchemaAsIs() {
    assertEquals(objectSchema, NodeTool(workflow()).declaration().parameters)
  }

  @Test
  fun declaration_wrapsPrimitiveInputSchemaUnderRequest() {
    val parameters =
      NodeTool(workflow(inputSchema = Schema(type = Type.STRING))).declaration().parameters

    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
    assertEquals(listOf("request"), parameters.required)
  }

  @Test
  fun declaration_infersParametersFromFunctionNodeInputType() {
    val tool = NodeTool(node<UserProfileInput, String>("lookup") { _, input -> input.userId })

    val parameters = tool.declaration().parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(setOf("userId"), parameters.properties?.keys)
  }

  @Test
  fun declaration_includesResponseFromOutputSchema() {
    val outputSchema =
      Schema(type = Type.OBJECT, properties = mapOf("result" to Schema(type = Type.STRING)))

    assertEquals(
      outputSchema,
      NodeTool(workflow(outputSchema = outputSchema)).declaration().response,
    )
  }

  @Test
  fun declaration_hasNoResponse_whenNodeHasNoOutputSchema() {
    assertNull(NodeTool(workflow()).declaration().response)
  }

  // -- Running --

  @Test
  fun run_primitiveInput_unwrapsRequestArgument() = runBlocking {
    val tool = NodeTool(node<String, String>("greet") { _, request -> "Hello, $request!" })

    assertEquals("Hello, world!", tool.run(testToolContext(), mapOf("request" to "world")))
  }

  @Test
  fun run_decodesArgumentsIntoInputTypeAndEncodesTypedOutput() = runBlocking {
    val tool =
      NodeTool(
        node<UserProfileInput, UserProfile>("get_user_profile") { _, input ->
          UserProfile(id = input.userId, role = "admin")
        }
      )

    val result = tool.run(testToolContext(), mapOf("userId" to "user_123"))

    assertEquals(mapOf("id" to "user_123", "role" to "admin"), result)
  }

  @Test
  fun run_returnsMapOutputUnchanged() = runBlocking {
    val tool =
      NodeTool(
        node<UserProfileInput, Map<String, Any?>>("get_user_profile") { _, input ->
          mapOf("id" to input.userId, "role" to "admin")
        }
      )

    val result = tool.run(testToolContext(), mapOf("userId" to "user_123"))

    assertEquals(mapOf("id" to "user_123", "role" to "admin"), result)
  }

  @Test
  fun run_noOutput_answersWithNullResult() = runBlocking {
    val tool = NodeTool(node<String, String?>("no_output") { _, _ -> null })

    assertEquals(mapOf("result" to null), tool.run(testToolContext(), mapOf("request" to "x")))
  }

  @Test
  fun run_functionNodeThatTakesNoArguments_ignoresArguments() = runBlocking {
    val tool = NodeTool(node<Unit, String>("get_constant") { _, _ -> "constant_val" })

    assertEquals("constant_val", tool.run(testToolContext(), emptyMap()))
    assertEquals("constant_val", tool.run(testToolContext(), mapOf("stray" to 1)))
  }

  @Test
  fun run_jsonNumbers_decodeIntoTypedCollections() = runBlocking {
    val tool = NodeTool(node<List<Int>, Int>("sum") { _, numbers -> numbers.sum() })

    // JSON numbers arrive as Long, as model arguments do.
    assertEquals(3, tool.run(testToolContext(), mapOf("request" to listOf(1L, 2L))))
  }

  @Test
  fun run_untypedMapInput_receivesArgumentsUnchanged() = runBlocking {
    val anyObject = Schema(type = Type.OBJECT)
    val typeOfN =
      node<Map<String, Any?>, String>("type_of_n", inputSchema = anyObject) { _, input ->
        input["n"]?.let { it::class.simpleName }.orEmpty()
      }

    assertEquals("Int", NodeTool(typeOfN).run(testToolContext(), mapOf("n" to 5)))
  }

  @Test
  fun run_nullInNonNullSlot_returnsValidationError() = runBlocking {
    val anyObject = Schema(type = Type.OBJECT)
    val size =
      node<Map<String, Any>, Int>("size", inputSchema = anyObject) { _, input -> input.size }

    val result = NodeTool(size).run(testToolContext(), mapOf("a" to null)) as Map<*, *>

    assertContains(result["error"] as String, "argument validation errors")
  }

  @Test
  fun run_artifactAndStateChanges_reachTheToolContext() = runBlocking {
    val tool =
      NodeTool(
        node<String, String>("writer") { context, _ ->
          context.actions.artifactDelta["report.txt"] = 2
          context.updateState("written", true)
          "ok"
        }
      )
    val toolContext = testToolContext()

    val unused = tool.run(toolContext, mapOf("request" to "x"))

    assertEquals(2, toolContext.actions.artifactDelta["report.txt"])
    assertEquals(true, toolContext.actions.stateDelta["written"])
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
    val toolContext = testToolContext()

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
        tool.run(testToolContext(), mapOf("request" to "x"))
      }
    assertContains(error.message.orEmpty(), "a tool call cannot pause yet")
  }

  @Test
  fun run_argumentsOfWrongType_returnValidationError() = runBlocking {
    val tool = NodeTool(node<TypedInput, Int>("typed") { _, input -> input.x })

    val result = tool.run(testToolContext(), mapOf("x" to "abc")) as Map<*, *>

    assertEquals(setOf("error"), result.keys)
    assertContains(
      result["error"] as String,
      "Invoking `typed()` failed due to argument validation errors",
    )
  }

  @Test
  fun run_argumentsOutsideSchema_returnValidationError() = runBlocking {
    val echo = node<Map<String, Any?>, String>("echo") { _, input -> input.toString() }
    val tool = NodeTool(workflow(edges = listOf(Edge(Start, echo))))

    val result = tool.run(testToolContext(), mapOf("query" to 5)) as Map<*, *>

    assertContains(result["error"] as String, "argument validation errors")
  }

  @Test
  fun run_nodeFailure_throwsTheNodesOwnError() = runBlocking {
    val tool =
      NodeTool(node<String, String>("fails") { _, _ -> throw IllegalStateException("boom") })

    val error =
      assertFailsWith<IllegalStateException> {
        tool.run(testToolContext(), mapOf("request" to "x"))
      }
    assertEquals("boom", error.message)
  }

  @Test
  fun run_runsOnBranchKeyedByFunctionCallId() = runBlocking {
    val tool =
      NodeTool(
        node<String, String>("where") { context, _ -> context.invocationContext.branch.orEmpty() }
      )
    val agentContext = testInvocationContext(branch = "agent")

    assertEquals(
      "agent.where@fc_1",
      tool.run(testToolContext(agentContext, functionCallId = "fc_1"), mapOf("request" to "x")),
    )
    assertEquals("where", tool.run(testToolContext(), mapOf("request" to "x")))
  }

  @Test
  fun run_stateChanges_reachLaterStepsAndTheToolResponse() = runBlocking {
    val write = node<Any?, Unit>("write") { context, _ -> context.updateState("k", "v") }
    val read = node<Any?, String>("read") { context, _ -> context.state["k"].toString() }
    val tool = NodeTool(workflow(edges = listOf(Edge(Start, write), Edge(write, read))))
    val toolContext = testToolContext()

    val result = tool.run(toolContext, mapOf("query" to "q"))

    assertEquals("v", result)
    assertEquals("v", toolContext.actions.stateDelta["k"])
    assertNull(toolContext.invocationContext.session.state["k"])
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
    val tool = NodeTool(workflow(edges = edges))

    assertEquals("A is 1 and B is 2.", tool.run(testToolContext(), mapOf("query" to "q")))
  }

  @Test
  fun run_nestedWorkflow_returnsInnerOutput() = runBlocking {
    val inner = node<Any?, String>("inner_node") { _, _ -> "inner_output" }
    val innerWorkflow = Workflow(name = "inner_wf", edges = listOf(Edge(Start, inner)))
    val outer = node<Any?, String>("outer_node") { _, _ -> "outer_output" }
    val tool = NodeTool(workflow(edges = listOf(Edge(Start, outer), Edge(outer, innerWorkflow))))

    assertEquals("inner_output", tool.run(testToolContext(), mapOf("query" to "q")))
  }

  @Test
  fun run_workflowWithDynamicChild_returnsParentOutput() = runBlocking {
    val child = node<String, String>("child") { _, input -> "child got: $input" }
    val parent =
      node<Any?, String>("parent_node", rerunOnResume = true) { context, _ ->
        "parent got: ${context.runNode(child, nodeInput = "hello")}"
      }
    val tool = NodeTool(workflow(edges = listOf(Edge(Start, parent))))

    assertEquals("parent got: child got: hello", tool.run(testToolContext(), mapOf("query" to "q")))
  }

  @Test
  fun run_workflowWithoutOutput_failsBecauseItWouldWait() = runBlocking {
    val silent = node<Any?, Unit>("silent") { _, _ -> }
    val tool = NodeTool(workflow(edges = listOf(Edge(Start, silent))))

    val error =
      assertFailsWith<IllegalStateException> { tool.run(testToolContext(), mapOf("query" to "q")) }
    assertContains(error.message.orEmpty(), "a tool call cannot pause yet")
  }

  // -- Through an agent --

  @Test
  fun agentCall_answersWithFunctionResponseAndContinues() = runBlocking {
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

    val responses = events.flatMap { it.functionResponses() }.associate { it.id to it.response }
    assertEquals(mapOf("result" to "alpha on process_tool@fc_alpha"), responses["fc_alpha"])
    assertEquals(mapOf("result" to "beta on process_tool@fc_beta"), responses["fc_beta"])
  }

  /** Runs one turn of an agent whose model first returns [firstResponse], then [DONE]. */
  private suspend fun runAgent(tool: NodeTool, firstResponse: LlmResponse): List<Event> {
    val agent =
      LlmAgent(
        name = "parent_agent",
        model =
          DummyModel(
            "model",
            listOf(flowOf(firstResponse), flowOf(LlmResponse(content = modelMessage(DONE)))),
          ),
        tools = listOf(tool),
      )
    return InMemoryRunner(App(appName = "app", rootAgent = agent))
      .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("go"))
      .toList()
  }

  private companion object {
    const val DONE = "Done."
  }
}
