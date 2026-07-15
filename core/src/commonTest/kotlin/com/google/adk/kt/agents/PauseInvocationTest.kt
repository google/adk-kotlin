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

import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.ResumableEvents.END_OF_AGENT
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.simplifyResumableEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.ExitLoopTool
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Pause-on-long-running-function-call scenarios across the different agent structures, ported from
 * Python ADK 1.x `v1/tests/unittests/runners/test_pause_invocation.py`.
 *
 * All scenarios run a resumable app. A long-running tool returns a value, so its function-response
 * event is still emitted, but the function-call event carries `longRunningToolIds`, so the
 * invocation pauses afterwards without emitting an end-of-agent marker (keeping the agent's state
 * live for a later resume). Composite agents additionally emit their state-checkpoint events, which
 * [simplifyResumableEvents] surfaces as the agent's state value.
 */
class PauseInvocationTest {

  @Test
  fun singleLlmAgent_pausesOnLongRunningFunctionCall() = runTest {
    val agent =
      LlmAgent(name = "root_agent", model = singleCall("root"), tools = listOf(longRunningTool()))

    assertEquals(
      listOf("root_agent" to TEST_TOOL_CALL_PART, "root_agent" to TEST_TOOL_RESPONSE_PART),
      simplifyResumableEvents(resumableRunner(agent).runTurn("test")),
    )
  }

  @Test
  fun sequentialAgent_pausesOnFirstSubAgentLongRunningFunctionCall() = runTest {
    val subAgent1 =
      LlmAgent(name = "sub_agent_1", model = singleCall("sub1"), tools = listOf(longRunningTool()))
    val subAgent2 =
      LlmAgent(name = "sub_agent_2", model = singleCall("sub2"), tools = listOf(longRunningTool()))
    val rootAgent = SequentialAgent(name = "root_agent", subAgents = listOf(subAgent1, subAgent2))

    assertEquals(
      listOf(
        "root_agent" to SequentialAgentState("sub_agent_1").toStateValue(),
        "sub_agent_1" to TEST_TOOL_CALL_PART,
        "sub_agent_1" to TEST_TOOL_RESPONSE_PART,
      ),
      simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test")),
    )
  }

  @Test
  fun sequentialAgent_pausesOnSecondSubAgentLongRunningFunctionCall() = runTest {
    // The first sub-agent uses a regular tool and finishes; the second pauses on a long-running
    // one.
    val subAgent1 =
      LlmAgent(
        name = "sub_agent_1",
        model =
          DummyModel.createSequential(
            "sub1",
            listOf(
              modelFunctionCallResponse("test_tool", id = "lr-1a"),
              LlmResponse(content = modelMessage("model response after tool call")),
            ),
          ),
        tools = listOf(regularTool()),
      )
    val subAgent2 =
      LlmAgent(name = "sub_agent_2", model = singleCall("sub2"), tools = listOf(longRunningTool()))
    val rootAgent = SequentialAgent(name = "root_agent", subAgents = listOf(subAgent1, subAgent2))

    assertEquals(
      listOf(
        "root_agent" to SequentialAgentState("sub_agent_1").toStateValue(),
        "sub_agent_1" to TEST_TOOL_CALL_PART,
        "sub_agent_1" to TEST_TOOL_RESPONSE_PART,
        "sub_agent_1" to "model response after tool call",
        "sub_agent_1" to END_OF_AGENT,
        "root_agent" to SequentialAgentState("sub_agent_2").toStateValue(),
        "sub_agent_2" to TEST_TOOL_CALL_PART,
        "sub_agent_2" to TEST_TOOL_RESPONSE_PART,
      ),
      simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test")),
    )
  }

  @Test
  fun parallelAgent_pausesOneBranchWhileSiblingCompletes() = runTest {
    val subAgent1 =
      LlmAgent(name = "sub_agent_1", model = singleCall("sub1"), tools = listOf(longRunningTool()))
    val subAgent2 = delayedAgent("sub_agent_2")
    val rootAgent = ParallelAgent(name = "root_agent", subAgents = listOf(subAgent1, subAgent2))

    val simplified = simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test"))

    assertContains(simplified, "sub_agent_1" to TEST_TOOL_CALL_PART)
    assertContains(simplified, "sub_agent_2" to "Delayed message")
  }

  @Test
  fun nestedParallelAgent_pausesDeepBranchWhileSiblingsComplete() = runTest {
    val nestedSubAgent1 =
      LlmAgent(
        name = "nested_sub_agent_1",
        model = singleCall("nsub1"),
        tools = listOf(longRunningTool()),
      )
    val nestedSubAgent2 = delayedAgent("nested_sub_agent_2")
    val nestedParallel =
      ParallelAgent(
        name = "nested_parallel_agent",
        subAgents = listOf(nestedSubAgent1, nestedSubAgent2),
      )
    val subAgent1 = delayedAgent("sub_agent_1")
    val rootAgent =
      ParallelAgent(name = "root_agent", subAgents = listOf(subAgent1, nestedParallel))

    val simplified = simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test"))

    assertContains(simplified, "nested_sub_agent_1" to TEST_TOOL_CALL_PART)
    assertContains(simplified, "sub_agent_1" to "Delayed message")
    assertContains(simplified, "nested_sub_agent_2" to "Delayed message")
  }

  @Test
  fun nestedParallelAgent_pausesOnMultipleLongRunningFunctionCalls() = runTest {
    val nestedSubAgent1 =
      LlmAgent(
        name = "nested_sub_agent_1",
        model = singleCall("nsub1"),
        tools = listOf(longRunningTool()),
      )
    val nestedSubAgent2 = delayedAgent("nested_sub_agent_2")
    val nestedParallel =
      ParallelAgent(
        name = "nested_parallel_agent",
        subAgents = listOf(nestedSubAgent1, nestedSubAgent2),
      )
    val subAgent1 =
      LlmAgent(name = "sub_agent_1", model = singleCall("sub1"), tools = listOf(longRunningTool()))
    val rootAgent =
      ParallelAgent(name = "root_agent", subAgents = listOf(subAgent1, nestedParallel))

    val events = resumableRunner(rootAgent).runTurn("test")
    val simplified = simplifyResumableEvents(events)

    assertContains(simplified, "sub_agent_1" to TEST_TOOL_CALL_PART)
    assertContains(simplified, "nested_sub_agent_1" to TEST_TOOL_CALL_PART)
    // Both paused agents keep their state live: neither emits an end-of-agent marker.
    assertFalse(simplified.contains("sub_agent_1" to END_OF_AGENT))
    assertFalse(simplified.contains("nested_sub_agent_1" to END_OF_AGENT))
  }

  @Test
  fun loopAgent_pausesOnLongRunningFunctionCall() = runTest {
    val subAgent1 =
      LlmAgent(name = "sub_agent_1", model = singleText("sub1", "sub agent 1 response"))
    val subAgent2 =
      LlmAgent(name = "sub_agent_2", model = singleCall("sub2"), tools = listOf(longRunningTool()))
    val subAgent3 =
      LlmAgent(
        name = "sub_agent_3",
        model = DummyModel.createSequential("sub3", listOf(modelFunctionCallResponse("exit_loop"))),
        tools = listOf(ExitLoopTool()),
      )
    val rootAgent =
      LoopAgent(
        name = "root_agent",
        subAgents = listOf(subAgent1, subAgent2, subAgent3),
        maxIterations = 2,
      )

    assertEquals(
      listOf(
        "root_agent" to LoopAgentState("sub_agent_1", 0).toStateValue(),
        "sub_agent_1" to "sub agent 1 response",
        "sub_agent_1" to END_OF_AGENT,
        "root_agent" to LoopAgentState("sub_agent_2", 0).toStateValue(),
        "sub_agent_2" to TEST_TOOL_CALL_PART,
        "sub_agent_2" to TEST_TOOL_RESPONSE_PART,
      ),
      simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test")),
    )
  }

  @Test
  fun llmAgentTree_pausesAtDeepestAgentAfterTransfers() = runTest {
    val subLlmAgent2 =
      LlmAgent(
        name = "sub_llm_agent_2",
        model = singleCall("sub2"),
        tools = listOf(longRunningTool()),
      )
    val subLlmAgent1 =
      LlmAgent(
        name = "sub_llm_agent_1",
        model =
          DummyModel.createSequential(
            "sub1",
            listOf(modelTransferToAgentResponse("sub_llm_agent_2")),
          ),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential(
            "root",
            listOf(modelTransferToAgentResponse("sub_llm_agent_1")),
          ),
        subAgents = listOf(subLlmAgent1, subLlmAgent2),
      )

    assertEquals(
      listOf(
        "root_agent" to transferToAgentCallPart("sub_llm_agent_1"),
        "root_agent" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_llm_agent_1" to transferToAgentCallPart("sub_llm_agent_2"),
        "sub_llm_agent_1" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_llm_agent_2" to TEST_TOOL_CALL_PART,
        "sub_llm_agent_2" to TEST_TOOL_RESPONSE_PART,
      ),
      simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test")),
    )
  }

  @Test
  fun transferLoop_pausesWhenControlReturnsToRoot() = runTest {
    val subLlmAgent2 =
      LlmAgent(
        name = "sub_llm_agent_2",
        model =
          DummyModel.createSequential("sub2", listOf(modelTransferToAgentResponse("root_agent"))),
      )
    val subLlmAgent1 =
      LlmAgent(
        name = "sub_llm_agent_1",
        model =
          DummyModel.createSequential(
            "sub1",
            listOf(modelTransferToAgentResponse("sub_llm_agent_2")),
          ),
      )
    val rootAgent =
      LlmAgent(
        name = "root_agent",
        model =
          DummyModel.createSequential(
            "root",
            listOf(
              modelTransferToAgentResponse("sub_llm_agent_1"),
              modelFunctionCallResponse("test_tool", id = "lr-loop"),
            ),
          ),
        subAgents = listOf(subLlmAgent1, subLlmAgent2),
        tools = listOf(longRunningTool()),
      )

    assertEquals(
      listOf(
        "root_agent" to transferToAgentCallPart("sub_llm_agent_1"),
        "root_agent" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_llm_agent_1" to transferToAgentCallPart("sub_llm_agent_2"),
        "sub_llm_agent_1" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "sub_llm_agent_2" to transferToAgentCallPart("root_agent"),
        "sub_llm_agent_2" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "root_agent" to TEST_TOOL_CALL_PART,
        "root_agent" to TEST_TOOL_RESPONSE_PART,
      ),
      simplifyResumableEvents(resumableRunner(rootAgent).runTurn("test")),
    )
  }

  // -- Helpers -----------------------------------------------------------------------------------

  private companion object {
    const val USER_ID = "user"
    const val SESSION_ID = "session"

    /** The simplified (id-stripped) `test_tool` call and response parts used in expectations. */
    val TEST_TOOL_CALL_PART: Part = Part(functionCall = FunctionCall(name = "test_tool"))
    val TEST_TOOL_RESPONSE_PART: Part =
      Part(
        functionResponse =
          FunctionResponse(name = "test_tool", response = mapOf("result" to "result"))
      )

    fun resumableRunner(rootAgent: BaseAgent): InMemoryRunner =
      InMemoryRunner(agent = rootAgent, resumabilityConfig = ResumabilityConfig(isResumable = true))

    suspend fun InMemoryRunner.runTurn(text: String): List<Event> =
      runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = userMessage(text)).toList()

    /** A model whose single response is a `test_tool` function call. */
    fun singleCall(tag: String): DummyModel =
      DummyModel.createSequential(
        "model-$tag",
        listOf(modelFunctionCallResponse("test_tool", id = "lr-$tag")),
      )

    /** A model whose single response is the given text. */
    fun singleText(tag: String, text: String): DummyModel =
      DummyModel.createSequential("model-$tag", listOf(LlmResponse(content = modelMessage(text))))

    /**
     * A long-running `test_tool` that returns a value (so a function response is still emitted).
     */
    fun longRunningTool(): DummyTool =
      DummyTool(
        name = "test_tool",
        isLongRunning = true,
        onRun = { _, _ -> mapOf("result" to "result") },
      )

    /** A regular (non-long-running) `test_tool` returning the same value. */
    fun regularTool(): DummyTool =
      DummyTool(name = "test_tool", onRun = { _, _ -> mapOf("result" to "result") })

    /** A non-LLM agent that immediately emits a single "Delayed message" text event. */
    fun delayedAgent(name: String): DummyAgent =
      DummyAgent(
        name = name,
        onRunAsync = { ctx ->
          emit(
            Event(
              invocationId = ctx.invocationId,
              author = name,
              branch = ctx.branch,
              content = modelMessage("Delayed message"),
            )
          )
        },
      )
  }
}
