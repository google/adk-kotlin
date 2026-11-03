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

package com.google.adk.kt.examples.collaboration;

import com.google.adk.kt.agents.AgentMode;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code input_output_schema} sample: a weather agent with an input and an
 * output schema runs in single-turn mode, so it is offered to its parent as a tool that takes a
 * city and answers with structured weather information.
 */
public final class InputOutputSchemaDemoJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /**
   * Builds the weather concierge, with both agents on {@code model}. Run it as a node, with {@code
   * App.Builder.rootNode}, since its sub-agent sets a mode.
   */
  public static LlmAgent create(Model model) {
    return LlmAgent.builder()
        .name("root_agent")
        .model(model)
        .instruction(
            "You are a helpful weather concierge assistant. Use the weather_agent tool to get"
                + " weather information for the user's city, and then answer the user in a"
                + " friendly manner.")
        .subAgents(weatherAgent(model))
        .build();
  }

  /**
   * Asks about three cities, each in its own session, and prints each message and tool call. Needs
   * {@code GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    LlmAgent root =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("input_output_schema").rootNode(root).build());
    List<String> questions =
        List.of(
            "What is the weather in San Jose?",
            "Can you check the weather for Cupertino?",
            "What is the weather in Tokyo?");
    for (int index = 0; index < questions.size(); index++) {
      System.out.println("\n=== " + questions.get(index));
      Content message = Content.fromText(Role.USER, questions.get(index));
      for (Event event :
          Flux.from(runner.runAsync("user", "session_" + index, null, message)).toIterable()) {
        String text = event.contentText(" ");
        if (!text.isBlank()) {
          System.out.println("[" + event.getAuthor() + "] " + text);
        }
        for (FunctionCall call : event.functionCalls()) {
          System.out.println(
              "[" + event.getAuthor() + "] calls " + call.getName() + "(" + call.getArgs() + ")");
        }
      }
    }
  }

  private static LlmAgent weatherAgent(Model model) {
    return LlmAgent.builder()
        .name("weather_agent")
        .model(model)
        // Runs as a tool for the parent, with the schemas as its parameters and answer.
        .mode(AgentMode.SINGLE_TURN)
        // No transfer_to_agent tool, so the agent answers instead of handing the request back.
        .disallowTransferToParent(true)
        .inputSchema(CITY_QUERY_SCHEMA)
        .outputSchema(WEATHER_INFO_SCHEMA)
        .instruction(
            """
            Provide weather information for the requested city.

            For San Jose, return temperature: 26 C, conditions: Sunny.
            For Cupertino, return temperature: 16 C, conditions: Foggy.
            For any other city, return temperature: unknown, conditions: unknown.\
            """)
        .build();
  }

  /** The weather agent's input, with the fields of adk-python's {@code CityQuery} model. */
  private static final Schema CITY_QUERY_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "city",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The name of the city to query weather for, e.g. San Jose")
                      .build()))
          .required("city")
          .build();

  /** The weather agent's answer, with the fields of adk-python's {@code WeatherInfo} model. */
  private static final Schema WEATHER_INFO_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "temperature",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The temperature in Celsius")
                      .build(),
                  "conditions",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The weather condition, e.g. Sunny")
                      .build()))
          .required("temperature", "conditions")
          .build();

  private InputOutputSchemaDemoJava() {}
}
