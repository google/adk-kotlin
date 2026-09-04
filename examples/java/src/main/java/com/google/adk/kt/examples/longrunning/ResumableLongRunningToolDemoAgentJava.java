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

package com.google.adk.kt.examples.longrunning;

import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.CHANGE_DESTINATION_TOOL;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.DEMO_SESSION_ID;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.DEMO_USER_ID;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.REQUESTED_DESTINATION;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.deviceResult;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.pausedLongRunningCall;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.printEvents;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.ChangeDestinationTool;
import com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.ScriptedNavModel;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable end-to-end demo of a long-running, client-side tool in a resumable app ({@link
 * ResumabilityConfig} with {@code isResumable = true}).
 *
 * <p>Same scenario as {@link LongRunningToolDemoAgentJava}, but resumability makes the invocation
 * pause immediately: the model is not re-invoked on the placeholder (one model call in turn 1
 * versus two), and the {@code endOfAgent} marker is suppressed so the invocation stays live. The
 * device's real result resumes the paused invocation by its invocation id.
 */
public final class ResumableLongRunningToolDemoAgentJava {

  private static final String APP_NAME = "nav_app";

  public static void main(String[] args) {
    ScriptedNavModel model = new ScriptedNavModel();
    BaseAgent agent =
        LlmAgent.builder()
            .name("nav_agent")
            .model(model)
            .instruction(
                "Help the driver navigate. Use " + CHANGE_DESTINATION_TOOL + " to reroute them.")
            .tools(new ChangeDestinationTool(false))
            .build();
    App app =
        App.builder()
            .appName(APP_NAME)
            .rootAgent(agent)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    InMemoryRunner inMemoryRunner = InMemoryRunner.builder().app(app).build();
    PublisherRunner runner = PublisherRunner.of(inMemoryRunner);

    System.out.println("=== Resumable long-running tool demo ===");
    System.out.println("User > Change my destination to " + REQUESTED_DESTINATION + ".");

    // Collected, not just streamed: the demo inspects these events afterward (paused call + id).
    List<Event> turn1 = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(
            DEMO_USER_ID,
            DEMO_SESSION_ID,
            null,
            Content.fromText(Role.USER, "Change my destination to " + REQUESTED_DESTINATION + ".")),
        turn1::add);
    printEvents("turn 1 (agent pauses; invocation stays live)", turn1);
    System.out.println(
        "   model invocations during turn 1: "
            + model.invocations()
            + " (resumable pauses immediately; the model is not re-invoked on the placeholder)");

    FunctionCall pausedCall = pausedLongRunningCall(turn1);
    String invocationId = turn1.isEmpty() ? null : turn1.get(0).getInvocationId();
    if (pausedCall == null || invocationId == null) {
      System.out.println("No paused long-running call; nothing to resume.");
      return;
    }
    System.out.println(
        "   paused on "
            + pausedCall.getName()
            + " (callId="
            + pausedCall.getId()
            + ", invocationId="
            + invocationId
            + ")");

    System.out.println(
        "[app] destination applied on the device; resuming invocation " + invocationId + ".");
    List<Event> turn2 = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(DEMO_USER_ID, DEMO_SESSION_ID, invocationId, deviceResult(pausedCall)),
        turn2::add);
    printEvents("turn 2 (resumed by invocationId with the device result)", turn2);

    Session session =
        AsyncJavaHelpers.await(
            c ->
                inMemoryRunner
                    .getSessionService()
                    .getSession(new SessionKey(APP_NAME, DEMO_USER_ID, DEMO_SESSION_ID), null, c));
    System.out.println(
        "Stored session now has "
            + (session == null ? 0 : session.getEvents().size())
            + " events.");
    runner.close();
    System.exit(0);
  }

  private ResumableLongRunningToolDemoAgentJava() {}
}
