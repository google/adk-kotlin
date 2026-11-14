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

import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.SpanBuilder
import com.google.adk.kt.telemetry.TelemetryContext
import com.google.adk.kt.telemetry.Tracer
import io.opentelemetry.api.trace.Span as OpenTelemetrySpan
import io.opentelemetry.api.trace.Tracer as OpenTelemetryTracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey

/** OpenTelemetry implementation of [Tracer]. */
internal class OtelTracer(private val otelTracer: OpenTelemetryTracer) : Tracer {

  override fun spanBuilder(spanName: String): SpanBuilder {
    return OtelSpanBuilder(otelTracer.spanBuilder(spanName))
  }

  override suspend fun currentContext(): TelemetryContext {
    return OtelTelemetryContext(Context.current())
  }

  override fun contextWithSpan(span: Span): TelemetryContext {
    val otelContext =
      if (span is OtelSpan) {
        Context.current().with(span.otelSpan).with(ADK_SPAN, span.otelSpan)
      } else {
        Context.current().with(OpenTelemetrySpan.getInvalid())
      }
    return OtelTelemetryContext(otelContext)
  }

  companion object {
    /** The innermost span ADK opened, kept on every context derived from it. */
    internal val ADK_SPAN: ContextKey<OpenTelemetrySpan> = ContextKey.named("adk-span")
  }
}
