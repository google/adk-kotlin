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

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Workflow;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code nested_workflow} sample: for the year the user names, a nested
 * workflow finds a famous person born that year and writes their bio while an agent describes an
 * event from that year, and a {@link JoinNode} waits for both branches before one message combines
 * them.
 */
public final class NestedWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    // A Workflow is a Node, so a whole workflow can be one branch of another.
    Node findFamousPerson =
        Workflow.builder()
            .name("find_famous_person")
            .edges(g -> g.fromStart().chain(findNameAgent(model), generateBioAgent(model)))
            .build();

    return Workflow.builder()
        .name("nested_workflow")
        .edges(
            g ->
                g.fromStart()
                    .then(processInput())
                    .then(findFamousPerson, findHistoricalEventAgent(model))
                    .joinTo(new JoinNode("join_for_aggregation"))
                    .then(aggregateResults()))
        .build();
  }

  /**
   * Runs the workflow on one year and prints each reply. Needs {@code GOOGLE_CLOUD_PROJECT} and
   * Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("nested_workflow").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "1984");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  private static final Pattern YEAR = Pattern.compile("\\b\\d{4}\\b");

  /** Stores the year from the user's message in state, and fails the run if there is none. */
  private static FunctionNode<Content, Object> processInput() {
    return FunctionNodes.of(
        "process_input",
        // START hands the first node the user's message as Content.
        Content.class,
        Object.class,
        (ctx, message) -> {
          Matcher year = YEAR.matcher(message.text());
          if (!year.find()) {
            // A function node can't emit before it fails, so the error carries the reply.
            throw new IllegalArgumentException("Please provide a valid 4-digit year (e.g., 1955).");
          }
          ctx.updateState("year", year.group());
          return null;
        });
  }

  private static LlmAgent findNameAgent(Model model) {
    return LlmAgent.builder()
        .name("find_name")
        .model(model)
        .instruction(
            """
            Find the name of one famous person who was born in this year: {year}.
            Return ONLY their name, nothing else.\
            """)
        .build();
  }

  private static LlmAgent generateBioAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_bio")
        .model(model)
        .instruction("Write a short, engaging 3-sentence biography for the specified person.")
        .build();
  }

  private static LlmAgent findHistoricalEventAgent(Model model) {
    return LlmAgent.builder()
        .name("find_historical_event")
        .model(model)
        .instruction(
            """
            Describe one highly significant historical event that occurred in this year: {year}.
            Keep the description to 2 sentences.\
            """)
        .build();
  }

  /** Combines both branches' answers, which the join passes on keyed by branch name. */
  private static FunctionNode<Map<String, String>, Event> aggregateResults() {
    return FunctionNodes.of(
        "aggregate_results",
        new TypeReference<Map<String, String>>() {},
        Event.class,
        (ctx, answers) ->
            Event.builder()
                .content(
                    Content.fromText(
                        Role.MODEL,
                        "# Year: "
                            + ctx.getState().get("year")
                            + "\n\n## Famous Person Bio:\n\n"
                            + answers.get("find_famous_person")
                            + "\n\n## Historical Event:\n\n"
                            + answers.get("find_historical_event")))
                .build());
  }

  private NestedWorkflowJava() {}
}
