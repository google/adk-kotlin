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

import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.DEMO_SESSION_ID;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.DEMO_USER_ID;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.DESTINATION_ARG;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.REQUESTED_DESTINATION;
import static com.google.adk.kt.examples.longrunning.LongRunningToolDemoSupportJava.printEvents;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import kotlin.Unit;
import org.reactivestreams.Publisher;

/**
 * Runnable demo of a single long-running tool that decides <em>per call</em> whether to answer now
 * (return a value) or defer for external input (return {@code Unit}), in a non-resumable app.
 *
 * <p>{@link NavigateToTool} returns a real value for a cached favorite, so the agent replies in one
 * turn, and returns {@code Unit} for an unknown place, so the turn ends awaiting the device's
 * result and a later {@code runAsync} delivers it as a {@code FunctionResponse}. It is hand-written
 * as a {@link BaseFutureTool} because {@code ReflectiveTools} refuses {@code isLongRunning} tools.
 */
public final class DynamicLongRunningToolDemoAgentJava {

  private static final String APP_NAME = "InMemoryRunner";
  private static final String NAVIGATE_TO_TOOL = "navigate_to";
  private static final String NAVIGATE_DESCRIPTION =
      "Reroute the driver. Cached favorites apply instantly; unknown places need the device to"
          + " geocode and confirm.";

  /**
   * Destinations the device already has cached, so {@link NavigateToTool} answers synchronously.
   */
  private static final Set<String> ON_DEVICE_FAVORITES = Set.of("home", "the office");

  public static void main(String[] args) {
    ScriptedNavPlannerModel model = new ScriptedNavPlannerModel();
    BaseAgent agent =
        LlmAgent.builder()
            .name("nav_planner_agent")
            .model(model)
            .instruction("Help the driver navigate. Use " + NAVIGATE_TO_TOOL + " to reroute them.")
            .tools(new NavigateToTool())
            .build();
    InMemoryRunner inMemoryRunner = InMemoryRunner.builder().agent(agent).appName(APP_NAME).build();
    PublisherRunner runner = PublisherRunner.of(inMemoryRunner);

    System.out.println("=== Dynamic long-running tool demo (non-resumable) ===");
    System.out.println(
        "One long-running tool decides per call whether to answer now or defer for the device.");

    // Scenario A: a cached favorite -- the tool returns a value and the agent answers in one turn.
    System.out.println();
    System.out.println("User > Navigate to " + REQUESTED_DESTINATION + ".");
    List<Event> turnA = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(
            DEMO_USER_ID,
            DEMO_SESSION_ID,
            null,
            Content.fromText(Role.USER, "Navigate to " + REQUESTED_DESTINATION + ".")),
        turnA::add);
    printEvents("scenario A (cached favorite -> value returned, no pause)", turnA);
    FunctionCall unresolvedA = unresolvedLongRunningCall(turnA);
    System.out.println(
        "   model invocations: "
            + model.invocations()
            + " (tool call + summary); unresolved long-running call: "
            + (unresolvedA == null ? "none" : unresolvedA.getName()));

    // Scenario B: an unknown place -- the tool returns Unit, so the turn defers for the device.
    String unknownDestination = "742 Maple Street";
    System.out.println();
    System.out.println("User > Navigate to " + unknownDestination + ".");
    List<Event> turnB = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(
            DEMO_USER_ID,
            DEMO_SESSION_ID,
            null,
            Content.fromText(Role.USER, "Navigate to " + unknownDestination + ".")),
        turnB::add);
    printEvents("scenario B (unknown place -> Unit returned, turn defers)", turnB);

    FunctionCall pending = unresolvedLongRunningCall(turnB);
    if (pending == null) {
      System.out.println("No deferred call was produced; nothing to resume.");
      runner.close();
      return;
    }
    System.out.println(
        "   deferred on "
            + pending.getName()
            + " (callId="
            + pending.getId()
            + "); waiting for the device.");

    System.out.println(
        "[app] device geocoded the address and confirmed; returning the result to the agent.");
    List<Event> turnC = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync(DEMO_USER_ID, DEMO_SESSION_ID, null, deviceGeocodeResult(pending)),
        turnC::add);
    printEvents("scenario B resume (device result delivered)", turnC);

    Session session =
        AsyncJavaHelpers.await(
            c ->
                inMemoryRunner
                    .getSessionService()
                    .getSession(new SessionKey(APP_NAME, DEMO_USER_ID, DEMO_SESSION_ID), null, c));
    System.out.println();
    System.out.println(
        "Stored session now has "
            + (session == null ? 0 : session.getEvents().size())
            + " events.");
    runner.close();
    System.exit(0);
  }

  /**
   * A long-running tool that answers cached favorites synchronously and defers everything else.
   *
   * <p>Returning a non-{@code Unit} value emits a function response the model summarizes in the
   * same turn; returning {@code Unit} suppresses that response so the turn ends on the long-running
   * call and the device's real result is injected later to resume it.
   */
  private static final class NavigateToTool extends BaseFutureTool {
    NavigateToTool() {
      super(NAVIGATE_TO_TOOL, NAVIGATE_DESCRIPTION, /* isLongRunning= */ true);
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name(NAVIGATE_TO_TOOL)
          .description(NAVIGATE_DESCRIPTION)
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          DESTINATION_ARG,
                          Schema.builder()
                              .type(Type.STRING)
                              .description("Where to reroute the driver.")
                              .build()))
                  .required(DESTINATION_ARG)
                  .build())
          .build();
    }

    @Override
    public CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      Object raw = args.get(DESTINATION_ARG);
      String destination = raw == null ? "" : raw.toString().trim();
      if (ON_DEVICE_FAVORITES.contains(destination.toLowerCase(Locale.ROOT))) {
        // Fast path: the route is already on the device, so respond now with a real value.
        System.out.println(
            "   [backend] '" + destination + "' is a cached favorite; rerouting immediately.");
        return CompletableFuture.completedFuture(
            Map.of("status", "rerouted", "destination", destination, "source", "on_device_cache"));
      }
      // Slow path: dispatch to the device and defer by returning Unit (no response yet).
      System.out.println(
          "   [backend] '"
              + destination
              + "' is unknown; asking the device to geocode and"
              + " confirm.");
      return CompletableFuture.completedFuture(Unit.INSTANCE);
    }
  }

  /**
   * A deterministic scripted model so the demo runs without an API key: it requests {@link
   * NavigateToTool} for a user message and returns a plain-text confirmation once a tool result (or
   * the resumed device result) is present.
   */
  private static final class ScriptedNavPlannerModel extends BasePublisherModel {
    private int invocations = 0;

    ScriptedNavPlannerModel() {
      super("scripted-nav-planner-model");
    }

    /** Number of times the model has been invoked, to show the tool-call-vs-summary contrast. */
    int invocations() {
      return invocations;
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      invocations++;
      List<Content> contents = request.getContents();
      List<Part> lastParts =
          contents.isEmpty() ? List.of() : contents.get(contents.size() - 1).getParts();
      if (lastParts == null) {
        lastParts = List.of();
      }
      FunctionResponse toolResult = null;
      String userText = null;
      for (Part part : lastParts) {
        if (toolResult == null && part.getFunctionResponse() != null) {
          toolResult = part.getFunctionResponse();
        }
        if (userText == null && part.getText() != null) {
          userText = part.getText();
        }
      }
      LlmResponse response;
      if (toolResult != null) {
        // A cached hit or the device's resumed result is in -- confirm to the driver.
        response =
            LlmResponse.builder()
                .content(
                    Content.fromText(Role.MODEL, "You're rerouted: " + toolResult.getResponse()))
                .build();
      } else {
        // A new user request -- call the tool with the destination named in the message.
        String destination = destinationFrom(userText == null ? "" : userText);
        response =
            LlmResponse.builder()
                .content(
                    new Content(
                        Role.MODEL,
                        List.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .name(NAVIGATE_TO_TOOL)
                                        .args(Map.of(DESTINATION_ARG, destination))
                                        .id("nav-call-" + invocations)
                                        .build())
                                .build())))
                .build();
      }
      return AsyncJavaHelpers.publisherOf(List.of(response));
    }
  }

  /** Extracts the destination that follows "to " in the driver's message. */
  private static String destinationFrom(String text) {
    int index = text.indexOf(" to ");
    String after = index < 0 ? text : text.substring(index + " to ".length());
    return after.trim().replaceAll("[.!?]+$", "");
  }

  /**
   * The device's geocoded result for a deferred {@link NavigateToTool} call, injected on resume.
   */
  private static Content deviceGeocodeResult(FunctionCall deferredCall) {
    Object destination = deferredCall.getArgs().get(DESTINATION_ARG);
    return new Content(
        Role.USER,
        List.of(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name(deferredCall.getName())
                        .response(
                            Map.of(
                                "status",
                                "rerouted",
                                "destination",
                                destination == null ? "<unknown>" : destination,
                                "eta_minutes",
                                23,
                                "source",
                                "device_geocode"))
                        .id(deferredCall.getId())
                        .build())
                .build()));
  }

  /**
   * Returns a long-running {@link FunctionCall} in these events that has no matching function
   * response yet -- the tool returned {@code Unit} and the turn deferred -- or null when every
   * long-running call already got a value back.
   */
  private static FunctionCall unresolvedLongRunningCall(List<Event> events) {
    Set<String> answered = new HashSet<>();
    for (Event event : events) {
      for (FunctionResponse response : event.functionResponses()) {
        if (response.getId() != null) {
          answered.add(response.getId());
        }
      }
    }
    for (Event event : events) {
      for (FunctionCall call : event.functionCalls()) {
        if (call.getId() != null
            && event.getLongRunningToolIds().contains(call.getId())
            && !answered.contains(call.getId())) {
          return call;
        }
      }
    }
    return null;
  }

  private DynamicLongRunningToolDemoAgentJava() {}
}
