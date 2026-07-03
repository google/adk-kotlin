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

package com.google.adk.kt.examples.telemetry

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.OtelTracer
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Self-contained demo of ADK telemetry with the instance-scoped `App.tracer` API.
 *
 * Telemetry is configured entirely on the [App] -- there is no global state. Build an ADK tracer
 * from any OpenTelemetry SDK with [OtelTracer] and pass it as `App(..., tracer = ...)`. With no
 * `App.tracer`, tracing is a no-op.
 *
 * Run [main] to execute one turn offline (a canned model, no credentials) and print every span the
 * SDK exports: `invocation` -> `invoke_agent telemetry-agent` -> `call_llm`.
 */
object TelemetryDemoAgent {
  /**
   * Exposed for the `kt_agent_debug` dev harness. It uses a canned model so the demo runs offline;
   * in production you would use a real model such as `Gemini(name = "...")`.
   */
  @JvmField val rootAgent = LlmAgent(name = "telemetry-agent", model = CannedModel)
}

/** A canned [Model] so the demo runs offline, without credentials. */
private object CannedModel : Model {
  override val name = "canned-model"

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flowOf(
      LlmResponse(
        content = Content(role = "model", parts = listOf(Part(text = "Hello! Magic happened.")))
      )
    )
}

/** Prints each exported span to stdout. */
private class PrintingSpanExporter : SpanExporter {
  override fun export(spans: Collection<SpanData>): CompletableResultCode {
    for (span in spans) {
      println("--- span: ${span.name} ---")
      println("  traceId=${span.traceId} spanId=${span.spanId} parent=${span.parentSpanId}")
      println("  attributes=${span.attributes}")
    }
    return CompletableResultCode.ofSuccess()
  }

  override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

  override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** Runs one turn with telemetry wired via `App.tracer` and prints the exported spans. */
fun main() {
  // 1. Build any OpenTelemetry SDK. Here, an in-process SDK that prints spans to stdout.
  val sdk =
    OpenTelemetrySdk.builder()
      .setTracerProvider(
        SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(PrintingSpanExporter()))
          .build()
      )
      .build()

  try {
    // 2. Wire the tracer into the App -- the only telemetry configuration. No globals.
    val app =
      App(
        appName = "telemetry_demo",
        rootAgent = TelemetryDemoAgent.rootAgent,
        tracer = OtelTracer(sdk),
      )

    // 3. Run a turn. Every engine span is parented under the run and exported.
    val runner = InMemoryRunner(app)
    val events = runBlocking {
      runner
        .runAsync(
          userId = "user",
          sessionId = "session",
          newMessage = Content(role = "user", parts = listOf(Part(text = "Do some magic"))),
        )
        .toList()
    }

    println("Run produced ${events.size} event(s); the spans above were exported via App.tracer.")
  } finally {
    sdk.close()
  }
}
