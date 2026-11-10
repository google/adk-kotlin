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
import com.google.adk.kt.classifiers.Binary;
import com.google.adk.kt.classifiers.Choice;
import com.google.adk.kt.classifiers.Classifier;
import com.google.adk.kt.classifiers.ClassifierNode;
import com.google.adk.kt.classifiers.LlmClassifier;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Workflow;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code classifier_node} workflow sample: a {@link ClassifierNode} asks
 * which team should handle a support ticket and routes the ticket to that team. Technical tickets
 * go to a second classifier node, which asks whether the ticket is urgent and either pages the
 * on-call engineer or files it in the engineering queue.
 */
public final class ClassifierNodeWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";
  private static final String URGENCY_KEY = "urgency_answer";

  /** Builds the workflow, with the classifier and both agents on {@code model}. */
  public static Workflow create(Model model) {
    // Any Classifier works here, such as a dedicated decision model.
    Classifier classifier = LlmClassifier.builder().model(model).build();
    ClassifierNode triage =
        ClassifierNode.builder()
            .name("triage")
            .classifier(classifier)
            .question(
                Choice.builder()
                    .instructions("Which team should handle this support ticket?")
                    .criterion("billing", "Invoices, refunds, or subscription charges")
                    .criterion("technical", "Bugs, errors, outages, or API problems")
                    .criterion("general", "Product questions and anything else")
                    .build())
            .answerKey("triage_answer")
            .build();
    ClassifierNode isUrgent =
        ClassifierNode.builder()
            .name("is_urgent")
            .classifier(classifier)
            .question(
                Binary.builder()
                    .instructions(
                        "Is this an outage, or does it block the customer's production use?")
                    .build())
            .threshold(0.7)
            .answerKey(URGENCY_KEY)
            .build();
    return Workflow.builder()
        .name("classifier_node")
        .edges(g -> {
          g.fromStart()
              .chain(processInput(), triage)
              .route(r -> {
                r.on("billing").then(billingAgent(model));
                r.on("technical").then(isUrgent);
                r.on("general").then(faqAgent(model));
              });
          g.from(isUrgent)
              .route(r -> {
                r.on(true).then(pageOncall());
                r.on(false).then(engQueue());
              });
        })
        .build();
  }

  /**
   * Runs the workflow on four tickets, each in its own session, and prints each reply. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("classifier_node").rootNode(workflow).build());
    List<String> tickets =
        List.of(
            "My invoice charged me twice this month.",
            "The checkout API returns 500 for every request and production is down.",
            "There is a typo on the pricing page.",
            "Do you have a dark mode?");
    for (int index = 0; index < tickets.size(); index++) {
      System.out.println("\n=== " + tickets.get(index));
      Content message = Content.fromText(Role.USER, tickets.get(index));
      for (Event event :
          Flux.from(runner.runAsync("user", "session_" + index, null, message)).toIterable()) {
        String text = event.contentText(" ");
        if (!text.isBlank()) {
          System.out.println("[" + event.getAuthor() + "] " + text);
        }
      }
    }
  }

  /** Stores the ticket in state for the agents' instructions to read, and outputs it to triage. */
  private static FunctionNode<String, String> processInput() {
    return FunctionNodes.of(
        "process_input",
        // START hands the first node the user's message, which a String input receives as text.
        String.class,
        String.class,
        (ctx, ticket) -> {
          ctx.updateState("ticket", ticket);
          return ticket;
        });
  }

  /** Reports the page with the urgency that {@code is_urgent} stored in state. */
  private static FunctionNode<String, Event> pageOncall() {
    return FunctionNodes.of(
        "page_oncall",
        String.class,
        Event.class,
        (ctx, ticket) -> {
          Map<?, ?> answer = (Map<?, ?>) ctx.getState().get(URGENCY_KEY);
          String urgency =
              NumberFormat.getPercentInstance(Locale.ROOT).format(answer.get("probability"));
          return message("Paged the on-call engineer (urgency " + urgency + "): " + ticket);
        });
  }

  private static FunctionNode<String, Event> engQueue() {
    return FunctionNodes.of(
        "eng_queue",
        String.class,
        Event.class,
        (ctx, ticket) -> message("Filed in the engineering queue: " + ticket));
  }

  private static Event message(String text) {
    return Event.builder().content(Content.fromText(Role.MODEL, text)).build();
  }

  private static LlmAgent billingAgent(Model model) {
    return LlmAgent.builder()
        .name("billing_agent")
        .model(model)
        .instruction("You are a billing specialist. Reply to this ticket: {ticket}")
        .build();
  }

  private static LlmAgent faqAgent(Model model) {
    return LlmAgent.builder()
        .name("faq_agent")
        .model(model)
        .instruction("You answer general product questions. Reply to: {ticket}")
        .build();
  }

  private ClassifierNodeWorkflowJava() {}
}
