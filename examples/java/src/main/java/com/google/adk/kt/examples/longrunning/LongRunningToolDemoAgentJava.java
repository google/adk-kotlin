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
import com.google.adk.kt.events.Event;
import com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.ChangeDestinationTool;
import com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.ScriptedNavModel;
import com.google.adk.kt.interop.CompletableFutureSessionService;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.InMemorySessionService;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Role;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Runnable end-to-end demo of a long-running, client-side tool in a non-resumable app (the default
 * {@link InMemoryRunner} configuration).
 *
 * <p>The tool is long-running and returns a placeholder, so the agent dispatches the action and the
 * turn ends; because the app is not resumable, the framework re-invokes the model on the
 * placeholder within the same turn. A later turn injects the device's real result as a {@code
 * FunctionResponse} and the model produces the final answer. Compare with {@link
 * ResumableLongRunningToolDemoAgentJava}, which pauses immediately instead.
 */
public final class LongRunningToolDemoAgentJava {

  private static final String APP_NAME = "InMemoryRunner";

  public static void main(String[] args) {
    ScriptedNavModel model = new ScriptedNavModel();
    BaseAgent agent =
        LlmAgent.builder()
            .name("nav_agent")
            .model(model)
            .instruction(
                "Help the driver navigate. Use " + CHANGE_DESTINATION_TOOL + " to reroute them.")
            .tools(new ChangeDestinationTool())
            .build();
    CompletableFutureSessionService sessionService =
        CompletableFutureSessionService.of(new InMemorySessionService());
    InMemoryRunner inMemoryRunner =
        InMemoryRunner.builder()
            .agent(agent)
            .appName(APP_NAME)
            .sessionService(sessionService.asKotlinSessionService())
            .build();
    PublisherRunner runner = PublisherRunner.of(inMemoryRunner);

    System.out.println("=== Non-resumable long-running tool demo ===");
    System.out.println("User > Change my destination to " + REQUESTED_DESTINATION + ".");

    // Collected, not just streamed: the demo inspects these events afterward (paused call + id).
    List<Event> turn1 =
        Flux.from(
                runner.runAsync(
                    DEMO_USER_ID,
                    DEMO_SESSION_ID,
                    null,
                    Content.fromText(
                        Role.USER, "Change my destination to " + REQUESTED_DESTINATION + ".")))
            .collectList()
            .block();
    printEvents("turn 1 (agent dispatches the client action)", turn1);
    System.out.println(
        "   model invocations during turn 1: "
            + model.invocations()
            + " (non-resumable re-invokes the model on the placeholder response)");

    FunctionCall pausedCall = pausedLongRunningCall(turn1);
    if (pausedCall == null) {
      System.out.println("No long-running call was produced; nothing to resume.");
      return;
    }
    System.out.println(
        "   paused on " + pausedCall.getName() + " (callId=" + pausedCall.getId() + ")");

    System.out.println(
        "[app] destination applied on the device; returning the result to the agent.");
    List<Event> turn2 =
        Flux.from(runner.runAsync(DEMO_USER_ID, DEMO_SESSION_ID, null, deviceResult(pausedCall)))
            .collectList()
            .block();
    printEvents("turn 2 (resumed with the device result)", turn2);

    Session session =
        sessionService.getSession(new SessionKey(APP_NAME, DEMO_USER_ID, DEMO_SESSION_ID)).join();
    System.out.println(
        "Stored session now has "
            + (session == null ? 0 : session.getEvents().size())
            + " events.");
    runner.close();
    sessionService.close();
    System.exit(0);
  }

  private LongRunningToolDemoAgentJava() {}
}
