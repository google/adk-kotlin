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
import com.google.adk.kt.events.EventActions;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.InteropContextView;
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
import com.google.adk.kt.workflow.Workflow;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code dynamic_nodes} workflow sample: the {@code loop} sample rewritten
 * as one node that runs the headline agents with {@link InteropContextView#runNodeBlocking} in a
 * loop, instead of routing between them with graph edges.
 */
public final class DynamicNodesWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("dynamic_nodes")
        .edges(g ->
            g.fromStart()
                .then(orchestrate(generateHeadlineAgent(model), evaluateHeadlineAgent(model))))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints each headline, each grade, and the result. Needs
   * {@code GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("dynamic_nodes").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "flower");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + path + "] " + text);
      } else if (event.getOutput() != null) {
        System.out.println("[" + path + "] " + event.getOutput());
      }
    }
  }

  /**
   * Runs the headline agent and then the grader on its headline until the grade is {@code
   * tech-related}, and outputs that headline. As in adk-python, nothing limits the number of
   * rounds.
   */
  private static FunctionNode<String, String> orchestrate(
      Node generateHeadline, Node evaluateHeadline) {
    // START hands the node the user's message, which a String input receives as text.
    return FunctionNodes.builder(String.class, String.class)
        .name("orchestrate")
        // Runs other nodes, so it reruns on resume to collect a paused child's output.
        .rerunOnResume(true)
        .body((ctx, topic) -> {
          InteropContextView view = InteropContextView.of(ctx);
          // Sends the topic first, so the session holds it when the agents read state.
          view.emit(
              Event.builder()
                  .actions(
                      EventActions.builder()
                          // The framework adds to a state delta, so it must be mutable.
                          .stateDelta(new HashMap<>(Map.of("topic", topic)))
                          .build())
                  .build());
          String headline;
          Map<?, ?> feedback;
          do {
            headline = (String) view.runNodeBlocking(generateHeadline, null);
            // The grader's object output schema makes its reply arrive as a map.
            feedback = (Map<?, ?>) view.runNodeBlocking(evaluateHeadline, headline);
          } while (!"tech-related".equals(feedback.get("grade")));
          return headline;
        })
        .build();
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

  /** The grader's answer, with the fields of adk-python's {@code Feedback} model. */
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

  private DynamicNodesWorkflowJava() {}
}
