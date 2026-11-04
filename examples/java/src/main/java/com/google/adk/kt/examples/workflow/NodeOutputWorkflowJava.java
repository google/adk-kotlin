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
import com.google.adk.kt.workflow.Workflow;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code node_output} workflow sample: a function node's return value
 * becomes its output, a returned {@link Event} passes through as is, and an agent with an output
 * schema answers with structured data, which the next node receives decoded into a record.
 */
public final class NodeOutputWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("node_output")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        generateStringOutput(),
                        generateEventOutput(),
                        topicAgent(model),
                        consumeStructuredOutput()))
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
        PublisherRunner.inMemory(App.builder().appName("node_output").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "cyberpunk future");
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

  /** Returns a node whose returned string becomes its output. */
  private static FunctionNode<String, String> generateStringOutput() {
    return FunctionNodes.of(
        "generate_string_output",
        // START hands the first node the user's message, which a String input receives as text.
        String.class,
        String.class,
        (ctx, text) -> "Processed input: " + text);
  }

  /**
   * Returns a node whose returned {@link Event} passes through as is and could also carry routes or
   * state.
   */
  private static FunctionNode<String, Event> generateEventOutput() {
    return FunctionNodes.of(
        "generate_event_output",
        String.class,
        Event.class,
        (ctx, text) -> Event.builder().output("Event wrapped output: " + text).build());
  }

  private static LlmAgent topicAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_structured_output")
        .model(model)
        .instruction("Generate a creative topic based on the following input.")
        .outputSchema(TOPIC_SCHEMA)
        .build();
  }

  /** Formats the agent's structured answer, decoded from the node input into a record. */
  private static FunctionNode<TopicDetails, String> consumeStructuredOutput() {
    return FunctionNodes.of(
        "consume_structured_output",
        TopicDetails.class,
        String.class,
        (ctx, topic) ->
            """
            Received structured output!
            Title: %s
            Description: %s
            Category: %s\
            """
                .formatted(topic.title(), topic.description(), topic.category()));
  }

  /** The agent's structured answer, with the fields of adk-python's {@code TopicDetails} model. */
  record TopicDetails(String title, String description, String category) {}

  /** The schema of {@link TopicDetails}, which the agent's answer must match. */
  private static final Schema TOPIC_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "title",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The title of the generated topic.")
                      .build(),
                  "description",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("A short description of the topic.")
                      .build(),
                  "category",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The broad category of the topic.")
                      .build()))
          .required("title", "description", "category")
          .build();

  private NodeOutputWorkflowJava() {}
}
