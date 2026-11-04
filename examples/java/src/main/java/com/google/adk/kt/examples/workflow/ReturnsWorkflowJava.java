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
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.ToolNodes;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A returns desk built with the workflow DSL, whose steps include the {@code @Tool} methods of
 * {@link OrderSystem}. A routing map sends each group of statuses, matched with {@code anyOf}, to
 * the tool that handles it, and declines any other status.
 */
public final class ReturnsWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    OrderSystem orders = new OrderSystem();

    return Workflow.builder()
        .name("returns")
        .edges(
            g ->
                g.fromStart()
                    .chain(toolStep(orders, "lookupOrder"), returnDesk())
                    .route(
                        r -> {
                          r.on(r.anyOf("processing", "packed"))
                              .then(toolStep(orders, "cancelOrder"));
                          r.on(r.anyOf("shipped", "delivered"))
                              .then(toolStep(orders, "sendReturnLabel"));
                          r.otherwise(decline());
                        }))
        .build();
  }

  /** Runs the workflow on three orders and prints each step's output. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("returns").rootNode(create()).build());
    for (String orderId : List.of("A-1001", "A-1002", "A-1003")) {
      // The first step is a tool, so the request is a JSON object of its arguments.
      Content request = Content.fromText(Role.USER, "{\"orderId\": \"" + orderId + "\"}");
      for (Event event : Flux.from(runner.runAsync("user", orderId, null, request)).toIterable()) {
        if (event.getOutput() != null) {
          String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
          System.out.println("[" + path + "] " + event.getOutput());
        }
      }
    }
  }

  /** A mock order backend whose tools the returns workflow runs as graph steps. */
  public static final class OrderSystem {
    private static final Map<String, String> STATUSES =
        Map.of("A-1001", "processing", "A-1002", "delivered", "A-1003", "returned");

    @Tool(description = "Looks up where an order is in fulfillment.")
    public Map<String, String> lookupOrder(
        @Param(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return Map.of("orderId", orderId, "status", STATUSES.getOrDefault(orderId, "unknown"));
    }

    @Tool(description = "Cancels an order that has not shipped yet and refunds it in full.")
    public String cancelOrder(
        @Param(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return "Canceled " + orderId + " before it shipped. The full refund is on its way.";
    }

    @Tool(description = "Emails the customer a prepaid label for sending an order back.")
    public String sendReturnLabel(
        @Param(name = "orderId", description = "The order ID, such as A-1001") String orderId) {
      return "Emailed a prepaid return label for "
          + orderId
          + ". The refund follows once the return arrives.";
    }
  }

  /** Routes on the order's status and passes the order ID to the next step. */
  private static FunctionNode<Map<String, Object>, Map<String, Object>> returnDesk() {
    return FunctionNodes.of(
        "return_desk",
        new TypeReference<Map<String, Object>>() {},
        new TypeReference<Map<String, Object>>() {},
        (ctx, lookup) -> {
          // Tools built from @Tool methods wrap their return value under "result".
          Map<?, ?> order = (Map<?, ?>) lookup.get(BaseTool.RESULT_KEY);
          ctx.setRoutes(List.of(new Route.Tag((String) order.get("status"))));
          return Map.of("orderId", order.get("orderId"));
        });
  }

  /** Declines a return for any other status, such as an already returned order. */
  private static FunctionNode<Map<String, Object>, String> decline() {
    return FunctionNodes.of(
        "decline",
        new TypeReference<Map<String, Object>>() {},
        String.class,
        (ctx, request) -> "Order " + request.get("orderId") + " can't be returned.");
  }

  /**
   * Turns the {@code @Tool} method {@code methodName} of {@code orders} into a tool with {@link
   * ReflectiveTools} and wraps it as a workflow step with {@link ToolNodes#asNode}.
   */
  private static Node toolStep(OrderSystem orders, String methodName) {
    return ToolNodes.asNode(ReflectiveTools.fromMethod(orders, methodName));
  }

  private ReturnsWorkflowJava() {}
}
