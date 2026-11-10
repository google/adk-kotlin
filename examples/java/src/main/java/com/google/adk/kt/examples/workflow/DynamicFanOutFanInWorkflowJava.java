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
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.RunNodeConfig;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.NodeConfig;
import com.google.adk.kt.workflow.Workflow;
import java.util.Arrays;
import java.util.List;
import org.reactivestreams.Publisher;
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
        .edges(g -> g.fromStart().then(new Orchestrator(generatorAgent(model))))
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

  /** An input schema under which a node receives its input, such as a message, as text. */
  private static final Schema TEXT_INPUT = Schema.builder().type(Type.STRING).build();

  /** Runs the generator once per topic and tabulates the headlines. */
  private static final class Orchestrator extends BasePublisherNode {
    private final Node generator;

    Orchestrator(Node generator) {
      // Runs other nodes, so it reruns on resume to collect a paused child's output.
      super(
          "orchestrator",
          "",
          /* rerunOnResume= */ true,
          /* waitForOutput= */ false,
          NodeConfig.builder().build(),
          TEXT_INPUT);
      this.generator = generator;
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      // START hands the first node the user's message, which TEXT_INPUT reads as text.
      List<String> topics =
          Arrays.stream(((String) nodeInput).split(","))
              .map(String::trim)
              .filter(topic -> !topic.isEmpty())
              .toList();
      InteropContextView view = InteropContextView.of(context);
      // Fans out: each run gets its own sub-branch, so it sees only its own topic.
      Mono<Event> tableMessage =
          Flux.range(0, topics.size())
              // Starts every run at once; flatMapSequential otherwise runs at most 256 at a time.
              .flatMapSequential(i -> headline(view, topics.get(i), i + 1), Integer.MAX_VALUE)
              .collectList()
              // Fans in: one table row per topic.
              .map(headlines -> message(headlineTable(topics, headlines)));
      return Flux.concat(
          Mono.just(message("Processing " + topics.size() + " topics in parallel.")), tableMessage);
    }

    /**
     * Runs the generator on the topic numbered {@code index}, publishing "" if it has no output.
     */
    private Mono<Object> headline(InteropContextView view, String topic, int index) {
      // Matches the Kotlin example's run IDs; a run ID must contain a non-digit.
      RunNodeConfig config =
          RunNodeConfig.builder().runId("topic_" + index).useSubBranch(true).build();
      return Mono.fromFuture(() -> view.runNode(generator, topic, config)).defaultIfEmpty("");
    }
  }

  private static String headlineTable(List<String> topics, List<Object> headlines) {
    StringBuilder table =
        new StringBuilder("### Aggregated Headlines\n\n| Topic | Headline |\n| :--- | :--- |\n");
    for (int i = 0; i < topics.size(); i++) {
      table.append("| " + topics.get(i) + " | " + headlines.get(i) + " |\n");
    }
    return table.toString();
  }

  /** A message from the node, which is not its output. */
  private static Event message(String text) {
    return Event.builder().content(Content.fromText(Role.MODEL, text)).build();
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
