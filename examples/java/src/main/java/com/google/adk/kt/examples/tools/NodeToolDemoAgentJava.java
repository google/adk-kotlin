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

package com.google.adk.kt.examples.tools;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.NodeTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Workflow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Example agent that calls a workflow as a tool through a {@link NodeTool}.
 *
 * <p>The two-step {@code track_order} workflow looks an order up in a mock store and formats its
 * status, and the model sees it as one tool whose parameters come from the workflow's input schema.
 * A {@link NodeTool} runs only on the node path, so {@link #main} makes the agent the app's root
 * node.
 */
public final class NodeToolDemoAgentJava {

  /**
   * The workflow the agent calls. A workflow infers no input schema, so it declares the one that
   * {@link NodeTool} turns into the tool's parameters; each call runs both steps on a branch of its
   * own.
   */
  public static final Workflow trackOrder =
      Workflow.builder()
          .name("track_order")
          .description("Looks up an order by its ID and reports its shipping status.")
          .inputSchema(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "orderId",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The order ID, such as A-1001.")
                              .build()))
                  .required("orderId")
                  .build())
          .edges(g -> g.fromStart().chain(lookupOrder(), formatStatus()))
          .build();

  /** The agent, which answers questions about orders by calling {@link #trackOrder} as a tool. */
  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("order_support_agent")
          .model(
              new Gemini(
                  "gemini-3.8-flash", VertexCredentials.builder().location("global").build()))
          .instruction(
              """
              You are a customer-support assistant for an online store.
              When the user asks about an order, call the `track_order` tool with its order ID and
              answer with the status it returns. If the user gives no order ID, ask for one.\
              """)
          .tools(new NodeTool(trackOrder))
          .build();

  /**
   * Chats with the agent over stdin until {@code exit} or end of input, printing its answers; try
   * asking "Where is my order A-1001?".
   */
  public static void main(String[] args) throws IOException {
    App app = App.builder().appName("node_tool_demo").rootNode(rootAgent).build();
    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, UTF_8));
    try (PublisherRunner runner = PublisherRunner.inMemory(app)) {
      System.out.println("Ask about an order. Type 'exit' to quit.");
      while (true) {
        System.out.print("You > ");
        System.out.flush();
        String line = input.readLine();
        if (line == null || line.strip().equals("exit")) {
          break;
        }
        if (line.isBlank()) {
          continue;
        }
        Content message = Content.fromText(Role.USER, line.strip());
        for (Event event :
            Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
          String text = event.contentText(" ");
          if (event.isFinalResponse() && !text.isBlank()) {
            System.out.println(event.getAuthor() + " > " + text);
          }
        }
      }
    }
  }

  private static FunctionNode<OrderQuery, Order> lookupOrder() {
    return FunctionNodes.of(
        "lookup_order",
        OrderQuery.class,
        Order.class,
        (ctx, query) -> {
          String orderId = query.orderId().toUpperCase(Locale.ROOT);
          return orders.getOrDefault(orderId, new Order(orderId, "unknown", null, null));
        });
  }

  private static FunctionNode<Order, String> formatStatus() {
    return FunctionNodes.of(
        "format_status",
        Order.class,
        String.class,
        (ctx, order) ->
            switch (order.status()) {
              case "shipped" ->
                  "Order %s (%s) has shipped and arrives in %d days."
                      .formatted(order.orderId(), order.item(), order.etaDays());
              case "unknown" -> "There is no order %s.".formatted(order.orderId());
              default ->
                  "Order %s (%s) is %s.".formatted(order.orderId(), order.item(), order.status());
            });
  }

  record OrderQuery(String orderId) {}

  /** {@code item} and {@code etaDays} may be null. */
  record Order(String orderId, String status, String item, Integer etaDays) {}

  private static final Map<String, Order> orders =
      Map.of(
          "A-1001", new Order("A-1001", "shipped", "noise-cancelling headphones", 2),
          "A-1002", new Order("A-1002", "processing", "espresso machine", null),
          "A-1003", new Order("A-1003", "delivered", "trail running shoes", null));

  private NodeToolDemoAgentJava() {}
}
