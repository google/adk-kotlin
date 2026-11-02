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

package com.google.adk.kt.examples.springboot

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.examples.springboot.OrderService.RefundDecision
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.Serializable
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration that defines the order-support workflow as a bean. Each node's output is the
 * next node's input: function nodes look up the order and apply the refund policy, and every branch
 * ends at the `reply` node, whose output is the workflow's reply.
 */
@Configuration(proxyBeanMethods = false)
class SupportWorkflowConfiguration {

  /**
   * Creates the workflow, with [model] for every agent and [orders] for order lookups and tools.
   */
  @Bean
  fun supportWorkflow(model: Model, orders: OrderService): Workflow {
    val shipping = shippingAgent(model, orders)
    val refunds = refundsAgent(model, orders)
    val refundPolicy = refundPolicy(orders)

    return workflow(WORKFLOW) {
      chain(Start, triageAgent(model), checkOrder(orders)).route {
        on("tracking") then shipping
        on("refund") then refundPolicy
        otherwise(askForOrder)
      }
      refundPolicy.route {
        on(true) then refunds
        on(false) then refundDenied
      }
      // Every branch ends at `reply`, so the service reads the reply from that one node.
      shipping then reply
      refunds then reply
      refundDenied then reply
      askForOrder then reply
    }
  }

  /** Classifies the customer's message and answers with [TRIAGE_SCHEMA]. */
  private fun triageAgent(model: Model): LlmAgent =
    LlmAgent(
      name = "triage",
      model = model,
      instruction =
        Instruction("Classify the customer's message and extract the order number, if present."),
      mode = AgentMode.SINGLE_TURN,
      outputSchema = TRIAGE_SCHEMA,
    )

  /** Answers a delivery question with the order service's tracking tool. */
  private fun shippingAgent(model: Model, orders: OrderService): LlmAgent =
    LlmAgent(
      name = SHIPPING,
      model = model,
      instruction =
        Instruction(
          "You answer delivery questions for an online store. The last message names the " +
            "customer's order. Look up its tracking status with the tool, then tell the " +
            "customer where their order is and when it arrives, in at most three sentences."
        ),
      mode = AgentMode.SINGLE_TURN,
      // Reads the customer's message; a single-turn agent otherwise sees only its input.
      includeContents = LlmAgent.IncludeContents.DEFAULT,
      // KSP generates TrackShipmentTool from the @Tool function OrderService.trackShipment.
      tools = listOf(TrackShipmentTool(orders)),
    )

  /** Creates the return label for an approved refund with the order service. */
  private fun refundsAgent(model: Model, orders: OrderService): LlmAgent =
    LlmAgent(
      name = "refunds",
      model = model,
      instruction =
        Instruction(
          "You handle approved refunds for an online store. The last message names the " +
            "customer's order, whose refund the store's policy approved. Create a return " +
            "label with the tool, then give the customer the label number, in at most three " +
            "sentences."
        ),
      mode = AgentMode.SINGLE_TURN,
      includeContents = LlmAgent.IncludeContents.DEFAULT,
      tools = listOf(CreateReturnLabelTool(orders)),
    )

  /**
   * The function node that takes the triage agent's answer, looks up the order, and routes on the
   * intent. It reports the order it found, and its output (the order's number and item) is the next
   * node's input.
   */
  private fun checkOrder(orders: OrderService) =
    node<Triage, OrderFacts?>("check_order") { context, triage ->
      // Without an order to act on, this node emits no route, so `otherwise` replies instead.
      val order = orders.findOrder(triage.orderId) ?: return@node null
      // An intent that no edge matches, such as `other`, also leads to `otherwise`.
      context.routes = listOf(Route.Tag(triage.intent))
      // `otherwise` replies without using the order, so only a matched route reports progress.
      if (triage.intent == "tracking" || triage.intent == "refund") {
        emitProgress("Found order ${order.id} (${order.item}).")
      }
      if (triage.intent == "tracking") emitProgress("Checking the carrier's tracking...")
      OrderFacts(order.id, order.item)
    }

  /**
   * The function node that applies the refund policy in code, so a model never makes the decision.
   * It routes on whether the refund is approved and adds the decision to the order it passes on.
   */
  private fun refundPolicy(orders: OrderService) =
    node<OrderFacts?, RefundFacts>("refund_policy") { context, input ->
      // check_order routes here only with an order; the edge check alone needs the nullable input.
      val order =
        checkNotNull(input) { "refund_policy runs only after check_order finds an order." }
      emitProgress("Checking the refund policy...")
      val decision = orders.decideRefund(order.orderId)
      val approved = decision == RefundDecision.APPROVED
      context.routes = listOf(Route.Flag(approved))
      if (approved) emitProgress("Creating a return label...")
      RefundFacts(order.orderId, order.item, decision.description)
    }

  /**
   * Publishes [text] as a progress message from a node body. The message is partial, so the runner
   * streams it to the caller but never stores it, and no agent sees it in its history.
   */
  private suspend fun FlowCollector<Event>.emitProgress(text: String) {
    emit(Event(content = Content.fromText(Role.MODEL, text), partial = true))
  }

  /** The function node that explains a denied refund without a model. Its output is the reply. */
  private val refundDenied =
    node<RefundFacts, String>("refund_denied") { _, refund ->
      "Your refund for order ${refund.orderId} (${refund.item}) was ${refund.decision}."
    }

  /**
   * The function node that replies without a model when there is no order or the request is
   * unsupported. Its output is the reply.
   */
  private val askForOrder =
    node<OrderFacts?, String>("ask_for_order") { _, _ ->
      "I can help you track an order or request a refund. Please send one of those requests " +
        "with a valid order number, such as 1042."
    }

  /** The final node, which every branch leads to, so its output is the workflow's reply. */
  private val reply = node<String?, String?>(REPLY) { _, reply -> reply }

  /**
   * The triage agent's structured answer, which `check_order` takes as its input. The ADK validates
   * it against `TRIAGE_SCHEMA`, which requires both fields, so neither is null.
   */
  @Serializable internal data class Triage(val intent: String, val orderId: String)

  /**
   * The order that `check_order` found. A function node writes it as JSON, which is how the
   * shipping agent receives it.
   */
  @Serializable internal data class OrderFacts(val orderId: String, val item: String)

  /** The order with the refund policy's decision, which `refund_policy` passes on. */
  @Serializable
  internal data class RefundFacts(val orderId: String, val item: String, val decision: String)

  companion object {
    /** The workflow's name. */
    private const val WORKFLOW = "support"

    // The names of the nodes that the service and its tests refer to.
    const val SHIPPING = "shipping"
    const val REPLY = "reply"

    private val TRIAGE_SCHEMA =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "intent" to
              Schema(
                type = Type.STRING,
                enum = listOf("tracking", "refund", "other"),
                description =
                  "tracking: where an order is or when it arrives. refund: money back for an " +
                    "order. other: anything else.",
              ),
            "orderId" to
              Schema(
                type = Type.STRING,
                description = "The order number in the message, or empty if it has none.",
              ),
          ),
        required = listOf("intent", "orderId"),
      )
  }
}
