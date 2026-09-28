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
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.sessions.State;
import com.google.adk.kt.tools.GoogleSearchTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.EdgesDsl;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Start;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.reactivestreams.Publisher;

/**
 * A research workflow built with the workflow DSL. A planner fans out to two researchers that
 * search the web concurrently, a {@link JoinNode} waits for both before the writer drafts a brief,
 * and a reviewer sends the brief back for another round until it is complete or the round cap is
 * reached.
 */
public final class ResearchWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    Node plan = plannerAgent(model);
    // Branches don't share events, so each researcher leaves its notes in state.
    Node searchWeb = webResearcherAgent(model);
    Node searchDocs = docsResearcherAgent(model);
    JoinNode gather = new JoinNode("gather");
    Node write = writerAgent(model);
    Node review = reviewerAgent(model);
    Node gate = new ReviewGate(3);
    Node publish = new BriefPublisher();

    return Workflow.builder()
        .name("research")
        .edges(
            EdgesDsl.edges(
                g -> {
                  g.then(Start.INSTANCE, plan);
                  // Fans out: both researchers run concurrently.
                  g.then(plan, searchWeb, searchDocs);
                  // Waits for both.
                  g.joinFrom(gather, searchWeb, searchDocs);
                  g.then(gather, write);
                  g.then(write, review);
                  g.then(review, gate);
                  g.thenRoute(
                      gate,
                      r -> {
                        // Loops back while the reviewer asks for more.
                        r.routesTo("needs-more", plan);
                        r.routesTo("done", publish);
                      });
                }))
        .build();
  }

  /**
   * Runs the workflow on one question and prints the published brief. Needs {@code GOOGLE_API_KEY}.
   */
  public static void main(String[] args) {
    Workflow workflow = create(new Gemini(MODEL_NAME));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("research").rootNode(workflow).build());
    Content question =
        Content.fromText(Role.USER, "Should a small team adopt Kotlin Multiplatform?");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, question),
        event -> {
          String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + path + "] " + text);
          }
          if (Objects.equals(path, "research@1/publisher@1")) {
            System.out.println("\nBrief: " + event.getOutput());
          }
        });
  }

  private static LlmAgent plannerAgent(Model model) {
    return LlmAgent.builder()
        .name("planner")
        .model(model)
        .instruction(
            """
            Split the user's research question into two focused sub-questions: the first about
            real-world adoption and experience, the second about what the official documentation
            says. If this review of an earlier brief asks for more, target the gap it names:
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
            Write a one-paragraph brief answering the user's question from these notes, and cite
            the sources you use:
            {web_notes?}
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
            Review this brief against the user's question and the research notes.
            Brief: {brief?}
            Notes: {web_notes?}
            {docs_notes?}\
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey("review")
        .build();
  }

  /** The reviewer's structured verdict, which {@link ReviewGate} routes on. */
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

  /**
   * Routes on the reviewer's verdict, since an LlmAgent node emits no route. Stops the loop after
   * {@code maxRounds} research rounds even if the reviewer still asks for more.
   */
  private static final class ReviewGate extends BasePublisherNode {
    private final int maxRounds;

    ReviewGate(int maxRounds) {
      super("review_gate");
      this.maxRounds = maxRounds;
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Object verdict =
          context.getState().get("review") instanceof Map<?, ?> review
              ? review.get("verdict")
              : null;
      // The run id counts this node's runs in the current invocation, so it is the round number.
      int round = Integer.parseInt(context.getRunId());
      String route =
          Objects.equals(verdict, "needs-more") && round < maxRounds ? "needs-more" : "done";
      // Clears the review and notes when the loop ends, so the next question starts fresh.
      if (route.equals("done")) {
        for (String key : List.of("review", "web_notes", "docs_notes")) {
          context.updateState(key, State.getREMOVED());
        }
      }
      context.setRoutes(List.of(new Route.Tag(route)));
      return AsyncJavaHelpers.publisherOf(List.of());
    }
  }

  /** Outputs the brief the writer stored, which makes it the workflow's output. */
  private static final class BriefPublisher extends BasePublisherNode {
    BriefPublisher() {
      super("publisher");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Object brief = context.getState().get("brief");
      // Consumes the brief, so a later question never publishes this one's answer.
      context.updateState("brief", State.getREMOVED());
      return AsyncJavaHelpers.publisherOf(brief == null ? List.of() : List.of(brief));
    }
  }

  private ResearchWorkflowJava() {}
}
