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

import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * A support-triage workflow built with the workflow DSL. A classifier labels the request, a routing
 * map sends it to a billing or tech specialist who looks up the facts with a tool, and a summarizer
 * writes the reply. Any other request goes straight to the summarizer through {@code otherwise}.
 */
public final class SupportTriageWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /**
   * Builds the workflow, with every agent on {@code model}. The billing specialist reads the
   * customer's account ID from the {@code account_id} session state key, so pass it with each
   * request.
   */
  public static Workflow create(Model model) {
    Node billing = billingAgent(model);
    Node tech = techAgent(model);
    Node summarize = summaryAgent(model);

    return Workflow.builder()
        .name("support_triage")
        .edges(
            g -> {
              // An LlmAgent is a Node, so it goes straight into the graph.
              g.fromStart()
                  .chain(classifierAgent(model), requestCategory())
                  .route(
                      r -> {
                        r.on("billing").then(billing);
                        r.on("tech").then(tech);
                        r.otherwise(summarize);
                      });
              g.from(billing).then(summarize);
              g.from(tech).then(summarize);
            })
        .build();
  }

  /**
   * Runs the workflow on one request from a signed-in customer and prints what each node said.
   * Needs {@code GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("support_triage").rootNode(workflow).build());
    Content request =
        Content.fromText(Role.USER, "I was charged twice for my subscription this month.");
    // The support app knows who is signed in, so it passes the account ID as session state.
    Map<String, Object> account = Map.of("account_id", "ACC-1042");
    Publisher<Event> events = runner.runAsync("user", "session", null, request, account);
    for (Event event : Flux.from(events).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + path + "] " + text);
      }
    }
  }

  private static LlmAgent classifierAgent(Model model) {
    return LlmAgent.builder()
        .name("classifier")
        .model(model)
        .instruction("Classify the customer's latest message.")
        .outputSchema(TRIAGE_SCHEMA)
        .build();
  }

  /** Routes on the category the classifier returned, since an LlmAgent node emits no route. */
  private static FunctionNode<Triage, Object> requestCategory() {
    return FunctionNodes.of(
        "request_category",
        Triage.class,
        Object.class,
        (ctx, triage) -> {
          // No edge matches `other`, so `otherwise` takes the request.
          ctx.setRoutes(List.of(new Route.Tag(triage.category())));
          return null;
        });
  }

  private static LlmAgent billingAgent(Model model) {
    return LlmAgent.builder()
        .name("billing")
        .model(model)
        .instruction(
            """
            You are a billing specialist. The customer's account ID is {account_id}.
            Look up their recent charges, then note in two bullets what happened and how to \
            fix it.\
            """)
        .tools(ReflectiveTools.fromMethod(new BillingSystem(), "listRecentCharges"))
        .build();
  }

  private static LlmAgent techAgent(Model model) {
    return LlmAgent.builder()
        .name("tech")
        .model(model)
        .instruction(
            "You are a support engineer. Check the service status, then note in two bullets the"
                + " likely cause and the fix or workaround.")
        .tools(ReflectiveTools.fromMethod(new StatusPage(), "getServiceStatus"))
        .build();
  }

  private static LlmAgent summaryAgent(Model model) {
    return LlmAgent.builder()
        .name("summary")
        .model(model)
        .instruction(
            "Write the reply to the customer in at most four sentences, based on any specialist"
                + " notes above. Do not mention internal tools or IDs other than the"
                + " customer's.")
        .build();
  }

  /** The classifier's structured answer, which matches {@link #TRIAGE_SCHEMA}. */
  record Triage(String category) {}

  /** The schema of {@link Triage}, so routing never depends on parsing free text. */
  private static final Schema TRIAGE_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "category",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("billing", "tech", "other")
                      .description(
                          "billing: charges, refunds or plans. tech: errors, outages or app"
                              + " problems. other: anything else.")
                      .build()))
          .required("category")
          .build();

  /** A mock billing backend that the billing specialist queries through a tool. */
  public static final class BillingSystem {
    @Tool(description = "Lists the charges on an account from the last 60 days, newest first.")
    public List<Map<String, Object>> listRecentCharges(
        @Param(name = "accountId", description = "The customer's account ID, such as ACC-1042")
            String accountId) {
      if (!accountId.equals("ACC-1042")) {
        return List.of();
      }
      return List.of(
          charge("ch_302", "2026-09-01"),
          charge("ch_301", "2026-09-01"),
          charge("ch_214", "2026-08-01"));
    }

    private static Map<String, Object> charge(String id, String date) {
      return Map.of(
          "id",
          id,
          "date",
          date,
          "description",
          "Pro plan, monthly",
          "amountCents",
          1200,
          "currency",
          "USD");
    }
  }

  /** A mock status page that the support engineer checks through a tool. */
  public static final class StatusPage {
    @Tool(description = "Returns the current status of each service.")
    public Map<String, String> getServiceStatus() {
      return Map.of(
          "sync", "Degraded since 09:10 UTC. A fix is rolling out.",
          "login", "Operational",
          "billing", "Operational");
    }
  }

  private SupportTriageWorkflowJava() {}
}
