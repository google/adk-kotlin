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

package com.google.adk.kt.examples.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.asNode
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

/**
 * A returns desk built with the workflow DSL, whose steps include the `@Tool` methods of
 * [OrderSystem]. A routing map sends each group of statuses, matched with `anyOf`, to the tool that
 * handles it, and declines any other status.
 */
object ReturnsWorkflow {

  /** Builds the workflow. It calls no model. */
  fun create(): Workflow {
    val orders = OrderSystem()
    // @Tool generates a tool class per method, such as LookupOrderTool for lookupOrder.
    val lookupOrder = LookupOrderTool(orders).asNode()
    val cancelOrder = CancelOrderTool(orders).asNode()
    val sendReturnLabel = SendReturnLabelTool(orders).asNode()
    return workflow("returns") {
      chain(Start, lookupOrder, returnDesk).route {
        on(anyOf("processing", "packed")) then cancelOrder
        on(anyOf("shipped", "delivered")) then sendReturnLabel
        otherwise(decline)
      }
    }
  }

  /** Routes on the order's status and passes the order ID to the next step. */
  private val returnDesk =
    node<Map<String, Any?>, Map<String, Any?>>("return_desk") { context, lookup ->
      // Tools built from @Tool methods wrap their return value under "result".
      val order = lookup[BaseTool.RESULT_KEY] as Map<*, *>
      context.routes = listOf(Route.Tag(order["status"] as String))
      mapOf("orderId" to order["orderId"])
    }

  /** Declines a return for any other status, such as an already returned order. */
  private val decline =
    node<Map<String, Any?>, String>("decline") { _, request ->
      "Order ${request["orderId"]} can't be returned."
    }
}

/** Runs the workflow on three orders and prints each step's output. */
fun main() = runBlocking {
  val runner = InMemoryRunner(app = App(appName = "returns", rootNode = ReturnsWorkflow.create()))
  for (orderId in listOf("A-1001", "A-1002", "A-1003")) {
    // The first step is a tool, so the request is a JSON object of its arguments.
    val request = Content.fromText(Role.USER, """{"orderId": "$orderId"}""")
    runner.runAsync(userId = "user", sessionId = orderId, newMessage = request).collect { event ->
      event.output?.let { println("[${event.nodeInfo?.path}] $it") }
    }
  }
}

/** A mock order backend whose tools the returns workflow runs as graph steps. */
class OrderSystem {
  private val statuses =
    mapOf("A-1001" to "processing", "A-1002" to "delivered", "A-1003" to "returned")

  /** Looks up where an order is in fulfillment. */
  @Tool
  fun lookupOrder(@Param("The order ID, such as A-1001") orderId: String): Map<String, String> =
    mapOf("orderId" to orderId, "status" to (statuses[orderId] ?: "unknown"))

  /** Cancels an order that has not shipped yet and refunds it in full. */
  @Tool
  fun cancelOrder(@Param("The order ID, such as A-1001") orderId: String): String =
    "Canceled $orderId before it shipped. The full refund is on its way."

  /** Emails the customer a prepaid label for sending an order back. */
  @Tool
  fun sendReturnLabel(@Param("The order ID, such as A-1001") orderId: String): String =
    "Emailed a prepaid return label for $orderId. The refund follows once the return arrives."
}
