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
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.interop.PublisherRunner
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.textAgent
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
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import io.opentelemetry.context.Context as OpenTelemetryContext
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

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

  @Test
  fun runAsync_rootSequentialAgentWithASuspendingCollector_keepsSubAgentsUnderIt() {
    val exported = mutableListOf<SpanData>()
    val provider = tracerProvider(exported)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      val agent =
        SequentialAgent("seq", subAgents = listOf(textAgent("a", "ok"), textAgent("b", "ok")))

      runBlocking {
        InMemoryRunner(agent, appName = "telemetryapp")
          .runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi"))
          // A caller that suspends on each event leaves its context on the resumed thread.
          .collect { delay(1) }
      }

      val seq = exported.single { it.name == "invoke_agent seq" }
      assertThat(exported.map { it.traceId }.toSet()).containsExactly(seq.traceId)
      assertThat(
          exported
            .filter { it.name in setOf("invoke_agent a", "invoke_agent b") }
            .map { it.parentSpanId }
        )
        .containsExactly(seq.spanId, seq.spanId)
    } finally {
      provider.close()
    }
  }

  // Ports of Python's stream_in_scope tests, except the asyncio-only close and finalizer cases.

  @Test
  fun runAsync_callerCollecting_keepsItsOwnSpanCurrent() = withOtelTracer { provider ->
    val hostSpan = provider.get("host").spanBuilder("host").startSpan()
    val seen = mutableListOf<OpenTelemetrySpan>()

    hostSpan.makeCurrent().use {
      runBlocking {
        val before = OpenTelemetryContext.current()
        toolCallingRunner().runTurn().collect { seen += OpenTelemetrySpan.current() }
        // Python's test_record_invocation_does_not_attach_its_context and its agent twin.
        assertThat(OpenTelemetryContext.current()).isSameInstanceAs(before)
      }
    }
    hostSpan.end()

    assertThat(seen).isNotEmpty()
    assertThat(seen.map { it.spanContext }.toSet()).containsExactly(hostSpan.spanContext)
  }

  @Test
  fun runAsync_callerSuspendingInCollect_keepsItsOwnSpanCurrent() = withOtelTracer { provider ->
    val hostSpan = provider.get("host").spanBuilder("host").startSpan()
    val seen = mutableListOf<OpenTelemetrySpan>()

    hostSpan.makeCurrent().use {
      runBlocking {
        toolCallingRunner().runTurn().collect {
          seen += OpenTelemetrySpan.current()
          delay(1)
          seen += OpenTelemetrySpan.current()
        }
      }
    }
    hostSpan.end()

    assertThat(seen).isNotEmpty()
    assertThat(seen.map { it.spanContext }.toSet()).containsExactly(hostSpan.spanContext)
  }

  @Test
  fun runAsync_callerThrowingInCollect_keepsItsOwnContext() = withOtelTracer { provider ->
    val hostSpan = provider.get("host").spanBuilder("host").startSpan()

    hostSpan.makeCurrent().use {
      runBlocking {
        val before = OpenTelemetryContext.current()
        val failure = runCatching {
          toolCallingRunner().runTurn().collect {
            if (it.isAnswer()) throw IllegalStateException("caller failed")
          }
        }
        assertThat(failure.exceptionOrNull()).hasMessageThat().isEqualTo("caller failed")
        assertThat(OpenTelemetryContext.current()).isSameInstanceAs(before)
      }
    }
    hostSpan.end()
  }

  @Test
  fun runAsync_callerCancelledInCollect_keepsItsOwnContext() = withOtelTracer { provider ->
    val hostSpan = provider.get("host").spanBuilder("host").startSpan()

    hostSpan.makeCurrent().use {
      runBlocking {
        var before: OpenTelemetryContext? = null
        var afterCancel: OpenTelemetryContext? = null
        val job = launch {
          before = OpenTelemetryContext.current()
          try {
            toolCallingRunner().runTurn().collect {
              if (it.isAnswer()) {
                this@launch.cancel()
                currentCoroutineContext().ensureActive()
              }
            }
          } catch (e: CancellationException) {
            afterCancel = OpenTelemetryContext.current()
            throw e
          }
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(afterCancel).isNotNull()
        assertThat(afterCancel).isSameInstanceAs(before)
        assertThat(OpenTelemetrySpan.current().spanContext).isEqualTo(hostSpan.spanContext)
      }
    }
    hostSpan.end()
  }

  @Test
  fun runAsync_collectorHoppingThreads_leavesNoAdkSpanForItsLaterCode() {
    val exported = Collections.synchronizedList(mutableListOf<SpanData>())
    val inCollect = Collections.synchronizedList(mutableListOf<OpenTelemetrySpan?>())
    val leftOnThreads = Collections.synchronizedList(mutableListOf<OpenTelemetrySpan?>())
    var afterCollect: OpenTelemetrySpan? = null
    var collectedOn: Thread? = null
    var answeredOn: Thread? = null

    withOtelTracer(exported) { provider ->
      PingPongDispatcher().use { dispatcher ->
        val tracer = OtelTracer(provider.get("test"))
        dispatcher.onEachThread { Telemetry.setTracerForTest(tracer) }
        runBlocking(dispatcher) {
          collectedOn = Thread.currentThread()
          toolCallingRunner().runTurn().collect {
            inCollect += OpenTelemetryContext.current().get(OtelTracer.ADK_SPAN)
            // With ADK's own dispatches, one more makes the run end on the other thread.
            if (inCollect.size == 1) yield()
            if (it.isAnswer()) answeredOn = Thread.currentThread()
          }
          afterCollect = OpenTelemetryContext.current().get(OtelTracer.ADK_SPAN)
          provider.get("host").spanBuilder("after").startSpan().end()
        }
        dispatcher.onEachThread {
          leftOnThreads += OpenTelemetryContext.current().get(OtelTracer.ADK_SPAN)
          Telemetry.resetTracer()
        }
      }
    }

    assertThat(exported.map { it.name }).contains("invocation")
    assertThat(answeredOn).isNotNull()
    assertThat(answeredOn).isNotSameInstanceAs(collectedOn)
    assertThat(inCollect).isNotEmpty()
    assertThat(inCollect.toSet()).containsExactly(null)
    assertThat(afterCollect).isNull()
    assertThat(exported.single { it.name == "after" }.parentSpanContext.isValid).isFalse()
    assertThat(leftOnThreads).containsExactly(null, null)
  }

  @Test
  fun runAsync_nestedInATool_leavesTheToolsSpanCurrentAfterCollect() {
    val exported = mutableListOf<SpanData>()
    var afterNested: String? = null

    withOtelTracer(exported) { provider ->
      val nestingTool =
        DummyTool(name = "nesting_tool", declares = true) { _, _ ->
          toolCallingRunner().runTurn().collect {}
          afterNested = currentSpanName()
          provider.get("host").spanBuilder("after_nested").startSpan().end()
          mapOf("status" to "done")
        }
      val outer =
        InMemoryRunner(
          LlmAgent(
            name = "outer",
            model =
              DummyModel.createSequential(
                "test-model",
                listOf(
                  modelFunctionCallResponse("nesting_tool"),
                  LlmResponse(content = modelMessage("done")),
                ),
              ),
            tools = listOf(nestingTool),
          ),
          appName = "telemetryapp",
        )
      runBlocking { outer.runTurn().collect {} }
    }

    val toolSpan = exported.single { it.name == "execute_tool nesting_tool" }
    assertThat(afterNested).isEqualTo("execute_tool nesting_tool")
    assertThat(exported.single { it.name == "after_nested" }.parentSpanId)
      .isEqualTo(toolSpan.spanId)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun publisherRunner_subscriber_keepsTheCallersSpanThroughCompletion() =
    withOtelTracer { provider ->
      val executor = Executors.newSingleThreadExecutor()
      try {
        val tracer = OtelTracer(provider.get("test"))
        executor.submit { Telemetry.setTracerForTest(tracer) }.get(10, TimeUnit.SECONDS)
        val hostSpan = provider.get("host").spanBuilder("host").startSpan()
        val inOnNext = CopyOnWriteArrayList<OpenTelemetrySpan>()
        val inOnComplete = CompletableFuture<OpenTelemetrySpan>()

        PublisherRunner.of(toolCallingRunner()).use { runner ->
          val events =
            hostSpan.makeCurrent().use {
              runner.runAsync(
                userId = "u",
                sessionId = "s",
                newMessage = userMessage("hi"),
                coroutineContext = executor.asCoroutineDispatcher(),
              )
            }
          events.subscribe(
            object : Subscriber<Event> {
              override fun onSubscribe(s: Subscription) = s.request(Long.MAX_VALUE)

              override fun onNext(event: Event) {
                inOnNext += OpenTelemetrySpan.current()
              }

              override fun onError(t: Throwable) {
                inOnComplete.completeExceptionally(t)
              }

              override fun onComplete() {
                inOnComplete.complete(OpenTelemetrySpan.current())
              }
            }
          )
          val completedIn = inOnComplete.get(10, TimeUnit.SECONDS)
          hostSpan.end()

          assertThat(inOnNext.map { it.spanContext }.toSet()).containsExactly(hostSpan.spanContext)
          assertThat(completedIn.spanContext).isEqualTo(hostSpan.spanContext)
        }
        executor.submit { Telemetry.resetTracer() }.get(10, TimeUnit.SECONDS)
      } finally {
        executor.shutdownNow()
      }
    }

  @Test
  fun trace_collector_keepsItsOwnContextWhileTheBodyRunsInTheSpan() = withOtelTracer {
    val inBody = mutableListOf<String?>()
    val inCollector = mutableListOf<OpenTelemetrySpan>()

    runBlocking {
      val before = OpenTelemetryContext.current()
      flow {
          repeat(5) {
            inBody += currentSpanName()
            emit(it)
          }
        }
        .trace("scope")
        .take(3)
        .collect { inCollector += OpenTelemetrySpan.current() }
      assertThat(OpenTelemetryContext.current()).isSameInstanceAs(before)
    }

    assertThat(inCollector.map { it.spanContext.isValid }).containsExactly(false, false, false)
    assertThat(inBody).containsExactly("scope", "scope", "scope")
  }

  @Test
  fun trace_spansOpenedInTheBody_areChildrenOfTheTracedSpan() {
    val exported = mutableListOf<SpanData>()

    withOtelTracer(exported) { provider ->
      val host = provider.get("host")
      runBlocking {
        flow {
            try {
              host.spanBuilder("body").startSpan().end()
              emit(1)
              emit(2)
            } finally {
              host.spanBuilder("teardown").startSpan().end()
            }
          }
          .trace("scope")
          .take(1)
          .collect {}
      }
    }

    val byName = exported.associateBy { it.name }
    assertThat(byName.keys).containsExactly("scope", "body", "teardown")
    for (name in listOf("body", "teardown")) {
      assertThat(byName.getValue(name).parentSpanId).isEqualTo(byName.getValue("scope").spanId)
    }
  }

  @Test
  fun trace_failingBody_propagatesItsErrorToTheCollector() = withOtelTracer {
    runBlocking {
      val before = OpenTelemetryContext.current()
      val failure = runCatching {
        flow {
            emit(1)
            throw IllegalStateException("boom")
          }
          .trace("scope")
          .collect {}
      }

      assertThat(failure.exceptionOrNull()).hasMessageThat().isEqualTo("boom")
      assertThat(OpenTelemetryContext.current()).isSameInstanceAs(before)
    }
  }

  @Test
  fun trace_cancelledCollector_cancelsTheBody() = withOtelTracer {
    var cancelledInBody = false

    runBlocking {
      val received = CompletableDeferred<Unit>()
      val job = launch {
        flow {
            try {
              emit(1)
              delay(10.seconds)
            } catch (e: CancellationException) {
              cancelledInBody = true
              throw e
            }
          }
          .trace("scope")
          .collect { received.complete(Unit) }
      }
      received.await()
      job.cancelAndJoin()
    }

    assertThat(cancelledInBody).isTrue()
  }

  @Test
  fun trace_innerSpanOpenedInTheBody_staysCurrentAcrossEmissions() = withOtelTracer { provider ->
    val callerSpan = provider.get("host").spanBuilder("caller_root").startSpan()
    val inBody = mutableListOf<String?>()
    val inCollector = mutableListOf<OpenTelemetrySpan>()
    val stream = flow {
      val inner = Telemetry.tracer.spanBuilder("generate_content").startSpan()
      try {
        emitAll(
          flow {
              emit(1)
              inBody += currentSpanName()
              emit(2)
              inBody += currentSpanName()
            }
            .flowOn(Telemetry.tracer.contextWithSpan(inner).asContextElement())
        )
      } finally {
        inner.end()
      }
    }

    callerSpan.makeCurrent().use {
      runBlocking {
        stream.trace("call_llm").collect { inCollector += OpenTelemetrySpan.current() }
      }
    }
    callerSpan.end()

    assertThat(inCollector.map { it.spanContext })
      .containsExactly(callerSpan.spanContext, callerSpan.spanContext)
    assertThat(inBody).containsExactly("generate_content", "generate_content")
  }

  /** Runs [block] with an OpenTelemetry SDK tracer that exports its spans to [sink]. */
  private fun withOtelTracer(
    sink: MutableList<SpanData> = mutableListOf(),
    block: (SdkTracerProvider) -> Unit,
  ) {
    val provider = tracerProvider(sink)
    try {
      Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
      block(provider)
    } finally {
      provider.close()
    }
  }

  /**
   * Runs one dispatched task at a time, each on the other of its two threads. Because it is serial,
   * a parallel tool call cannot finish before the agent awaits it, so the agent resumes from a
   * dispatch.
   */
  private class PingPongDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val threads = List(2) { Executors.newSingleThreadExecutor() }
    private val serial = Executors.newSingleThreadExecutor()
    private val dispatches = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      serial.execute {
        threads[dispatches.getAndIncrement() % 2].submit(block).get(10, TimeUnit.SECONDS)
      }
    }

    /** Runs [action] on each of the two threads in turn. */
    fun onEachThread(action: () -> Unit) {
      for (thread in threads) thread.submit(action).get(10, TimeUnit.SECONDS)
    }

    override fun close() {
      serial.shutdownNow()
      threads.forEach { it.shutdownNow() }
    }
  }

  /** A runner whose agent calls `dummy_tool` once, then answers. */
  private fun toolCallingRunner() =
    InMemoryRunner(
      LlmAgent(
        name = "helper",
        model =
          DummyModel.createSequential(
            "test-model",
            listOf(
              modelFunctionCallResponse("dummy_tool"),
              LlmResponse(content = modelMessage("done")),
            ),
          ),
        tools = listOf(DummyTool(declares = true)),
      ),
      appName = "telemetryapp",
    )

  private fun currentSpanName(): String? = (OpenTelemetrySpan.current() as? ReadableSpan)?.name

  private fun InMemoryRunner.runTurn() =
    runAsync(userId = "u", sessionId = "s", newMessage = userMessage("hi"))

  private fun Event.isAnswer() = content?.parts?.any { it.text == "done" } == true

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
