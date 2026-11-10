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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.RequestInput;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code request_input_advanced} workflow sample: an agent extracts a
 * time-off request from the user's message, a node approves one day on its own and otherwise asks a
 * manager, sending the request as the payload and asking for an answer that matches a schema, and
 * the last node reports the decision.
 */
public final class RequestInputAdvancedWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("request_input_advanced")
        .edges(
            g ->
                g.fromStart()
                    .chain(processRequestAgent(model), evaluateRequest(), processDecision()))
        .build();
  }

  /**
   * Runs the workflow on a one-day request, a five-day request that a manager approves for four
   * days, and a three-day request that a manager declines, and prints each decision. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("request_input_advanced").rootNode(workflow).build());
    // A request of one day is approved without asking anyone.
    send(
        runner,
        "sick_day",
        Content.fromText(Role.USER, "I'm feeling under the weather and need to take today off."));
    // A longer request waits for the manager, whose answer must match the response schema.
    send(
        runner,
        "vacation",
        Content.fromText(
            Role.USER, "Taking my family to Disney World, I'll be out for 5 days next week."));
    send(
        runner,
        "vacation",
        managerAnswer("manager_approval", Map.of("approved", true, "approved_days", 4)));
    // The manager can also decline, and the employee is told the request was denied.
    send(
        runner,
        "conference",
        Content.fromText(Role.USER, "I need 3 days off next month for a conference."));
    send(
        runner,
        "conference",
        managerAnswer("manager_approval", Map.of("approved", false, "approved_days", 0)));
  }

  /**
   * Sends {@code message} in session {@code sessionId} and prints the messages and questions it
   * leads to.
   */
  private static void send(PublisherRunner runner, String sessionId, Content message) {
    for (Event event : Flux.from(runner.runAsync("user", sessionId, null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
      for (FunctionCall call : event.functionCalls()) {
        if (call.getName().equals(RequestInput.FUNCTION_CALL_NAME)) {
          System.out.println(
              "["
                  + event.getAuthor()
                  + "] asks: "
                  + call.getArgs().get("message")
                  + " "
                  + call.getArgs().get("payload"));
        }
      }
    }
  }

  /** The manager's {@code decision} on the request {@code interruptId}. */
  private static Content managerAnswer(String interruptId, Map<String, Object> decision) {
    FunctionResponse response =
        FunctionResponse.builder()
            .name(RequestInput.FUNCTION_CALL_NAME)
            .id(interruptId)
            .response(Map.of("result", decision))
            .build();
    return new Content(Role.USER, List.of(Part.builder().functionResponse(response).build()));
  }

  private static LlmAgent processRequestAgent(Model model) {
    return LlmAgent.builder()
        .name("process_request")
        .model(model)
        .instruction(
            "Extract the number of days and the reason from the user's natural language time off"
                + " request.")
        .outputSchema(TIME_OFF_REQUEST_SCHEMA)
        .outputKey("request")
        .build();
  }

  /** Approves a request of at most one day, and asks a manager to decide on anything longer. */
  private static FunctionNode<TimeOffRequest, TimeOffDecision> evaluateRequest() {
    return FunctionNodes.of(
        "evaluate_request",
        TimeOffRequest.class,
        TimeOffDecision.class,
        (ctx, request) ->
            request.days() <= 1
                ? new TimeOffDecision(true, null)
                // The manager's answer, checked against the schema, becomes this node's output.
                : FunctionNodes.requestInput(
                    "manager_approval",
                    "Please review this time off request.",
                    request,
                    TIME_OFF_DECISION_SCHEMA));
  }

  /** Reports the decision, reading the requested day count from the {@code request} state key. */
  private static FunctionNode<TimeOffDecision, Event> processDecision() {
    return FunctionNodes.of(
        "process_decision",
        TimeOffDecision.class,
        Event.class,
        (ctx, decision) -> {
          Map<?, ?> request = (Map<?, ?>) ctx.getState().get("request");
          int requested = ((Number) request.get("days")).intValue();
          int approved = decision.approvedDays() != null ? decision.approvedDays() : requested;
          String message =
              decision.approved()
                  ? "Time Off Approved! " + approved + " out of " + requested + " days granted."
                  : "Time Off Denied.";
          return Event.builder().content(Content.fromText(Role.MODEL, message)).build();
        });
  }

  /** The agent's structured request, with the fields of adk-python's {@code TimeOffRequest}. */
  record TimeOffRequest(int days, String reason) {}

  /** The schema of {@link TimeOffRequest}, which the agent's answer must match. */
  private static final Schema TIME_OFF_REQUEST_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "days",
                  Schema.builder()
                      .type(Type.INTEGER)
                      .description("Number of days requested.")
                      .build(),
                  "reason",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("Reason for the time off.")
                      .build()))
          .required("days", "reason")
          .build();

  /** The decision, with the fields of adk-python's {@code TimeOffDecision} model. */
  record TimeOffDecision(boolean approved, @JsonProperty("approved_days") Integer approvedDays) {}

  /** The schema of {@link TimeOffDecision}, which the manager's answer must match. */
  private static final Schema TIME_OFF_DECISION_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .description("The structured response we expect back from the human manager.")
          .properties(
              Map.of(
                  "approved",
                  Schema.builder()
                      .type(Type.BOOLEAN)
                      .description("Whether the time off is approved.")
                      .build(),
                  "approved_days",
                  Schema.builder()
                      .type(Type.INTEGER)
                      .nullable(true)
                      .description("Number of days approved.")
                      .build()))
          .required("approved")
          .build();

  private RequestInputAdvancedWorkflowJava() {}
}
