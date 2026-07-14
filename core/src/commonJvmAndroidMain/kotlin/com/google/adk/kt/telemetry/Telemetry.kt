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

import com.google.adk.kt.VERSION
import com.google.adk.kt.telemetry.otel.OtelTracer
import io.opentelemetry.api.GlobalOpenTelemetry
import java.lang.ThreadLocal

/**
 * Resolves the default [Tracer] shared by the JVM and Android platforms.
 *
 * This relies on [GlobalOpenTelemetry] to provide the underlying tracer. It is expected that the
 * host application using this library (e.g., via a Java Agent, the OpenTelemetry Android SDK, or
 * explicit OpenTelemetry SDK initialization) will have configured the global OpenTelemetry
 * instance. If unconfigured, this safely defaults to a No-Op tracer provided by OpenTelemetry,
 * preventing any crashes or side effects.
 *
 * Emitted spans carry the `gcp.vertex.agent` instrumentation scope name and the ADK library
 * [VERSION].
 */
internal actual fun defaultTracer(): Tracer =
  OtelTracer(
    GlobalOpenTelemetry.getTracerProvider()
      .tracerBuilder(TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT)
      .setInstrumentationVersion(VERSION)
      .build()
  )

private val testTracerOverride = ThreadLocal<Tracer>()

internal actual fun internalSetTestTracer(tracer: Tracer) {
  testTracerOverride.set(tracer)
}

internal actual fun getTestTracer(): Tracer? = testTracerOverride.get()

internal actual fun internalResetTestTracer() {
  testTracerOverride.remove()
}
