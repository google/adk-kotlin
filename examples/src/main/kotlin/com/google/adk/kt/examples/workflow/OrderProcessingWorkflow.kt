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

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.asNode
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * An order-processing workflow with fraud review, built with the workflow DSL. Nested fraud and
 * inventory workflows and a credit-check tool run concurrently, and a [JoinNode] waits for all
 * three before a risk agent routes the order to be charged, reviewed by finance and compliance
 * agents, or frozen as fraud. Every outcome ends with a receipt.
 */
object OrderProcessingWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow =
    with(OrderNodes(model)) {
      // Each nested workflow is one fan-out branch, so risk_join waits for its last step.
      val fraudCheck = workflow("fraud_check") { chain(Start, geoCheck, fraudScore) }
      val inventory = workflow("inventory") { chain(Start, reserveInventory, warehouseHold) }
      workflow("order_processing") {
        // Stage 1: the three branches run concurrently; risk_join waits for all of them.
        Start.then(parseOrder)
          .then(nodes(fraudCheck, inventory, creditCheck))
          .joinTo(riskJoin)
          .chain(assessRisk, riskDecision)
          // Stage 2: route on risk decision; a route mapped to two nodes fans out.
          .route {
            on("auto_approve").then(chargeCard)
            on(anyOf("manual_review", "high_value")).then(financeReview, complianceReview)
            on("fraud").then(freezeAccount, alertSecOps)
            otherwise(cancelOrder)
          }
        nodes(financeReview, complianceReview).joinTo(reviewJoin).then(reviewGate).route {
          on("approved").then(chargeCard)
          on("recheck").then(assessRisk) // Loops back for another assessment.
          otherwise(cancelOrder)
        }
        nodes(freezeAccount, alertSecOps).joinTo(auditJoin).then(cancelOrder)
        // Stage 3: only one outcome runs per order, so the receipt needs no JoinNode.
        chain(chargeCard, shipOrder, sendReceipt)
        cancelOrder.then(sendReceipt)
      }
    }
}

/**
 * Runs the workflow on three orders and prints what each step and agent did. Needs
 * `GOOGLE_API_KEY`.
 */
fun main() = runSampleOrders(OrderProcessingWorkflow.create(Gemini(name = MODEL_NAME)))

/** Runs [workflow] on three sample orders and prints what each step and agent did. */
internal fun runSampleOrders(workflow: Workflow) = runBlocking {
  val runner = InMemoryRunner(app = App(appName = "order_processing", rootNode = workflow))
  for (order in SAMPLE_ORDERS) {
    val orderId = order.substringAfter("order_id=").substringBefore(' ')
    println("\n=== Order $orderId")
    val message = Content.fromText(Role.USER, order)
    runner.runAsync(userId = "user", sessionId = orderId, newMessage = message).collect { event ->
      val path = event.nodeInfo?.path
      val text = event.contentText(" ")
      if (text.isNotBlank()) {
        println("[$path] $text")
      } else {
        event.output?.let { println("[$path] $it") }
      }
    }
  }
}

/**
 * Orders as the storefront sends them: a small one, a large cross-border one, and a likely fraud.
 */
private val SAMPLE_ORDERS =
  listOf(
    "order_id=O-1001 customer_id=C-118 total_usd=120 ship_country=US bill_country=US" +
      " account_age_days=900",
    "order_id=O-1002 customer_id=C-310 total_usd=2400 ship_country=US bill_country=CA" +
      " account_age_days=400",
    "order_id=O-1003 customer_id=C-977 total_usd=4800 ship_country=NG bill_country=US" +
      " account_age_days=2",
  )

/**
 * The nodes of the order workflow, connected by both the dot-call and infix examples. Each step
 * that outputs a `String` stands in for a backend call and says what it did.
 */
internal class OrderNodes(model: Model) {
  /**
   * Parses the order's `key=value` fields into state, where the steps and agents read them, and
   * outputs the credit check's arguments.
   */
  val parseOrder: Node =
    node<Content, Map<String, Any>>("parse_order") { context, message ->
      // START hands the first node the user's message as Content.
      val fields =
        message.text().trim().split(Regex("\\s+")).associate {
          it.substringBefore('=') to it.substringAfter('=')
        }
      for ((key, value) in fields) context.updateState(key, value.toIntOrNull() ?: value)
      // Every branch of the fan-out gets this output; only the tool reads it, as its arguments.
      mapOf(
        "customerId" to fields.getValue("customer_id"),
        "orderTotalUsd" to fields.getValue("total_usd").toInt(),
      )
    }
  val geoCheck: Node =
    node<Any?, String>("geo_check") { context, _ ->
      val mismatch = context.state["ship_country"] != context.state["bill_country"]
      context.updateState("geo_mismatch", mismatch)
      if (mismatch) "The shipping and billing countries differ." else "The countries match."
    }
  val fraudScore: Node =
    node<Any?, String>("fraud_score") { context, _ ->
      var score = 10
      if (context.state["geo_mismatch"] == true) score += 25
      if ((context.state["account_age_days"] as Number).toInt() < 30) score += 35
      if ((context.state["total_usd"] as Number).toInt() > 2000) score += 15
      context.updateState("fraud_score", score)
      "Fraud score: $score out of 100."
    }
  val reserveInventory: Node =
    node<Any?, String>("reserve_inventory") { context, _ ->
      val reservation = "RSV-${context.state["order_id"]}"
      context.updateState("reservation", reservation)
      "Reserved stock for order ${context.state["order_id"]} as $reservation."
    }
  val warehouseHold: Node =
    node<Any?, String>("warehouse_hold") { context, _ ->
      val hold = "${context.state["reservation"]} is held at the NJ-2 warehouse."
      context.updateState("inventory", hold)
      hold
    }
  val creditCheck: Node = CreditCheckTool(CreditBureau()).asNode()
  val riskJoin = JoinNode("risk_join")
  val assessRisk: Node = riskAgent(model)
  /** Routes on the risk agent's decision, since an [LlmAgent] node emits no route. */
  val riskDecision: Node =
    node<Any?, Unit>("risk_decision") { context, _ ->
      val decision = (context.state[RISK_KEY] as? Map<*, *>)?.get("decision") as? String
      // Without a decision, this node emits no route, so `otherwise` cancels the order.
      if (decision != null) context.routes = listOf(Route.Tag(decision))
    }
  val financeReview: Node = financeReviewAgent(model)
  val complianceReview: Node = complianceReviewAgent(model)
  val reviewJoin = JoinNode("review_join")
  val reviewGate: Node = orderReviewGate(maxRounds = 2)
  val chargeCard: Node =
    node<Any?, String>("charge_card") { context, _ ->
      "Charged ${context.state["total_usd"]} USD for order ${context.state["order_id"]}."
    }
  val shipOrder: Node =
    node<Any?, String>("ship_order") { context, _ ->
      context.updateState("outcome", "charged and shipped")
      "Shipped order ${context.state["order_id"]} from the NJ-2 warehouse."
    }
  val freezeAccount: Node =
    node<Any?, String>("freeze_account") { context, _ ->
      "Froze account ${context.state["customer_id"]} pending an investigation."
    }
  val alertSecOps: Node =
    node<Any?, String>("alert_sec_ops") { context, _ ->
      "Sent SecOps the fraud signals for order ${context.state["order_id"]}."
    }
  val auditJoin = JoinNode("audit_join")
  val cancelOrder: Node =
    node<Any?, String>("cancel_order") { context, _ ->
      context.updateState("outcome", "canceled")
      "Canceled order ${context.state["order_id"]} and released ${context.state["reservation"]}."
    }
  val sendReceipt: Node =
    node<Any?, String>("send_receipt") { context, _ ->
      "Emailed customer ${context.state["customer_id"]}: order ${context.state["order_id"]} was" +
        " ${context.state["outcome"]}."
    }

  /**
   * Returns a node that routes on both reviewers' verdicts: it emits approved when both approve,
   * recheck when either asks for one and fewer than [maxRounds] reviews have run, and no route
   * otherwise, which cancels the order.
   */
  private fun orderReviewGate(maxRounds: Int): Node =
    node<Any?, Unit>("review_gate") { context, _ ->
      val verdicts =
        listOf(FINANCE_KEY, COMPLIANCE_KEY).map {
          (context.state[it] as? Map<*, *>)?.get("verdict")
        }
      // The run id counts this node's runs in the current invocation, so it is the review round.
      val round = context.runId.toInt()
      val route =
        when {
          verdicts.all { it == "approve" } -> "approved"
          "recheck" in verdicts && round < maxRounds -> "recheck"
          else -> null
        }
      if (route != null) context.routes = listOf(Route.Tag(route))
    }
}

private fun riskAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "assess_risk",
    model = model,
    instruction =
      Instruction(
        """
        Assess the risk of this order before the card is charged.
        Order {order_id}: {total_usd} USD from customer {customer_id}, whose account is
        {account_age_days} days old. It ships to {ship_country} and is billed in {bill_country}.
        Fraud score: {fraud_score} out of 100. Inventory: {inventory}. Credit: {credit}.
        If reviewers asked for a recheck, weigh their notes:
        {finance_review?} {compliance_review?}
        """
          .trimIndent()
      ),
    outputSchema = RISK_SCHEMA,
    outputKey = RISK_KEY,
  )

private fun financeReviewAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "finance_review",
    model = model,
    instruction =
      Instruction(
        """
        You review orders for the finance team. Order {order_id} totals {total_usd} USD.
        Credit: {credit}. Risk assessment: {risk}.
        Approve if the customer's credit covers the order, reject if it does not, and ask for a
        recheck if the risk assessment overlooked something.
        """
          .trimIndent()
      ),
    outputSchema = REVIEW_SCHEMA,
    outputKey = FINANCE_KEY,
  )

private fun complianceReviewAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "compliance_review",
    model = model,
    instruction =
      Instruction(
        """
        You review orders for the compliance team. Order {order_id} ships to {ship_country}, is
        billed in {bill_country}, and comes from an account {account_age_days} days old.
        Fraud score: {fraud_score} out of 100. Risk assessment: {risk}.
        Approve or reject the order, or ask for a recheck if the risk assessment overlooked
        something.
        """
          .trimIndent()
      ),
    outputSchema = REVIEW_SCHEMA,
    outputKey = COMPLIANCE_KEY,
  )

private const val RISK_KEY = "risk"
private const val FINANCE_KEY = "finance_review"
private const val COMPLIANCE_KEY = "compliance_review"

/** The risk agent's structured decision, which [OrderNodes.riskDecision] routes on. */
private val RISK_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "decision" to
          Schema(
            type = Type.STRING,
            enum = listOf("auto_approve", "manual_review", "high_value", "fraud"),
            description =
              "auto_approve: low risk. high_value: over 2000 USD but otherwise low risk." +
                " manual_review: the signals conflict. fraud: the signals point to fraud.",
          ),
        "reason" to Schema(type = Type.STRING, description = "One sentence on the decision."),
      ),
    required = listOf("decision", "reason"),
  )

/** A reviewer's structured verdict, which [OrderNodes.reviewGate] routes on. */
private val REVIEW_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "verdict" to Schema(type = Type.STRING, enum = listOf("approve", "reject", "recheck")),
        "note" to Schema(type = Type.STRING, description = "One sentence on the verdict."),
      ),
    required = listOf("verdict", "note"),
  )

/** A mock credit bureau whose check runs as a step of the workflow. */
class CreditBureau {
  private val creditLimitsUsd = mapOf("C-118" to 1500, "C-310" to 5000, "C-977" to 500)

  /** Checks whether the customer's credit limit covers the order total. */
  @Tool
  fun creditCheck(
    context: ToolContext,
    @Param("The customer ID, such as C-310") customerId: String,
    @Param("The order total in US dollars") orderTotalUsd: Int,
  ): Map<String, Any> {
    val limitUsd = creditLimitsUsd[customerId] ?: 0
    val result = mapOf("creditLimitUsd" to limitUsd, "covered" to (orderTotalUsd <= limitUsd))
    // Agent nodes read state, not the join's output, so the check also records its result there.
    context.updateState("credit", result)
    return result
  }
}
