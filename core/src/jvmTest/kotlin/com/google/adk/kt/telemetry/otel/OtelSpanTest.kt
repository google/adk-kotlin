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
package com.google.adk.kt.telemetry.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class OtelSpanTest {

  @Test
  fun set_string_delegatesToAttribute() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.set("key", "value")

    verify(mockSpan).setAttribute("key", "value")
  }

  @Test
  fun set_long_delegatesToAttribute() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.set("key", 42L)

    verify(mockSpan).setAttribute("key", 42L)
  }

  @Test
  fun set_double_delegatesToAttribute() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.set("key", 3.14)

    verify(mockSpan).setAttribute("key", 3.14)
  }

  @Test
  fun set_boolean_delegatesToAttribute() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.set("key", true)

    verify(mockSpan).setAttribute("key", true)
  }

  @Test
  fun set_stringList_delegatesToStringArrayAttribute() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.set("key", listOf("a", "b"))

    verify(mockSpan).setAttribute(AttributeKey.stringArrayKey("key"), listOf("a", "b"))
  }

  @Test
  fun addEvent_delegatesToAddEvent() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.addEvent("myEvent")

    verify(mockSpan).addEvent("myEvent")
  }

  @Test
  fun recordException_delegatesToRecordException() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)
    val exception = RuntimeException("test error")

    otelSpan.recordException(exception)

    verify(mockSpan).recordException(exception)
  }

  @Test
  fun end_delegatesToEnd() {
    val mockSpan = mock(OpenTelemetrySpan::class.java)
    val otelSpan = OtelSpan(mockSpan)

    otelSpan.end()

    verify(mockSpan).end()
  }
}
