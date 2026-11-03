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
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code single_turn_sub_agent} sample: a phone sales assistant turns the
 * user's request into structured preferences and calls a single-turn sub-agent, which checks prices
 * with a tool and answers with a structured recommendation without talking to the user. Unlike an
 * {@code AgentTool}, the sub-agent's tool calls stay in the session history.
 */
public final class SingleTurnSubAgentDemoJava {

  // Avoid gemini-3.8-flash, which often keeps calling check_phone_price instead of answering.
  private static final String MODEL_NAME = "gemini-4-argon";

  /**
   * Builds the sales assistant, with both agents on {@code model}. Run it as a node, with {@code
   * App.Builder.rootNode}, since its sub-agent sets a mode.
   */
  public static LlmAgent create(Model model) {
    return LlmAgent.builder()
        .name("root_agent")
        .model(model)
        .instruction(
            """
            You are a helpful phone sales associate.
            If the user is asking for a phone recommendation, use the `phone_recommender` to get a
            structured recommendation.
            Once the recommender finishes, present the model, price, and reason to the user in a
            friendly way.\
            """)
        // A single-turn sub-agent is offered to the root as a tool that takes its input schema.
        .subAgents(phoneRecommenderAgent(model))
        .build();
  }

  /**
   * Runs one request for a recommendation and prints each message and tool call. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    LlmAgent root =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("single_turn_sub_agent").rootNode(root).build());
    Content message =
        Content.fromText(
            Role.USER,
            "I need a phone mostly for gaming. I have about $1000 to spend, and I prefer large"
                + " phones.");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
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

  private static LlmAgent phoneRecommenderAgent(Model model) {
    return LlmAgent.builder()
        .name("phone_recommender")
        .model(model)
        .description("Recommends a Pixel phone based on preferences.")
        .mode(AgentMode.SINGLE_TURN)
        .inputSchema(USER_PREFERENCES_SCHEMA)
        .outputSchema(PHONE_RECOMMENDATION_SCHEMA)
        .tools(ReflectiveTools.fromMethod(new PixelPrices(), "checkPhonePrice"))
        .instruction(
            """
            You are an expert Google Pixel hardware recommender.
            Based on the provided UserPreferences, recommend exactly one Pixel phone model.
            You must use the `check_phone_price` tool to find the exact current price of the model
            you are recommending before you finish your task.
            Only recommend these phones: Pixel 10a, Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL,
            Pixel 10 Pro Fold.\
            """)
        .build();
  }

  /** A mock price list that the recommender checks through a tool. */
  static final class PixelPrices {
    // Longest names first, since a shorter name like "Pixel 10" is part of longer ones.
    private static final List<Map.Entry<String, Double>> PRICES =
        List.of(
            Map.entry("Pixel 10 Pro Fold", 1799.0),
            Map.entry("Pixel 10 Pro XL", 1199.0),
            Map.entry("Pixel 10 Pro", 999.0),
            Map.entry("Pixel 10a", 499.0),
            Map.entry("Pixel 10", 799.0));

    @Tool(
        name = "check_phone_price",
        description = "Mock tool to check the current price of a Pixel phone model.")
    public double checkPhonePrice(
        @Param(name = "modelName", description = "The Pixel model, such as Pixel 10 Pro")
            String modelName) {
      String requested = modelName.toLowerCase(Locale.ROOT);
      for (Map.Entry<String, Double> price : PRICES) {
        if (requested.contains(price.getKey().toLowerCase(Locale.ROOT))) {
          return price.getValue();
        }
      }
      return 799.0;
    }
  }

  /** The user's preferences, with the fields of adk-python's {@code UserPreferences} model. */
  private static final Schema USER_PREFERENCES_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "budget",
                  Schema.builder()
                      .type(Type.INTEGER)
                      .description("The user's maximum budget in USD")
                      .build(),
                  "primary_use",
                  Schema.builder()
                      .type(Type.STRING)
                      .description(
                          "What the user primarily uses their phone for (e.g., photography,"
                              + " gaming, basics)")
                      .build(),
                  "preferred_size",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("Preferred phone size (e.g., small, large, any)")
                      .build()))
          .required("budget", "primary_use", "preferred_size")
          .build();

  /** The recommendation, with the fields of adk-python's {@code PhoneRecommendation} model. */
  private static final Schema PHONE_RECOMMENDATION_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "model_name",
                  Schema.builder().type(Type.STRING).build(),
                  "price",
                  Schema.builder().type(Type.NUMBER).build(),
                  "reason",
                  Schema.builder().type(Type.STRING).build()))
          .required("model_name", "price", "reason")
          .build();

  private SingleTurnSubAgentDemoJava() {}
}
