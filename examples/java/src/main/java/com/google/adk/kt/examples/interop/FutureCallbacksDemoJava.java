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

package com.google.adk.kt.examples.interop;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.callbacks.AfterModelCallback;
import com.google.adk.kt.callbacks.BeforeModelCallback;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.Choices;
import com.google.adk.kt.interop.FutureCallbacks;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.reactivestreams.Publisher;

/**
 * Demonstrates wiring agent callbacks from Java via {@link FutureCallbacks} + {@link Choices}. The
 * engine's callback interfaces are {@code suspend}; the adapters turn them into {@link
 * CompletableFuture}-returning lambdas. The {@code beforeModel} callback is an input guardrail: it
 * short-circuits with {@link Choices#breakWith} for a blocked word and otherwise {@link
 * Choices#proceed}s. Runs offline against a fake model.
 */
public final class FutureCallbacksDemoJava {

  private static final String BLOCKED_WORD = "forbidden";

  /** A minimal offline model returning a fixed reply (only reached when the guardrail proceeds). */
  private static final class FixedModel extends BasePublisherModel {
    FixedModel() {
      super("fixed-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      return AsyncJavaHelpers.publisherOf(
          List.of(
              LlmResponse.builder()
                  .content(Content.fromText(Role.MODEL, "The model answered your question."))
                  .build()));
    }
  }

  public static void main(String[] args) {
    BeforeModelCallback guardrail =
        FutureCallbacks.beforeModel(
            (context, request) -> {
              if (lastUserText(request).toLowerCase(Locale.ROOT).contains(BLOCKED_WORD)) {
                LlmResponse blocked =
                    LlmResponse.builder()
                        .content(Content.fromText(Role.MODEL, "Request blocked by guardrail."))
                        .build();
                return CompletableFuture.completedFuture(Choices.breakWith(blocked));
              }
              return CompletableFuture.completedFuture(Choices.proceed(request));
            });

    AfterModelCallback logResponse =
        FutureCallbacks.afterModel(
            (context, response) -> {
              System.out.println("[afterModel] agent=" + context.getAgentName());
              return CompletableFuture.completedFuture(response);
            });

    BaseAgent agent =
        LlmAgent.builder()
            .name("guarded_agent")
            .model(new FixedModel())
            .instruction("Answer the user.")
            .beforeModelCallbacks(List.of(guardrail))
            .afterModelCallbacks(List.of(logResponse))
            .build();

    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder().agent(agent).appName("FutureCallbacksDemo").build());

    System.out.println("=== allowed prompt ===");
    runTurn(runner, "Tell me a fun fact.");
    System.out.println("=== blocked prompt ===");
    runTurn(runner, "Tell me something forbidden.");
  }

  private static void runTurn(PublisherRunner runner, String prompt) {
    List<Event> events = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(
            "demo-user", "session-" + System.nanoTime(), null, Content.fromText(Role.USER, prompt)),
        events::add);
    for (Event event : events) {
      String text = firstText(event.getContent());
      if (text != null && !text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  private static String lastUserText(LlmRequest request) {
    List<Content> contents = request.getContents();
    for (int i = contents.size() - 1; i >= 0; i--) {
      Content content = contents.get(i);
      if (Role.USER.equals(content.getRole())) {
        String text = firstText(content);
        if (text != null) {
          return text;
        }
      }
    }
    return "";
  }

  private static String firstText(Content content) {
    if (content == null) {
      return null;
    }
    for (Part part : content.getParts()) {
      if (part.getText() != null) {
        return part.getText();
      }
    }
    return null;
  }

  private FutureCallbacksDemoJava() {}
}
