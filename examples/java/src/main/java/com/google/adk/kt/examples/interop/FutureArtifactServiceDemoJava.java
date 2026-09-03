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
import com.google.adk.kt.interop.BaseFutureArtifactService;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.Choices;
import com.google.adk.kt.interop.FutureCallbacks;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing an {@link com.google.adk.kt.artifacts.ArtifactService} from Java via
 * {@link BaseFutureArtifactService}. Each engine method is {@code suspend}; the base asks the
 * subclass for a {@link CompletableFuture}. Versions are an append-only list; the version number is
 * the list index.
 *
 * <p>{@code main} shows the typical usage: the custom service is configured on an {@link
 * InMemoryRunner}, and an agent run saves an artifact through the invocation's artifact service
 * (from an after-agent callback). The service logs each save, so persistence is visible without any
 * direct read back into the service.
 */
public final class FutureArtifactServiceDemoJava {

  /** A tiny in-memory artifact store keyed by session, then filename, then version index. */
  private static final class InMemoryDemoArtifactService extends BaseFutureArtifactService {
    private final Map<SessionKey, Map<String, List<Part>>> store = new HashMap<>();

    private List<Part> versions(SessionKey key, String filename) {
      return store.getOrDefault(key, Map.of()).get(filename);
    }

    @Override
    protected CompletableFuture<Integer> saveArtifactAsync(
        SessionKey sessionKey, String filename, Part artifact) {
      List<Part> versions =
          store
              .computeIfAbsent(sessionKey, k -> new HashMap<>())
              .computeIfAbsent(filename, f -> new ArrayList<>());
      versions.add(artifact);
      int version = versions.size() - 1;
      System.out.println(
          "[artifact-service] saved artifact '" + filename + "' as version " + version);
      return CompletableFuture.completedFuture(version);
    }

    @Override
    protected CompletableFuture<Part> loadArtifactAsync(
        SessionKey sessionKey, String filename, Integer version) {
      List<Part> versions = versions(sessionKey, filename);
      if (versions == null || versions.isEmpty()) {
        return CompletableFuture.completedFuture(null);
      }
      int index = version != null ? version : versions.size() - 1;
      return CompletableFuture.completedFuture(versions.get(index));
    }

    @Override
    protected CompletableFuture<List<String>> listArtifactKeysAsync(SessionKey sessionKey) {
      Map<String, List<Part>> files = store.get(sessionKey);
      List<String> keys = files == null ? List.of() : new ArrayList<>(files.keySet());
      return CompletableFuture.completedFuture(keys);
    }

    @Override
    protected CompletableFuture<Void> deleteArtifactAsync(SessionKey sessionKey, String filename) {
      Map<String, List<Part>> files = store.get(sessionKey);
      if (files != null) {
        files.remove(filename);
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    protected CompletableFuture<List<Integer>> listVersionsAsync(
        SessionKey sessionKey, String filename) {
      List<Part> versions = versions(sessionKey, filename);
      List<Integer> indices =
          versions == null
              ? List.of()
              : IntStream.range(0, versions.size()).boxed().collect(Collectors.toList());
      return CompletableFuture.completedFuture(indices);
    }

    @Override
    protected CompletableFuture<Part> saveAndReloadArtifactAsync(
        SessionKey sessionKey, String filename, Part artifact) {
      List<Part> versions =
          store
              .computeIfAbsent(sessionKey, k -> new HashMap<>())
              .computeIfAbsent(filename, f -> new ArrayList<>());
      versions.add(artifact);
      System.out.println(
          "[artifact-service] saved artifact '"
              + filename
              + "' as version "
              + (versions.size() - 1));
      return CompletableFuture.completedFuture(artifact);
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
                  .content(Content.fromText(Role.MODEL, "Report ready."))
                  .build()));
    }
  }

  /**
   * Saves an artifact through the invocation's (custom) artifact service once the agent finishes.
   */
  private static AfterAgentCallback saveArtifactCallback() {
    return FutureCallbacks.afterAgent(
        context ->
            CompletableFuture.supplyAsync(
                () -> {
                  var unused =
                      AsyncJavaHelpers.await(
                          c ->
                              context.saveArtifact(
                                  "report.md",
                                  Part.builder()
                                      .text("# Report\n\nGenerated by the agent.")
                                      .build(),
                                  c));
                  return Choices.proceed();
                }));
  }

  public static void main(String[] args) {
    InMemoryDemoArtifactService service = new InMemoryDemoArtifactService();
    String appName = "demo-app";
    String userId = "demo-user";
    String sessionId = "demo-session";

    // Configure the custom artifact service on the runner; the run saves through it.
    BaseAgent agent =
        LlmAgent.builder()
            .name("report_agent")
            .model(new EchoModel())
            .instruction("Write a report.")
            .afterAgentCallbacks(List.of(saveArtifactCallback()))
            .build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder()
                .agent(agent)
                .appName(appName)
                .artifactService(service)
                .build());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(userId, sessionId, null, Content.fromText(Role.USER, "Write a report."));
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

  private FutureArtifactServiceDemoJava() {}
}
