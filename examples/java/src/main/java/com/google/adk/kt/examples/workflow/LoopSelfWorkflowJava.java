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
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code loop_self} workflow sample: the user picks a number from 0 to 10,
 * and a node keeps routing back to itself until it guesses it.
 */
public final class LoopSelfWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    FunctionNode<Object, Object> guessNumber = guessNumber();

    return Workflow.builder()
        .name("loop_self")
        .edges(g ->
            g.fromStart()
                .chain(validateInput(), guessNumber)
                .route(r -> r.on("guessed_wrong").then(guessNumber)))
        .build();
  }

  /** Runs the workflow on one number and prints every guess. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("loop_self").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "3");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println(text);
      }
    }
  }

  /** Sends the user's number as a state change, and fails the run if it is out of range. */
  private static FunctionNode<String, Object> validateInput() {
    return FunctionNodes.of(
        "validate_input",
        // START hands the first node the user's message, which a String input receives as text.
        String.class,
        Object.class,
        (ctx, text) -> {
          InteropContextView view = InteropContextView.of(ctx);
          int number = Integer.parseInt(text.trim());
          if (number < 0 || number > 10) {
            view.emit(Content.fromText(Role.MODEL, "Please provide a number between 0 and 10."));
            throw new IllegalArgumentException("Invalid input.");
          }
          view.emit(
              Event.builder()
                  .actions(
                      EventActions.builder()
                          // The framework adds to a state delta, so it must be mutable.
                          .stateDelta(new HashMap<>(Map.of("target_number", number)))
                          .build())
                  .build());
          return null;
        });
  }

  /** Guesses a number, and sends a route back to itself when the guess is wrong. */
  private static FunctionNode<Object, Object> guessNumber() {
    return FunctionNodes.of(
        "guess_number",
        Object.class,
        Object.class,
        (ctx, input) -> {
          InteropContextView view = InteropContextView.of(ctx);
          int guess = ThreadLocalRandom.current().nextInt(0, 11);
          view.emit(Content.fromText(Role.MODEL, "Guessing " + guess + "..."));
          if (guess == ((Number) ctx.getState().get("target_number")).intValue()) {
            view.emit(Content.fromText(Role.MODEL, "Correct!"));
          } else {
            view.emit(
                Event.builder()
                    .actions(
                        EventActions.builder()
                            .route(List.of(new Route.Tag("guessed_wrong")))
                            .build())
                    .build());
          }
          return null;
        });
  }

  private LoopSelfWorkflowJava() {}
}
