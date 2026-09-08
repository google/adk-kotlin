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
 * Runnable demo of a long-running tool that returns a <b>value</b> in a <b>resumable</b> app
 * ({@link ResumabilityConfig} with {@code isResumable = true}).
 *
 * <p>Counterpart of {@link ResumableLongRunningToolDemoAgentJava} (which defers with {@code Unit}):
 * here {@link ChangeDestinationTool} answers the dispatch with a placeholder value, so the call is
 * resolved and the model is re-invoked to summarize it in the same turn (two model calls) instead
 * of pausing. The {@code endOfAgent} marker is still suppressed, so the invocation stays live and
 * the device's real result can be delivered later by a resume.
 */
public final class ResumableValueLongRunningToolDemoAgentJava {

  private static final String APP_NAME = "nav_value_app";

  public static void main(String[] args) {
    ScriptedNavModel model = new ScriptedNavModel();
    BaseAgent agent =
        LlmAgent.builder()
            .name("nav_agent")
            .model(model)
            .instruction(
                "Help the driver navigate. Use " + CHANGE_DESTINATION_TOOL + " to reroute them.")
            .tools(new ChangeDestinationTool(true))
            .build();
    App app =
        App.builder()
            .appName(APP_NAME)
            .rootAgent(agent)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    InMemoryRunner inMemoryRunner = InMemoryRunner.builder().app(app).build();
    PublisherRunner runner = PublisherRunner.of(inMemoryRunner);

    System.out.println("=== Resumable long-running tool demo (value return continues) ===");
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
    printEvents(
        "turn 1 (value answers the call; the model summarizes, invocation stays live)", turn1);
    System.out.println(
        "   model invocations during turn 1: "
            + model.invocations()
            + " (the value answered the call, so the model is re-invoked to summarize in the same"
            + " turn)");

    FunctionCall resumableCall = pausedLongRunningCall(turn1);
    String invocationId = turn1.isEmpty() ? null : turn1.get(0).getInvocationId();
    if (resumableCall == null || invocationId == null) {
      System.out.println("No live long-running call; nothing to resume.");
      runner.close();
      return;
    }
    System.out.println(
        "   summarized but still resumable on "
            + resumableCall.getName()
            + " (callId="
            + resumableCall.getId()
            + ", invocationId="
            + invocationId
            + ")");

    System.out.println(
        "[app] device applied the destination; delivering the real result to invocation "
            + invocationId
            + ".");
    List<Event> turn2 = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(DEMO_USER_ID, DEMO_SESSION_ID, invocationId, deviceResult(resumableCall)),
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

  private ResumableValueLongRunningToolDemoAgentJava() {}
}
