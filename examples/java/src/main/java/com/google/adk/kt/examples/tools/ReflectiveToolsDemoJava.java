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

package com.google.adk.kt.examples.tools;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;

/**
 * Demonstrates {@link ReflectiveTools}: a Java caller annotates a plain method with {@code @Tool} /
 * {@code @Param} and reflection builds the tool, instead of hand-writing a {@code BaseFutureTool}
 * plus a {@code FunctionDeclaration}. This reflective path is for javac-only modules; if your Java
 * code can be built with the Kotlin toolchain (KSP), prefer letting KSP process {@code @Tool}
 * directly (see
 * examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java). The
 * demo runs offline against a scripted model.
 */
public final class ReflectiveToolsDemoJava {

  /** A plain object whose annotated method becomes a tool with no boilerplate. */
  public static final class WeatherTools {
    @Tool(description = "Look up the current weather in a city.")
    public Map<String, Object> getWeather(
        @Param(name = "city", description = "The city to look up.") String city) {
      return Map.of("city", city, "conditions", "sunny", "temperatureF", 68);
    }
  }

  /** Scripted model: first turn calls {@code getWeather}, second turn returns a text summary. */
  static final class ScriptedWeatherModel extends BasePublisherModel {
    private int invocations = 0;

    ScriptedWeatherModel() {
      super("scripted-weather-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      LlmResponse response;
      if (invocations++ == 0) {
        response =
            LlmResponse.builder()
                .content(
                    new Content(
                        Role.MODEL,
                        List.of(
                            Part.builder()
                                .functionCall(
                                    new FunctionCall(
                                        "getWeather",
                                        Map.of("city", "San Francisco"),
                                        "call-1",
                                        null,
                                        null))
                                .build())))
                .build();
      } else {
        response =
            LlmResponse.builder()
                .content(Content.fromText(Role.MODEL, "It's sunny and 68F in San Francisco."))
                .build();
      }
      return AsyncJavaHelpers.publisherOf(List.of(response));
    }
  }

  // Reflection is costly, so build the tool once and reuse it. This javac-only path is for modules
  // built without KSP; with the Kotlin toolchain prefer @Tool -
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java
  private static final BaseTool WEATHER_TOOL =
      ReflectiveTools.fromMethod(new WeatherTools(), "getWeather");

  public static void main(String[] args) {
    System.out.println("Reflectively built tool: " + WEATHER_TOOL.getName());
    System.out.println("  declaration: " + WEATHER_TOOL.declaration());

    BaseAgent agent =
        LlmAgent.builder()
            .name("weather_agent")
            .model(new ScriptedWeatherModel())
            .instruction("Use getWeather to answer weather questions.")
            .tools(WEATHER_TOOL)
            .build();
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("weather_app").rootAgent(agent).build());

    System.out.println("User > What's the weather in San Francisco?");
    List<Event> events = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(
            "user-1",
            "session-1",
            null,
            Content.fromText(Role.USER, "What's the weather in San Francisco?")),
        events::add);

    for (Event event : events) {
      if (!event.functionCalls().isEmpty()) {
        System.out.println("  call: " + event.functionCalls());
      } else if (!event.functionResponses().isEmpty()) {
        System.out.println("  tool result: " + event.functionResponses());
      } else if (event.getContent() != null) {
        System.out.println("  " + event.getAuthor() + ": " + event.getContent());
      }
    }
  }

  private ReflectiveToolsDemoJava() {}
}
