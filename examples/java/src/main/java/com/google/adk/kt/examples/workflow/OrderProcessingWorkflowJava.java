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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.kt.agents.Context;
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
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.JoinNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.ToolNodes;
import com.google.adk.kt.workflow.Workflow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * An order-processing workflow with fraud review, built with the workflow DSL. Nested fraud and
 * inventory workflows and a credit-check tool run concurrently, and a {@link JoinNode} waits for
 * all three before a risk agent routes the order to be charged, reviewed by finance and compliance
 * agents, or frozen as fraud. Every outcome ends with a receipt.
 */
public final class OrderProcessingWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(Model model) {
    OrderNodes nodes = new OrderNodes(model);
    // Each nested workflow is one fan-out branch, so risk_join waits for its last step.
    Workflow fraudCheck =
        Workflow.builder()
            .name("fraud_check")
            .edges(g -> g.fromStart().chain(nodes.geoCheck, nodes.fraudScore))
            .build();
    Workflow inventory =
        Workflow.builder()
            .name("inventory")
            .edges(g -> g.fromStart().chain(nodes.reserveInventory, nodes.warehouseHold))
            .build();
    return Workflow.builder()
        .name("order_processing")
        .edges(
            g -> {
              // Stage 1: the three branches run concurrently; risk_join waits for all of them.
              g.fromStart()
                  .then(nodes.parseOrder)
                  .then(fraudCheck, inventory, nodes.creditCheck)
                  .joinTo(nodes.riskJoin)
                  .chain(nodes.assessRisk, nodes.riskDecision)
                  // Stage 2: route on risk decision; a route mapped to two nodes fans out.
                  .route(
                      r -> {
                        r.on("auto_approve").then(nodes.chargeCard);
                        r.on(r.anyOf("manual_review", "high_value"))
                            .then(nodes.financeReview, nodes.complianceReview);
                        r.on("fraud").then(nodes.freezeAccount, nodes.alertSecOps);
                        r.otherwise(nodes.cancelOrder);
                      });
              g.from(nodes.financeReview, nodes.complianceReview)
                  .joinTo(nodes.reviewJoin)
                  .then(nodes.reviewGate)
                  .route(
                      r -> {
                        r.on("approved").then(nodes.chargeCard);
                        // Loops back for another assessment.
                        r.on("recheck").then(nodes.assessRisk);
                        r.otherwise(nodes.cancelOrder);
                      });
              g.from(nodes.freezeAccount, nodes.alertSecOps)
                  .joinTo(nodes.auditJoin)
                  .then(nodes.cancelOrder);
              // Stage 3: only one outcome runs per order, so the receipt needs no JoinNode.
              g.from(nodes.chargeCard).chain(nodes.shipOrder, nodes.sendReceipt);
              g.from(nodes.cancelOrder).then(nodes.sendReceipt);
            })
        .build();
  }

  /**
   * Runs the workflow on three orders and prints what each step and agent did. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("order_processing").rootNode(workflow).build());
    for (String order : SAMPLE_ORDERS) {
      String orderId = order.substring("order_id=".length(), order.indexOf(' '));
      System.out.println("\n=== Order " + orderId);
      Content message = Content.fromText(Role.USER, order);
      for (Event event : Flux.from(runner.runAsync("user", orderId, null, message)).toIterable()) {
        String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
        String text = event.contentText(" ");
        if (!text.isBlank()) {
          System.out.println("[" + path + "] " + text);
        } else if (event.getOutput() != null) {
          System.out.println("[" + path + "] " + event.getOutput());
        }
      }
    }
  }

  /**
   * Orders as the storefront sends them: a small one, a large cross-border one, and a likely fraud.
   */
  private static final List<String> SAMPLE_ORDERS =
      List.of(
          "order_id=O-1001 customer_id=C-118 total_usd=120 ship_country=US bill_country=US"
              + " account_age_days=900",
          "order_id=O-1002 customer_id=C-310 total_usd=2400 ship_country=US bill_country=CA"
              + " account_age_days=400",
          "order_id=O-1003 customer_id=C-977 total_usd=4800 ship_country=NG bill_country=US"
              + " account_age_days=2");

  /**
   * The nodes of the order workflow. Each step that outputs a String stands in for a backend call
   * and says what it did.
   */
  private static final class OrderNodes {
    /**
     * Parses the order's {@code key=value} fields into state, where the steps and agents read them,
     * and outputs the credit check's arguments.
     */
    final Node parseOrder =
        FunctionNodes.of(
            "parse_order",
            // START hands the first node the user's message as Content.
            Content.class,
            new TypeReference<Map<String, Object>>() {},
            (ctx, message) -> {
              Map<String, String> fields = new HashMap<>();
              for (String field : message.text().trim().split("\\s+", -1)) {
                String[] keyAndValue = field.split("=", 2);
                fields.put(keyAndValue[0], keyAndValue.length > 1 ? keyAndValue[1] : field);
              }
              for (Map.Entry<String, String> field : fields.entrySet()) {
                String value = field.getValue();
                ctx.updateState(
                    field.getKey(), value.matches("-?\\d+") ? Integer.valueOf(value) : value);
              }
              // Every fan-out branch receives this output; only the tool reads it as arguments.
              return Map.of(
                  "customerId",
                  fields.get("customer_id"),
                  "orderTotalUsd",
                  Integer.parseInt(fields.get("total_usd")));
            });

    final Node geoCheck =
        orderStep(
            "geo_check",
            context -> {
              boolean mismatch =
                  !Objects.equals(
                      context.getState().get("ship_country"),
                      context.getState().get("bill_country"));
              context.updateState("geo_mismatch", mismatch);
              return mismatch
                  ? "The shipping and billing countries differ."
                  : "The countries match.";
            });
    final Node fraudScore =
        orderStep(
            "fraud_score",
            context -> {
              Map<String, Object> state = context.getState();
              int score = 10;
              if (Objects.equals(state.get("geo_mismatch"), true)) {
                score += 25;
              }
              if (((Number) state.get("account_age_days")).intValue() < 30) {
                score += 35;
              }
              if (((Number) state.get("total_usd")).intValue() > 2000) {
                score += 15;
              }
              context.updateState("fraud_score", score);
              return "Fraud score: " + score + " out of 100.";
            });
    final Node reserveInventory =
        orderStep(
            "reserve_inventory",
            context -> {
              Object orderId = context.getState().get("order_id");
              String reservation = "RSV-" + orderId;
              context.updateState("reservation", reservation);
              return "Reserved stock for order " + orderId + " as " + reservation + ".";
            });
    final Node warehouseHold =
        orderStep(
            "warehouse_hold",
            context -> {
              String hold =
                  context.getState().get("reservation") + " is held at the NJ-2 warehouse.";
              context.updateState("inventory", hold);
              return hold;
            });
    final Node creditCheck =
        ToolNodes.asNode(ReflectiveTools.fromMethod(new CreditBureau(), "creditCheck"));
    final JoinNode riskJoin = new JoinNode("risk_join");
    final Node assessRisk;

    /** Routes on the risk agent's decision, since an LlmAgent node emits no route. */
    final Node riskDecision =
        FunctionNodes.of(
            "risk_decision",
            Object.class,
            Object.class,
            (ctx, input) -> {
              Object decision =
                  ctx.getState().get(RISK_KEY) instanceof Map<?, ?> risk
                      ? risk.get("decision")
                      : null;
              // Without a decision, this node emits no route, so `otherwise` cancels the order.
              if (decision instanceof String route) {
                ctx.setRoutes(List.of(new Route.Tag(route)));
              }
              return null;
            });

    final Node financeReview;
    final Node complianceReview;
    final JoinNode reviewJoin = new JoinNode("review_join");
    final Node reviewGate = orderReviewGate(2);
    final Node chargeCard =
        orderStep(
            "charge_card",
            context ->
                "Charged "
                    + context.getState().get("total_usd")
                    + " USD for order "
                    + context.getState().get("order_id")
                    + ".");
    final Node shipOrder =
        orderStep(
            "ship_order",
            context -> {
              context.updateState("outcome", "charged and shipped");
              return "Shipped order "
                  + context.getState().get("order_id")
                  + " from the NJ-2 warehouse.";
            });
    final Node freezeAccount =
        orderStep(
            "freeze_account",
            context ->
                "Froze account "
                    + context.getState().get("customer_id")
                    + " pending an investigation.");
    final Node alertSecOps =
        orderStep(
            "alert_sec_ops",
            context ->
                "Sent SecOps the fraud signals for order "
                    + context.getState().get("order_id")
                    + ".");
    final JoinNode auditJoin = new JoinNode("audit_join");
    final Node cancelOrder =
        orderStep(
            "cancel_order",
            context -> {
              context.updateState("outcome", "canceled");
              return "Canceled order "
                  + context.getState().get("order_id")
                  + " and released "
                  + context.getState().get("reservation")
                  + ".";
            });
    final Node sendReceipt =
        orderStep(
            "send_receipt",
            context ->
                "Emailed customer "
                    + context.getState().get("customer_id")
                    + ": order "
                    + context.getState().get("order_id")
                    + " was "
                    + context.getState().get("outcome")
                    + ".");

    OrderNodes(Model model) {
      assessRisk = riskAgent(model);
      financeReview = financeReviewAgent(model);
      complianceReview = complianceReviewAgent(model);
    }
  }

  private static LlmAgent riskAgent(Model model) {
    return LlmAgent.builder()
        .name("assess_risk")
        .model(model)
        .instruction(
            """
            Assess the risk of this order before the card is charged.
            Order {order_id}: {total_usd} USD from customer {customer_id}, whose account is
            {account_age_days} days old. It ships to {ship_country} and is billed in {bill_country}.
            Fraud score: {fraud_score} out of 100. Inventory: {inventory}. Credit: {credit}.
            If reviewers asked for a recheck, weigh their notes:
            {finance_review?} {compliance_review?}\
            """)
        .outputSchema(RISK_SCHEMA)
        .outputKey(RISK_KEY)
        .build();
  }

  private static LlmAgent financeReviewAgent(Model model) {
    return LlmAgent.builder()
        .name("finance_review")
        .model(model)
        .instruction(
            """
            You review orders for the finance team. Order {order_id} totals {total_usd} USD.
            Credit: {credit}. Risk assessment: {risk}.
            Approve if the customer's credit covers the order, reject if it does not, and ask for a
            recheck if the risk assessment overlooked something.\
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey(FINANCE_KEY)
        .build();
  }

  private static LlmAgent complianceReviewAgent(Model model) {
    return LlmAgent.builder()
        .name("compliance_review")
        .model(model)
        .instruction(
            """
            You review orders for the compliance team. Order {order_id} ships to {ship_country}, is
            billed in {bill_country}, and comes from an account {account_age_days} days old.
            Fraud score: {fraud_score} out of 100. Risk assessment: {risk}.
            Approve or reject the order, or ask for a recheck if the risk assessment overlooked
            something.\
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey(COMPLIANCE_KEY)
        .build();
  }

  private static final String RISK_KEY = "risk";
  private static final String FINANCE_KEY = "finance_review";
  private static final String COMPLIANCE_KEY = "compliance_review";

  /** The risk agent's structured decision, which {@link OrderNodes#riskDecision} routes on. */
  private static final Schema RISK_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "decision",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("auto_approve", "manual_review", "high_value", "fraud")
                      .description(
                          "auto_approve: low risk. high_value: over 2000 USD but otherwise low"
                              + " risk. manual_review: the signals conflict. fraud: the signals"
                              + " point to fraud.")
                      .build(),
                  "reason",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("One sentence on the decision.")
                      .build()))
          .required("decision", "reason")
          .build();

  /** A reviewer's structured verdict, which {@link OrderNodes#reviewGate} routes on. */
  private static final Schema REVIEW_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "verdict",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("approve", "reject", "recheck")
                      .build(),
                  "note",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("One sentence on the verdict.")
                      .build()))
          .required("verdict", "note")
          .build();

  /** A mock credit bureau whose check runs as a step of the workflow. */
  public static final class CreditBureau {
    private static final Map<String, Integer> CREDIT_LIMITS_USD =
        Map.of("C-118", 1500, "C-310", 5000, "C-977", 500);

    @Tool(description = "Checks whether the customer's credit limit covers the order total.")
    public Map<String, Object> creditCheck(
        ToolContext context,
        @Param(name = "customerId", description = "The customer ID, such as C-310")
            String customerId,
        @Param(name = "orderTotalUsd", description = "The order total in US dollars")
            int orderTotalUsd) {
      int limitUsd = CREDIT_LIMITS_USD.getOrDefault(customerId, 0);
      Map<String, Object> result =
          Map.of("creditLimitUsd", limitUsd, "covered", orderTotalUsd <= limitUsd);
      // The agents' instructions read the result from state, so the check also records it there.
      context.updateState("credit", result);
      return result;
    }
  }

  /**
   * Returns a node that routes on both reviewers' verdicts: it emits approved when both approve,
   * recheck when either asks for one and fewer than {@code maxRounds} reviews have run, and no
   * route otherwise, which cancels the order.
   */
  private static Node orderReviewGate(int maxRounds) {
    return FunctionNodes.of(
        "review_gate",
        Object.class,
        Object.class,
        (ctx, input) -> {
          List<Object> verdicts = new ArrayList<>();
          for (String key : List.of(FINANCE_KEY, COMPLIANCE_KEY)) {
            verdicts.add(
                ctx.getState().get(key) instanceof Map<?, ?> review ? review.get("verdict") : null);
          }
          // The run id counts this node's runs in this invocation, so it is the review round.
          int round = Integer.parseInt(ctx.getRunId());
          String route = null;
          if (verdicts.stream().allMatch("approve"::equals)) {
            route = "approved";
          } else if (verdicts.contains("recheck") && round < maxRounds) {
            route = "recheck";
          }
          if (route != null) {
            ctx.setRoutes(List.of(new Route.Tag(route)));
          }
          return null;
        });
  }

  /**
   * Returns a step that simulates a backend call by running {@code act} and outputting the returned
   * line.
   */
  private static Node orderStep(String name, Function<Context, String> act) {
    return FunctionNodes.of(name, Object.class, String.class, (ctx, input) -> act.apply(ctx));
  }

  private OrderProcessingWorkflowJava() {}
}
