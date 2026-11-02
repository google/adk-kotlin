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
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.ParallelWorkers;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code parallel_worker} workflow sample: an agent lists three topics
 * related to the user's topic, a function node uppercases each one, an agent explains how each
 * relates to the original topic, and a last node joins the explanations. The two middle nodes are
 * parallel workers, which run once per item of their list input, concurrently.
 */
public final class ParallelWorkerWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("parallel_worker")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        processInput(),
                        findRelatedTopicsAgent(model),
                        makeUpperCase(),
                        // parallelNode runs any node, an agent included, as a parallel worker.
                        ParallelWorkers.parallelNode(explainTopicAgent(model)),
                        aggregate()))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints what each node produced. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("parallel_worker").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "machine learning");
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

  /** Stores the user's topic in state, where both agents' instructions read it. */
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

  private static LlmAgent findRelatedTopicsAgent(Model model) {
    return LlmAgent.builder()
        .name("find_related_topics")
        .model(model)
        .instruction("Given the specific topic \"{topic}\", generate a list of 3 related topics.")
        // A workflow node takes a list schema, which works here because the agent has no tools.
        .outputSchema(
            Schema.builder()
                .type(Type.ARRAY)
                .items(Schema.builder().type(Type.STRING).build())
                .build())
        .build();
  }

  /** Uppercases each topic of its list input, in one concurrent run per topic. */
  private static FunctionNode<List<String>, List<String>> makeUpperCase() {
    return FunctionNode.parallelOf(
        FunctionNodes.of(
            "make_upper_case",
            String.class,
            String.class,
            (ctx, topic) -> topic.toUpperCase(Locale.ROOT)));
  }

  private static LlmAgent explainTopicAgent(Model model) {
    return LlmAgent.builder()
        .name("explain_topic")
        .model(model)
        .instruction("Explain how the following topic relates to the original topic: \"{topic}\".")
        .outputSchema(TOPIC_EXPLANATION_SCHEMA)
        .build();
  }

  /** Joins the explanations, which the parallel agent outputs as a list in input order. */
  private static FunctionNode<List<TopicExplanation>, Event> aggregate() {
    return FunctionNodes.of(
        "aggregate",
        new TypeReference<List<TopicExplanation>>() {},
        Event.class,
        (ctx, explanations) ->
            Event.builder()
                .content(
                    Content.fromText(
                        Role.MODEL,
                        explanations.stream()
                            .map(
                                explanation ->
                                    explanation.topic() + ": " + explanation.explanation())
                            .collect(Collectors.joining("\n\n---\n\n"))))
                .build());
  }

  /** An explanation from the agent, with the fields of adk-python's {@code TopicExplanation}. */
  record TopicExplanation(String topic, String explanation) {}

  /** The schema of {@link TopicExplanation}, which each run of the agent must match. */
  private static final Schema TOPIC_EXPLANATION_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "topic",
                  Schema.builder().type(Type.STRING).build(),
                  "explanation",
                  Schema.builder().type(Type.STRING).build()))
          .required("topic", "explanation")
          .build();

  private ParallelWorkerWorkflowJava() {}
}
