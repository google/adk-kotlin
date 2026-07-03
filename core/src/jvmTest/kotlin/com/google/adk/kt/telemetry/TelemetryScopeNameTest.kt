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

import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.common.truth.Truth.assertThat
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Verifies that a tracer built with the ADK instrumentation scope name records spans under the
 * `gcp.vertex.agent` scope, matching Python, Java, and Go ADK. The production `OtelTracer(...)`
 * factory uses the same [TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT] constant.
 *
 * This is intentionally self-contained (a local in-memory SDK, no
 * [io.opentelemetry.api .GlobalOpenTelemetry]) so it is robust when the whole `jvmTest` suite runs
 * in a single JVM.
 */
@RunWith(JUnit4::class)
class TelemetryScopeNameTest {

  @Test
  fun adkScopeName_recordsGcpVertexAgentInstrumentationScope() {
    val exportedSpans = mutableListOf<SpanData>()
    val exporter =
      object : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
          exportedSpans.addAll(spans)
          return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
      }
    val tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()

    try {
      // Build the tracer exactly as OtelTracer(...) does, via the ADK instrumentation scope name.
      val tracer = OtelTracer(tracerProvider.get(TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT))
      tracer.spanBuilder("scope-check").startSpan().end()

      assertThat(TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT).isEqualTo("gcp.vertex.agent")
      assertThat(exportedSpans).hasSize(1)
      assertThat(exportedSpans.single().instrumentationScopeInfo.name).isEqualTo("gcp.vertex.agent")
    } finally {
      tracerProvider.close()
    }
  }
}
