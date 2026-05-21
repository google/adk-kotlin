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
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelParallelFunctionCallsResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.FunctionCall
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Verifies that parallel function calls emitted by the model in a single turn are dispatched
 * concurrently rather than sequentially. Mirrors Python ADK's
 * `test_parallel_function_execution_timing` in `flows/llm_flows/test_functions_simple.py` and Go
 * ADK's `TestHandleFunctionCallsAsync` in
 * `internal/llminternal/handle_function_calls_async_test.go`.
 *
 * **Why this matters.** When a model emits N function calls in one response, each tool's `run` is
 * suspended on independent IO. If the agent dispatched them serially, total latency would scale
 * linearly with N; ADK's contract is that they run concurrently so total latency tracks the slowest
 * single call. The Kotlin implementation in `InvocationContext.handleFunctionCalls` uses
 * `async`/`awaitAll` for this. This test exercises that path through the public Runner.
 *
 * **Timing assertion.** [runTest] runs on a [kotlinx.coroutines.test.TestScope] whose virtual clock
 * advances only when coroutines suspend on time-based operations like [delay]. Three concurrent
 * `delay(100ms)` calls advance virtual time by ~100ms total; serial would advance by ~300ms. The
 * control test below ensures this measurement actually distinguishes the two.
 */
class ParallelFunctionCallTimingTest {

  /**
   * Three tools each `delay(100ms)`, all invoked from a single model response. Virtual time must
   * advance by ~100ms (concurrent), not ~300ms (serial).
   */
  @Test
  fun runAsync_threeParallelToolCalls_dispatchedConcurrently() = runTest {
    val toolNames = listOf("tool_a", "tool_b", "tool_c")
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              modelParallelFunctionCallsResponse(
                *toolNames
                  .mapIndexed { idx, name ->
                    FunctionCall(name = name, args = emptyMap(), id = "call_$idx")
                  }
                  .toTypedArray()
              ),
              LlmResponse(content = modelMessage("all done")),
            ),
          ),
        tools = toolNames.map { name -> delayingTool(name) },
      )
    val runner = InMemoryRunner(agent = agent)

    val startTime = testScheduler.currentTime
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("do all")).toList()
    val elapsed = testScheduler.currentTime - startTime

    assertTrue(
      elapsed in DELAY_MS..(DELAY_MS * 2),
      "expected ~${DELAY_MS}ms (concurrent dispatch); got ${elapsed}ms - serial would take " +
        "~${DELAY_MS * toolNames.size}ms",
    )
  }

  /**
   * Control test: same three tools each `delay(100ms)`, but invoked in three SEPARATE model turns
   * (one tool call per turn). The runner must dispatch them serially, so virtual time advances by
   * ~3 × 100ms. This proves the timing assertion in the parallel test above actually distinguishes
   * concurrent dispatch from serial dispatch.
   */
  @Test
  fun runAsync_threeSequentialToolCalls_dispatchedSerially() = runTest {
    val toolNames = listOf("tool_a", "tool_b", "tool_c")
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel.createSequential(
            "model",
            listOf(
              modelFunctionCallResponse(toolNames[0], id = "call_0"),
              modelFunctionCallResponse(toolNames[1], id = "call_1"),
              modelFunctionCallResponse(toolNames[2], id = "call_2"),
              LlmResponse(content = modelMessage("all done")),
            ),
          ),
        tools = toolNames.map { name -> delayingTool(name) },
      )
    val runner = InMemoryRunner(agent = agent)

    val startTime = testScheduler.currentTime
    runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("do all")).toList()
    val elapsed = testScheduler.currentTime - startTime

    val expected = DELAY_MS * toolNames.size
    assertTrue(
      elapsed >= expected,
      "expected at least ${expected}ms (serial dispatch across $toolNames.size turns); got ${elapsed}ms",
    )
  }

  private fun delayingTool(name: String): DummyTool =
    DummyTool(
      name = name,
      onRun = { _, _ ->
        delay(DELAY_MS)
        mapOf("from" to name)
      },
    )

  private companion object {
    const val DELAY_MS = 100L
  }
}
