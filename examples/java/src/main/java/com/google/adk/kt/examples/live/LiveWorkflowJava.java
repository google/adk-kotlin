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

package com.google.adk.kt.examples.live;

import com.google.adk.kt.agents.AgentMode;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.webserver.AdkServerConfig;
import com.google.adk.kt.webserver.dev.AdkDevServer;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A workflow of live voice agents that runs as one call: the first confirms who is calling, the
 * second checks their date of birth with a tool, and the third tells them about their appointment.
 * Each agent is a task that talks until it finishes; the first two hand a typed result to the next.
 * Only a caller whose date of birth matched reaches the third agent; any other caller is told they
 * could not be verified, and the call ends.
 *
 * <p>{@link #main} serves it over the ADK web API, so a browser can hold the call through the
 * bundled dev UI's microphone button: typed text goes to {@code /run_sse}, which a live-only model
 * rejects.
 */
public final class LiveWorkflowJava {

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("workflow")
        .description(
            "A Workflow of live voice agents: confirm the caller, verify their date of birth, then"
                + " share the call details.")
        .edges(g ->
            g.fromStart()
                .chain(greeterAgent(model), dobVerifierAgent(model), routeOnVerification())
                .route(r -> {
                  r.on("verified").then(goalsAgent(model));
                  r.on("unverified").then(closingAgent(model));
                }))
        .build();
  }

  public static void main(String[] args) {
    int port = AdkServerConfig.DEFAULT_PORT;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--port requires a value.");
          }
          port = Integer.parseInt(args[++i]);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    // Live models are served from a region, not the global endpoint other examples use.
    Model model =
        new Gemini("gemini-3.8-live", VertexCredentials.builder().location("us-central1").build());
    App app = App.builder().appName("workflow").rootNode(create(model)).build();
    System.out.println("Serving " + app.getAppName() + " at http://localhost:" + port + "/dev-ui");
    new AdkDevServer(AdkServerConfig.inMemory(app, port)).start(/* wait= */ true);
  }

  /**
   * A mock patient record system for the date-of-birth check. {@link ReflectiveTools} builds its
   * tool, as this module has no KSP.
   */
  static final class PatientRecords {

    @Tool(
        name = "validate_date_of_birth",
        description = "Validates a confirmed date of birth against the patient's records (mocked).")
    public Map<String, Boolean> validateDateOfBirth(
        ToolContext context,
        @Param(name = "dob", description = "The patient's date of birth in YYYY-MM-DD format.")
            String dob) {
      boolean match = dob.equals("1985-07-12"); // The demo persona's record.
      context.updateState("dob_verified", match);
      return Map.of("match", match);
    }
  }

  private static LlmAgent greeterAgent(Model model) {
    return LlmAgent.builder()
        .name("greeter_agent")
        .model(model)
        .description("Greets on a recorded line and confirms the right person.")
        // Talks until its finish_task call matches the output schema, then hands that output on.
        .mode(AgentMode.TASK)
        .outputSchema(resultSchema("The confirmed name of the person on the line."))
        .instruction(
            """
            You are Sam, a friendly care-team assistant.
            Greet the caller and confirm you are speaking with John Doe before
            sharing anything else. Ask one question per turn. Once the name is
            confirmed, briefly acknowledge it and complete your task, passing the
            confirmed name as 'result'.\
            """)
        .build();
  }

  private static LlmAgent dobVerifierAgent(Model model) {
    return LlmAgent.builder()
        .name("dob_verifier_agent")
        .model(model)
        .description("Captures and validates the date of birth before serving.")
        .mode(AgentMode.TASK)
        .outputSchema(resultSchema("Identity verification result, e.g. \"verified\"."))
        .tools(ReflectiveTools.fromMethod(new PatientRecords(), "validateDateOfBirth"))
        .instruction(
            """
            Verify the caller's identity by date of birth. Ask for their date of
            birth, read it back to confirm, then validate it with
            `validate_date_of_birth` using YYYY-MM-DD format. Once it matches, let
            the caller know their identity is verified and complete your task with
            "verified". If it still does not match after two tries, complete your
            task with "unverified". Ask one question per turn.\
            """)
        .build();
  }

  /**
   * Sends the caller on to {@link #goalsAgent} only when the verifier reported "verified" and the
   * date-of-birth tool recorded a match, and to {@link #closingAgent} otherwise, handing the next
   * agent that outcome as its opening turn.
   */
  private static FunctionNode<TaskResult, TaskResult> routeOnVerification() {
    return FunctionNodes.of(
        "route_on_verification",
        TaskResult.class,
        TaskResult.class,
        (ctx, verification) -> {
          boolean verified =
              "verified".equals(verification.result())
                  && Boolean.TRUE.equals(ctx.getState().get("dob_verified"));
          String result = verified ? "verified" : "unverified";
          ctx.setRoutes(List.of(new Route.Tag(result)));
          return new TaskResult(result);
        });
  }

  private static LlmAgent goalsAgent(Model model) {
    return LlmAgent.builder()
        .name("goals_agent")
        .model(model)
        .description("Delivers the call goals once identity is verified.")
        .mode(AgentMode.TASK)
        .instruction(
            """
            Identity is already verified. As soon as it is your turn, proactively
            tell the caller about their upcoming appointment on Tuesday, June 16th at
            3 PM with Dr. Example, and ask if they have any questions for the visit.
            Do not wait to be asked. Answer any questions briefly, ask if there is
            anything else, then wrap up warmly, end with "Goodbye.", and complete
            your task.\
            """)
        .build();
  }

  private static LlmAgent closingAgent(Model model) {
    return LlmAgent.builder()
        .name("closing_agent")
        .model(model)
        .description("Ends the call when identity cannot be verified.")
        .mode(AgentMode.TASK)
        .instruction(
            """
            The caller's identity could not be verified. As soon as it is your turn,
            tell the caller you could not verify their identity, so you cannot share
            any details on this call, and suggest they call the clinic directly. End
            with "Goodbye." and complete your task.\
            """)
        .build();
  }

  /** A task's output, as {@link #resultSchema} describes it. */
  record TaskResult(@Nullable String result) {}

  /** A task's output: one optional string, {@code result}, described by {@code description}. */
  private static Schema resultSchema(String description) {
    return Schema.builder()
        .type(Type.OBJECT)
        .properties(
            Map.of("result", Schema.builder().type(Type.STRING).description(description).build()))
        .build();
  }

  private LiveWorkflowJava() {}
}
