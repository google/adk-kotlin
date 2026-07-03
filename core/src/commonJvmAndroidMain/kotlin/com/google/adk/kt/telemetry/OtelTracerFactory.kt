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

import com.google.adk.kt.telemetry.otel.OtelTracer as OtelTracerImpl
import io.opentelemetry.api.OpenTelemetry

/**
 * Builds an ADK [Tracer] from a host [OpenTelemetry] instance, for use as `App(..., tracer = ...)`.
 *
 * This is the instance-scoped way to point ADK at a specific SDK. The tracer uses the
 * `gcp.vertex.agent` instrumentation scope.
 */
fun OtelTracer(openTelemetry: OpenTelemetry): Tracer =
  OtelTracerImpl(openTelemetry.getTracer(TelemetryAttributes.SYSTEM_GCP_VERTEX_AGENT))
