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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AgentToolTest {

  @Test
  fun declaration_withInputSchema_buildsCorrectParameters() {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("query" to Schema(type = Type.STRING)),
        required = listOf("query"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
    assertEquals(inputSchema, declaration.parameters)
  }

  @Test
  fun declaration_withoutInputSchema_fallsBackToRequest() {
    val agent = LlmAgent(name = "inner-agent", model = DummyModel("test"))
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(1, parameters.properties?.size)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
  }

  @Test
  fun run_executesInnerAgent_returnsResult() = runTest {
    val responseContent = modelMessage("Response from inner agent")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val result = tool.run(context, mapOf("request" to "Hello"))

    assertEquals("Response from inner agent", result)
  }

  @Test
  fun run_executesInnerAgent_propagatesActions() = runTest {
    val responseContent = modelMessage("Response from inner agent")
    val eventActions =
      EventActions(
        stateDelta = mutableMapOf("testStateKey" to "testStateValue"),
        artifactDelta = mutableMapOf("testArtifactKey" to 1),
      )
    val agent =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = {
          emit(Event(author = "inner-agent", content = responseContent, actions = eventActions))
        },
      )
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val result = tool.run(context, mapOf("request" to "Hello"))

    assertEquals("Response from inner agent", result)
    assertEquals("testStateValue", context.actions.stateDelta["testStateKey"])
    assertEquals(1, context.actions.artifactDelta["testArtifactKey"])
  }

  /**
   * The agent wrapped by an [AgentTool] runs in a separate runner/invocation, so it starts on a
   * fresh (null) branch regardless of the caller's branch -- AgentTool isolates via its own runner,
   * not via the branch field. Mirrors Python ADK 1.x.
   */
  @Test
  fun run_wrappedAgent_runsOnFreshBranch() = runTest {
    val branches = mutableListOf<String?>()
    val inner =
      DummyAgent(
        name = "inner-agent",
        onRunAsync = { ctx ->
          branches.add(ctx.branch)
          emit(Event(author = "inner-agent", content = modelMessage("done")))
        },
      )
    val tool = AgentTool(inner)
    // The caller runs on a non-null branch; the wrapped agent must still start fresh.
    val context = testToolContext(testInvocationContext(agent = inner, branch = "caller.branch"))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(listOf<String?>(null), branches)
  }

  @Test
  fun run_withSkipSummarization_setsFlagInContext() = runTest {
    val responseContent = modelMessage("Response")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model)
    val tool = AgentTool(agent, skipSummarization = true)
    val context = testToolContext(testInvocationContext(agent = agent))

    val unused = tool.run(context, mapOf("request" to "Hello"))

    assertEquals(true, context.actions.skipSummarization)
  }

  @Test
  fun declaration_returnsCorrectTool() {
    val agent = LlmAgent(name = "inner-agent", model = DummyModel("test"))
    val tool = AgentTool(agent)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("inner-agent", declaration.name)
  }

  @Test
  fun declaration_withNonLlmAgent_resolvesSubAgentSchema() {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("query" to Schema(type = Type.STRING)),
        required = listOf("query"),
      )
    val inner =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val wrapper =
      com.google.adk.kt.testing.DummyAgent(name = "wrapper-agent", subAgents = listOf(inner))
    val tool = AgentTool(wrapper)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("wrapper-agent", declaration.name)
    assertEquals(inputSchema, declaration.parameters)
  }

  @Test
  fun declaration_withNonLlmAgentNoSubAgents_fallsBackToRequest() {
    val wrapper = com.google.adk.kt.testing.DummyAgent(name = "wrapper-agent")
    val tool = AgentTool(wrapper)

    val declaration = tool.declaration()

    assertNotNull(declaration)
    assertEquals("wrapper-agent", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    assertEquals(1, parameters.properties?.size)
    assertEquals(Type.STRING, parameters.properties?.get("request")?.type)
  }

  @Test
  fun run_throughInMemoryRunner_executesSuccessfully() = runTest {
    val agentTool =
      AgentTool(
        LlmAgent(
          name = "inner-agent",
          model =
            DummyModel("inner-model") {
              flowOf(LlmResponse(content = modelMessage("Response from inner agent")))
            },
        )
      )
    val mainModel =
      DummyModel(
        name = "main-model",
        flows =
          listOf(
            flowOf(
              modelFunctionCallResponse(
                name = "inner-agent",
                args = mapOf("request" to "Hello inner"),
              )
            ),
            flowOf(LlmResponse(content = modelMessage("Final Answer"))),
          ),
      )
    val mainAgent = LlmAgent(name = "main-agent", model = mainModel, tools = listOf(agentTool))
    val runner = InMemoryRunner(agent = mainAgent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("hi"))
        .toList()

    assertEquals(
      listOf(
        "main-agent" to
          Part(
            functionCall = FunctionCall("inner-agent", args = mapOf("request" to "Hello inner"))
          ),
        "main-agent" to
          Part(
            functionResponse =
              FunctionResponse(
                "inner-agent",
                response = mapOf("result" to "Response from inner agent"),
              )
          ),
        "main-agent" to "Final Answer",
      ),
      simplifyEvents(events),
    )
  }

  @Test
  fun run_withValidInputSchema_executesInnerAgent() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("name" to Schema(type = Type.STRING), "age" to Schema(type = Type.INTEGER)),
        required = listOf("name"),
      )
    val responseContent = modelMessage("Hello John")
    val model = DummyModel("test") { flowOf(LlmResponse(content = responseContent)) }
    val agent = LlmAgent(name = "inner-agent", model = model, inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    val result = tool.run(context, mapOf("name" to "John", "age" to 30L))

    assertEquals("Hello John", result)
  }

  @Test
  fun run_withInvalidInputSchema_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("age" to Schema(type = Type.INTEGER)),
        required = listOf("age"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> { tool.run(context, mapOf("age" to "not-a-number")) }
  }

  @Test
  fun run_withMissingRequiredArg_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> { tool.run(context, emptyMap()) }
  }

  @Test
  fun run_withExtraArgNotInSchema_throwsIllegalArgumentException() = runTest {
    val inputSchema =
      Schema(type = Type.OBJECT, properties = mapOf("name" to Schema(type = Type.STRING)))
    val agent =
      LlmAgent(name = "inner-agent", model = DummyModel("test"), inputSchema = inputSchema)
    val tool = AgentTool(agent)
    val context = testToolContext(testInvocationContext(agent = agent))

    assertFailsWith<IllegalArgumentException> {
      tool.run(context, mapOf("name" to "John", "unexpected" to "value"))
    }
  }
}
