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

import com.google.adk.kt.agents.Context;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.Arrays;
import java.util.List;
import org.reactivestreams.Publisher;

/**
 * A port of the message-triage graph from the ADK docs: an agent labels a message as a bug, a
 * customer-support request, a logistics question, or several of these, and a custom node emits one
 * route per label so each matching handler runs.
 */
public final class MessageTriageWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /** Builds the workflow, with the classifier on {@code model}. */
  public static Workflow create(Model model) {
    Node processMessage = processMessageAgent(model);

    return Workflow.builder()
        .name("routing_workflow")
        .edges(
            g ->
                g.fromStart()
                    .chain(processMessage, new MessageCategories())
                    .route(
                        r -> {
                          r.on("BUG").then(new Responder("response_1_bug", "Handling bug..."));
                          r.on("CUSTOMER_SUPPORT")
                              .then(
                                  new Responder(
                                      "response_2_support", "Handling customer support..."));
                          r.on("LOGISTICS")
                              .then(new Responder("response_3_logistics", "Handling logistics..."));
                        }))
        .build();
  }

  /** Runs the workflow on one message and prints each reply. Needs {@code GOOGLE_API_KEY}. */
  public static void main(String[] args) {
    Workflow workflow = create(new Gemini(MODEL_NAME));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("routing_workflow").rootNode(workflow).build());
    Content message =
        Content.fromText(Role.USER, "The app crashes when I open my order, and my parcel is late.");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, message),
        event -> {
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
        });
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
        .outputKey("categories")
        .build();
  }

  /** Emits a route for each label the classifier left in state; several routes fan out. */
  private static final class MessageCategories extends BasePublisherNode {
    MessageCategories() {
      super("message_categories");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      String labels =
          context.getState().get("categories") instanceof String categories ? categories : "";
      context.setRoutes(
          Arrays.stream(labels.split(",", -1)).map(label -> new Route.Tag(label.trim())).toList());
      return AsyncJavaHelpers.publisherOf(List.of());
    }
  }

  /** Replies that the message is being handled. */
  private static final class Responder extends BasePublisherNode {
    private final String reply;

    Responder(String name, String reply) {
      super(name);
      this.reply = reply;
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      return AsyncJavaHelpers.publisherOf(
          List.of(Event.builder().author("").content(Content.fromText(Role.MODEL, reply)).build()));
    }
  }

  private MessageTriageWorkflowJava() {}
}
