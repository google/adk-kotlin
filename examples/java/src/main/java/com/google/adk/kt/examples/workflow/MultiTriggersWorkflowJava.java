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

import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Workflow;
import java.util.Locale;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code multi_triggers} workflow sample: three nodes transform the user's
 * text concurrently and each one triggers the same node, which therefore runs three times.
 */
public final class MultiTriggersWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    Node makeUppercase = makeUppercase();
    Node countCharacters = countCharacters();
    Node reverseString = reverseString();
    Node sendMessage = sendMessage();

    return Workflow.builder()
        .name("multi_triggers")
        // As adk-python's input_schema=str does, the workflow reads the user's message as text.
        .inputSchema(Schema.builder().type(Type.STRING).build())
        .edges(
            g -> {
              g.fromStart().then(makeUppercase, countCharacters, reverseString);
              // Without a JoinNode, each predecessor triggers its own run of send_message.
              g.from(makeUppercase).then(sendMessage);
              g.from(countCharacters).then(sendMessage);
              g.from(reverseString).then(sendMessage);
            })
        .build();
  }

  /** Runs the workflow on one message and prints the three reports. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("multi_triggers").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "Hello, workflows!");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println(text);
      }
    }
  }

  private static FunctionNode<String, String> makeUppercase() {
    return FunctionNodes.of(
        "make_uppercase", String.class, String.class, (ctx, text) -> text.toUpperCase(Locale.ROOT));
  }

  private static FunctionNode<String, Integer> countCharacters() {
    return FunctionNodes.of(
        "count_characters", String.class, Integer.class, (ctx, text) -> text.length());
  }

  private static FunctionNode<String, String> reverseString() {
    return FunctionNodes.of(
        "reverse_string",
        String.class,
        String.class,
        (ctx, text) -> new StringBuilder(text).reverse().toString());
  }

  /** Reports the output of whichever predecessor triggered this run. */
  private static FunctionNode<Object, Event> sendMessage() {
    return FunctionNodes.of(
        "send_message",
        Object.class,
        Event.class,
        (ctx, input) ->
            Event.builder()
                .content(Content.fromText(Role.MODEL, "Triggered for input: " + input))
                .build());
  }

  private MultiTriggersWorkflowJava() {}
}
