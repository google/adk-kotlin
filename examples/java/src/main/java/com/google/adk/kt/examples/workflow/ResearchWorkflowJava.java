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
import com.google.adk.kt.sessions.State;
import com.google.adk.kt.tools.GoogleSearchTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * A research workflow built with the workflow DSL. A planner fans out to two researchers that
 * search the web concurrently, a {@link JoinNode} waits for both before the writer drafts a brief,
 * and a reviewer sends the brief back for another round until it is complete or the round cap is
 * reached.
 */
public final class ResearchWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    Node plan = plannerAgent(model);

    return Workflow.builder()
        .name("research")
        .edges(
            g ->
                g.fromStart()
                    .then(saveQuestion())
                    .then(plan)
                    // Fans out; notes also go to state, since the reviewer sees only the brief.
                    .then(webResearcherAgent(model), docsResearcherAgent(model))
                    // Waits for both.
                    .joinTo(new JoinNode("gather"))
                    .chain(writerAgent(model), reviewerAgent(model), reviewGate(3))
                    .route(
                        r -> {
                          // Loops back while the reviewer asks for more.
                          r.on("needs-more").then(plan);
                          r.on("done").then(briefPublisher());
                        }))
        .build();
  }

  /**
   * Runs the workflow on one question and prints the published brief. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("research").rootNode(workflow).build());
    Content question =
        Content.fromText(Role.USER, "Should a small team adopt Kotlin Multiplatform?");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, question)).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + path + "] " + text);
      }
      if (Objects.equals(path, "research@1/publisher@1")) {
        System.out.println("\nBrief: " + event.getOutput());
      }
    }
  }

  private static LlmAgent plannerAgent(Model model) {
    return LlmAgent.builder()
        .name("planner")
        .model(model)
        .instruction(
            """
            Split this research question into two focused sub-questions: the first about
            real-world adoption and experience, the second about what the official documentation
            says.
            Question: {question}
            If this review of an earlier brief asks for more, target the gap it names:
            {review?}\
            """)
        .build();
  }

  private static LlmAgent webResearcherAgent(Model model) {
    return LlmAgent.builder()
        .name("web_researcher")
        .model(model)
        .instruction(
            "Research the first sub-question with Google Search. Reply with at most three"
                + " bullets, each ending with its source.")
        .tools(GoogleSearchTool.builder().build())
        .outputKey("web_notes")
        .build();
  }

  private static LlmAgent docsResearcherAgent(Model model) {
    return LlmAgent.builder()
        .name("docs_researcher")
        .model(model)
        .instruction(
            "Research the second sub-question with Google Search, preferring official"
                + " documentation. Reply with at most three bullets, each ending with its"
                + " source.")
        .tools(GoogleSearchTool.builder().build())
        .outputKey("docs_notes")
        .build();
  }

  private static LlmAgent writerAgent(Model model) {
    return LlmAgent.builder()
        .name("writer")
        .model(model)
        .instruction(
            """
            Write a one-paragraph brief answering this question from these notes, and cite the
            sources you use.
            Question: {question}
            Notes: {web_notes?}
            {docs_notes?}\
            """)
        .outputKey("brief")
        .build();
  }

  private static LlmAgent reviewerAgent(Model model) {
    return LlmAgent.builder()
        .name("reviewer")
        .model(model)
        .instruction(
            """
            Review this brief against the question and the research notes.
            Question: {question}
            Brief: {brief?}
            Notes: {web_notes?}
            {docs_notes?}\
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey("review")
        .build();
  }

  /** The reviewer's structured verdict, which the review gate routes on. */
  private static final Schema REVIEW_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "verdict",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("done", "needs-more")
                      .description(
                          "needs-more if the brief leaves part of the question unanswered or makes"
                              + " a claim the notes do not support; otherwise done.")
                      .build(),
                  "feedback",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The gap to research next, or empty if done.")
                      .build()))
          .required("verdict", "feedback")
          .build();

  /** Stores the user's question in state, where the planner, writer and reviewer read it. */
  private static FunctionNode<Content, String> saveQuestion() {
    return FunctionNodes.of(
        "save_question",
        // START hands the first node the user's message as Content.
        Content.class,
        String.class,
        (ctx, message) -> {
          String question = message.text();
          ctx.updateState("question", question);
          return question;
        });
  }

  /**
   * Returns a node that routes on the reviewer's verdict, since an LlmAgent node emits no route. It
   * stops the loop after {@code maxRounds} research rounds even if the reviewer still asks for
   * more.
   */
  private static FunctionNode<Object, Object> reviewGate(int maxRounds) {
    return FunctionNodes.of(
        "review_gate",
        Object.class,
        Object.class,
        (ctx, input) -> {
          Object verdict =
              ctx.getState().get("review") instanceof Map<?, ?> review
                  ? review.get("verdict")
                  : null;
          // The run id counts this node's runs in this invocation, so it is the round number.
          int round = Integer.parseInt(ctx.getRunId());
          String route =
              Objects.equals(verdict, "needs-more") && round < maxRounds ? "needs-more" : "done";
          // Clears the review and notes when the loop ends, so the next question starts fresh.
          if (route.equals("done")) {
            for (String key : List.of("review", "web_notes", "docs_notes")) {
              ctx.updateState(key, State.getREMOVED());
            }
          }
          ctx.setRoutes(List.of(new Route.Tag(route)));
          return null;
        });
  }

  /** Outputs the brief the writer stored, which makes it the workflow's output. */
  private static FunctionNode<Object, String> briefPublisher() {
    return FunctionNodes.of(
        "publisher",
        Object.class,
        String.class,
        (ctx, input) -> {
          Object brief = ctx.getState().get("brief");
          // Consumes the brief, so a later question never publishes this one's answer.
          ctx.updateState("brief", State.getREMOVED());
          return (String) brief;
        });
  }

  private ResearchWorkflowJava() {}
}
