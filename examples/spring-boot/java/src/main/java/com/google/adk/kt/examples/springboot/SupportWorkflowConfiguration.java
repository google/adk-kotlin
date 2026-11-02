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

package com.google.adk.kt.examples.springboot;

import com.google.adk.kt.agents.AgentMode;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.examples.springboot.OrderService.RefundDecision;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that defines the order-support workflow as a bean. Each node's output is the
 * next node's input: function nodes look up the order and apply the refund policy, and every branch
 * ends at the {@code reply} node, whose output is the workflow's reply.
 */
@Configuration(proxyBeanMethods = false)
class SupportWorkflowConfiguration {

  /** The workflow's name. */
  private static final String WORKFLOW = "support";

  // The names of the nodes that the service and its tests refer to.
  static final String SHIPPING = "shipping";
  static final String REPLY = "reply";

  /**
   * Creates the workflow, with {@code model} for every agent and {@code orders} for order lookups
   * and tools.
   */
  @Bean
  Workflow supportWorkflow(Model model, OrderService orders) {
    LlmAgent shipping = shippingAgent(model, orders);
    LlmAgent refunds = refundsAgent(model, orders);
    FunctionNode<OrderFacts, RefundFacts> refundPolicy = refundPolicy(orders);
    FunctionNode<RefundFacts, String> refundDenied = refundDenied();
    FunctionNode<@Nullable OrderFacts, String> askForOrder = askForOrder();
    FunctionNode<@Nullable String, @Nullable String> reply = reply();

    return Workflow.builder()
        .name(WORKFLOW)
        .edges(
            g -> {
              g.fromStart()
                  .chain(triageAgent(model), checkOrder(orders))
                  .route(
                      r -> {
                        r.on("tracking").then(shipping);
                        r.on("refund").then(refundPolicy);
                        r.otherwise(askForOrder);
                      });
              g.from(refundPolicy)
                  .route(
                      r -> {
                        r.on(true).then(refunds);
                        r.on(false).then(refundDenied);
                      });
              // Every branch ends at `reply`, so the service reads the reply from that one node.
              g.from(shipping).then(reply);
              g.from(refunds).then(reply);
              g.from(refundDenied).then(reply);
              g.from(askForOrder).then(reply);
            })
        .build();
  }

  /** Classifies the customer's message and answers with {@code TRIAGE_SCHEMA}. */
  private static LlmAgent triageAgent(Model model) {
    return LlmAgent.builder()
        .name("triage")
        .model(model)
        .instruction("Classify the customer's message and extract the order number, if present.")
        .mode(AgentMode.SINGLE_TURN)
        .outputSchema(TRIAGE_SCHEMA)
        .build();
  }

  /** Answers a delivery question with the order service's tracking tool. */
  private static LlmAgent shippingAgent(Model model, OrderService orders) {
    return LlmAgent.builder()
        .name(SHIPPING)
        .model(model)
        .instruction(
            """
            You answer delivery questions for an online store. The last message names the \
            customer's order. Look up its tracking status with the tool, then tell the \
            customer where their order is and when it arrives, in at most three sentences.\
            """)
        .mode(AgentMode.SINGLE_TURN)
        // Reads the customer's message; a single-turn agent otherwise sees only its input.
        .includeContents(LlmAgent.IncludeContents.DEFAULT)
        // This module has no Kotlin compiler; with one, KSP generates tools at compile time:
        // https://github.com/google/adk-kotlin/blob/main/examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java
        .tools(ReflectiveTools.fromMethod(orders, "trackShipment"))
        .build();
  }

  /** Creates the return label for an approved refund with the order service. */
  private static LlmAgent refundsAgent(Model model, OrderService orders) {
    return LlmAgent.builder()
        .name("refunds")
        .model(model)
        .instruction(
            """
            You handle approved refunds for an online store. The last message names the \
            customer's order, whose refund the store's policy approved. Create a return \
            label with the tool, then give the customer the label number, in at most three \
            sentences.\
            """)
        .mode(AgentMode.SINGLE_TURN)
        .includeContents(LlmAgent.IncludeContents.DEFAULT)
        .tools(ReflectiveTools.fromMethod(orders, "createReturnLabel"))
        .build();
  }

  /**
   * The function node that takes the triage agent's answer, looks up the order, and routes on the
   * intent. Its output (the order's number and item) is the next node's input.
   */
  private static FunctionNode<Triage, @Nullable OrderFacts> checkOrder(OrderService orders) {
    return FunctionNodes.of(
        "check_order",
        Triage.class,
        OrderFacts.class,
        (context, triage) -> {
          // Without an order to act on, this node emits no route, so `otherwise` replies instead.
          OrderService.Order order = orders.findOrder(triage.orderId());
          if (order == null) {
            return null;
          }
          // An intent that no edge matches, such as `other`, also leads to `otherwise`.
          context.setRoutes(List.of(new Route.Tag(triage.intent())));
          return new OrderFacts(order.id(), order.item());
        });
  }

  /**
   * The function node that applies the refund policy in code, so a model never makes the decision.
   * It routes on whether the refund is approved and adds the decision to the order it passes on.
   */
  private static FunctionNode<OrderFacts, RefundFacts> refundPolicy(OrderService orders) {
    return FunctionNodes.of(
        "refund_policy",
        OrderFacts.class,
        RefundFacts.class,
        (context, order) -> {
          RefundDecision decision = orders.decideRefund(order.orderId());
          boolean approved = decision == RefundDecision.APPROVED;
          context.setRoutes(List.of(new Route.Flag(approved)));
          return new RefundFacts(order.orderId(), order.item(), decision.description());
        });
  }

  /** The function node that explains a denied refund without a model. Its output is the reply. */
  private static FunctionNode<RefundFacts, String> refundDenied() {
    return FunctionNodes.of(
        "refund_denied",
        RefundFacts.class,
        String.class,
        (context, refund) ->
            "Your refund for order "
                + refund.orderId()
                + " ("
                + refund.item()
                + ") was "
                + refund.decision()
                + ".");
  }

  /**
   * The function node that replies without a model when there is no order or the request is
   * unsupported. Its output is the reply.
   */
  private static FunctionNode<@Nullable OrderFacts, String> askForOrder() {
    return FunctionNodes.of(
        "ask_for_order",
        OrderFacts.class,
        String.class,
        (context, input) ->
            "I can help you track an order or request a refund. Please send one of those requests"
                + " with a valid order number, such as 1042.");
  }

  /** The final node, which every branch leads to, so its output is the workflow's reply. */
  private static FunctionNode<@Nullable String, @Nullable String> reply() {
    return FunctionNodes.of(REPLY, String.class, String.class, (context, reply) -> reply);
  }

  private static final Schema TRIAGE_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "intent",
                  Schema.builder()
                      .type(Type.STRING)
                      .enumValues("tracking", "refund", "other")
                      .description(
                          "tracking: where an order is or when it arrives. refund: money back"
                              + " for an order. other: anything else.")
                      .build(),
                  "orderId",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("The order number in the message, or empty if it has none.")
                      .build()))
          .required("intent", "orderId")
          .build();

  /**
   * The triage agent's structured answer, which {@code check_order} takes as its input. The ADK
   * validates it against {@code TRIAGE_SCHEMA}, which requires both fields, so neither is null.
   */
  record Triage(String intent, String orderId) {}

  /**
   * The order that {@code check_order} found. A function node writes it as JSON, which is how the
   * shipping agent receives it.
   */
  record OrderFacts(String orderId, String item) {}

  /** The order with the refund policy's decision, which {@code refund_policy} passes on. */
  record RefundFacts(String orderId, String item, String decision) {}
}
