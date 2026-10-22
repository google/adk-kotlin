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

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * A demo agent that demonstrates telemetry emission after every turn using the real OpenTelemetry
 * implementation and a custom exporter that prints spans to stdout. It includes a tool to exercise
 * tool tracing.
 */
object TelemetryDemoAgent {

  /** A simple span exporter that prints span details to stdout. */
  class PrintingSpanExporter : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
      for (span in spans) {
        println("--- Span: ${span.name} ---")
        println("  TraceId: ${span.traceId}")
        println("  SpanId: ${span.spanId}")
        println("  ParentSpanId: ${span.parentSpanId}")
        println("  Attributes: ${span.attributes}")
        println("  Events: ${span.events}")
        println("------------------------")
      }
      return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
  }

  /** A mock tool to exercise tool tracing. */
  class TelemetryMagicTool :
    BaseTool("telemetry_magic", "A tool that does magic and emits telemetry.") {
    override fun declaration(): FunctionDeclaration {
      return FunctionDeclaration(
        name = "telemetry_magic",
        description = "A tool that does magic and emits telemetry.",
      )
    }

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
      return mapOf("result" to "Magic happened!")
    }
  }

  @JvmField
  val rootAgent = run {
    // Initialize OTel SDK
    val exporter = PrintingSpanExporter()
    val tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal()

    LlmAgent(
      name = "telemetry-agent",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          """
          You are a helpful assistant that demonstrates telemetry.
          You have access to a tool called `telemetry_magic`.
          Please use this tool if the user asks for magic or to test tool tracing.
          """
            .trimIndent()
        ),
      tools = listOf(TelemetryMagicTool()),
    )
  }
}
