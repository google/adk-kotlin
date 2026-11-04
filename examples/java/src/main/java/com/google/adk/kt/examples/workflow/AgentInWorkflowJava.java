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
import com.google.adk.kt.agents.AgentMode;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.ToolConfirmation;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
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
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code agent_in_workflow} workflow sample, a medical lab intake. A task
 * agent chats with the patient until it has their name and phone number, and a function node checks
 * them and sends the patient back to the agent when it finds no records. A single-turn agent then
 * looks up the patient's orders with a tool that waits for the user to approve the call.
 */
public final class AgentInWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(Model model) {
    Node intakeAgent = intakeAgent(model);

    return Workflow.builder()
        .name("task_in_workflow")
        .edges(
            g ->
                g.fromStart()
                    .chain(intakeAgent, checkIdentity())
                    .route(
                        r -> {
                          r.on("retry").then(intakeAgent);
                          r.otherwise(generateInstructionAgent(model));
                        }))
        .build();
  }

  /**
   * Runs the workflow with a patient who first gives an unknown name, then Jane Doe's details, and
   * approves the orders lookup, printing each message and tool call. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("task_in_workflow").rootNode(workflow).build());
    // A retried intake agent starts a new conversation, so Jane Doe gives both details at once.
    Deque<String> replies =
        new ArrayDeque<>(List.of("My name is John Doe", "555-1234", "Jane Doe, 555-1234"));
    Content message = Content.fromText(Role.USER, replies.removeFirst());
    while (message != null) {
      String confirmationId = send(runner, message);
      // Approves a pending confirmation request, or else sends the next reply.
      if (confirmationId != null) {
        message = approval(confirmationId);
      } else {
        String reply = replies.pollFirst();
        message = reply == null ? null : Content.fromText(Role.USER, reply);
      }
    }
  }

  /**
   * Sends {@code message}, prints the messages and tool calls that follow, and returns the id of
   * the confirmation request the turn ends with, or null if it ends without one.
   */
  private static String send(PublisherRunner runner, Content message) {
    String confirmationId = null;
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
      for (FunctionCall call : event.functionCalls()) {
        System.out.println(
            "[" + event.getAuthor() + "] calls " + call.getName() + "(" + call.getArgs() + ")");
        if (call.getName().equals(FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)) {
          confirmationId = call.getId();
        }
      }
    }
    return confirmationId;
  }

  /** The user's approval of the tool call for confirmation request {@code confirmationId}. */
  private static Content approval(String confirmationId) {
    FunctionResponse response =
        FunctionResponse.builder()
            .name(FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
            .id(confirmationId)
            .response(Map.of(ToolConfirmation.CONFIRMED_KEY, true))
            .build();
    return new Content(Role.USER, List.of(Part.builder().functionResponse(response).build()));
  }

  private static LlmAgent intakeAgent(Model model) {
    return LlmAgent.builder()
        .name("intake_agent")
        .model(model)
        // Holds a conversation until its finish_task call matches the output schema.
        .mode(AgentMode.TASK)
        .outputSchema(PATIENT_IDENTITY_SCHEMA)
        .instruction(
            """
            You are a medical lab intake assistant. Your job is to chat with
            the user to get their full name and phone number. Do not make up
            information. Once you have both, finish your task.
            If identity check failed, ask for another name.\
            """)
        .build();
  }

  /** Mocks a records check, and routes back to the intake agent unless the patient is Jane Doe. */
  private static FunctionNode<PatientIdentity, Event> checkIdentity() {
    return FunctionNodes.of(
        "check_identity",
        PatientIdentity.class,
        Event.class,
        (ctx, patient) -> {
          if (patient.name().equalsIgnoreCase("Jane Doe")) {
            // No route matches, so `otherwise` takes the patient on to their orders.
            return message("Hello " + patient.name() + "! Let me look up your orders.");
          }
          ctx.setRoutes(List.of(new Route.Tag("retry")));
          return message(
              "Could not find matching records for " + patient.name() + ". Let's try again.");
        });
  }

  private static LlmAgent generateInstructionAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_instruction")
        .model(model)
        .tools(ReflectiveTools.fromMethod(new LabOrders(), "findOrders"))
        .instruction(
            """
            Use the find_orders tool to get the patient's orders.
            List the orders found, and then generate a concise instruction about how to prepare
            based on those orders.\
            """)
        .build();
  }

  private static Event message(String text) {
    return Event.builder().content(Content.fromText(Role.MODEL, text)).build();
  }

  /** A mock lab system whose orders lookup waits for the user to approve each call. */
  public static final class LabOrders {
    @Tool(
        name = "find_orders",
        description = "Finds orders for the patient.",
        requireConfirmation = true)
    public List<String> findOrders() {
      return List.of("CBC (Complete Blood Count)", "Lipid Panel");
    }
  }

  /** The patient's identity, which the intake agent's task outputs. */
  record PatientIdentity(String name, @JsonProperty("phone_number") String phoneNumber) {}

  /** The schema of the patient's identity, with the fields of adk-python's PatientIdentity. */
  private static final Schema PATIENT_IDENTITY_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "name",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The patient's full name.")
                      .build(),
                  "phone_number",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The patient's phone number.")
                      .build()))
          .required("name", "phone_number")
          .build();

  private AgentInWorkflowJava() {}
}
