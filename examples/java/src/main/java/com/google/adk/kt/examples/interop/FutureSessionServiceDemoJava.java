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
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureSessionService;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.GetSessionConfig;
import com.google.adk.kt.sessions.ListEventsResponse;
import com.google.adk.kt.sessions.ListSessionsResponse;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing a {@link com.google.adk.kt.sessions.SessionService} from Java via
 * {@link BaseFutureSessionService}. Every engine method is {@code suspend}; the base asks the
 * subclass for a {@link CompletableFuture} per method.
 *
 * <p>{@code main} shows the typical usage: the custom service is configured on an {@link
 * InMemoryRunner}, and running an agent drives it (the runner creates the session and appends the
 * turn's events through it). The run's effect is then read back through the same service.
 */
public final class FutureSessionServiceDemoJava {

  /** A tiny in-memory session store. */
  private static final class InMemoryDemoSessionService extends BaseFutureSessionService {
    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();

    @Override
    protected CompletableFuture<Session> createSessionAsync(
        SessionKey key, Map<String, Object> state) {
      String id = key.getId() != null ? key.getId() : UUID.randomUUID().toString();
      SessionKey resolved = new SessionKey(key.getAppName(), key.getUserId(), id);
      Session session = Session.builder().key(resolved).build();
      sessions.put(resolved, session);
      return CompletableFuture.completedFuture(session);
    }

    @Override
    protected CompletableFuture<Session> getSessionAsync(SessionKey key, GetSessionConfig config) {
      return CompletableFuture.completedFuture(sessions.get(key));
    }

    @Override
    protected CompletableFuture<ListSessionsResponse> listSessionsAsync(
        String appName, String userId) {
      List<Session> matches =
          sessions.values().stream()
              .filter(
                  s ->
                      s.getKey().getAppName().equals(appName)
                          && s.getKey().getUserId().equals(userId))
              .collect(Collectors.toList());
      return CompletableFuture.completedFuture(new ListSessionsResponse(matches));
    }

    @Override
    protected CompletableFuture<Void> deleteSessionAsync(SessionKey key) {
      sessions.remove(key);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    protected CompletableFuture<ListEventsResponse> listEventsAsync(SessionKey key) {
      Session session = sessions.get(key);
      List<Event> events = session == null ? List.of() : session.getEvents();
      return CompletableFuture.completedFuture(new ListEventsResponse(events, null));
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
                  .content(Content.fromText(Role.MODEL, "Hello from the agent."))
                  .build()));
    }
  }

  public static void main(String[] args) {
    InMemoryDemoSessionService service = new InMemoryDemoSessionService();
    String appName = "demo-app";
    String userId = "demo-user";
    String sessionId = "demo-session";

    // Configure the custom session service on the runner; the run drives it end to end.
    BaseAgent agent =
        LlmAgent.builder().name("demo_agent").model(new EchoModel()).instruction("Reply.").build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder().agent(agent).appName(appName).sessionService(service).build());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(userId, sessionId, null, Content.fromText(Role.USER, "Hello!"));
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          String text = firstText(event.getContent());
          if (text != null && !text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
        });

    // Read back through the custom service to show the runner drove it.
    ListSessionsResponse listed =
        AsyncJavaHelpers.await(c -> service.listSessions(appName, userId, c));
    System.out.println("sessions stored by the custom service: " + listed.getSessions().size());
    Session stored =
        AsyncJavaHelpers.await(
            c -> service.getSession(new SessionKey(appName, userId, sessionId), null, c));
    System.out.println(
        "events persisted in session "
            + sessionId
            + ": "
            + (stored == null ? 0 : stored.getEvents().size()));
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

  private FutureSessionServiceDemoJava() {}
}
