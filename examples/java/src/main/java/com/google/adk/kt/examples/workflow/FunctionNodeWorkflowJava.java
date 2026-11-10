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

package com.google.adk.kt.examples.workflow;

import static com.google.adk.kt.interop.FunctionNodes.requestInput;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.RequestInput;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;

/**
 * A time-off workflow built from typed {@link FunctionNode} steps. It reads the request, checks
 * team cover for each requested day in parallel, approves one day on its own, and asks a manager
 * with {@link FunctionNodes#requestInput} for anything longer, sending the cover list as JSON. A
 * later turn answers, and the last step receives the manager's decision as a {@link
 * TimeOffDecision}.
 */
public final class FunctionNodeWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("time_off")
        .edges(
            g ->
                g.fromStart()
                    .chain(readRequest(), checkAllDays(), evaluateRequest(), processDecision()))
        .build();
  }

  /** Runs a three-day request, answers the manager's question, then runs a one-day request. */
  public static void main(String[] args) {
    App app =
        App.builder()
            .appName("time_off")
            .rootNode(create())
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    PublisherRunner runner = PublisherRunner.inMemory(app);
    run(runner, "long", Content.fromText(Role.USER, "3 days off for a family trip"));
    // The manager's answer decodes into the TimeOffDecision that evaluate_request outputs.
    run(runner, "long", answer("manager_approval", Map.of("approved", true, "approved_days", 2)));
    run(runner, "short", Content.fromText(Role.USER, "1 day off for a dentist visit"));
  }

  /** Reads the number of requested days and outputs the list of day numbers. */
  private static FunctionNode<String, List<Integer>> readRequest() {
    return FunctionNodes.of(
        "read_request",
        // START hands the first node the user's message, which a String input receives as text.
        String.class,
        new TypeReference<List<Integer>>() {},
        (ctx, message) -> {
          int days = Integer.parseInt(message.trim().split("\\s+", 2)[0]);
          return IntStream.rangeClosed(1, days).boxed().toList();
        });
  }

  /** Runs check_cover on each day, at most two at a time, and outputs the covers in day order. */
  private static FunctionNode<List<Integer>, List<DayCover>> checkAllDays() {
    FunctionNode<Integer, DayCover> checkCover =
        FunctionNodes.of(
            "check_cover",
            Integer.class,
            DayCover.class,
            (ctx, day) -> new DayCover(day, day % 3 != 0));
    return FunctionNode.parallelOf(checkCover, 2);
  }

  /** Approves one day on its own, and asks a manager about anything longer. */
  private static FunctionNode<List<DayCover>, TimeOffDecision> evaluateRequest() {
    return FunctionNodes.of(
        "evaluate_request",
        new TypeReference<List<DayCover>>() {},
        TimeOffDecision.class,
        (ctx, cover) ->
            cover.size() <= 1
                ? new TimeOffDecision(true, cover.size())
                : requestInput(
                    "manager_approval",
                    "Approve "
                        + cover.size()
                        + " days off? "
                        + cover.stream().filter(day -> !day.covered()).count()
                        + " day(s) lack cover.",
                    cover));
  }

  /** Reports the decision, which arrives as a {@link TimeOffDecision}. */
  private static FunctionNode<TimeOffDecision, String> processDecision() {
    return FunctionNodes.of(
        "process_decision",
        TimeOffDecision.class,
        String.class,
        (ctx, decision) ->
            decision.approved()
                ? "Time off approved: " + decision.approvedDays() + " day(s)."
                : "Time off denied.");
  }

  /** Whether a teammate covers one requested day. */
  record DayCover(int day, boolean covered) {}

  /** The decision on a request; the manager's answer decodes into it. */
  record TimeOffDecision(boolean approved, @JsonProperty("approved_days") Integer approvedDays) {}

  /** Sends {@code message} in a session and prints each step's output and any question. */
  private static void run(PublisherRunner runner, String sessionId, Content message) {
    for (Event event : Flux.from(runner.runAsync("user", sessionId, null, message)).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      if (event.getOutput() != null) {
        System.out.println("[" + path + "] " + event.getOutput());
      }
      event
          .functionCalls()
          .forEach(
              call ->
                  System.out.println(
                      "["
                          + path
                          + "] asks: "
                          + call.getArgs().get("message")
                          + " "
                          + call.getArgs().get("payload")));
    }
  }

  /** The manager's answer to the request {@code interruptId}. */
  private static Content answer(String interruptId, Map<String, Object> decision) {
    FunctionResponse response =
        FunctionResponse.builder()
            .name(RequestInput.FUNCTION_CALL_NAME)
            .id(interruptId)
            .response(Map.of("result", decision))
            .build();
    return new Content(Role.USER, List.of(Part.builder().functionResponse(response).build()));
  }

  private FunctionNodeWorkflowJava() {}
}
