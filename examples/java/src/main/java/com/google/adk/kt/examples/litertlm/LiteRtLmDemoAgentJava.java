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
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.litertlm.LiteRtLmModel;
import com.google.adk.kt.tools.BaseTool;
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

/**
 * Example agent demonstrating how to use local tools with LiteRT-LM in the Kotlin ADK.
 *
 * <p>This agent uses {@link LiteRtLmModel} as its execution model and has access to the local tools
 * defined below, built from annotated methods by {@link ReflectiveTools} (used here because this
 * example is compiled with javac, not the Kotlin compiler; prefer the KSP {@code @Tool} path when
 * available).
 */
public final class LiteRtLmDemoAgentJava {

  /** The agent's local tools. */
  static final class LocalTools {
    @Tool(name = "getCurrentTime", description = "Returns the current local date and time.")
    public Map<String, Object> getCurrentTime() {
      LocalDateTime current =
          LocalDateTime.ofInstant(InstantSource.system().instant(), ZoneId.systemDefault());
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      System.out.println(">>> LocalToolService [SYSTEM]: getCurrentTime() called -> " + current);
      return Map.of("time", current.format(formatter));
    }

    @Tool(name = "getWeather", description = "Retrieves a weather report for the specified city.")
    public Map<String, Object> getWeather(
        @Param(name = "city", description = "The name of the city, e.g. \"San Francisco\"")
            String city) {
      System.out.println(
          ">>> LocalToolService [SYSTEM]: getWeather() called for city '" + city + "'...");
      List<String> mockReports =
          List.of(
              "Sunny, 75°F (24°C), with a light breeze.",
              "Rainy, 55°F (13°C), with 80% humidity.",
              "Partly cloudy, 68°F (20°C), perfect weather.",
              "Foggy, 50°F (10°C), visibility 1 mile.");
      int hash = Math.floorMod(city.hashCode(), mockReports.size());
      return Map.of("weather", "The current weather in " + city + " is: " + mockReports.get(hash));
    }

    @Tool(name = "addNumbers", description = "Calculates the sum of two integers.")
    public Map<String, Object> addNumbers(
        @Param(name = "a", description = "The first number to add") int a,
        @Param(name = "b", description = "The second number to add") int b) {
      System.out.println(
          ">>> LocalToolService [SYSTEM]: addNumbers() called with a=" + a + ", b=" + b + "...");
      return Map.of("sum", String.valueOf(a + b));
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

  private static final LocalTools TOOLS = new LocalTools();

  // javac can't run the @Tool KSP processor, so these tools are built reflectively; with Kotlin +
  // KSP prefer @Tool -
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java
  // Reflection is costly, so each tool is built once here and reused.
  private static final BaseTool GET_CURRENT_TIME =
      ReflectiveTools.fromMethod(TOOLS, "getCurrentTime");
  private static final BaseTool GET_WEATHER = ReflectiveTools.fromMethod(TOOLS, "getWeather");
  private static final BaseTool ADD_NUMBERS = ReflectiveTools.fromMethod(TOOLS, "addNumbers");

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
        .tools(GET_CURRENT_TIME, GET_WEATHER, ADD_NUMBERS)
        .build();
  }

  private LiteRtLmDemoAgentJava() {}
}
