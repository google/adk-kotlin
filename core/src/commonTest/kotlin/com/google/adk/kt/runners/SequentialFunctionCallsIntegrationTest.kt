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
package com.google.adk.kt.runners

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LlmCallsLimitExceededException
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Multi-turn function calling: a single tool invoked across multiple model turns, and parallel
 * function calls in one turn that all mutate session state. Mirrors Python ADK's
 * `flows/llm_flows/test_functions_sequential.py` and `test_functions_parallel.py`.
 *
 * Verifies the runner correctly threads conversation history across turns: each successive
 * `LlmRequest` must contain the cumulative call/response history so the model can reason over its
 * previous tool outputs.
 */
class SequentialFunctionCallsIntegrationTest {

  /**
   * Three sequential model turns, each issuing one call to the same tool, followed by a final text
   * answer. Asserts the tool was invoked exactly three times.
   */
  @Test
  fun runAsync_toolCalledThreeTimesAcrossTurns_toolInvokedOncePerTurn() = runTest {
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel.createSequential(
            "scripted-model",
            listOf(
              modelFunctionCallResponse("increment", id = "call_0"),
              modelFunctionCallResponse("increment", id = "call_1"),
              modelFunctionCallResponse("increment", id = "call_2"),
              LlmResponse(content = modelMessage("Final answer.")),
            ),
          ),
        tools =
          listOf(
            DummyTool(
              name = "increment",
              onRun = { _, _ ->
                toolInvocations++
                mapOf("count" to toolInvocations)
              },
            )
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
        .toList()

    assertEquals(3, toolInvocations)
    val finalEvent = events.last { it.author == "test-agent" }
    assertEquals("Final answer.", finalEvent.content?.parts?.singleOrNull()?.text)
  }

  /**
   * Wraps the model in a capturing decorator so we can inspect every `LlmRequest` it receives.
   * Verifies that successive turns observe an ever-growing history (the runner forwards prior
   * function-call/response pairs to the model). Mirrors Python's `1 -> 3 -> 5 -> 7 entries`
   * progression assertion. (Once `DummyModel` exposes captured requests directly, the local
   * decorator can go away.)
   */
  @Test
  fun runAsync_toolCalledAcrossTurns_eachLlmRequestContainsAccumulatedHistory() = runTest {
    val capturedRequests = mutableListOf<LlmRequest>()
    val scripted =
      DummyModel.createSequential(
        "scripted-model",
        listOf(
          modelFunctionCallResponse("increment", id = "call_0"),
          modelFunctionCallResponse("increment", id = "call_1"),
          modelFunctionCallResponse("increment", id = "call_2"),
          LlmResponse(content = modelMessage("Final answer.")),
        ),
      )
    val capturingModel =
      object : Model {
        override val name = scripted.name

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            capturedRequests += request
            emitAll(scripted.generateContent(request, stream))
          }
      }
    val agent =
      LlmAgent(
        name = "test-agent",
        model = capturingModel,
        tools = listOf(DummyTool(name = "increment", onRun = { _, _ -> mapOf("ok" to true) })),
      )
    val runner = InMemoryRunner(agent = agent)

    runner
      .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
      .toList()

    // 4 turns total: 3 turns issuing function calls + 1 turn returning the final text answer.
    assertEquals(4, capturedRequests.size)
    val sizes = capturedRequests.map { it.contents.size }
    assertTrue(
      sizes.zipWithNext().all { (prev, next) -> next > prev },
      "expected request contents to grow monotonically across turns, got sizes=$sizes",
    )
  }

  /**
   * Parallel function calls in one turn: the model returns two `FunctionCall`s in a single
   * response, both writing to session state. Verifies both tools execute, both state mutations are
   * persisted, and the merged function-response event carries both responses.
   */
  @Test
  fun runAsync_parallelToolCalls_modifyState_mergedFunctionResponseEventCarriesCombinedStateDelta() =
    runTest {
      val agent =
        LlmAgent(
          name = "test-agent",
          model =
            DummyModel.createSequential(
              "parallel-model",
              listOf(
                modelParallelFunctionCallsResponse(
                  FunctionCall(name = "tool_a", id = "call_a"),
                  FunctionCall(name = "tool_b", id = "call_b"),
                ),
                LlmResponse(content = modelMessage("Final.")),
              ),
            ),
          tools =
            listOf(
              DummyTool(
                name = "tool_a",
                onRun = { ctx, _ ->
                  ctx.actions.stateDelta["key_a"] = "value_a"
                  mapOf("ok" to "a")
                },
              ),
              DummyTool(
                name = "tool_b",
                onRun = { ctx, _ ->
                  ctx.actions.stateDelta["key_b"] = "value_b"
                  mapOf("ok" to "b")
                },
              ),
            ),
        )
      val runner = InMemoryRunner(agent = agent)

      val events =
        runner
          .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("go"))
          .toList()

      val responseEvent =
        events.firstOrNull { it.functionResponses().size == 2 }
          ?: error("expected a merged function-response event with 2 responses")
      assertEquals(
        setOf("tool_a", "tool_b"),
        responseEvent.functionResponses().map { it.name }.toSet(),
      )

      val session =
        runner.sessionService.getSession(SessionKey(runner.appName, "user1", "session1"))
      assertNotNull(session)
      assertEquals("value_a", session.state["key_a"])
      assertEquals("value_b", session.state["key_b"])
    }

  /**
   * The model is scripted to request a tool call on every turn, so the agent loop would never
   * terminate on its own. Verifies that [RunConfig.maxLlmCalls] caps the invocation: the model is
   * invoked exactly `maxLlmCalls` times and the run then fails with
   * [LlmCallsLimitExceededException]. Mirrors Python ADK `test_run_async_with_max_llm_calls`.
   */
  @Test
  fun runAsync_modelKeepsCallingTool_maxLlmCallsCapAbortsRun() = runTest {
    var modelCalls = 0
    var toolInvocations = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel("looping-model") {
            modelCalls++
            flowOf(modelFunctionCallResponse("increment", id = "call_$modelCalls"))
          },
        tools =
          listOf(
            DummyTool(
              name = "increment",
              onRun = { _, _ ->
                toolInvocations++
                mapOf("count" to toolInvocations)
              },
            )
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    assertFailsWith<LlmCallsLimitExceededException> {
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("go"),
          runConfig = RunConfig(maxLlmCalls = 3),
        )
        .toList()
    }

    // The 4th turn trips the cap before the model is invoked again: exactly 3 LLM calls happen.
    assertEquals(3, modelCalls)
    assertEquals(3, toolInvocations)
  }
}
