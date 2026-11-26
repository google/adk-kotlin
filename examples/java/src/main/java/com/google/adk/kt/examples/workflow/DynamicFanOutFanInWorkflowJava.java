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
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.RunNodeConfig;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Workflow;
import java.util.Arrays;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A port of adk-python's {@code dynamic_fan_out_fan_in} workflow sample: one node splits the user's
 * comma-separated topics, runs a headline agent on each topic concurrently with {@link
 * InteropContextView#runNode}, and collects the headlines into one table. The number of runs comes
 * from the input, not the graph.
 */
public final class DynamicFanOutFanInWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("dynamic_fan_out_fan_in")
        .edges(g -> g.fromStart().then(orchestrator(generatorAgent(model))))
        .build();
  }

  /**
   * Runs the workflow on three topics and prints the table. Needs {@code GOOGLE_CLOUD_PROJECT} and
   * Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("dynamic_fan_out_fan_in").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "AI, Cloud Computing, Quantum Computing");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  /** Returns the node that runs {@code generator} once per topic and tabulates the headlines. */
  private static FunctionNode<String, Object> orchestrator(Node generator) {
    return FunctionNodes.builder(String.class, Object.class)
        .name("orchestrator")
        // Runs other nodes, so it reruns on resume to collect a paused child's output.
        .rerunOnResume(true)
        .body((ctx, input) -> {
          // START hands the node the user's message, which a String input receives as text.
          List<String> topics =
              Arrays.stream(input.split(","))
                  .map(String::trim)
                  .filter(topic -> !topic.isEmpty())
                  .toList();
          InteropContextView view = InteropContextView.of(ctx);
          view.emit(
              Content.fromText(Role.MODEL, "Processing " + topics.size() + " topics in parallel."));
          // Fans out: each run gets its own sub-branch, so it sees only its own topic.
          List<Object> headlines =
              Flux.range(0, topics.size())
                  // Starts every run at once; flatMapSequential otherwise runs 256 at most.
                  .flatMapSequential(
                      i -> headline(view, generator, topics.get(i), i + 1), Integer.MAX_VALUE)
                  .collectList()
                  // get() rather than block(), which would turn a child's pause into a failure.
                  .toFuture()
                  .get();
          // Fans in: one table row per topic.
          view.emit(Content.fromText(Role.MODEL, headlineTable(topics, headlines)));
          return null;
        })
        .build();
  }

  /**
   * Runs {@code generator} on the topic numbered {@code index}, publishing "" if it has no output.
   */
  private static Mono<Object> headline(
      InteropContextView view, Node generator, String topic, int index) {
    // Matches the Kotlin example's run IDs; a run ID must contain a non-digit.
    RunNodeConfig config =
        RunNodeConfig.builder().runId("topic_" + index).useSubBranch(true).build();
    return Mono.fromFuture(() -> view.runNode(generator, topic, config)).defaultIfEmpty("");
  }

  private static String headlineTable(List<String> topics, List<Object> headlines) {
    StringBuilder table =
        new StringBuilder("### Aggregated Headlines\n\n| Topic | Headline |\n| :--- | :--- |\n");
    for (int i = 0; i < topics.size(); i++) {
      table.append("| " + topics.get(i) + " | " + headlines.get(i) + " |\n");
    }
    return table.toString();
  }

  private static LlmAgent generatorAgent(Model model) {
    return LlmAgent.builder()
        .name("generator")
        .model(model)
        .instruction(
            "Write a catchy one-line headline about the topic provided in the user message.")
        .build();
  }

  private DynamicFanOutFanInWorkflowJava() {}
}
