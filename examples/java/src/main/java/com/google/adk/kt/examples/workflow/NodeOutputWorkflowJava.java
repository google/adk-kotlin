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
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;

/**
 * A port of adk-python's {@code node_output} workflow sample: a node's published value becomes its
 * output, a published {@link Event} passes through as is, and an agent with an output schema
 * answers with structured data. An {@link LlmAgent} node answers the conversation rather than its
 * node input and emits no output, so the last node reads the agent's answer from state, where
 * Python passes it as input.
 */
public final class NodeOutputWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";
  private static final String TOPIC_KEY = "topic_details";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(Model model) {
    Node generateStructuredOutput = topicAgent(model);

    return Workflow.builder()
        .name("node_output")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        new GenerateStringOutput(),
                        new GenerateEventOutput(),
                        generateStructuredOutput,
                        new ConsumeStructuredOutput()))
        .build();
  }

  /**
   * Runs the workflow on one topic and prints what each node produced. Needs {@code
   * GOOGLE_API_KEY}.
   */
  public static void main(String[] args) {
    Workflow workflow = create(new Gemini(MODEL_NAME));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("node_output").rootNode(workflow).build());
    Content message = Content.fromText(Role.USER, "cyberpunk future");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, message),
        event -> {
          String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + path + "] " + text);
          } else if (event.getOutput() != null) {
            System.out.println("[" + path + "] " + event.getOutput());
          }
        });
  }

  private static LlmAgent topicAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_structured_output")
        .model(model)
        .instruction("Generate a creative topic based on the user's input.")
        .outputSchema(TOPIC_SCHEMA)
        .outputKey(TOPIC_KEY)
        .build();
  }

  /** The agent's structured answer, with the fields of adk-python's {@code TopicDetails} model. */
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

  /** Publishes a string, which becomes the node's output. */
  private static final class GenerateStringOutput extends BasePublisherNode {
    GenerateStringOutput() {
      super("generate_string_output");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      // START hands the first node the user's message as Content.
      return AsyncJavaHelpers.publisherOf(
          List.of("Processed input: " + ((Content) nodeInput).text()));
    }
  }

  /** Publishes an {@link Event}, which passes through as is and could also carry routes. */
  private static final class GenerateEventOutput extends BasePublisherNode {
    GenerateEventOutput() {
      super("generate_event_output");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Event event = Event.builder().output("Event wrapped output: " + nodeInput).build();
      return AsyncJavaHelpers.publisherOf(List.of(event));
    }
  }

  /** Formats the agent's structured answer, which the agent left in state. */
  private static final class ConsumeStructuredOutput extends BasePublisherNode {
    ConsumeStructuredOutput() {
      super("consume_structured_output");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Map<?, ?> topic =
          context.getState().get(TOPIC_KEY) instanceof Map<?, ?> details ? details : Map.of();
      String result =
          """
          Received structured output!
          Title: %s
          Description: %s
          Category: %s\
          """
              .formatted(topic.get("title"), topic.get("description"), topic.get("category"));
      return AsyncJavaHelpers.publisherOf(List.of(result));
    }
  }

  private NodeOutputWorkflowJava() {}
}
