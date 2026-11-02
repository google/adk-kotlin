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
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code loop} workflow sample: an agent writes a headline about the user's
 * topic, a second agent grades whether it is about technology, and the workflow loops back with the
 * grader's feedback until it is.
 */
public final class LoopWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(Model model) {
    Node generateHeadline = generateHeadlineAgent(model);

    return Workflow.builder()
        .name("loop")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        processInput(),
                        generateHeadline,
                        evaluateHeadlineAgent(model),
                        routeHeadline())
                    .route(r -> r.on("unrelated").then(generateHeadline)))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints each headline and grade. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("loop").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "flower");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  /** Stores the user's topic in state, where the headline agent's instruction reads it. */
  private static FunctionNode<Content, Object> processInput() {
    return FunctionNodes.of(
        "process_input",
        // START hands the first node the user's message as Content.
        Content.class,
        Object.class,
        (ctx, message) -> {
          ctx.updateState("topic", message.text());
          return null;
        });
  }

  private static LlmAgent generateHeadlineAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_headline")
        .model(model)
        .instruction(
            """
            Write a headline about the topic "{topic}".
            If feedback is provided, take it into account.
            The feedback: {feedback?}\
            """)
        .build();
  }

  private static LlmAgent evaluateHeadlineAgent(Model model) {
    return LlmAgent.builder()
        .name("evaluate_headline")
        .model(model)
        .instruction("Grade whether the headline is related to technology or software engineering.")
        .outputSchema(FEEDBACK_SCHEMA)
        // Stores the grade and feedback for the headline agent's next attempt.
        .outputKey("feedback")
        .build();
  }

  /** Routes on the grade; no edge matches {@code tech-related}, so that grade ends the loop. */
  private static FunctionNode<Feedback, Object> routeHeadline() {
    return FunctionNodes.of(
        "route_headline",
        Feedback.class,
        Object.class,
        (ctx, feedback) -> {
          ctx.setRoutes(List.of(new Route.Tag(feedback.grade())));
          return null;
        });
  }

  /** The grader's structured answer, with the fields of adk-python's {@code Feedback} model. */
  record Feedback(String grade, String feedback) {}

  /** The schema of {@link Feedback}, which the grader's answer must match. */
  private static final Schema FEEDBACK_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "grade",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("tech-related", "unrelated")
                      .description(
                          "Decide if the headline is related to technology or software"
                              + " engineering.")
                      .build(),
                  "feedback",
                  Schema.builder()
                      .type(Type.STRING)
                      .description(
                          "If the headline is unrelated to technology, provide feedback on how to"
                              + " make it more tech-focused.")
                      .build()))
          .required("grade", "feedback")
          .build();

  private LoopWorkflowJava() {}
}
