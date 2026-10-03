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
import com.google.adk.kt.workflow.EdgesDsl;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Start;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;

/**
 * A port of adk-python's {@code nested_workflow} sample: for the year the user names, a nested
 * workflow finds a famous person born that year and writes their bio while an agent describes an
 * event from that year, and a {@link JoinNode} waits for both branches before one message combines
 * them.
 */
public final class NestedWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    Node findName = findNameAgent(model);
    Node generateBio = generateBioAgent(model);
    // A Workflow is a Node, so a whole workflow can be one branch of another.
    Node findFamousPerson =
        Workflow.builder()
            .name("find_famous_person")
            .edges(
                EdgesDsl.edges(
                    g -> {
                      g.then(Start.INSTANCE, findName);
                      g.then(findName, generateBio);
                    }))
            .build();
    Node findHistoricalEvent = findHistoricalEventAgent(model);
    JoinNode join = new JoinNode("join_for_aggregation");
    Node processInput = new ProcessInput();
    Node aggregateResults = new AggregateResults();

    return Workflow.builder()
        .name("nested_workflow")
        .edges(
            EdgesDsl.edges(
                g -> {
                  g.then(Start.INSTANCE, processInput);
                  g.then(processInput, findFamousPerson, findHistoricalEvent);
                  g.joinFrom(join, findFamousPerson, findHistoricalEvent);
                  g.then(join, aggregateResults);
                }))
        .build();
  }

  /** Runs the workflow on one year and prints each reply. Needs {@code GOOGLE_API_KEY}. */
  public static void main(String[] args) {
    Workflow workflow = create(new Gemini(MODEL_NAME));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("nested_workflow").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "1984");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, message),
        event -> {
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
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
        .outputKey("bio")
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
        .outputKey("historical_event")
        .build();
  }

  private static final Pattern YEAR = Pattern.compile("\\b\\d{4}\\b");

  /** Stores the year from the user's message in state, and fails the run if there is none. */
  private static final class ProcessInput extends BasePublisherNode {
    ProcessInput() {
      super("process_input");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      // START hands the first node the user's message as Content.
      Matcher year = YEAR.matcher(((Content) nodeInput).text());
      if (!year.find()) {
        // A synchronous node fails before publishing, so the error carries the message.
        throw new IllegalArgumentException("Please provide a valid 4-digit year (e.g., 1955).");
      }
      context.updateState("year", year.group());
      return AsyncJavaHelpers.publisherOf(List.of());
    }
  }

  /** Combines both branches' answers, which LlmAgent nodes leave in state rather than output. */
  private static final class AggregateResults extends BasePublisherNode {
    AggregateResults() {
      super("aggregate_results");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      String message =
          "# Year: "
              + context.getState().get("year")
              + "\n\n## Famous Person Bio:\n\n"
              + context.getState().get("bio")
              + "\n\n## Historical Event:\n\n"
              + context.getState().get("historical_event");
      return AsyncJavaHelpers.publisherOf(
          List.of(
              Event.builder().author("").content(Content.fromText(Role.MODEL, message)).build()));
    }
  }

  private NestedWorkflowJava() {}
}
