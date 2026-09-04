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

import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import kotlin.Unit;
import org.reactivestreams.Publisher;

/**
 * Shared building blocks for the long-running tool demos ({@link LongRunningToolDemoAgentJava} and
 * {@link ResumableLongRunningToolDemoAgentJava}).
 *
 * <p>They model a "client-side" action: a tool whose real work runs on the user's device, so the
 * backend agent dispatches the action, pauses, and later resumes when the device returns the
 * result. The two demos share this tool and model and differ only in whether the app is resumable.
 */
final class LongRunningToolDemoSupportJava {

  static final String CHANGE_DESTINATION_TOOL = "change_destination";
  static final String DESTINATION_ARG = "destination";
  static final String REQUESTED_DESTINATION = "the office";
  static final String DEMO_USER_ID = "driver-1";
  static final String DEMO_SESSION_ID = "drive-session-1";

  /**
   * A long-running tool standing in for a client-side action: it dispatches the action and the real
   * result arrives out-of-band as a later {@code FunctionResponse}. When {@code respondImmediately}
   * is true it returns a placeholder that answers the call so the model summarizes it; when false
   * it returns {@code Unit} ("no response yet"), so a resumable invocation pauses on the call
   * alone. Hand-written as a {@link BaseFutureTool} because {@code ReflectiveTools} (the only
   * {@code @Tool} path in this javac module) refuses {@code isLongRunning} tools, and the KSP
   * {@code @Tool} path that builds them needs the Kotlin compiler (see
   * examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java).
   */
  static final class ChangeDestinationTool extends BaseFutureTool {
    private final boolean respondImmediately;

    ChangeDestinationTool() {
      this(true);
    }

    ChangeDestinationTool(boolean respondImmediately) {
      super(
          CHANGE_DESTINATION_TOOL,
          "Change the active navigation destination on the driver's device.",
          /* isLongRunning= */ true);
      this.respondImmediately = respondImmediately;
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name(CHANGE_DESTINATION_TOOL)
          .description("Change the active navigation destination on the driver's device.")
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
      Object destination = args.getOrDefault(DESTINATION_ARG, "<unknown>");
      System.out.println(
          "   [backend] dispatching client action to the app: "
              + CHANGE_DESTINATION_TOOL
              + "(destination="
              + destination
              + ")");
      // Returning Unit suppresses the function-response so a resumable invocation pauses.
      return CompletableFuture.completedFuture(
          respondImmediately ? Map.of("status", "dispatched_to_client") : Unit.INSTANCE);
    }
  }

  /**
   * A deterministic scripted model so the demos run without an API key: the first call requests the
   * {@link ChangeDestinationTool}, and every later call returns a plain-text confirmation.
   */
  static final class ScriptedNavModel extends BasePublisherModel {
    private int invocations = 0;

    ScriptedNavModel() {
      super("scripted-nav-model");
    }

    /** Number of times the model has been invoked, to show the resumable-vs-not call contrast. */
    int invocations() {
      return invocations;
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      LlmResponse response;
      if (invocations++ == 0) {
        response =
            LlmResponse.builder()
                .content(
                    new Content(
                        Role.MODEL,
                        List.of(
                            Part.builder()
                                .functionCall(
                                    new FunctionCall(
                                        CHANGE_DESTINATION_TOOL,
                                        Map.of(DESTINATION_ARG, REQUESTED_DESTINATION),
                                        "client-call-1",
                                        null,
                                        null))
                                .build())))
                .build();
      } else {
        response =
            LlmResponse.builder()
                .content(Content.fromText(Role.MODEL, "Your destination has been updated."))
                .build();
      }
      return AsyncJavaHelpers.publisherOf(List.of(response));
    }
  }

  /** The device's real result for the change-destination action, injected on resume. */
  static Content deviceResult(FunctionCall pausedCall) {
    return new Content(
        Role.USER,
        List.of(
            Part.builder()
                .functionResponse(
                    new FunctionResponse(
                        pausedCall.getName(),
                        Map.of("status", "applied", "eta_minutes", 12),
                        pausedCall.getId()))
                .build()));
  }

  /** Returns the pending long-running function call these events paused on, or null if none. */
  static FunctionCall pausedLongRunningCall(List<Event> events) {
    for (Event event : events) {
      for (FunctionCall call : event.functionCalls()) {
        if (call.getId() != null && event.getLongRunningToolIds().contains(call.getId())) {
          return call;
        }
      }
    }
    return null;
  }

  /** Prints a one-line summary of each event under [label]. */
  static void printEvents(String label, List<Event> events) {
    System.out.println("-- " + label + " (" + events.size() + " event(s)) --");
    for (Event event : events) {
      System.out.println("   " + describeEvent(event));
    }
  }

  private static String describeEvent(Event event) {
    List<FunctionCall> calls = event.functionCalls();
    List<FunctionResponse> responses = event.functionResponses();
    Content content = event.getContent();
    String text =
        content == null
            ? ""
            : content.getParts().stream()
                .map(Part::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
    String detail;
    if (!calls.isEmpty()) {
      detail =
          "call "
              + calls.stream()
                  .map(c -> c.getName() + "(" + c.getArgs() + ")")
                  .collect(Collectors.joining(", "))
              + (event.getLongRunningToolIds().isEmpty() ? "" : " [long-running]");
    } else if (!responses.isEmpty()) {
      detail =
          "response "
              + responses.stream()
                  .map(r -> r.getName() + " -> " + r.getResponse())
                  .collect(Collectors.joining(", "));
    } else if (!text.isEmpty()) {
      detail = "text \"" + text + "\"";
    } else {
      detail = "(no content)";
    }
    String end = event.getActions().getEndOfAgent() ? " {endOfAgent}" : "";
    return event.getAuthor() + ": " + detail + end;
  }

  private LongRunningToolDemoSupportJava() {}
}
