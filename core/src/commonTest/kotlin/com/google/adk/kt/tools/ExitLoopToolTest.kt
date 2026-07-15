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
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.LoopAgentState
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ExitLoopToolTest {

  @Test
  fun run_setsEscalateAndSkipSummarization() = runTest {
    val tool = ExitLoopTool()
    val toolContext = testToolContext()

    val result = tool.run(toolContext, emptyMap())

    assertEquals(emptyMap<String, Any>(), result)
    assertTrue(toolContext.actions.escalate)
    assertTrue(toolContext.actions.skipSummarization)
  }

  @Test
  fun declaration_returnsCorrectToolDefinition() {
    val tool = ExitLoopTool()

    val declaration = tool.declaration()

    assertEquals("exit_loop", declaration.name)
    assertEquals(
      "Exits the loop.\n\nCall this function only when you are instructed to do so.\n",
      declaration.description,
    )
  }

  @Test
  fun llmAgent_withExitLoopTool_exitsLoopAgent() = runTest {
    val mockModel =
      DummyModel.createSequential(
        "mock-model",
        listOf(
          LlmResponse(content = modelMessage("Counter: 1")),
          LlmResponse(content = modelMessage("Counter: 2")),
          LlmResponse(content = modelMessage("Counter: 3")),
          modelFunctionCallResponse("exit_loop", id = "call_1"),
        ),
      )
    val llmAgent = LlmAgent(name = "llm-agent", model = mockModel, tools = listOf(ExitLoopTool()))
    val loopAgent = LoopAgent(name = "loop", subAgents = listOf(llmAgent), maxIterations = 5)

    val context = testInvocationContext(resumabilityConfig = ResumabilityConfig(isResumable = true))
    val events = loopAgent.runAsync(context).toList()

    val functionCallEvents = events.filter {
      it.functionCalls().any { fc -> fc.name == "exit_loop" }
    }
    assertEquals(1, functionCallEvents.size)
    val functionResponseEvents = events.filter {
      it.functionResponses().any { fr -> fr.name == "exit_loop" }
    }
    assertEquals(1, functionResponseEvents.size)
    // 3 iterations of (text response + end-of-agent) plus the exit-loop iteration
    // (exit_loop call + response + end-of-agent) = 9. The loop emits all of a sub-agent's events
    // before stopping, including the end-of-agent marker on the escalating iteration.
    val llmAgentEvents = events.filter { it.author == "llm-agent" }
    assertEquals(9, llmAgentEvents.size)
    val stateEvents = events.filter { it.actions.agentState != null && it.author == "loop" }
    assertEquals(4, stateEvents.size)
    val lastStateEvent = stateEvents.last()
    val loopState = lastStateEvent.actions.agentState as TypedData.MapValue
    val timesLooped = LoopAgentState.fromValue(loopState)?.timesLooped ?: -1
    // iteration_count is 3 because it reports completed iterations before the current one
    assertEquals(3, timesLooped)
  }
}
