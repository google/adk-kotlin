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
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.noop.NoOpTracer
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

  @Test
  fun noOpTracer_whenMethodsCalled_doesNotCrash() = runBlocking {
    val tracer = NoOpTracer
    assertThat(tracer).isInstanceOf(NoOpTracer::class.java)

    // Verify we can call all methods without crashing
    val span =
      tracer
        .spanBuilder("test-span")
        .set("key", "value")
        .set("key", 1L)
        .set("key", 2.0)
        .set("key", true)
        .startSpan()

    span.addEvent("event")
    span.recordException(RuntimeException("test"))

    // Set parent
    tracer.spanBuilder("child").setParent(tracer.currentContext()).startSpan().end()

    span.end()
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
          runner.runAsync(userId = "u", sessionId = "s", newMessage = hi())
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

  private fun hi(): Content = Content(role = "user", parts = listOf(Part(text = "hi")))

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
}
