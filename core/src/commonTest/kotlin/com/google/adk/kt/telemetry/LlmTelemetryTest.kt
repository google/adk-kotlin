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

package com.google.adk.kt.telemetry

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.toTracePayload
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LlmTelemetryTest {

  private val dummyTracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    TelemetryConfig.captureMessageContent = false
  }

  @AfterTest
  fun tearDown() {
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun runAsync_recordsCallLlmSpan() =
    runTest(TracerElement(dummyTracer)) {
      val testModel =
        DummyModel.createSequential(
          "test-model",
          listOf(LlmResponse(content = modelMessage("Hello"))),
        )
      val agent = LlmAgent(name = "test-agent", model = testModel)
      val sessionService = InMemorySessionService()
      val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
      val context = InvocationContext(agent = agent, session = session, runConfig = null)

      agent.runAsync(context).toList()

      assertEquals(2, dummyTracer.recordedSpans.size)
      val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
      assertNotNull(span)
      assertEquals("test-model", span.attributes[TelemetryAttributes.GEN_AI_REQUEST_MODEL])
      // Payloads are emitted as "{}" (not omitted) so the ADK Dev UI can JSON.parse them.
      assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
      assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
    }

  @Test
  fun runAsync_recordsChunkReceivedEvents() =
    runTest(TracerElement(dummyTracer)) {
      val testModel =
        DummyModel("test-model") {
          flowOf(
            LlmResponse(content = modelMessage("Hel")),
            LlmResponse(content = modelMessage("lo")),
          )
        }
      val agent = LlmAgent(name = "test-agent", model = testModel)
      val sessionService = InMemorySessionService()
      val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
      val context = InvocationContext(agent = agent, session = session, runConfig = null)

      agent.runAsync(context).toList()

      assertEquals(2, dummyTracer.recordedSpans.size)
      val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
      assertNotNull(span)

      val events = span.events
      assertEquals(2, events.size)
      assertEquals("chunk_received", events[0])
      assertEquals("chunk_received", events[1])
    }

  // ---------------------------------------------------------------------------
  // Python parity: these mirror the Python suite
  // tests/unittests/telemetry/test_spans.py::test_trace_call_llm.
  // ---------------------------------------------------------------------------

  @Test
  fun runAsync_callLlm_setsGenAiSystem() =
    runTest(TracerElement(dummyTracer)) {
      val testModel =
        DummyModel.createSequential(
          "test-model",
          listOf(LlmResponse(content = modelMessage("Hello"))),
        )
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      assertEquals("gcp.vertex.agent", span.attributes[TelemetryAttributes.GEN_AI_SYSTEM])
    }

  @Test
  fun runAsync_callLlm_setsRequestConfigAttributes() =
    runTest(TracerElement(dummyTracer)) {
      val testModel =
        DummyModel.createSequential(
          "test-model",
          listOf(LlmResponse(content = modelMessage("Hello"))),
        )
      val agent =
        LlmAgent(
          name = "test-agent",
          model = testModel,
          generateContentConfig = GenerateContentConfig(topP = 0.95f, maxOutputTokens = 1024),
        )

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      val topP = span.attributes[TelemetryAttributes.GEN_AI_REQUEST_TOP_P]
      assertNotNull(topP)
      assertEquals(0.95, topP as Double, absoluteTolerance = 1e-4)
      assertEquals(1024L, span.attributes[TelemetryAttributes.GEN_AI_REQUEST_MAX_TOKENS])
    }

  @Test
  fun runAsync_callLlm_setsUsageTokens() =
    runTest(TracerElement(dummyTracer)) {
      val response =
        LlmResponse(
          content = modelMessage("Hello"),
          usageMetadata = UsageMetadata(promptTokenCount = 50, candidatesTokenCount = 60),
        )
      val testModel = DummyModel.createSequential("test-model", listOf(response))
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      assertEquals(50L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
      assertEquals(60L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
    }

  @Test
  fun runAsync_callLlm_aggregatesInputOutputAndRecordsCacheAndReasoningTokens() =
    runTest(TracerElement(dummyTracer)) {
      // Parity with Python TokenUsage: input = prompt + tool-use, output = candidates + thoughts,
      // plus separate cache-read and reasoning ("thoughts") counts.
      val response =
        LlmResponse(
          content = modelMessage("Hello"),
          usageMetadata =
            UsageMetadata(
              promptTokenCount = 50,
              candidatesTokenCount = 60,
              thoughtsTokenCount = 15,
              toolUsePromptTokenCount = 10,
              cachedContentTokenCount = 5,
            ),
        )
      val testModel = DummyModel.createSequential("test-model", listOf(response))
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      assertEquals(60L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
      assertEquals(75L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
      assertEquals(5L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS])
      assertEquals(15L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS])
    }

  @Test
  fun runAsync_callLlm_setsReasoningTokensLimitFromThinkingBudget() =
    runTest(TracerElement(dummyTracer)) {
      val testModel =
        DummyModel.createSequential(
          "test-model",
          listOf(LlmResponse(content = modelMessage("Hello"))),
        )
      val agent =
        LlmAgent(
          name = "test-agent",
          model = testModel,
          generateContentConfig =
            GenerateContentConfig(thinkingConfig = ThinkingConfig(thinkingBudget = 2048)),
        )

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      assertEquals(2048L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_REASONING_TOKENS_LIMIT])
    }

  @Test
  fun runAsync_callLlm_noUsageMetadata_omitsUsageTokens() =
    runTest(TracerElement(dummyTracer)) {
      val response = LlmResponse(content = modelMessage("Hello"))
      val testModel = DummyModel.createSequential("test-model", listOf(response))
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      assertNull(span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
      assertNull(span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
    }

  @Test
  fun runAsync_callLlm_setsFinishReasons() =
    runTest(TracerElement(dummyTracer)) {
      val response = LlmResponse(content = modelMessage("Hello"), finishReason = FinishReason.STOP)
      val testModel = DummyModel.createSequential("test-model", listOf(response))
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      // OTEL models finish reasons as a lower-cased string list, e.g. ["stop"].
      assertEquals(
        listOf("stop"),
        span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
      )
    }

  @Test
  fun runAsync_callLlm_capturesRequestAndResponseWhenEnabled() =
    runTest(TracerElement(dummyTracer)) {
      TelemetryConfig.captureMessageContent = true
      val testModel =
        DummyModel.createSequential(
          "test-model",
          listOf(LlmResponse(content = modelMessage("Hello"))),
        )
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
      val llmRequest = span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST] as? String
      assertNotNull(llmRequest)
      assertNotEquals("{}", llmRequest)
      assertTrue(llmRequest.isNotEmpty())
      val llmResponse =
        span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] as? String
      assertNotNull(llmResponse)
      assertNotEquals("{}", llmResponse)
      assertTrue(llmResponse.isNotEmpty())
    }

  // ---------------------------------------------------------------------------
  // Python parity: LlmRequest trace view mirrors `_build_llm_request_for_trace`
  // (tests/unittests/telemetry/test_spans.py::test_trace_call_llm_with_binary_content).
  // ---------------------------------------------------------------------------

  @Test
  fun toTracePayload_dropsInlineDataPartsAndExcludesResponseSchema() {
    val request =
      LlmRequest(
        contents =
          listOf(
            Content(
              role = "user",
              parts =
                listOf(
                  Part(text = "hello"),
                  Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
                ),
            )
          ),
        config =
          GenerateContentConfig(
            responseSchema = Schema(description = "result schema"),
            temperature = 0.5f,
          ),
      )

    val payload = request.toTracePayload()

    // Binary inline_data parts are dropped; text parts are kept.
    val contents = payload["contents"] as List<*>
    val parts = (contents.single() as Content).parts
    assertEquals(1, parts.size)
    assertEquals("hello", parts.single().text)
    assertNull(parts.single().inlineData)
    // response_schema is excluded, other config is preserved.
    val config = payload["config"] as GenerateContentConfig
    assertNull(config.responseSchema)
    assertEquals(0.5f, config.temperature)
  }

  private suspend fun newContext(agent: LlmAgent): InvocationContext {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    return InvocationContext(agent = agent, session = session, runConfig = null)
  }
}
