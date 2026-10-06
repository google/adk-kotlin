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
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * The order-processing workflow from [OrderProcessingWorkflow], with edges declared using the
 * workflow DSL's infix form. Its routing maps give each fan-out target its own entry, because
 * `then` with multiple targets has no infix form.
 */
object OrderProcessingInfixWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow =
    with(OrderNodes(model)) {
      // Each nested workflow is one fan-out branch, so risk_join waits for its last step.
      val fraudCheck = workflow("fraud_check") { Start then geoCheck then fraudScore }
      val inventory = workflow("inventory") { Start then reserveInventory then warehouseHold }
      workflow("order_processing") {
        // Stage 1: the three branches run concurrently; risk_join waits for all of them.
        Start then parseOrder then nodes(fraudCheck, inventory, creditCheck) joinTo riskJoin
        riskJoin then assessRisk then riskDecision
        // Stage 2: route on risk decision; a route mapped to two nodes fans out.
        riskDecision route
          {
            "auto_approve" then chargeCard
            anyOf("manual_review", "high_value") then financeReview
            anyOf("manual_review", "high_value") then complianceReview
            "fraud" then freezeAccount
            "fraud" then alertSecOps
            otherwise(cancelOrder)
          }
        nodes(financeReview, complianceReview) joinTo reviewJoin then reviewGate
        reviewGate route
          {
            "approved" then chargeCard
            "recheck" then assessRisk // Loops back for another assessment.
            otherwise(cancelOrder)
          }
        nodes(freezeAccount, alertSecOps) joinTo auditJoin then cancelOrder
        // Stage 3: only one outcome runs per order, so the receipt needs no JoinNode.
        chargeCard then shipOrder then sendReceipt
        cancelOrder then sendReceipt
      }
    }
}

/**
 * Runs the workflow on three orders and prints what each step and agent did. Needs
 * `GOOGLE_API_KEY`.
 */
fun main() = runSampleOrders(OrderProcessingInfixWorkflow.create(Gemini(name = MODEL_NAME)))
