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

package com.google.adk.kt.examples.collaboration;

import com.google.adk.kt.agents.AgentMode;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.ToolConfirmation;
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.FunctionTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code task_sub_agent} sample: a food-delivery coordinator delegates to
 * two task sub-agents, one that collects the order and one that collects the payment details. Each
 * holds its own conversation with the user until it finishes its task with structured data, which
 * answers the coordinator's call, and the coordinator then places the order with a tool.
 */
public final class TaskSubAgentDemoJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /**
   * Builds the coordinator, with every agent on {@code model}. Run it as a node, with {@code
   * App.Builder.rootNode}, since its sub-agents set a mode.
   */
  public static LlmAgent create(Model model) {
    return LlmAgent.builder()
        .name("coordinator")
        .model(model)
        .instruction(
            """
            You are a helpful coordinator for a food delivery service.
            You need both order and payment information to place an order.\
            """)
        // A task sub-agent is offered to the coordinator as a tool rather than a transfer target.
        .subAgents(orderCollectorAgent(model), paymentCollectorAgent(model))
        .tools(new PlaceOrderTool())
        .build();
  }

  /**
   * Runs a conversation in which the user orders food and then pays, printing each message and tool
   * call and approving any tool call that asks. Needs {@code GOOGLE_CLOUD_PROJECT} and Application
   * Default Credentials.
   */
  public static void main(String[] args) {
    LlmAgent coordinator =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("task_sub_agent").rootNode(coordinator).build());
    Deque<String> replies =
        new ArrayDeque<>(
            List.of(
                "I want 4 pizzas and 2 salads.",
                "That's everything.",
                "My credit card is 1234-5678-9012-3456 and my CVV is 123."));
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

  private static LlmAgent orderCollectorAgent(Model model) {
    return LlmAgent.builder()
        .name("order_collector")
        .model(model)
        .description("Collects the food order from the user.")
        // Chats with the user until its finish_task call carries the order as a list of items.
        .mode(AgentMode.TASK)
        .outputSchema(Schema.builder().type(Type.ARRAY).items(ORDER_ITEM_SCHEMA).build())
        .instruction(
            """
            You are an order collection assistant for a food delivery service.
            Our menu today has exactly 3 items: 1. Pizza, 2. Burger, 3. Salad.
            Ask the user what they would like to order and collect their choice and quantity.
            Do not offer anything else.
            If the combined quantity of items exceeds 5, you MUST use the `confirmation` tool to get
            user's confirmation before proceeding.
            Do not ask for confirmation in natural language, always use the confirmation tool.
            Once you have their final order and confirmation if needed, finish your task.\
            """)
        .tools(new ConfirmationTool())
        .build();
  }

  private static LlmAgent paymentCollectorAgent(Model model) {
    return LlmAgent.builder()
        .name("payment_collector")
        .model(model)
        .description("Collects credit card and CVV from the user.")
        .mode(AgentMode.TASK)
        .outputSchema(PAYMENT_INFO_SCHEMA)
        .instruction(
            """
            You are a payment collection assistant.
            Ask the user for their credit card number and CVV.
            Once you have both pieces of information, finish your task.\
            """)
        .build();
  }

  /**
   * Places the order. Hand-written because ReflectiveTools maps only scalar and enum parameters,
   * not the list of items and the card that this tool takes.
   */
  private static final class PlaceOrderTool extends BaseFutureTool {
    PlaceOrderTool() {
      super("place_order", "Mock an order placement operation.");
    }

    @Override
    public FunctionDeclaration declaration() {
      Schema parameters =
          Schema.builder()
              .type(Type.OBJECT)
              .properties(
                  Map.of(
                      "orders",
                      Schema.builder()
                          .type(Type.ARRAY)
                          .description("The items to order")
                          .items(ORDER_ITEM_SCHEMA)
                          .build(),
                      "paymentInfo",
                      PAYMENT_INFO_SCHEMA.toBuilder()
                          .description("The card that pays for the order")
                          .build()))
              .required("orders", "paymentInfo")
              .build();
      return FunctionDeclaration.builder()
          .name(getName())
          .description(getDescription())
          .parameters(parameters)
          .build();
    }

    @Override
    protected CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      // Returns the Kotlin tool's errors for a malformed `orders` instead of throwing.
      if (!(args.get("orders") instanceof List<?> orders)) {
        return error("Missing required parameter orders");
      }
      long totalItems = 0;
      for (Object item : orders) {
        if (!(item instanceof Map<?, ?> order)) {
          return error("Missing or invalid item in list orders");
        }
        if (!(order.get("quantity") instanceof Number quantity)) {
          return error("Missing required parameter orders[].quantity");
        }
        totalItems += quantity.longValue();
      }
      return CompletableFuture.completedFuture(
          "Successfully placed order for " + totalItems + " items.");
    }

    private static CompletableFuture<Object> error(String message) {
      return CompletableFuture.completedFuture(Map.of(FunctionTool.ERROR_KEY, message));
    }
  }

  /**
   * Confirms proceeding with the order once the user approves the call. Hand-written to follow the
   * protocol of a {@code requireConfirmation} tool, because ReflectiveTools builds no tool that
   * waits for approval.
   */
  private static final class ConfirmationTool extends BaseFutureTool {
    ConfirmationTool() {
      super("confirmation", "Confirm proceeding with the order.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder().name(getName()).description(getDescription()).build();
    }

    @Override
    protected CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      ToolConfirmation confirmation = context.getToolConfirmation();
      if (confirmation == null) {
        context.requestConfirmation(
            "Please approve or reject the tool call "
                + getName()
                + "() by responding with a FunctionResponse with an expected ToolConfirmation"
                + " payload.",
            null);
        // Ends the turn on the request, as FunctionTool does, instead of calling the model again.
        context.getActions().setSkipSummarization(true);
        return CompletableFuture.completedFuture(
            Map.of(FunctionTool.ERROR_KEY, FunctionTool.CONFIRMATION_REQUIRED_ERROR));
      }
      if (!confirmation.getConfirmed()) {
        return CompletableFuture.completedFuture(
            Map.of(FunctionTool.ERROR_KEY, FunctionTool.REJECTED_ERROR));
      }
      return CompletableFuture.completedFuture("Proceeding with order.");
    }
  }

  /** The schema of one item of a food order. */
  private static final Schema ORDER_ITEM_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "name",
                  Schema.builder()
                      .type(Type.STRING)
                      .description("Name of the food item ordered")
                      .build(),
                  "quantity",
                  Schema.builder().type(Type.INTEGER).description("Quantity ordered").build()))
          .required("name", "quantity")
          .build();

  /** The schema of the card that pays for an order, the payment collector's output. */
  private static final Schema PAYMENT_INFO_SCHEMA =
      Schema.builder()
          .type(Type.OBJECT)
          .properties(
              Map.of(
                  "creditCardNumber",
                  Schema.builder().type(Type.STRING).build(),
                  "cvv",
                  Schema.builder().type(Type.STRING).build()))
          .required("creditCardNumber", "cvv")
          .build();

  private TaskSubAgentDemoJava() {}
}
