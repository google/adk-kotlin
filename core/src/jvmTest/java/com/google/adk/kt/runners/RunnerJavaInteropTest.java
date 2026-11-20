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

package com.google.adk.kt.runners;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.kt.agents.InvocationContext;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.artifacts.InMemoryArtifactService;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherAgent;
import com.google.adk.kt.memory.InMemoryMemoryService;
import com.google.adk.kt.plugins.PluginManager;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.sessions.SessionNotFoundException;
import com.google.adk.kt.types.Content;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.reactivestreams.Publisher;

/**
 * Calls the runner constructors from Java: the overloads from before {@code autoCreateSession}
 * still compile and still create missing sessions, and the new parameter can be passed.
 */
@RunWith(JUnit4.class)
public final class RunnerJavaInteropTest {

  private static final String APP_NAME = "app";
  private static final String USER_ID = "u";
  private static final String SESSION_ID = "missing";

  /** An agent that emits no events. */
  private static final class SilentAgent extends BasePublisherAgent {
    SilentAgent() {
      super("agent");
    }

    @Override
    protected Publisher<Event> runAsyncImplJava(InvocationContext context) {
      return AsyncJavaHelpers.publisherOf(ImmutableList.of());
    }
  }

  @Test
  public void inMemoryRunner_agentConstructorWithoutAutoCreateSession_createsMissingSession() {
    InMemoryRunner runner =
        new InMemoryRunner(
            new SilentAgent(),
            APP_NAME,
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            new InMemoryMemoryService(),
            ImmutableList.of());

    run(runner);

    assertThat(session(runner)).isNotNull();
  }

  @Test
  public void inMemoryRunner_appConstructorWithoutAutoCreateSession_createsMissingSession() {
    InMemoryRunner runner =
        new InMemoryRunner(
            app(),
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            new InMemoryMemoryService(),
            /* skipClosingPlugins= */ false);

    run(runner);

    assertThat(session(runner)).isNotNull();
  }

  @Test
  public void inMemoryRunner_agentConstructorWithAutoCreateSessionOff_throwsForMissingSession() {
    InMemoryRunner runner =
        new InMemoryRunner(
            new SilentAgent(),
            APP_NAME,
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            new InMemoryMemoryService(),
            ImmutableList.of(),
            /* autoCreateSession= */ false);

    assertThrows(SessionNotFoundException.class, () -> run(runner));

    assertThat(session(runner)).isNull();
  }

  @Test
  public void abstractRunner_agentConstructorWithoutAutoCreateSession_createsMissingSession() {
    AbstractRunner runner =
        new AbstractRunner(
            APP_NAME,
            new SilentAgent(),
            new InMemorySessionService(),
            /* artifactService= */ null,
            /* memoryService= */ null,
            new PluginManager(ImmutableList.of(), /* skipClosingPlugins= */ false),
            new ResumabilityConfig()) {};

    run(runner);

    assertThat(session(runner)).isNotNull();
  }

  @Test
  public void abstractRunner_appConstructorWithoutAutoCreateSession_createsMissingSession() {
    AbstractRunner runner =
        new AbstractRunner(
            app(),
            new InMemorySessionService(),
            /* artifactService= */ null,
            /* memoryService= */ null,
            /* skipClosingPlugins= */ false) {};

    run(runner);

    assertThat(session(runner)).isNotNull();
  }

  private static App app() {
    return App.builder().appName(APP_NAME).rootAgent(new SilentAgent()).build();
  }

  private static void run(Runner runner) {
    var unused =
        AsyncJavaHelpers.collect(
            runner.runAsync(
                USER_ID,
                SESSION_ID,
                /* invocationId= */ null,
                Content.fromText("user", "hi"),
                /* stateDelta= */ null,
                /* runConfig= */ null));
  }

  private static Session session(Runner runner) {
    SessionKey key = new SessionKey(runner.getAppName(), USER_ID, SESSION_ID);
    return AsyncJavaHelpers.await(
        c -> runner.getSessionService().getSession(key, /* config= */ null, c));
  }
}
