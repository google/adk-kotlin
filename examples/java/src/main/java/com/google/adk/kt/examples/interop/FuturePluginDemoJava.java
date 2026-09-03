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

import com.google.adk.kt.agents.InvocationContext;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFuturePlugin;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing a {@link com.google.adk.kt.plugins.Plugin} from Java via {@link
 * BaseFuturePlugin}. The plugin's lifecycle hooks are {@code suspend} on the engine; the base
 * re-exposes them as optional {@link CompletableFuture}-returning methods. This plugin only
 * observes (returning the unchanged value), and runs against an offline fake model.
 */
public final class FuturePluginDemoJava {

  /** Observes the run lifecycle; returns the unchanged value from each hook. */
  private static final class LoggingPlugin extends BaseFuturePlugin {
    private final AtomicInteger events = new AtomicInteger();

    LoggingPlugin() {
      super("logging_plugin");
    }

    @Override
    protected CompletableFuture<Content> onUserMessageAsync(
        InvocationContext invocationContext, Content userMessage) {
      System.out.println("[plugin] user message: " + firstText(userMessage));
      return CompletableFuture.completedFuture(userMessage); // Keep the original message.
    }

    @Override
    protected CompletableFuture<Event> onEventAsync(
        InvocationContext invocationContext, Event event) {
      events.incrementAndGet();
      return CompletableFuture.completedFuture(event); // Keep the original event.
    }

    @Override
    protected CompletableFuture<Void> afterRunAsync(InvocationContext invocationContext) {
      System.out.println("[plugin] run complete; observed " + events.get() + " event(s)");
      return CompletableFuture.completedFuture(null);
    }
  }

  /** A minimal offline model returning a fixed reply. */
  private static final class FixedModel extends BasePublisherModel {
    FixedModel() {
      super("fixed-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      return AsyncJavaHelpers.publisherOf(
          List.of(
              LlmResponse.builder()
                  .content(Content.fromText(Role.MODEL, "Hello from the model."))
                  .build()));
    }
  }

  public static void main(String[] args) {
    App app =
        App.builder()
            .appName("FuturePluginDemo")
            .rootAgent(
                LlmAgent.builder()
                    .name("plugin_agent")
                    .model(new FixedModel())
                    .instruction("Greet the user.")
                    .build())
            .plugins(new LoggingPlugin())
            .build();

    PublisherRunner runner = PublisherRunner.of(InMemoryRunner.builder().app(app).build());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync("demo-user", "demo-session", null, Content.fromText(Role.USER, "Hi!"));
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          String text = firstText(event.getContent());
          if (text != null && !text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
        });
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

  private FuturePluginDemoJava() {}
}
