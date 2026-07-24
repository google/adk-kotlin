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

package com.google.adk.kt.agents

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmAgentTurnTest {

  @Test
  fun runAsync_toolsetHookAddsTool_preservesHookTool() = runTest {
    val capturedRequests = mutableListOf<LlmRequest>()
    val hookTool = RunTrackingDeclaredTool("runtime_tool")
    val toolset =
      object : Toolset {
        override suspend fun processLlmRequest(
          toolContext: ToolContext,
          llmRequest: LlmRequest,
        ): LlmRequest = llmRequest.appendTools(listOf(hookTool))

        override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
          emptyList()
      }
    val model =
      DummyModel("hook-tool-model") { request ->
        capturedRequests += request
        flow {
          val hasFunctionResponse =
            request.contents.lastOrNull()?.parts?.any { it.functionResponse != null } == true
          if (hasFunctionResponse) {
            emit(LlmResponse(content = modelMessage("done")))
          } else {
            emit(modelFunctionCallResponse("runtime_tool", id = "runtime-tool-call"))
          }
        }
      }
    val agent = LlmAgent(name = "test-agent", model = model, toolsets = listOf(toolset))
    val runner = InMemoryRunner(agent)

    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("run tool"))
      .toList()

    assertEquals(2, capturedRequests.size)
    capturedRequests.forEach { request ->
      val declarationNames =
        request.config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
      assertEquals(listOf("runtime_tool"), declarationNames)
      assertEquals(1, request.toolsDict.count { it.name == "runtime_tool" })
    }
    assertEquals(1, hookTool.runCalls)
  }

  @Test
  fun runAsync_directToolNotInToolsDictCollidesWithToolset_keepsDirectTool() = runTest {
    val capturedRequests = mutableListOf<LlmRequest>()
    val directTool = ConfigOnlyRunTrackingDeclaredTool("shared_tool")
    val additionalTool = RunTrackingDeclaredTool("shared_tool")
    val toolset =
      object : Toolset {
        override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
          listOf(additionalTool)
      }
    val model =
      DummyModel("direct-tool-collision-model") { request ->
        capturedRequests += request
        flow {
          val hasFunctionResponse =
            request.contents.lastOrNull()?.parts?.any { it.functionResponse != null } == true
          if (hasFunctionResponse) {
            emit(LlmResponse(content = modelMessage("done")))
          } else {
            emit(modelFunctionCallResponse("shared_tool", id = "shared-tool-call"))
          }
        }
      }
    val agent =
      LlmAgent(
        name = "test-agent",
        model = model,
        tools = listOf(directTool),
        toolsets = listOf(toolset),
      )
    val runner = InMemoryRunner(agent)

    runner
      .runAsync(userId = "user", sessionId = "session", newMessage = userMessage("run tool"))
      .toList()

    assertEquals(2, capturedRequests.size)
    capturedRequests.forEach { request ->
      val declarationNames =
        request.config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
      assertEquals(listOf("shared_tool"), declarationNames)
      assertEquals(0, request.toolsDict.count { it.name == "shared_tool" })
    }
    assertEquals(1, directTool.runCalls)
    assertEquals(0, additionalTool.runCalls)
  }

  /**
   * Root `MissionControl` transfers to sub-agent `HeartOfGold`, which calls a tool and then
   * answers. Asserts the exact 5-event sequence (transfer call → transfer response → tool call →
   * tool response → final text) and that the tool runs exactly once. Regression guard for the
   * `tracedFlow` fix: the old `channelFlow + send` design could let the sub-agent's second turn see
   * stale conversation history and re-emit the tool call.
   */
  @Test
  fun runAsync_rootAgentDelegatesToSubAgentThatInvokesTool_emitsOrderedEventSequence() = runTest {
    var toolCallCount = 0
    val returnRandomNumberTool =
      DummyTool(
        name = "return_random_number",
        description = "Returns a random number.",
        onRun = { _, _ ->
          toolCallCount++
          mapOf("number" to 42)
        },
      )
    // Tool call on first turn; final text once a function response is in the conversation.
    val subModel =
      DummyModel("sub-model") { request ->
        flow {
          val lastContent = request.contents.lastOrNull()
          val isFunctionResponse = lastContent?.parts?.any { it.functionResponse != null } == true
          if (!isFunctionResponse) {
            emit(
              modelFunctionCallResponse(
                name = "return_random_number",
                args = emptyMap(),
                id = "call_1",
              )
            )
          } else {
            emit(LlmResponse(content = modelMessage("The answer is 42.")))
          }
        }
      }
    // Root agent model: unconditionally delegates to the HeartOfGold sub-agent.
    val rootModel =
      DummyModel("root-model") {
        flow { emit(modelTransferToAgentResponse("HeartOfGold", id = "transfer_call_1")) }
      }
    val heartOfGoldAgent =
      LlmAgent(
        name = "HeartOfGold",
        description = "Sub-agent that knows about random numbers.",
        model = subModel,
        tools = listOf(returnRandomNumberTool),
      )
    val missionControlAgent =
      LlmAgent(
        name = "MissionControl",
        description = "Root agent that transfers to HeartOfGold.",
        subAgents = listOf(heartOfGoldAgent),
        model = rootModel,
      )
    val runner = InMemoryRunner(agent = missionControlAgent)
    val userMessage = userMessage("What's the answer?")

    val flowEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage)
        .toList()
        .filter { it.author != Role.USER }

    assertEquals("tool should have been invoked exactly once", 1, toolCallCount)
    assertEquals(
      listOf(
        "MissionControl" to transferToAgentCallPart("HeartOfGold"),
        "MissionControl" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "HeartOfGold" to Part(functionCall = FunctionCall("return_random_number")),
        "HeartOfGold" to
          Part(
            functionResponse =
              FunctionResponse("return_random_number", response = mapOf("number" to 42))
          ),
        "HeartOfGold" to "The answer is 42.",
      ),
      simplifyEvents(flowEvents),
    )
  }
}

private open class RunTrackingDeclaredTool(name: String) :
  BaseTool(name = name, description = "Run-tracking tool $name") {
  var runCalls = 0

  override fun declaration() =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    runCalls++
    return emptyMap<String, Any>()
  }
}

private class ConfigOnlyRunTrackingDeclaredTool(name: String) : RunTrackingDeclaredTool(name) {
  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val existingTools = llmRequest.config.tools?.toMutableList() ?: mutableListOf()
    existingTools += Tool(functionDeclarations = listOf(declaration()))
    return llmRequest.copy(config = llmRequest.config.copy(tools = existingTools))
  }
}
