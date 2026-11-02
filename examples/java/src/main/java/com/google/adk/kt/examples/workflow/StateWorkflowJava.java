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
import com.google.adk.kt.events.EventActions;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Workflow;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code state} workflow sample: function nodes write session state through
 * the context and through an event, and read it back through the context. ADK's Java function nodes
 * take no parameters from state, so {@code read_state_via_param} reads its key through the context
 * too.
 */
public final class StateWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("state_sample")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        processInitialInput(),
                        updateStateViaEvent(),
                        readStateViaContext(),
                        readStateViaParam()))
        .build();
  }

  /** Runs the workflow on one message and prints each node's state changes and output. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("state_sample").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "Hello ADK!");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      if (!event.getActions().getStateDelta().isEmpty()) {
        System.out.println("[" + path + "] state: " + event.getActions().getStateDelta());
      }
      if (event.getOutput() != null) {
        System.out.println("[" + path + "] output: " + event.getOutput());
      }
    }
  }

  /** Stores the user's text in state through the context, and outputs it. */
  private static FunctionNode<Content, String> processInitialInput() {
    return FunctionNodes.of(
        "process_initial_input",
        // START hands the first node the user's message as Content.
        Content.class,
        String.class,
        (ctx, message) -> {
          String text = message.text();
          ctx.updateState("original_text", text);
          return text;
        });
  }

  /** Stores the uppercased text by returning an event whose state delta the session applies. */
  private static FunctionNode<String, Event> updateStateViaEvent() {
    return FunctionNodes.of(
        "update_state_via_event",
        String.class,
        Event.class,
        (ctx, text) -> {
          // The framework may add to an event's state delta, so the map must be mutable.
          Map<String, Object> stateDelta = new HashMap<>();
          stateDelta.put("uppercased_text", text.toUpperCase(Locale.ROOT));
          return Event.builder()
              .actions(EventActions.builder().stateDelta(stateDelta).build())
              .build();
        });
  }

  /** Reads both earlier values from state, then stores and outputs a sentence combining them. */
  private static FunctionNode<Object, String> readStateViaContext() {
    return FunctionNodes.of(
        "read_state_via_ctx",
        Object.class,
        String.class,
        (ctx, input) -> {
          Map<String, Object> state = ctx.getState();
          String result =
              state.get("uppercased_text") + " (Original was: " + state.get("original_text") + ")";
          ctx.updateState("appended_text", result);
          return result;
        });
  }

  /** Outputs the final result built from the sentence the previous node stored. */
  private static FunctionNode<Object, String> readStateViaParam() {
    return FunctionNodes.of(
        "read_state_via_param",
        Object.class,
        String.class,
        (ctx, input) -> "Final Result: " + ctx.getState().get("appended_text") + "!");
  }

  private StateWorkflowJava() {}
}
