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

package com.google.adk.kt.examples.telemetry;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.tools.BlockingTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.FunctionDeclaration;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;

/**
 * A demo agent that demonstrates telemetry emission after every turn using the real OpenTelemetry
 * implementation and a custom exporter that prints spans to stdout. It includes a tool to exercise
 * tool tracing.
 */
public final class TelemetryDemoAgentJava {

  /** A simple span exporter that prints span details to stdout. */
  private static final class PrintingSpanExporter implements SpanExporter {
    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      for (SpanData span : spans) {
        System.out.println("--- Span: " + span.getName() + " ---");
        System.out.println("  TraceId: " + span.getTraceId());
        System.out.println("  SpanId: " + span.getSpanId());
        System.out.println("  ParentSpanId: " + span.getParentSpanId());
        System.out.println("  Attributes: " + span.getAttributes());
        System.out.println("  Events: " + span.getEvents());
        System.out.println("------------------------");
      }
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  /** A mock tool to exercise tool tracing. */
  private static final class TelemetryMagicTool extends BlockingTool {
    TelemetryMagicTool() {
      super("telemetry_magic", "A tool that does magic and emits telemetry.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("telemetry_magic")
          .description("A tool that does magic and emits telemetry.")
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      return Map.of("result", "Magic happened!");
    }
  }

  public static final BaseAgent rootAgent = createRootAgent();

  private static BaseAgent createRootAgent() {
    // Initialize OTel SDK
    PrintingSpanExporter exporter = new PrintingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

    return LlmAgent.builder()
        .name("telemetry-agent")
        .model(new Gemini("gemini-3.1-flash-lite"))
        .instruction(
            """
            You are a helpful assistant that demonstrates telemetry.
            You have access to a tool called `telemetry_magic`.
            Please use this tool if the user asks for magic or to test tool tracing.\
            """)
        .tools(new TelemetryMagicTool())
        .build();
  }

  private TelemetryDemoAgentJava() {}
}
