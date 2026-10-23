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

import com.google.adk.kt.testing.DummyTracer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class CoroutinesTest {

  private lateinit var tracer: DummyTracer

  @BeforeTest
  fun setUp() {
    tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
  }

  @AfterTest
  fun tearDown() {
    Telemetry.resetTracer()
  }

  @Test
  fun withSpan_executesBlock() = runTest {
    var executed = false
    withSpan("my_span") { parentSpan ->
      assertNotNull(parentSpan)
      executed = true
    }
    assertTrue(executed)
  }

  @Test
  fun withSpan_supportsChildCoroutines() = runTest {
    var executedLaunch = false
    var executedAsync = false
    withSpan("parent_span") {
      val childJob = launch {
        assertNotNull(coroutineContext[TelemetryContextElement.Key])
        executedLaunch = true
      }
      val childDeferred = async {
        assertNotNull(coroutineContext[TelemetryContextElement.Key])
        executedAsync = true
        "result"
      }
      childJob.join()
      assertEquals("result", childDeferred.await())
    }
    assertTrue(executedLaunch)
    assertTrue(executedAsync)
  }

  @Test
  fun inSpan_executesBlockWithSpan() {
    var executed = false
    val result =
      inSpan("sync_span") { span ->
        assertNotNull(span)
        executed = true
        "success"
      }

    assertEquals("success", result)
    assertTrue(executed)
  }

  @Test
  fun inSpan_blockThrows_recordsExceptionEndsSpanAndRethrows() {
    val exception = RuntimeException("Test exception")

    val thrown = assertFailsWith<RuntimeException> { inSpan("error_span") { throw exception } }

    assertSame(exception, thrown)
    val span = tracer.recordedSpans.single()
    assertEquals("error_span", span.name)
    assertTrue(span.isEnded)
    assertEquals(listOf<Throwable>(exception), span.recordedExceptions)
  }

  @Test
  fun inSpan_blockCancelled_endsSpanWithoutRecordingTheCancellation() {
    assertFailsWith<CancellationException> {
      inSpan("cancelled_span") { throw CancellationException("cancelled") }
    }

    val span = tracer.recordedSpans.single()
    assertTrue(span.isEnded)
    assertEquals(emptyList(), span.recordedExceptions)
  }

  @Test
  fun flowTrace_collectsProperly() = runTest {
    val result =
      flow {
          emit("A")
          emit("B")
        }
        .trace("flow_span")
        .toList()

    assertEquals(listOf("A", "B"), result)
  }

  @Test
  fun tracedFlow_emitsValuesInOrder() = runTest {
    val result =
      tracedFlow<String>("traced") { _, _ ->
          emit("A")
          emit("B")
          emit("C")
        }
        .toList()

    assertEquals(listOf("A", "B", "C"), result)
  }

  @Test
  fun tracedFlow_startsAndEndsSpanWithConfiguredAttributes() = runTest {
    tracedFlow<Unit>("call_llm", { this["model"] = "gemini" }) { _, _ -> }.collect {}

    assertEquals(1, tracer.recordedSpans.size)
    val span = tracer.recordedSpans.single()
    assertEquals("call_llm", span.name)
    assertEquals("gemini", span.attributes["model"])
    assertTrue(span.isEnded)
  }

  @Test
  fun tracedFlow_exposesSpanForEvents() = runTest {
    tracedFlow<Int>("with_events") { span, _ ->
        span.addEvent("chunk_received")
        emit(1)
        span.addEvent("chunk_received")
        emit(2)
      }
      .toList()

    val span = tracer.recordedSpans.single()
    assertEquals(listOf("chunk_received", "chunk_received"), span.events)
  }

  @Test
  fun tracedFlow_emitSuspendsUntilCollectorProcessesValue() = runTest {
    // Regression guard for the channelFlow race: each producer emit must be sandwiched by the
    // collector's start/end entries.
    val trace = mutableListOf<String>()

    tracedFlow<Int>("synchronous") { _, _ ->
        trace.add("producer:before-emit-1")
        emit(1)
        trace.add("producer:after-emit-1")
        emit(2)
        trace.add("producer:after-emit-2")
      }
      .collect { value ->
        trace.add("collector:start-$value")
        yield()
        trace.add("collector:end-$value")
      }

    assertEquals(
      listOf(
        "producer:before-emit-1",
        "collector:start-1",
        "collector:end-1",
        "producer:after-emit-1",
        "collector:start-2",
        "collector:end-2",
        "producer:after-emit-2",
      ),
      trace,
    )
  }

  @Test
  fun tracedFlow_recordsExceptionAndEndsSpan() = runTest {
    val boom = RuntimeException("boom")

    val thrown =
      assertFailsWith<RuntimeException> {
        tracedFlow<Int>("failing") { _, _ ->
            emit(1)
            throw boom
          }
          .toList()
      }

    assertSame(boom, thrown)
    val span = tracer.recordedSpans.single()
    assertTrue(span.isEnded)
    assertEquals(listOf<Throwable>(boom), span.recordedExceptions)
  }

  @Test
  fun tracedFlow_doesNotInstallSpanIntoCollectorContext() = runTest {
    // The collector runs in the caller's context; callers needing the span on upstream work must
    // opt in via `.flowOn(spanContext)`.
    var collectorSawSpanContext = true

    tracedFlow<Int>("scope_check") { _, _ -> emit(1) }
      .collect { collectorSawSpanContext = coroutineContext[TelemetryContextElement.Key] != null }

    assertFalse(collectorSawSpanContext)
  }

  @Test
  fun tracedFlow_handsSpanContextElementToBlock() = runTest {
    var receivedElement: TelemetryContextElement? = null

    tracedFlow<Int>("captures_context") { _, spanContext ->
        receivedElement = spanContext
        emit(1)
      }
      .toList()

    assertNotNull(receivedElement)
  }
}
