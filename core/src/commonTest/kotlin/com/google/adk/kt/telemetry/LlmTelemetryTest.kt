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
import com.google.adk.kt.agents.genAiSystemName
import com.google.adk.kt.agents.recordTokenUsage
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.adk.kt.models.toTracePayload
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummySpan
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.kt.types.ThinkingLevel
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LlmTelemetryTest {

  private val dummyTracer = DummyTracer()

  @BeforeTest
  fun setUp() {
    Telemetry.setTracerForTest(dummyTracer)
    TelemetryConfig.captureMessageContent = false
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun runAsync_recordsCallLlmSpan() = runTest {
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

    assertEquals(3, dummyTracer.recordedSpans.size)
    val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
    assertNotNull(span)
    assertEquals("test-model", span.attributes[TelemetryAttributes.GEN_AI_REQUEST_MODEL])
    // Payloads are emitted as "{}" (not omitted) so the ADK Dev UI can JSON.parse them.
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_REQUEST])
    assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE])
  }

  @Test
  fun runAsync_recordsChunkReceivedEvents() = runTest {
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

    assertEquals(3, dummyTracer.recordedSpans.size)
    val span = dummyTracer.recordedSpans.find { it.name == "call_llm" }
    assertNotNull(span)

    val events = span.events
    assertEquals(2, events.size)
    assertEquals("chunk_received", events[0])
    assertEquals("chunk_received", events[1])
  }

  @Test
  fun runAsync_streamingChunks_callLlmSpanEventIdMatchesFinalEventId() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flowOf(
          LlmResponse(content = modelMessage("Part 1"), partial = true),
          LlmResponse(content = modelMessage("Part 1 Part 2"), partial = false),
        )
      }
    val agent = LlmAgent(name = "test-agent", model = testModel)

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val finalEvent = emittedEvents.last { !it.partial && it.content != null }
    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(finalEvent.id, span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_errorRecoveredAfterPartialChunk_spanEventIdMatchesRecoveredEvent() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flow {
          emit(LlmResponse(content = modelMessage("Part 1"), partial = true))
          throw IllegalStateException("stream broke")
        }
      }
    val recover = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Recovered")))
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, onModelErrorCallbacks = listOf(recover))

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val recoveredEvent = emittedEvents.last()
    assertEquals("Recovered", recoveredEvent.content?.parts?.single()?.text)
    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(recoveredEvent.id, span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_errorRecovered_setsRecoveredFinishReasons() = runBlocking {
    val testModel = DummyModel("test-model") { flow { throw IllegalStateException("model boom") } }
    val recover = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(
        LlmResponse(content = modelMessage("Recovered"), finishReason = FinishReason.STOP)
      )
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, onModelErrorCallbacks = listOf(recover))

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(
      listOf("stop"),
      span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
    )
  }

  @Test
  fun runAsync_beforeModelCallbackShortCircuits_spanEventIdMatchesEmittedEvent() = runBlocking {
    val testModel =
      DummyModel("test-model") { flowOf(LlmResponse(content = modelMessage("unused"))) }
    val shortCircuit = BeforeModelCallback { _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Cached")))
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, beforeModelCallbacks = listOf(shortCircuit))

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val cachedEvent = emittedEvents.last()
    assertEquals("Cached", cachedEvent.content?.parts?.single()?.text)
    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(cachedEvent.id, span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_trailingEmptyResponse_spanEventIdMatchesLastEmittedEvent() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flowOf(
          LlmResponse(content = modelMessage("Hel"), partial = true),
          LlmResponse(content = modelMessage("Hello")),
          LlmResponse(),
        )
      }
    val agent = LlmAgent(name = "test-agent", model = testModel)

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val helloEvent = emittedEvents.last()
    assertEquals("Hello", helloEvent.content?.parts?.single()?.text)
    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(helloEvent.id, span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_streamEndsOnPartialChunk_spanEventIdMatchesLastPartial() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flowOf(
          LlmResponse(content = modelMessage("Part 1"), partial = true),
          LlmResponse(content = modelMessage("Part 2"), partial = true),
        )
      }
    val agent = LlmAgent(name = "test-agent", model = testModel)

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val lastPartial = emittedEvents.last()
    assertEquals("Part 2", lastPartial.content?.parts?.single()?.text)
    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals(lastPartial.id, span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_modelEmitsNothing_spanStillCarriesEventId() = runBlocking {
    val testModel = DummyModel("test-model") { flowOf(LlmResponse()) }
    val agent = LlmAgent(name = "test-agent", model = testModel)

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertNotNull(span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
    assertTrue(emittedEvents.isEmpty())
  }

  @Test
  fun runAsync_toolCall_spanEventIdMatchesFunctionCallEventNotResponse() = runBlocking {
    val testModel =
      DummyModel(
        "test-model",
        listOf(
          flowOf(modelFunctionCallResponse("dummy_tool")),
          flowOf(LlmResponse(content = modelMessage("Done"))),
        ),
      )
    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(DummyTool()))

    val emittedEvents = agent.runAsync(newContext(agent)).toList()

    val callEvent = emittedEvents.single { it.functionCalls().isNotEmpty() }
    val doneEvent = emittedEvents.last()
    assertTrue(emittedEvents.any { it.functionResponses().isNotEmpty() })
    val spanEventIds =
      dummyTracer.recordedSpans
        .filter { it.name == "call_llm" }
        .map { it.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] }
    assertEquals(listOf(callEvent.id, doneEvent.id), spanEventIds)
  }

  // ---------------------------------------------------------------------------
  // Python parity: these mirror the Python suite
  // tests/unittests/telemetry/test_spans.py::test_trace_call_llm.
  // ---------------------------------------------------------------------------

  @Test
  fun runAsync_callLlm_setsGenAiSystem() = runTest {
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
  fun runAsync_callLlm_setsRequestConfigAttributes() = runTest {
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
  fun runAsync_callLlm_setsUsageTokens() = runTest {
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
  fun runAsync_callLlm_aggregatesInputOutputAndRecordsCacheAndReasoningTokens() = runTest {
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
  fun runAsync_callLlm_setsReasoningTokensLimitFromThinkingBudget() = runTest {
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
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_REQUEST_REASONING_LEVEL])
  }

  @Test
  fun runAsync_callLlm_setsReasoningLevelFromThinkingLevel() = runBlocking {
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
          GenerateContentConfig(thinkingConfig = ThinkingConfig(thinkingLevel = ThinkingLevel.HIGH)),
      )

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals("HIGH", span.attributes[TelemetryAttributes.GEN_AI_REQUEST_REASONING_LEVEL])
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_USAGE_REASONING_TOKENS_LIMIT])
  }

  @Test
  fun runAsync_callLlm_noUsageMetadata_omitsUsageTokens() = runTest {
    val response = LlmResponse(content = modelMessage("Hello"))
    val testModel = DummyModel.createSequential("test-model", listOf(response))
    val agent = LlmAgent(name = "test-agent", model = testModel)

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
  }

  @Test
  fun runAsync_callLlm_setsFinishReasons() = runTest {
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
  fun runAsync_callLlm_capturesRequestAndResponseWhenEnabled() = runTest {
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
    val llmResponse = span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_LLM_RESPONSE] as? String
    assertNotNull(llmResponse)
    assertNotEquals("{}", llmResponse)
    assertTrue(llmResponse.isNotEmpty())
  }

  // ---------------------------------------------------------------------------
  // Python parity: the `generate_content <model>` span mirrors telemetry schema v1
  // (tests/unittests/telemetry/functional_goldens/agent/stable-no-capture-schema-v1.json).
  // ---------------------------------------------------------------------------

  @Test
  fun runAsync_generateContent_nestsUnderCallLlmWithSchemaV1Attributes() = runBlocking {
    val response =
      LlmResponse(
        content = modelMessage("Hello"),
        finishReason = FinishReason.STOP,
        usageMetadata =
          UsageMetadata(
            promptTokenCount = 100,
            candidatesTokenCount = 20,
            thoughtsTokenCount = 5,
            cachedContentTokenCount = 40,
          ),
      )
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel.createSequential("gemini-2.5-flash", listOf(response)),
      )
    val context = newContext(agent)

    val events = agent.runAsync(context).toList()

    val callLlm = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    val span = dummyTracer.recordedSpans.single { it.name == "generate_content gemini-2.5-flash" }
    assertEquals(callLlm, span.parentSpan)
    assertEquals(
      mapOf(
        TelemetryAttributes.GEN_AI_SYSTEM to "gemini",
        TelemetryAttributes.GEN_AI_OPERATION_NAME to "generate_content",
        TelemetryAttributes.GEN_AI_REQUEST_MODEL to "gemini-2.5-flash",
        TelemetryAttributes.GEN_AI_AGENT_NAME to "test-agent",
        TelemetryAttributes.GEN_AI_CONVERSATION_ID to "test-session",
        TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID to events.single().id,
        TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID to context.invocationId,
        TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS to listOf("stop"),
        TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS to 100L,
        TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS to 25L,
        TelemetryAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS to 40L,
        TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS to 5L,
      ),
      span.attributes,
    )
  }

  @Test
  fun runAsync_generateContent_setsRequestConfigAttributes() = runBlocking {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(LlmResponse(content = modelMessage("Hello"))),
          ),
        generateContentConfig = GenerateContentConfig(topP = 0.95f, maxOutputTokens = 1024),
      )

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertEquals(
      0.95,
      span.attributes[TelemetryAttributes.GEN_AI_REQUEST_TOP_P] as Double,
      absoluteTolerance = 1e-4,
    )
    assertEquals(1024L, span.attributes[TelemetryAttributes.GEN_AI_REQUEST_MAX_TOKENS])
  }

  @Test
  fun runAsync_generateContent_endsAtFinishReasonBeforeTheToolRuns() = runBlocking {
    val testModel =
      DummyModel(
        "test-model",
        listOf(
          flowOf(modelFunctionCallResponse("dummy_tool").copy(finishReason = FinishReason.STOP)),
          flowOf(LlmResponse(content = modelMessage("Done"), finishReason = FinishReason.STOP)),
        ),
      )
    val agent = LlmAgent(name = "test-agent", model = testModel, tools = listOf(DummyTool()))

    val events = agent.runAsync(newContext(agent)).toList()

    // Spans are recorded as they end.
    val names = dummyTracer.recordedSpans.map { it.name }
    assertTrue(
      names.indexOf("generate_content test-model") < names.indexOf("execute_tool dummy_tool"),
      "$names",
    )
    val callEvent = events.single { it.functionCalls().isNotEmpty() }
    val first = dummyTracer.recordedSpans.first { it.name == "generate_content test-model" }
    assertEquals(callEvent.id, first.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID])
  }

  @Test
  fun runAsync_generateContentStreamed_recordsOnlyTheCompleteResponse() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flowOf(
          // A chunk that already carries a finish reason must not end the span early.
          LlmResponse(
            content = modelMessage("Hel"),
            partial = true,
            finishReason = FinishReason.STOP,
            usageMetadata = UsageMetadata(promptTokenCount = 1, candidatesTokenCount = 1),
          ),
          LlmResponse(
            content = modelMessage("Hello"),
            finishReason = FinishReason.MAX_TOKENS,
            usageMetadata = UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 2),
          ),
        )
      }
    val agent = LlmAgent(name = "test-agent", model = testModel)

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertEquals(
      listOf("max_tokens"),
      span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
    )
    assertEquals(10L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
    assertEquals(2L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
  }

  @Test
  fun runAsync_generateContentUnspecifiedFinishReason_omitsFinishReasons() = runBlocking {
    val response =
      LlmResponse(
        content = modelMessage("Hello"),
        finishReason = FinishReason.FINISH_REASON_UNSPECIFIED,
      )
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel.createSequential("test-model", listOf(response)),
      )

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS])
  }

  @Test
  fun runAsync_generateContentResponseWithoutFinishReason_staysOpenUntilTheFinishReason() =
    runBlocking {
      assertGenerateContentEndsAtTheStopAfter(LlmResponse(content = modelMessage("I")))
    }

  @Test
  fun runAsync_generateContentUnspecifiedThenStop_endsAtTheStop() = runBlocking {
    assertGenerateContentEndsAtTheStopAfter(
      LlmResponse(
        content = modelMessage("I"),
        finishReason = FinishReason.FINISH_REASON_UNSPECIFIED,
      )
    )
  }

  @OptIn(FrameworkInternalApi::class)
  @Test
  fun runAsync_generateContentStreamedThroughTheAggregator_recordsTheStreamsFinishReasonAndUsage() =
    runBlocking {
      val chunks =
        listOf(
          LlmResponse(
            content = modelMessage("Hel"),
            finishReason = FinishReason.FINISH_REASON_UNSPECIFIED,
            usageMetadata = UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 1),
          ),
          LlmResponse(
            content = modelMessage("lo"),
            finishReason = FinishReason.STOP,
            usageMetadata =
              UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 4, thoughtsTokenCount = 1),
          ),
        )
      // Streams the way Gemini does: each chunk as a partial response, then the aggregate.
      val testModel =
        DummyModel("test-model") {
          flow {
            val aggregator = StreamingResponseAggregator()
            for (chunk in chunks) emit(aggregator.processResponse(chunk))
            aggregator.aggregate()?.let { emit(it) }
          }
        }
      val agent = LlmAgent(name = "test-agent", model = testModel)

      agent.runAsync(newContext(agent)).toList()

      val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
      assertEquals(
        listOf("stop"),
        span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
      )
      assertEquals(10L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
      assertEquals(5L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
      assertEquals(1L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS])
    }

  @Test
  fun runAsync_modelFailureRecoveredByCallback_isRecordedOnGenerateContent() = runBlocking {
    val failure = IllegalStateException("model boom")
    val testModel = DummyModel("test-model") { flow { throw failure } }
    val recover = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(
        LlmResponse(content = modelMessage("Recovered"), finishReason = FinishReason.STOP)
      )
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, onModelErrorCallbacks = listOf(recover))

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertEquals(listOf<Throwable>(failure), span.recordedExceptions)
    assertEquals("IllegalStateException", span.attributes[TelemetryAttributes.ERROR_TYPE])
    assertEquals("IllegalStateException: model boom", span.errorStatus)
    // The recovered response is the callback's, not the model's.
    assertNull(span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS])
  }

  @Test
  fun runAsync_beforeModelCallbackThrows_setsErrorTypeOnCallLlm() = runBlocking {
    val failing = BeforeModelCallback { _, _ -> throw IllegalStateException("callback boom") }
    val agent =
      LlmAgent(
        name = "test-agent",
        model = DummyModel.createSequential("test-model", listOf(LlmResponse())),
        beforeModelCallbacks = listOf(failing),
      )

    assertFailsWith<IllegalStateException> { agent.runAsync(newContext(agent)).toList() }

    val callLlm = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertEquals("IllegalStateException", callLlm.attributes[TelemetryAttributes.ERROR_TYPE])
  }

  @Test
  fun runAsync_callerFailsHandlingAnEvent_setsNoErrorTypeOnTheAgentSpan() = runBlocking {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(LlmResponse(content = modelMessage("Hi"))),
          ),
      )

    assertFailsWith<IllegalStateException> {
      agent.runAsync(newContext(agent)).collect { throw IllegalStateException("caller boom") }
    }

    val agentSpan = dummyTracer.recordedSpans.single { it.name == "invoke_agent test-agent" }
    assertNull(agentSpan.attributes[TelemetryAttributes.ERROR_TYPE])
    assertTrue(agentSpan.recordedExceptions.isEmpty())
    val callLlm = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertNull(callLlm.attributes[TelemetryAttributes.ERROR_TYPE])
    assertTrue(callLlm.recordedExceptions.isEmpty())
  }

  @Test
  fun runAsync_toolFailure_marksTheToolSpanFailedNotCallLlm() = runBlocking {
    val agent =
      LlmAgent(
        name = "test-agent",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(modelFunctionCallResponse("dummy_tool")),
          ),
        tools = listOf(DummyTool(onRun = { _, _ -> throw IllegalStateException("tool boom") })),
      )

    assertFailsWith<IllegalStateException> { agent.runAsync(newContext(agent)).toList() }

    val callLlm = dummyTracer.recordedSpans.single { it.name == "call_llm" }
    assertNull(callLlm.attributes[TelemetryAttributes.ERROR_TYPE])
    assertTrue(callLlm.recordedExceptions.isEmpty())
    assertNull(callLlm.errorStatus)
    val tool = dummyTracer.recordedSpans.single { it.name == "execute_tool dummy_tool" }
    assertEquals("IllegalStateException", tool.attributes[TelemetryAttributes.ERROR_TYPE])
    assertEquals("IllegalStateException: tool boom", tool.errorStatus)
  }

  @Test
  fun runAsync_beforeModelCallbackShortCircuits_recordsNoGenerateContent() = runBlocking {
    val testModel =
      DummyModel("test-model") { flowOf(LlmResponse(content = modelMessage("unused"))) }
    val shortCircuit = BeforeModelCallback { _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Cached")))
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, beforeModelCallbacks = listOf(shortCircuit))

    agent.runAsync(newContext(agent)).toList()

    assertTrue(dummyTracer.recordedSpans.none { it.name.startsWith("generate_content") })
  }

  @Test
  fun runAsync_generateContent_ignoresWhatFollowsTheFinishReason() = runBlocking {
    val testModel =
      DummyModel("test-model") {
        flow {
          emit(
            LlmResponse(
              content = modelMessage("Hello"),
              finishReason = FinishReason.STOP,
              usageMetadata = UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 2),
            )
          )
          emit(
            LlmResponse(
              content = modelMessage("More"),
              finishReason = FinishReason.MAX_TOKENS,
              usageMetadata = UsageMetadata(promptTokenCount = 99, candidatesTokenCount = 99),
            )
          )
          throw IllegalStateException("late failure")
        }
      }
    val recover = OnModelErrorCallback { _, _, _ ->
      CallbackChoice.Break(LlmResponse(content = modelMessage("Recovered")))
    }
    val agent =
      LlmAgent(name = "test-agent", model = testModel, onModelErrorCallbacks = listOf(recover))

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertEquals(
      listOf("stop"),
      span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
    )
    assertEquals(10L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
    assertEquals(2L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
    assertTrue(span.recordedExceptions.isEmpty())
    assertNull(span.attributes[TelemetryAttributes.ERROR_TYPE])
  }

  @Test
  fun genAiSystemName_reportsTheProviderTheModelIdNames() {
    fun systemFor(modelName: String) = genAiSystemName(DummyModel(modelName) { flowOf() })

    assertEquals("anthropic", systemFor("claude-sonnet-4-5"))
    assertEquals("anthropic", systemFor("apigee/claude-sonnet-4-5"))
    assertEquals("openai", systemFor("openai/gpt-4o"))
    assertEquals("gemini", systemFor("apigee/openai/gpt-4o"))
    assertEquals("gemini", systemFor("projects/p/locations/l/models/claude-x"))
    assertEquals("gemini", systemFor("openrouter/google/gemini-2.5-pro"))
    assertEquals(
      "gemini",
      systemFor("projects/p/locations/l/publishers/google/models/gemini-2.5-flash"),
    )
    assertEquals("gemini", systemFor("gemma-3n"))
    assertEquals("anthropic", systemFor("claude-3-5-haiku-latest"))
    assertEquals("anthropic", systemFor("anthropic/claude-sonnet-4-5"))
    assertEquals(
      "anthropic",
      systemFor("projects/p/locations/l/publishers/anthropic/models/claude-sonnet-4-5"),
    )
    assertEquals("groq", systemFor("groq/llama-3.1-8b-instant"))
    assertEquals("gemini", systemFor("projects/p/locations/l/publishers/meta/models/llama-3"))
    assertEquals("gemini", systemFor("projects/p/locations/l/endpoints/123456"))
    assertEquals("gemini", systemFor("tunedModels/my-tuned-model"))
    assertEquals("gemini", systemFor("gemini/gemini-2.0-flash"))
  }

  @Test
  fun recordTokenUsage_recordsWhatEachCountContributes() {
    fun attributesFor(usage: UsageMetadata): Map<String, Any> {
      val span = dummyTracer.spanBuilder("span").startSpan() as DummySpan
      span.recordTokenUsage(usage)
      return span.attributes
    }
    val input = TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS
    val output = TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS
    val cacheRead = TelemetryAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS
    val reasoning = TelemetryAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS

    assertEquals(
      mapOf(input to 0L, output to 0L, cacheRead to 0L, reasoning to 0L),
      attributesFor(
        UsageMetadata(
          promptTokenCount = 0,
          candidatesTokenCount = 0,
          cachedContentTokenCount = 0,
          thoughtsTokenCount = 0,
        )
      ),
    )
    assertEquals(mapOf(input to 5L), attributesFor(UsageMetadata(toolUsePromptTokenCount = 5)))
    assertEquals(
      mapOf(output to 8L, reasoning to 8L),
      attributesFor(UsageMetadata(thoughtsTokenCount = 8)),
    )
    assertEquals(mapOf(input to 10L), attributesFor(UsageMetadata(promptTokenCount = 10)))
    assertEquals(emptyMap(), attributesFor(UsageMetadata(totalTokenCount = 15)))
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
            userMessage(
              Part(text = "hello"),
              Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
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
    val parts = payload["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
    assertEquals(1, parts.size)
    assertEquals("hello", parts.single().jsonObject["text"]!!.jsonPrimitive.content)
    assertNull(parts.single().jsonObject["inlineData"])
    // response_schema is excluded, other config is preserved.
    val config = payload["config"]!!.jsonObject
    assertNull(config["responseSchema"])
    assertEquals("0.5", config["temperature"]!!.jsonPrimitive.content)
  }

  /** Runs one model call answering [first], then a STOP, and checks one span covers both. */
  private suspend fun assertGenerateContentEndsAtTheStopAfter(first: LlmResponse) {
    val stop =
      LlmResponse(
        content = modelMessage("yo"),
        finishReason = FinishReason.STOP,
        usageMetadata =
          UsageMetadata(promptTokenCount = 10, candidatesTokenCount = 4, thoughtsTokenCount = 1),
      )
    val agent =
      LlmAgent(name = "test-agent", model = DummyModel("test-model") { flowOf(first, stop) })

    agent.runAsync(newContext(agent)).toList()

    val span = dummyTracer.recordedSpans.single { it.name == "generate_content test-model" }
    assertEquals(
      listOf("stop"),
      span.attributes[TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS],
    )
    assertEquals(10L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS])
    assertEquals(5L, span.attributes[TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS])
  }

  private suspend fun newContext(agent: LlmAgent): InvocationContext {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "test-session"))
    return InvocationContext(agent = agent, session = session, runConfig = null)
  }
}
