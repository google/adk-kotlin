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

import com.google.adk.kt.agents.Context;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.sessions.State;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.EdgesDsl;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Start;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;

/**
 * A support-triage workflow built with the workflow DSL. A classifier labels the request, a routing
 * map sends it to a billing or tech specialist who looks up the facts with a tool, and a summarizer
 * writes the reply. Any other request goes straight to the summarizer through {@code otherwise}.
 */
public final class SupportTriageWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";

  /**
   * Builds the workflow, with every agent on {@code model}. The billing specialist reads the
   * customer's account ID from the {@code account_id} session state key, so pass it with each
   * request.
   */
  public static Workflow create(Model model) {
    // An LlmAgent is a Node, so it goes straight into the graph.
    Node classify = classifierAgent(model);
    Node billing = billingAgent(model);
    Node tech = techAgent(model);
    Node summarize = summaryAgent(model);
    Node router = new CategoryRouter();

    return Workflow.builder()
        .name("support_triage")
        .edges(
            EdgesDsl.edges(
                g -> {
                  g.then(Start.INSTANCE, classify);
                  g.then(classify, router);
                  g.thenRoute(
                      router,
                      r -> {
                        r.routesTo("billing", billing);
                        r.routesTo("tech", tech);
                        r.otherwise(summarize);
                      });
                  g.then(billing, summarize);
                  g.then(tech, summarize);
                }))
        .build();
  }

  /**
   * Runs the workflow on one request from a signed-in customer and prints what each node said.
   * Needs {@code GOOGLE_API_KEY}.
   */
  public static void main(String[] args) {
    Workflow workflow = create(new Gemini(MODEL_NAME));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("support_triage").rootNode(workflow).build());
    Content request =
        Content.fromText(Role.USER, "I was charged twice for my subscription this month.");
    // The support app knows who is signed in, so it passes the account ID as session state.
    Map<String, Object> account = Map.of("account_id", "ACC-1042");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, request, account),
        event -> {
          String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + path + "] " + text);
          }
        });
  }

  private static LlmAgent classifierAgent(Model model) {
    return LlmAgent.builder()
        .name("classifier")
        .model(model)
        .instruction("Classify the customer's latest message.")
        .outputSchema(TRIAGE_SCHEMA)
        .outputKey(TRIAGE_KEY)
        .build();
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

  private static final String TRIAGE_KEY = "triage";

  /** The classifier's structured answer, so routing never depends on parsing free text. */
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

  /** Routes on the category the classifier returned, since an LlmAgent node emits no route. */
  private static final class CategoryRouter extends BasePublisherNode {
    CategoryRouter() {
      super("category_router");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Object category =
          context.getState().get(TRIAGE_KEY) instanceof Map<?, ?> triage
              ? triage.get("category")
              : null;
      // Consumes the answer, so a later turn never routes on this turn's category.
      context.updateState(TRIAGE_KEY, State.getREMOVED());
      // Without a category the router emits no route, so otherwise takes the request.
      if (category instanceof String name) {
        context.setRoutes(List.of(new Route.Tag(name)));
      }
      return AsyncJavaHelpers.publisherOf(List.of());
    }
  }

  private SupportTriageWorkflowJava() {}
}
