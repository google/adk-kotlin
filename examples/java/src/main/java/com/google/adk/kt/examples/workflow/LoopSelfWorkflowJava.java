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
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.NodeConfig;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A port of adk-python's {@code loop_self} workflow sample: the user picks a number from 0 to 10,
 * and a node keeps routing back to itself until it guesses it.
 */
public final class LoopSelfWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    Node guessNumber = new GuessNumber();

    return Workflow.builder()
        .name("loop_self")
        .edges(
            g ->
                g.fromStart()
                    .chain(new ValidateInput(), guessNumber)
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

  /** An input schema under which a node receives its input, such as a message, as text. */
  private static final Schema TEXT_INPUT = Schema.builder().type(Type.STRING).build();

  /** Stores the user's number in state, and fails the run if it is out of range. */
  private static final class ValidateInput extends BasePublisherNode {
    ValidateInput() {
      super(
          "validate_input",
          "",
          /* rerunOnResume= */ false,
          /* waitForOutput= */ false,
          NodeConfig.builder().build(),
          TEXT_INPUT);
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      // START hands the first node the user's message, which TEXT_INPUT reads as text.
      int number = Integer.parseInt(((String) nodeInput).trim());
      if (number < 0 || number > 10) {
        Event message =
            Event.builder()
                .content(Content.fromText(Role.MODEL, "Please provide a number between 0 and 10."))
                .build();
        return Mono.<Object>just(message)
            .concatWith(Mono.error(new IllegalArgumentException("Invalid input.")));
      }
      context.updateState("target_number", number);
      return Mono.empty();
    }
  }

  /** Guesses a number, and routes back to itself when the guess is wrong. */
  private static final class GuessNumber extends BasePublisherNode {
    GuessNumber() {
      super("guess_number");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      int guess = ThreadLocalRandom.current().nextInt(0, 11);
      Event guessing =
          Event.builder()
              .content(Content.fromText(Role.MODEL, "Guessing " + guess + "..."))
              .build();
      if (guess == ((Number) context.getState().get("target_number")).intValue()) {
        Event correct = Event.builder().content(Content.fromText(Role.MODEL, "Correct!")).build();
        return Flux.just(guessing, correct);
      }
      context.setRoutes(List.of(new Route.Tag("guessed_wrong")));
      return Mono.just(guessing);
    }
  }

  private LoopSelfWorkflowJava() {}
}
