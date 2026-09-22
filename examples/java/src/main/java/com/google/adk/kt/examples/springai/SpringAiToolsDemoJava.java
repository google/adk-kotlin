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

package com.google.adk.kt.examples.springai;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.springai.SpringAiModel;
import com.google.adk.kt.models.springai.SpringAiTool;
import com.google.adk.kt.models.springai.SpringAiToolset;
import com.google.genai.Client;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Java port of the mixed-tools demo. An ADK agent runs on a Spring AI {@code ChatModel} via {@link
 * SpringAiModel}; its tools are Spring AI {@code ToolCallback}s bridged into ADK via {@link
 * SpringAiTool}, and a {@code ToolCallbackProvider} is exposed as an ADK toolset via {@link
 * SpringAiToolset}. Native ADK tools are omitted here because {@code BaseTool.run} is a Kotlin
 * {@code suspend} function that Java cannot implement; the Kotlin demo covers that case.
 */
public final class SpringAiToolsDemoJava {

  private static final String MODEL = "gemini-flash-latest";

  private static GoogleGenAiChatModel chatModel() {
    String location = System.getenv("GOOGLE_CLOUD_LOCATION");
    if (location == null || location.isBlank()) {
      location = "global";
    }
    Client client =
        Client.builder()
            .vertexAI(true)
            .project(System.getenv("GOOGLE_CLOUD_PROJECT"))
            .location(location)
            .build();
    return GoogleGenAiChatModel.builder()
        .genAiClient(client)
        .options(GoogleGenAiChatOptions.builder().model(MODEL).build())
        .build();
  }

  private static ToolCallback weather() {
    Function<Map<String, Object>, String> fn = args -> "{\"tempC\":18}";
    return FunctionToolCallback.builder("get_weather", fn)
        .description("Get the current weather for a city, in Celsius.")
        .inputType(Map.class)
        .inputSchema(
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                + "\"required\":[\"city\"]}")
        .build();
  }

  private static ToolCallback time() {
    Function<Map<String, Object>, String> fn = args -> "09:00 UTC";
    return FunctionToolCallback.builder("get_time", fn)
        .description("Get the current local time for a timezone.")
        .inputType(Map.class)
        .inputSchema(
            "{\"type\":\"object\",\"properties\":{\"tz\":{\"type\":\"string\"}},"
                + "\"required\":[\"tz\"]}")
        .build();
  }

  private static ToolCallbackProvider timeProvider() {
    return () -> new ToolCallback[] {time()};
  }

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("spring_ai_tools_demo")
          .model(new SpringAiModel(MODEL, chatModel()))
          .instruction("Before answering, call the tools you need, then summarize.")
          // Exercises the SpringAiTool.from(List) factory and the SpringAiToolset provider wrapper
          // from Java.
          .tools(SpringAiTool.from(List.of(weather())))
          .toolsets(new SpringAiToolset(timeProvider()))
          .build();

  private SpringAiToolsDemoJava() {}
}
