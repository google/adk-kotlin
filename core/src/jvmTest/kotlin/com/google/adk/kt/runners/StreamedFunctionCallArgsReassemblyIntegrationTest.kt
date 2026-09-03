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
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionCallingConfig
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.PartialArg
import com.google.adk.kt.types.PartialArgValue
import com.google.adk.kt.types.ToolConfig
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.Client
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Runner-level regression for streamed function-call arguments driven through the real [Gemini]
 * aggregator: the model streams two calls whose arguments arrive as `partialArgs` with
 * `willContinue`, each terminated by a separate empty marker chunk. The aggregator must reassemble
 * both calls (no drop, no arg bleed) and the runner must execute both tools.
 *
 * Complements [StreamingPartialFunctionCallsIntegrationTest], which covers the post-aggregator
 * parallel-partial-event contract; this one exercises the aggregator itself end-to-end.
 *
 * JVM-only: it builds a GenAI SDK `Client`, which the SDK rejects on Android.
 */
class StreamedFunctionCallArgsReassemblyIntegrationTest {

  @Test
  fun runAsync_streamedFunctionCallArgs_reassembledAndToolsExecuted(): Unit = runBlocking {
    // A fake backend that streams raw chunks the aggregator must reassemble. Turn 1 has two calls,
    // each ended by a standalone empty marker chunk (willContinue unset). Turn 2 is the final text.
    val fakeModels =
      object : Gemini.GeminiModels {
        var turn = 0

        override fun generateContentStream(
          model: String,
          contents: List<Content>,
          config: GenerateContentConfig,
        ): Flow<GenerateContentResponse> =
          if (turn++ == 0) {
            // Two multi-arg calls: getTemperature streams city across two chunks plus a unit arg;
            // getCondition uses distinct values. Each call ends with an empty willContinue=false
            // marker.
            flowOf(
              fcChunk(FunctionCall(name = "getTemperature", willContinue = true)),
              fcChunk(partialArg("\$.city", "Krak")),
              fcChunk(partialArg("\$.city", "ow")),
              fcChunk(partialArg("\$.unit", "C")),
              fcChunk(FunctionCall(willContinue = false)),
              fcChunk(FunctionCall(name = "getCondition", willContinue = true)),
              fcChunk(partialArg("\$.city", "Warsaw")),
              fcChunk(partialArg("\$.unit", "F")),
              fcChunk(FunctionCall(willContinue = false), finishReason = FinishReason.STOP),
            )
          } else {
            flowOf(textChunk("Done."))
          }

        override suspend fun generateContent(
          model: String,
          contents: List<Content>,
          config: GenerateContentConfig,
        ): GenerateContentResponse = throw UnsupportedOperationException("stream only")
      }
    val model = Gemini(Client(apiKey = "fake"), "gemini-3.1-flash-preview", models = fakeModels)

    val agent =
      LlmAgent(
        name = "test-agent",
        model = model,
        tools =
          listOf(
            DummyTool(name = "getTemperature", onRun = { _, _ -> mapOf("temperature" to "21C") }),
            DummyTool(name = "getCondition", onRun = { _, _ -> mapOf("condition" to "Sunny") }),
          ),
        generateContentConfig =
          GenerateContentConfig(
            toolConfig =
              ToolConfig(
                functionCallingConfig = FunctionCallingConfig(streamFunctionCallArguments = true)
              )
          ),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("Weather and time in Krakow?"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()

    // Both streamed calls are reassembled with their own args (no drop, no arg bleed) ...
    val reassembledCalls =
      events.flatMap { it.functionCalls() }.filter { it.args.isNotEmpty() }.associateBy { it.name }
    assertThat(reassembledCalls["getTemperature"]?.args)
      .containsExactly("city", "Krakow", "unit", "C")
    assertThat(reassembledCalls["getCondition"]?.args)
      .containsExactly("city", "Warsaw", "unit", "F")
    // ... and both tools are executed.
    val executedTools = events.flatMap { it.functionResponses() }.map { it.name }
    assertThat(executedTools).containsExactly("getTemperature", "getCondition")
  }

  private fun partialArg(jsonPath: String, value: String): FunctionCall =
    FunctionCall(
      partialArgs =
        listOf(PartialArg(value = PartialArgValue.StringValue(value), jsonPath = jsonPath)),
      willContinue = true,
    )

  private fun fcChunk(
    functionCall: FunctionCall,
    finishReason: FinishReason? = null,
  ): GenerateContentResponse =
    GenerateContentResponse(
      candidates =
        listOf(
          Candidate(
            content = Content(role = "model", parts = listOf(Part(functionCall = functionCall))),
            finishReason = finishReason,
          )
        )
    )

  private fun textChunk(text: String): GenerateContentResponse =
    GenerateContentResponse(
      candidates =
        listOf(
          Candidate(
            content = Content(role = "model", parts = listOf(Part(text = text))),
            finishReason = FinishReason.STOP,
          )
        )
    )
}
