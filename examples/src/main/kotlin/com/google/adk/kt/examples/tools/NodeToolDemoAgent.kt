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

package com.google.adk.kt.examples.tools

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.NodeTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Example agent that calls a workflow as a tool through a [NodeTool]. The two-step `track_order`
 * workflow looks an order up in a mock store and formats its status, and the model sees it as one
 * tool whose parameters come from the workflow's input schema. A [NodeTool] runs only on the node
 * path, so [main] makes the agent the app's root node.
 */
object NodeToolDemoAgent {

  private val lookupOrder =
    node<OrderQuery, Order>("lookup_order") { _, query ->
      val orderId = query.orderId.uppercase()
      ORDERS[orderId] ?: Order(orderId, status = "unknown")
    }

  private val formatStatus =
    node<Order, String>("format_status") { _, order ->
      when (order.status) {
        "shipped" ->
          "Order ${order.orderId} (${order.item}) has shipped and arrives in ${order.etaDays} days."
        "unknown" -> "There is no order ${order.orderId}."
        else -> "Order ${order.orderId} (${order.item}) is ${order.status}."
      }
    }

  /**
   * The workflow the agent calls. A workflow infers no input schema, so it declares the one that
   * [NodeTool] turns into the tool's parameters; each call runs both steps on a branch of its own.
   */
  @JvmField
  val trackOrder: Workflow =
    workflow(
      name = "track_order",
      description = "Looks up an order by its ID and reports its shipping status.",
      inputSchema =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "orderId" to Schema(type = Type.STRING, description = "The order ID, such as A-1001.")
            ),
          required = listOf("orderId"),
        ),
    ) {
      chain(Start, lookupOrder, formatStatus)
    }

  /** The agent, which answers questions about orders by calling [trackOrder] as a tool. */
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "order_support_agent",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          """
          You are a customer-support assistant for an online store.
          When the user asks about an order, call the `track_order` tool with its order ID and
          answer with the status it returns. If the user gives no order ID, ask for one.
          """
            .trimIndent()
        ),
      tools = listOf(NodeTool(trackOrder)),
    )
}

/**
 * Chats with the agent over stdin until `exit` or end of input, printing its answers; try asking
 * "Where is my order A-1001?".
 */
fun main(): Unit = runBlocking {
  val app = App(appName = "node_tool_demo", rootNode = NodeToolDemoAgent.rootAgent)
  InMemoryRunner(app = app).use { runner ->
    println("Ask about an order. Type 'exit' to quit.")
    while (true) {
      print("You > ")
      val line = readlnOrNull()?.trim() ?: break
      if (line == "exit") break
      if (line.isEmpty()) continue
      val message = Content.fromText(Role.USER, line)
      runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event
        ->
        val text = event.contentText(" ")
        if (event.isFinalResponse && text.isNotBlank()) println("${event.author} > $text")
      }
    }
  }
}

@Serializable private data class OrderQuery(val orderId: String)

@Serializable
private data class Order(
  val orderId: String,
  val status: String,
  val item: String? = null,
  val etaDays: Int? = null,
)

private val ORDERS =
  listOf(
      Order("A-1001", "shipped", "noise-cancelling headphones", etaDays = 2),
      Order("A-1002", "processing", "espresso machine"),
      Order("A-1003", "delivered", "trail running shoes"),
    )
    .associateBy { it.orderId }
