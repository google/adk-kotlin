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
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.RequestInput
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.requestInput
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `request_input_advanced` workflow sample: an agent extracts a time-off
 * request from the user's message, a node approves one day on its own and otherwise asks a manager,
 * sending the request as the payload and asking for an answer that matches a schema, and the last
 * node reports the decision.
 */
object RequestInputAdvancedWorkflow {

  /** Builds the workflow, with the agent on [model]. */
  fun create(model: Model): Workflow =
    workflow("request_input_advanced") {
      chain(Start, processRequestAgent(model), evaluateRequest, processDecision)
    }

  /** Approves a request of at most one day, and asks a manager to decide on anything longer. */
  private val evaluateRequest =
    node<TimeOffRequest, TimeOffDecision>("evaluate_request") { _, request ->
      if (request.days <= 1) {
        TimeOffDecision(approved = true)
      } else {
        // The manager's answer, checked against the schema, becomes this node's output.
        requestInput(
          interruptId = "manager_approval",
          message = "Please review this time off request.",
          payload = request,
          responseSchema = TIME_OFF_DECISION_SCHEMA,
        )
      }
    }

  /** Reports the decision, reading the requested day count from the `request` state key. */
  private val processDecision =
    node<TimeOffDecision, Unit>("process_decision") { context, decision ->
      val requested = ((context.state["request"] as Map<*, *>)["days"] as Number).toInt()
      val message =
        if (decision.approved) {
          val approved = decision.approvedDays ?: requested
          "Time Off Approved! $approved out of $requested days granted."
        } else {
          "Time Off Denied."
        }
      emit(Content.fromText(Role.MODEL, message))
    }
}

/**
 * Runs the workflow on a one-day request, then on a five-day request that a manager approves for
 * four days, and prints each decision. Needs `GOOGLE_CLOUD_PROJECT` and Application Default
 * Credentials.
 */
fun main() = runBlocking {
  val workflow =
    RequestInputAdvancedWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "request_input_advanced", rootNode = workflow))
  // A request of one day is approved without asking anyone.
  runner.send(
    "sick_day",
    Content.fromText(Role.USER, "I'm feeling under the weather and need to take today off."),
  )
  // A longer request waits for the manager, whose answer must match the response schema.
  runner.send(
    "vacation",
    Content.fromText(
      Role.USER,
      "Taking my family to Disney World, I'll be out for 5 days next week.",
    ),
  )
  runner.send(
    "vacation",
    managerAnswer("manager_approval", mapOf("approved" to true, "approved_days" to 4)),
  )
}

/** The manager's [decision] on the request [interruptId]. */
private fun managerAnswer(interruptId: String, decision: Map<String, Any>): Content {
  val response =
    FunctionResponse(
      id = interruptId,
      name = RequestInput.FUNCTION_CALL_NAME,
      response = mapOf("result" to decision),
    )
  return Content(role = Role.USER, parts = listOf(Part(functionResponse = response)))
}

/** Sends [message] in session [sessionId] and prints the messages and questions it leads to. */
private suspend fun InMemoryRunner.send(sessionId: String, message: Content) {
  runAsync(userId = "user", sessionId = sessionId, newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
    for (call in event.functionCalls()) {
      if (call.name != RequestInput.FUNCTION_CALL_NAME) continue
      println("[${event.author}] asks: ${call.args["message"]} ${call.args["payload"]}")
    }
  }
}

private fun processRequestAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "process_request",
    model = model,
    instruction =
      Instruction(
        "Extract the number of days and the reason from the user's natural language time off" +
          " request."
      ),
    outputSchema = TIME_OFF_REQUEST_SCHEMA,
    outputKey = "request",
  )

/** The agent's structured request, with the fields of adk-python's `TimeOffRequest` model. */
@Serializable private data class TimeOffRequest(val days: Int, val reason: String)

/** The schema of [TimeOffRequest], which the agent's answer must match. */
private val TIME_OFF_REQUEST_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "days" to Schema(type = Type.INTEGER, description = "Number of days requested."),
        "reason" to Schema(type = Type.STRING, description = "Reason for the time off."),
      ),
    required = listOf("days", "reason"),
  )

/** The decision, with the fields of adk-python's `TimeOffDecision` model. */
@Serializable
private data class TimeOffDecision(
  val approved: Boolean,
  @SerialName("approved_days") val approvedDays: Int? = null,
)

/** The schema of [TimeOffDecision], which the manager's answer must match. */
private val TIME_OFF_DECISION_SCHEMA =
  Schema(
    type = Type.OBJECT,
    description = "The structured response we expect back from the human manager.",
    properties =
      mapOf(
        "approved" to
          Schema(type = Type.BOOLEAN, description = "Whether the time off is approved."),
        "approved_days" to
          Schema(type = Type.INTEGER, nullable = true, description = "Number of days approved."),
      ),
    required = listOf("approved"),
  )
