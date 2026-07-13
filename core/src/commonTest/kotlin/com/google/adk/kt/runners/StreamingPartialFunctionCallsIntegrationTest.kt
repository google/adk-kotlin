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
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Streaming (SSE) parallel function calls: the model streams one partial event per tool as each
 * call arrives, then a single aggregated (non-partial) event carrying both calls (reusing the same
 * function-call ids). Each tool must execute exactly once - partial function-call events are
 * surfaced to consumers but skipped for execution (see [LlmAgentTurn]).
 *
 * Mirrors ADK Java core `RunnerTest.runAsync_streamingPartialParallelFunctionCalls_*`.
 */
class StreamingPartialFunctionCallsIntegrationTest {

  @Test
  fun runAsync_streamingPartialParallelFunctionCalls_executesEachToolExactlyOnce() = runBlocking {
    val temperatureCall =
      FunctionCall(name = "getTemperature", args = mapOf("city" to "London"), id = "adk-temp-id")
    val conditionCall =
      FunctionCall(name = "getCondition", args = mapOf("city" to "London"), id = "adk-cond-id")

    fun response(vararg calls: FunctionCall, partial: Boolean) =
      LlmResponse(
        content = Content(Role.MODEL, calls.map { Part(functionCall = it) }),
        partial = partial,
      )

    // Turn 1 mirrors the streaming aggregator output: a partial event per call, then one aggregated
    // (non-partial) event carrying both. Turn 2 is the final text produced after both tools run.
    val streamingModel =
      DummyModel(
        "streaming-model",
        listOf(
          flowOf(
            response(temperatureCall, partial = true),
            response(conditionCall, partial = true),
            response(temperatureCall, conditionCall, partial = false),
          ),
          flowOf(LlmResponse(content = modelMessage("done"))),
        ),
      )

    var temperatureCalls = 0
    var conditionCalls = 0
    val agent =
      LlmAgent(
        name = "test-agent",
        model = streamingModel,
        tools =
          listOf(
            DummyTool(
              name = "getTemperature",
              onRun = { _, _ ->
                temperatureCalls++
                mapOf("temperature" to "21C")
              },
            ),
            DummyTool(
              name = "getCondition",
              onRun = { _, _ ->
                conditionCalls++
                mapOf("condition" to "Sunny")
              },
            ),
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("weather in London?"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()

    val partialFcEvents = events.filter { it.partial && it.functionCalls().isNotEmpty() }
    val aggregatedFcEvents = events.filter { !it.partial && it.functionCalls().isNotEmpty() }
    val functionResponses = events.sumOf { it.functionResponses().size }

    // Two partial function-call events (one per tool) are surfaced to consumers ...
    assertEquals(2, partialFcEvents.size)
    assertEquals(2, partialFcEvents.sumOf { it.functionCalls().size })
    // ... followed by exactly one aggregated event carrying BOTH calls ...
    assertEquals(1, aggregatedFcEvents.size)
    val aggregated = aggregatedFcEvents.single()
    assertEquals(2, aggregated.functionCalls().size)
    // ... whose function-call ids match the ones streamed in the partial events ...
    assertEquals(listOf("adk-temp-id", "adk-cond-id"), aggregated.functionCalls().map { it.id })
    // ... but each tool executes exactly once (partial events are skipped) ...
    assertEquals(1, temperatureCalls)
    assertEquals(1, conditionCalls)
    // ... producing exactly two function responses (one per call).
    assertEquals(2, functionResponses)
  }
}
