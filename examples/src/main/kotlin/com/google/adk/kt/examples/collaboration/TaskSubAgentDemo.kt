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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.collaboration

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `task_sub_agent` sample: a food-delivery coordinator delegates to two task
 * sub-agents, one that collects the order and one that collects the payment details. Each holds its
 * own conversation with the user until it finishes its task with structured data, which answers the
 * coordinator's call, and the coordinator then places the order with a tool.
 */
object TaskSubAgentDemo {

  /**
   * Builds the coordinator, with every agent on [model]. Run it as a node, with `App(rootNode =
   * ...)`, since its sub-agents set a mode.
   */
  fun create(model: Model): LlmAgent {
    val delivery = FoodDelivery()

    return LlmAgent(
      name = "coordinator",
      model = model,
      instruction =
        Instruction(
          """
          You are a helpful coordinator for a food delivery service.
          You need both order and payment information to place an order.
          """
            .trimIndent()
        ),
      // A task sub-agent is offered to the coordinator as a tool rather than a transfer target.
      subAgents = listOf(orderCollectorAgent(model, delivery), paymentCollectorAgent(model)),
      // KSP generates PlaceOrderTool from the @Tool function FoodDelivery.placeOrder.
      tools = listOf(PlaceOrderTool(delivery)),
    )
  }
}

/**
 * Runs a conversation in which the user orders food and then pays, printing each message and tool
 * call and approving any tool call that asks. Needs `GOOGLE_CLOUD_PROJECT` and Application Default
 * Credentials.
 */
fun main() = runBlocking {
  val coordinator =
    TaskSubAgentDemo.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "task_sub_agent", rootNode = coordinator))
  val replies =
    ArrayDeque(
      listOf(
        "I want 4 pizzas and 2 salads.",
        "That's everything.",
        "My credit card is 1234-5678-9012-3456 and my CVV is 123.",
      )
    )
  var message: Content? = Content.fromText(Role.USER, replies.removeFirst())
  while (message != null) {
    val confirmationId = runner.send(message)
    // Approves a pending confirmation request, or else sends the next reply.
    message =
      if (confirmationId != null) {
        approval(confirmationId)
      } else {
        replies.removeFirstOrNull()?.let { Content.fromText(Role.USER, it) }
      }
  }
}

/**
 * Sends [message], prints the messages and tool calls that follow, and returns the id of the
 * confirmation request the turn ends with, or null if it ends without one.
 */
private suspend fun InMemoryRunner.send(message: Content): String? {
  var confirmationId: String? = null
  runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
    for (call in event.functionCalls()) {
      println("[${event.author}] calls ${call.name}(${call.args})")
      if (call.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME) {
        confirmationId = call.id
      }
    }
  }
  return confirmationId
}

/** The user's approval of the tool call for confirmation request [confirmationId]. */
private fun approval(confirmationId: String): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              id = confirmationId,
              name = FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME,
              response = mapOf(ToolConfirmation.CONFIRMED_KEY to true),
            )
        )
      ),
  )

private fun orderCollectorAgent(model: Model, delivery: FoodDelivery): LlmAgent =
  LlmAgent(
    name = "order_collector",
    model = model,
    description = "Collects the food order from the user.",
    // Chats with the user until its finish_task call carries the order as a list of items.
    mode = AgentMode.TASK,
    outputSchema = Schema(type = Type.ARRAY, items = ORDER_ITEM_SCHEMA),
    instruction =
      Instruction(
        """
        You are an order collection assistant for a food delivery service.
        Our menu today has exactly 3 items: 1. Pizza, 2. Burger, 3. Salad.
        Ask the user what they would like to order and collect their choice and quantity.
        Do not offer anything else.
        If the combined quantity of items exceeds 5, you MUST use the `confirmation` tool to get
        user's confirmation before proceeding.
        Do not ask for confirmation in natural language, always use the confirmation tool.
        Once you have their final order and confirmation if needed, finish your task.
        """
          .trimIndent()
      ),
    tools = listOf(ConfirmationTool(delivery)),
  )

private fun paymentCollectorAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "payment_collector",
    model = model,
    description = "Collects credit card and CVV from the user.",
    mode = AgentMode.TASK,
    outputSchema = PAYMENT_INFO_SCHEMA,
    instruction =
      Instruction(
        """
        You are a payment collection assistant.
        Ask the user for their credit card number and CVV.
        Once you have both pieces of information, finish your task.
        """
          .trimIndent()
      ),
  )

/** The schema of [OrderItem], one item of the order collector's output. */
private val ORDER_ITEM_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "name" to Schema(type = Type.STRING, description = "Name of the food item ordered"),
        "quantity" to Schema(type = Type.INTEGER, description = "Quantity ordered"),
      ),
    required = listOf("name", "quantity"),
  )

/** The schema of [PaymentInfo], the payment collector's output. */
private val PAYMENT_INFO_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf("creditCardNumber" to Schema(type = Type.STRING), "cvv" to Schema(type = Type.STRING)),
    required = listOf("creditCardNumber", "cvv"),
  )

/** One item of a food order. */
data class OrderItem(val name: String, val quantity: Int)

/** The card that pays for an order. */
data class PaymentInfo(val creditCardNumber: String, val cvv: String)

/** A mock food-delivery backend whose functions the agents call as tools. */
class FoodDelivery {

  /** Mock an order placement operation. */
  @Tool(name = "place_order")
  fun placeOrder(
    @Param("The items to order") orders: List<OrderItem>,
    @Param("The card that pays for the order") paymentInfo: PaymentInfo,
  ): String = "Successfully placed order for ${orders.sumOf { it.quantity }} items."

  /** Confirm proceeding with the order. */
  @Tool(requireConfirmation = true) fun confirmation(): String = "Proceeding with order."
}
