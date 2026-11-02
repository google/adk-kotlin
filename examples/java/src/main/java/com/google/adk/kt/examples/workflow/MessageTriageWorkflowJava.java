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

package com.google.adk.kt.examples.workflow;

import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.Arrays;
import reactor.core.publisher.Flux;

/**
 * A port of the message-triage graph from the ADK docs: an agent labels a message as a bug, a
 * customer-support request, a logistics question, or several of these, and a function node emits
 * one route per label so each matching handler runs.
 */
public final class MessageTriageWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the classifier on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("routing_workflow")
        .edges(
            g ->
                g.fromStart()
                    .chain(processMessageAgent(model), messageCategories())
                    .route(
                        r -> {
                          r.on("BUG").then(responder("response_1_bug", "Handling bug..."));
                          r.on("CUSTOMER_SUPPORT")
                              .then(
                                  responder("response_2_support", "Handling customer support..."));
                          r.on("LOGISTICS")
                              .then(responder("response_3_logistics", "Handling logistics..."));
                        }))
        .build();
  }

  /**
   * Runs the workflow on one message and prints each reply. Needs {@code GOOGLE_CLOUD_PROJECT} and
   * Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("routing_workflow").rootNode(workflow).build());
    Content message =
        Content.fromText(Role.USER, "The app crashes when I open my order, and my parcel is late.");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  private static LlmAgent processMessageAgent(Model model) {
    return LlmAgent.builder()
        .name("process_message")
        .model(model)
        .instruction(
            """
            Classify user message into either "BUG", "CUSTOMER_SUPPORT",
            or "LOGISTICS". If you think a message applies to more than one category,
            reply with a comma separated list of categories.\
            """)
        .build();
  }

  /** Emits a route for each label in the classifier's answer; several routes fan out. */
  private static FunctionNode<String, Object> messageCategories() {
    return FunctionNodes.of(
        "message_categories",
        String.class,
        Object.class,
        (ctx, labels) -> {
          ctx.setRoutes(
              Arrays.stream(labels.split(",", -1))
                  .map(label -> new Route.Tag(label.trim()))
                  .toList());
          return null;
        });
  }

  /** Returns a node that tells the user the message is being handled. */
  private static Node responder(String name, String reply) {
    return FunctionNodes.of(
        name,
        Object.class,
        Event.class,
        (ctx, input) -> Event.builder().content(Content.fromText(Role.MODEL, reply)).build());
  }

  private MessageTriageWorkflowJava() {}
}
