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
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code route} workflow sample: an agent classifies the user's input as a
 * question, a statement, or something else, and a function node routes it to the agent or node that
 * handles that category.
 */
public final class RouteWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("route")
        .edges(
            g ->
                g.fromStart()
                    .chain(processInput(), classifyInputAgent(model), routeOnCategory())
                    .route(
                        r -> {
                          r.on("question").then(answerQuestionAgent(model));
                          r.on("statement").then(commentOnStatementAgent(model));
                          r.on("other").then(handleOther());
                        }))
        .build();
  }

  /**
   * Runs the workflow on a question, a statement, and a request, each in its own session, and
   * prints each reply. Needs {@code GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("route").rootNode(workflow).build());
    List<String> inputs =
        List.of(
            "What is the capital of France?",
            "The weather is very nice today.",
            "Translate bonjour to english");
    for (int index = 0; index < inputs.size(); index++) {
      System.out.println("\n=== " + inputs.get(index));
      Content message = Content.fromText(Role.USER, inputs.get(index));
      for (Event event :
          Flux.from(runner.runAsync("user", "session_" + index, null, message)).toIterable()) {
        String text = event.contentText(" ");
        if (!text.isBlank()) {
          System.out.println("[" + event.getAuthor() + "] " + text);
        }
      }
    }
  }

  /** Stores the user's input in state, where the agents' instructions read it. */
  private static FunctionNode<Content, Object> processInput() {
    return FunctionNodes.of(
        "process_input",
        // START hands the first node the user's message as Content.
        Content.class,
        Object.class,
        (ctx, message) -> {
          ctx.updateState("input", message.text());
          return null;
        });
  }

  private static LlmAgent classifyInputAgent(Model model) {
    return LlmAgent.builder()
        .name("classify_input")
        .model(model)
        .instruction("Based on this input, decide which category it belongs to: {input}")
        .outputSchema(INPUT_CATEGORY_SCHEMA)
        .outputKey("category")
        .build();
  }

  /** Routes on the classifier's category, decoded from the node input into a record. */
  private static FunctionNode<InputCategory, Object> routeOnCategory() {
    return FunctionNodes.of(
        "route_on_category",
        InputCategory.class,
        Object.class,
        (ctx, category) -> {
          ctx.setRoutes(List.of(new Route.Tag(category.category())));
          return null;
        });
  }

  private static LlmAgent answerQuestionAgent(Model model) {
    return LlmAgent.builder()
        .name("answer_question")
        .model(model)
        .instruction("Answer the question: {input}")
        .build();
  }

  private static LlmAgent commentOnStatementAgent(Model model) {
    return LlmAgent.builder()
        .name("comment_on_statement")
        .model(model)
        .instruction("Comment on the statement: {input}")
        .build();
  }

  private static FunctionNode<Object, Event> handleOther() {
    return FunctionNodes.of(
        "handle_other",
        Object.class,
        Event.class,
        (ctx, input) ->
            Event.builder()
                .content(
                    Content.fromText(
                        Role.MODEL, "Sorry I can only answer questions or comment on statements."))
                .build());
  }

  /**
   * The classifier's structured answer, with the field of adk-python's {@code InputCategory} model.
   */
  record InputCategory(String category) {}

  /** The schema of {@link InputCategory}, which the classifier's answer must match. */
  private static final Schema INPUT_CATEGORY_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "category",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("question", "statement", "other")
                      .build()))
          .required("category")
          .build();

  private RouteWorkflowJava() {}
}
