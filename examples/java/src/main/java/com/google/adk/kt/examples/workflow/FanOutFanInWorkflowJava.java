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
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Workflow;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code fan_out_fan_in} workflow sample: three nodes transform the user's
 * text concurrently, and a {@link JoinNode} waits for all of them before one node combines their
 * outputs.
 */
public final class FanOutFanInWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("fan_out_fan_in")
        .edges(
            g ->
                g.fromStart()
                    // START hands each branch the user's message as Content.
                    .then(makeUppercase(), countCharacters(), reverseString())
                    .joinTo(new JoinNode("join_for_results"))
                    .then(aggregate()))
        .build();
  }

  /** Runs the workflow on one message and prints the combined result. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("fan_out_fan_in").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "Hello World");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println(text);
      }
    }
  }

  private static FunctionNode<Content, String> makeUppercase() {
    return FunctionNodes.of(
        "make_uppercase",
        Content.class,
        String.class,
        (ctx, message) -> message.text().toUpperCase(Locale.ROOT));
  }

  private static FunctionNode<Content, Integer> countCharacters() {
    return FunctionNodes.of(
        "count_characters",
        Content.class,
        Integer.class,
        (ctx, message) -> message.text().length());
  }

  private static FunctionNode<Content, String> reverseString() {
    return FunctionNodes.of(
        "reverse_string",
        Content.class,
        String.class,
        (ctx, message) -> new StringBuilder(message.text()).reverse().toString());
  }

  /** Combines the three outputs, which the join passes on keyed by node name. */
  private static FunctionNode<Map<String, Object>, Event> aggregate() {
    return FunctionNodes.of(
        "aggregate",
        new TypeReference<Map<String, Object>>() {},
        Event.class,
        (ctx, results) ->
            Event.builder()
                .content(
                    Content.fromText(
                        Role.MODEL,
                        "Uppercase: "
                            + results.get("make_uppercase")
                            + "\n\nCharacter Count: "
                            + results.get("count_characters")
                            + "\n\nReversed: "
                            + results.get("reverse_string")
                            + "\n\n"))
                .build());
  }

  private FanOutFanInWorkflowJava() {}
}
