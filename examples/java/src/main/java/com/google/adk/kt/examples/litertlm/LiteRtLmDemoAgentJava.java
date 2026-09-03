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

package com.google.adk.kt.examples.litertlm;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.litertlm.LiteRtLmModel;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.LogSeverity;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Example agent demonstrating how to use local tools with LiteRT-LM in the Kotlin ADK.
 *
 * <p>This agent uses {@link LiteRtLmModel} as its execution model and has access to the local tools
 * defined below.
 *
 * <p>These tools are hand-written {@link BaseFutureTool} subclasses because this module is compiled
 * by javac. If you compile with the Kotlin toolchain (KSP), the recommended approach is instead the
 * {@code @Tool} annotation shown in
 * examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java.
 */
public final class LiteRtLmDemoAgentJava {

  /** Returns the current local date and time. */
  private static final class GetCurrentTimeTool extends BaseFutureTool {

    GetCurrentTimeTool() {
      super("getCurrentTime", "Returns the current local date and time.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getCurrentTime")
          .description("Returns the current local date and time.")
          .build();
    }

    @Override
    public CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      LocalDateTime current =
          LocalDateTime.ofInstant(InstantSource.system().instant(), ZoneId.systemDefault());
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      System.out.println(">>> LocalToolService [SYSTEM]: getCurrentTime() called -> " + current);
      return CompletableFuture.completedFuture(Map.of("time", current.format(formatter)));
    }
  }

  /** Retrieves a weather report for the specified city. */
  private static final class GetWeatherTool extends BaseFutureTool {

    GetWeatherTool() {
      super("getWeather", "Retrieves a weather report for the specified city.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getWeather")
          .description("Retrieves a weather report for the specified city.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "city",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The name of the city, e.g. \"San Francisco\"")
                              .build()))
                  .required("city")
                  .build())
          .build();
    }

    @Override
    public CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      String city = (String) args.get("city");
      System.out.println(
          ">>> LocalToolService [SYSTEM]: getWeather() called for city '" + city + "'...");
      List<String> mockReports =
          List.of(
              "Sunny, 75°F (24°C), with a light breeze.",
              "Rainy, 55°F (13°C), with 80% humidity.",
              "Partly cloudy, 68°F (20°C), perfect weather.",
              "Foggy, 50°F (10°C), visibility 1 mile.");
      int hash = Math.abs(city.hashCode()) % mockReports.size();
      return CompletableFuture.completedFuture(
          Map.of("weather", "The current weather in " + city + " is: " + mockReports.get(hash)));
    }
  }

  /** Calculates the sum of two integers. */
  private static final class AddNumbersTool extends BaseFutureTool {

    AddNumbersTool() {
      super("addNumbers", "Calculates the sum of two integers.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("addNumbers")
          .description("Calculates the sum of two integers.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "a",
                          Schema.builder()
                              .type(Type.INTEGER)
                              .description("The first number to add")
                              .build(),
                          "b",
                          Schema.builder()
                              .type(Type.INTEGER)
                              .description("The second number to add")
                              .build()))
                  .required("a", "b")
                  .build())
          .build();
    }

    @Override
    public CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      int a = ((Number) args.get("a")).intValue();
      int b = ((Number) args.get("b")).intValue();
      System.out.println(
          ">>> LocalToolService [SYSTEM]: addNumbers() called with a=" + a + ", b=" + b + "...");
      return CompletableFuture.completedFuture(Map.of("sum", String.valueOf(a + b)));
    }
  }

  static {
    try {
      Engine.Companion.setNativeMinLogSeverity(LogSeverity.INFINITY);
    } catch (Throwable t) {
      System.err.println("Failed to set native min log severity for LiteRT-LM Engine.");
      t.printStackTrace(System.err);
    }
  }

  public static final BaseAgent rootAgent = createRootAgent();

  private static BaseAgent createRootAgent() {
    String modelPath = System.getenv("LITERT_LM_MODEL_PATH");
    if (modelPath == null) {
      throw new IllegalStateException(
          "LITERT_LM_MODEL_PATH environment variable must be set pointing to a .litertlm file.");
    }
    EngineConfig engineConfig =
        new EngineConfig(
            modelPath,
            new Backend.CPU(),
            /* visionBackend= */ null,
            /* audioBackend= */ null,
            /* maxNumTokens= */ null,
            /* maxNumImages= */ null,
            /* cacheDir= */ null);
    return LlmAgent.builder()
        .name("litert_lm_agent")
        .model(LiteRtLmModel.Companion.create(engineConfig, "LiteRtLmModel"))
        .instruction(
            """
            You are a helpful assistant.
            You have access to tools for getting the current time, weather of a city, and adding two numbers.
            Please use these tools when necessary to fulfill user requests. Keep your answers concise.\
            """)
        .tools(new GetCurrentTimeTool(), new GetWeatherTool(), new AddNumbersTool())
        .build();
  }

  private LiteRtLmDemoAgentJava() {}
}
