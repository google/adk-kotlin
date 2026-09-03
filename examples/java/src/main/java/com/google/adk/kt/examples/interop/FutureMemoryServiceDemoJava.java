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
import com.google.adk.kt.callbacks.AfterAgentCallback;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureMemoryService;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.Choices;
import com.google.adk.kt.interop.FutureCallbacks;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.memory.MemoryEntry;
import com.google.adk.kt.memory.SearchMemoryResponse;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing a {@link com.google.adk.kt.memory.MemoryService} from Java via {@link
 * BaseFutureMemoryService}. The two required engine methods are {@code suspend}; the base asks the
 * subclass for a {@link CompletableFuture}. This store does naive keyword matching.
 *
 * <p>{@code main} shows the typical usage: the custom service is configured on an {@link
 * InMemoryRunner}, an agent run writes its session back to memory through it (from an after-agent
 * callback that calls {@code addSessionToMemory}), and the stored memory is then searched through
 * the same service.
 */
public final class FutureMemoryServiceDemoJava {

  /** A tiny in-memory memory store keyed by "appName/userId". */
  private static final class InMemoryDemoMemoryService extends BaseFutureMemoryService {
    private final Map<String, List<MemoryEntry>> memories = new ConcurrentHashMap<>();

    @Override
    protected CompletableFuture<Void> addSessionToMemoryAsync(Session session) {
      List<MemoryEntry> bucket =
          memories.computeIfAbsent(bucketKey(session.getKey()), k -> new ArrayList<>());
      for (Event event : session.getEvents()) {
        Content content = event.getContent();
        if (content != null) {
          bucket.add(MemoryEntry.builder().content(content).author(event.getAuthor()).build());
        }
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    protected CompletableFuture<SearchMemoryResponse> searchMemoryAsync(
        String appName, String userId, String query) {
      String needle = query.toLowerCase(Locale.ROOT);
      List<MemoryEntry> matches =
          memories.getOrDefault(appName + "/" + userId, List.of()).stream()
              .filter(entry -> textOf(entry.getContent()).toLowerCase(Locale.ROOT).contains(needle))
              .collect(Collectors.toList());
      return CompletableFuture.completedFuture(new SearchMemoryResponse(matches, null));
    }

    private static String bucketKey(SessionKey key) {
      return key.getAppName() + "/" + key.getUserId();
    }
  }

  /** A minimal offline model returning a fixed reply, so the run needs no API key. */
  private static final class EchoModel extends BasePublisherModel {
    EchoModel() {
      super("echo-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      return AsyncJavaHelpers.publisherOf(
          List.of(
              LlmResponse.builder()
                  .content(Content.fromText(Role.MODEL, "Hiking is great exercise!"))
                  .build()));
    }
  }

  /** Writes the finished session back to the invocation's (custom) memory service. */
  private static AfterAgentCallback storeSessionToMemory() {
    return FutureCallbacks.afterAgent(
        context ->
            CompletableFuture.supplyAsync(
                () -> {
                  var unused = AsyncJavaHelpers.await(c -> context.addSessionToMemory(c));
                  return Choices.proceed();
                }));
  }

  public static void main(String[] args) {
    InMemoryDemoMemoryService service = new InMemoryDemoMemoryService();
    String appName = "demo-app";
    String userId = "demo-user";

    // Configure the custom memory service on the runner; the run writes back to it.
    BaseAgent agent =
        LlmAgent.builder()
            .name("memory_agent")
            .model(new EchoModel())
            .instruction("Chat with the user.")
            .afterAgentCallbacks(List.of(storeSessionToMemory()))
            .build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder().agent(agent).appName(appName).memoryService(service).build());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(
            userId,
            "demo-session",
            null,
            Content.fromText(Role.USER, "I love hiking in the mountains."));
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          String text = textOf(event.getContent());
          if (!text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
        });

    // Search the memory the run stored, through the same custom service.
    SearchMemoryResponse response =
        AsyncJavaHelpers.await(c -> service.searchMemory(appName, userId, "hiking", c));
    System.out.println("matches for 'hiking': " + response.getMemories().size());
    for (MemoryEntry entry : response.getMemories()) {
      System.out.println("  [" + entry.getAuthor() + "] " + textOf(entry.getContent()));
    }
  }

  private static String textOf(Content content) {
    if (content == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (Part part : content.getParts()) {
      if (part.getText() != null) {
        builder.append(part.getText());
      }
    }
    return builder.toString();
  }

  private FutureMemoryServiceDemoJava() {}
}
