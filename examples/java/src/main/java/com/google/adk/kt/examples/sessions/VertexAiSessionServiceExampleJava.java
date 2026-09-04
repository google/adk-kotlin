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

package com.google.adk.kt.examples.sessions;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.sessions.VertexAiSessionService;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;

/**
 * Runs a tool-using agent whose session is persisted in the managed Vertex AI Session Service.
 *
 * <p>A {@link VertexAiSessionService} is handed to an {@link InMemoryRunner} (only the session
 * service is Vertex-backed; artifacts and memory stay in-memory), so the user turn, the agent's
 * {@code getWeather} function call, the tool response, and the model's reply all round-trip through
 * the managed service. Each non-partial event is printed as it streams, showing the run completing
 * through the managed session service.
 *
 * <p>The Vertex service assigns the session id, so the session is created up front and its id is
 * reused for the run.
 *
 * <p>Authentication uses Application Default Credentials ({@code gcloud auth application-default
 * login}).
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code GOOGLE_CLOUD_PROJECT} - GCP project id.
 *   <li>{@code VERTEX_REASONING_ENGINE_ID} - the numeric reasoning-engine id. Passed to the service
 *       at construction (the session key's app name is just a label and is not parsed for the
 *       engine).
 *   <li>{@code GOOGLE_CLOUD_LOCATION} - Vertex region for sessions (optional, defaults to {@code
 *       us-central1}).
 * </ul>
 */
public final class VertexAiSessionServiceExampleJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /**
   * A single tool the agent can call, built from an annotated method by {@link ReflectiveTools}
   * (used here because this example is compiled with javac, not the Kotlin compiler; prefer the KSP
   * {@code @Tool} path when available).
   */
  static final class WeatherTools {
    private static final List<String> CONDITIONS = List.of("sunny", "cloudy", "rainy", "windy");

    @Tool(name = "getWeather", description = "Looks up the current weather in a city.")
    public Map<String, Object> getWeather(
        @Param(name = "city", description = "The city to look up.") String city) {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      return Map.of(
          "city",
          city,
          "temperatureCelsius",
          random.nextInt(-5, 35),
          "condition",
          CONDITIONS.get(random.nextInt(CONDITIONS.size())));
    }
  }

  // Reflection is costly, so build the tool once and reuse it. javac-only path; with Kotlin + KSP
  // prefer @Tool -
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java
  private static final BaseTool GET_WEATHER =
      ReflectiveTools.fromMethod(new WeatherTools(), "getWeather");

  public static void main(String[] args) {
    String project = requireEnv("GOOGLE_CLOUD_PROJECT");
    String reasoningEngineId = requireEnv("VERTEX_REASONING_ENGINE_ID");
    String locationEnv = System.getenv("GOOGLE_CLOUD_LOCATION");
    String location = locationEnv == null || locationEnv.isBlank() ? "us-central1" : locationEnv;

    // Pin the reasoning engine at construction; the session key's app name is then just a label.
    VertexAiSessionService sessionService =
        VertexAiSessionService.builder()
            .project(project)
            .location(location)
            .reasoningEngineId(reasoningEngineId)
            .build();
    String appName = "weather-app";
    BaseAgent agent =
        LlmAgent.builder()
            .name("weather_agent")
            .description("Answers weather questions using the getWeather tool.")
            .model(new Gemini(MODEL_NAME, new VertexCredentials(project, "global", null)))
            .instruction("Use the getWeather tool to answer weather questions. Answer briefly.")
            .tools(GET_WEATHER)
            .build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder()
                .agent(agent)
                .appName(appName)
                .sessionService(sessionService)
                .build());

    String userId = "demo-user";
    // Session management is a SessionService concern, not the runner's. The Vertex service assigns
    // the session id, so create the session first and reuse its id.
    Session created =
        AsyncJavaHelpers.await(
            c -> sessionService.createSession(new SessionKey(appName, userId, null), null, c));
    String sessionId = Objects.requireNonNull(created.getKey().getId());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(
            userId,
            sessionId,
            null,
            Content.fromText(Role.USER, "What's the weather in San Francisco?"));
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          if (event.getPartial()) {
            return;
          }
          Content content = event.getContent();
          String text =
              content == null
                  ? ""
                  : content.getParts().stream()
                      .map(Part::getText)
                      .filter(Objects::nonNull)
                      .collect(Collectors.joining(" "));
          if (!text.isBlank()) {
            System.out.println(event.getAuthor() + " > " + text);
          }
        });

    System.exit(0);
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Environment variable " + name + " is not set.");
    }
    return value;
  }

  private VertexAiSessionServiceExampleJava() {}
}
