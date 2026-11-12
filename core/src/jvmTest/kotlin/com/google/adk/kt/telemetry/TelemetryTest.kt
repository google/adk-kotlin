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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.GenAiApiException
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TelemetryTest {

  @After
  fun tearDown() {
    Telemetry.resetTracer()
  }

  @Test
  fun tracer_byDefault_isOtelTracer() {
    assertThat(Telemetry.tracer).isInstanceOf(OtelTracer::class.java)
  }

  @Test
  fun setTracerForTest_withCustomTracer_overridesTracer() {
    val customTracer =
      object : Tracer {
        override fun spanBuilder(spanName: String): SpanBuilder = throw NotImplementedError()

        override suspend fun currentContext(): TelemetryContext = throw NotImplementedError()

        override fun contextWithSpan(span: Span): TelemetryContext = throw NotImplementedError()
      }

    Telemetry.setTracerForTest(customTracer)

    assertThat(Telemetry.tracer).isSameInstanceAs(customTracer)
  }

  @Test
  fun setTracerForTest_whenSetInOneThread_doesNotAffectOtherThreads() {
    val customTracer =
      object : Tracer {
        override fun spanBuilder(spanName: String): SpanBuilder = throw NotImplementedError()

        override suspend fun currentContext(): TelemetryContext = throw NotImplementedError()

        override fun contextWithSpan(span: Span): TelemetryContext = throw NotImplementedError()
      }

    Telemetry.setTracerForTest(customTracer)
    assertThat(Telemetry.tracer).isSameInstanceAs(customTracer)

    var tracerInOtherThread: Tracer? = null
    val thread = Thread { tracerInOtherThread = Telemetry.tracer }
    thread.start()
    thread.join()

    // The other thread should see the default OtelTracer, not the custom one set in the current
    // thread.
    assertThat(tracerInOtherThread).isInstanceOf(OtelTracer::class.java)
  }

  // --- parent-span capture: nesting ADK spans under the caller's active span ---

  @Test
  fun trace_withCapturedParent_joinsCallersTrace() = runBlocking {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val hostSpan = provider.get("host").spanBuilder("host").startSpan()
      val parent = hostSpan.makeCurrent().use { currentTelemetryContext() }
      flowOf(1).trace("invocation", parent = parent).collect {}
      hostSpan.end()

      val invocation = exported.single { it.name == "invocation" }
      assertThat(invocation.spanContext.traceId).isEqualTo(hostSpan.spanContext.traceId)
      assertThat(invocation.parentSpanContext.spanId).isEqualTo(hostSpan.spanContext.spanId)
    } finally {
      provider.close()
    }
  }

  @Test
  fun trace_withEmptyCapturedParent_startsNewRootTrace() = runBlocking {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      // No caller span is active, so the captured context is empty and the span must be a valid
      // standalone root rather than an error.
      val parent = currentTelemetryContext()
      flowOf(1).trace("invocation", parent = parent).collect {}

      val invocation = exported.single { it.name == "invocation" }
      assertThat(invocation.parentSpanContext.isValid).isFalse()
    } finally {
      provider.close()
    }
  }

  @Test
  fun runAsync_capturesCallerSpanEagerly_soInvocationNestsAfterSpanDeactivated() {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val runner = InMemoryRunner(NoopAgent(), appName = "telemetryapp")
      val hostSpan = provider.get("host").spanBuilder("host").startSpan()

      // Invoke runAsync while the caller's span is active so it is captured eagerly, then let the
      // scope close before collecting the cold flow. Lazy capture would lose the parent here; eager
      // capture keeps the invocation span nested under the caller's span.
      val flow =
        hostSpan.makeCurrent().use {
          runner.runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi"))
        }
      runBlocking { flow.collect {} }
      hostSpan.end()

      val invocation = exported.single { it.name == "invocation" }
      assertThat(invocation.spanContext.traceId).isEqualTo(hostSpan.spanContext.traceId)
      assertThat(invocation.parentSpanContext.spanId).isEqualTo(hostSpan.spanContext.spanId)
    } finally {
      provider.close()
    }
  }

  @OptIn(ExperimentalLiveApi::class)
  @Test
  fun runLive_capturesCallerSpanEagerly_soAgentSpanNestsUnderIt() {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val runner = InMemoryRunner(LiveNoopAgent(), appName = "telemetryapp")
      val hostSpan = provider.get("host").spanBuilder("host").startSpan()
      val queue = LiveRequestQueue()
      queue.close()

      // Captured at the runLive call; with no live invocation span, the agent span nests under it.
      val flow = hostSpan.makeCurrent().use { runner.runLive(userId = "u", sessionId = "s", queue) }
      runBlocking { flow.collect {} }
      hostSpan.end()

      val agent = exported.single { it.name == "invoke_agent liveNoopAgent" }
      assertThat(agent.spanContext.traceId).isEqualTo(hostSpan.spanContext.traceId)
      assertThat(agent.parentSpanContext.spanId).isEqualTo(hostSpan.spanContext.spanId)
    } finally {
      provider.close()
    }
  }

  @Test
  fun runAsync_modelOpensASpan_nestsUnderGenerateContentAndDoesNotParentTheTool() {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val inner =
        DummyModel.createSequential(
          "test-model",
          listOf(
            modelFunctionCallResponse("dummy_tool"),
            LlmResponse(content = modelMessage("done")),
          ),
        )
      // Like a Python model that yields its responses inside its own `start_as_current_span`.
      val model =
        DummyModel("test-model") { request ->
          flow {
            val span = Telemetry.tracer.spanBuilder("model_wrapper").startSpan()
            try {
              emitAll(
                inner
                  .generateContent(request, stream = false)
                  .flowOn(Telemetry.tracer.contextWithSpan(span).asContextElement())
              )
            } finally {
              span.end()
            }
          }
        }
      val agent =
        LlmAgent(name = "helper", model = model, tools = listOf(DummyTool(declares = true)))

      runBlocking {
        InMemoryRunner(agent, appName = "telemetryapp")
          .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi"))
          .collect {}
      }

      val generateContentIds =
        exported.filter { it.name == "generate_content test-model" }.map { it.spanId }
      assertThat(exported.filter { it.name == "model_wrapper" }.map { it.parentSpanId })
        .containsExactlyElementsIn(generateContentIds)
      assertThat(exported.single { it.name == "execute_tool dummy_tool" }.parentSpanId)
        .isEqualTo(exported.single { it.name == "invoke_agent helper" }.spanId)
    } finally {
      provider.close()
    }
  }

  /** A GenAI error from a tool passes back through `Gemini`, which rewraps a 429 on the way. */
  @Test
  fun runAsync_toolThrowsQuotaErrorThroughGemini_setsNoErrorTypeOnCallLlm() {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val models =
        object : Gemini.GeminiModels {
          override fun generateContentStream(
            model: String,
            contents: List<Content>,
            config: GenerateContentConfig,
          ): Flow<GenerateContentResponse> = throw NotImplementedError()

          override suspend fun generateContent(
            model: String,
            contents: List<Content>,
            config: GenerateContentConfig,
          ): GenerateContentResponse =
            GenerateContentResponse(
              candidates = listOf(Candidate(content = modelFunctionCall("quota_tool")))
            )
        }
      val quotaTool =
        DummyTool(name = "quota_tool", declares = true) { _, _ ->
          throw ClientException(code = 429, status = "RESOURCE_EXHAUSTED", message = "Slow down.")
        }
      val agent =
        LlmAgent(
          name = "quota_agent",
          model = Gemini(Client(apiKey = "fake"), "gemini-test", models = models),
          tools = listOf(quotaTool),
        )
      val runner = InMemoryRunner(agent, appName = "telemetryapp")

      val thrown =
        assertFailsWith<GenAiApiException> {
          runBlocking {
            runner
              .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi"))
              .collect {}
          }
        }

      // Gemini rewraps the 429 on its way out, so the failure leaving call_llm is a new instance.
      assertThat(thrown).isNotInstanceOf(ClientException::class.java)
      assertThat(thrown.cause).isInstanceOf(ClientException::class.java)

      val callLlm = exported.single { it.name == "call_llm" }
      assertThat(callLlm.attributes.get(ERROR_TYPE_KEY)).isNull()
      assertThat(callLlm.events.map { it.name }).doesNotContain("exception")
      val tool = exported.single { it.name == "execute_tool quota_tool" }
      assertThat(tool.attributes.get(ERROR_TYPE_KEY)).isEqualTo("429")
    } finally {
      provider.close()
    }
  }

  private fun tracerProvider(sink: MutableList<SpanData>): SdkTracerProvider =
    SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(recordingExporter(sink)))
      .build()

  private fun recordingExporter(sink: MutableList<SpanData>): SpanExporter =
    object : SpanExporter {
      override fun export(spans: Collection<SpanData>): CompletableResultCode {
        sink.addAll(spans)
        return CompletableResultCode.ofSuccess()
      }

      override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

      override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

  /** Minimal agent that emits no events; used to exercise the runner's span wiring. */
  private class NoopAgent : BaseAgent(name = "noopAgent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}
  }

  /** Minimal agent that ends its live run at once; used to exercise the runner's span wiring. */
  private class LiveNoopAgent : BaseAgent(name = "liveNoopAgent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}

    @OptIn(ExperimentalLiveApi::class)
    override fun runLiveImpl(context: InvocationContext): Flow<Event> = flow {}
  }

  private companion object {
    val ERROR_TYPE_KEY: AttributeKey<String> =
      AttributeKey.stringKey(TelemetryAttributes.ERROR_TYPE)
  }
}
