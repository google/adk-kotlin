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

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
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
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `agent_in_workflow` workflow sample, a medical lab intake. A task agent
 * chats with the patient until it has their name and phone number, and a function node checks them
 * and sends the patient back to the agent when it finds no records. A single-turn agent then looks
 * up the patient's orders with a tool that waits for the user to approve the call.
 */
object AgentInWorkflow {

  /** Builds the workflow, with both agents on [model]. */
  fun create(model: Model): Workflow {
    val intakeAgent = intakeAgent(model)

    return workflow("task_in_workflow") {
      chain(Start, intakeAgent, checkIdentity).route {
        on("retry") then intakeAgent
        otherwise(generateInstructionAgent(model))
      }
    }
  }

  /** The patient's identity, which the intake agent's task outputs. */
  @Serializable
  private data class PatientIdentity(
    val name: String,
    @SerialName("phone_number") val phoneNumber: String,
  )

  /** Mocks a records check, and routes back to the intake agent unless the patient is Jane Doe. */
  private val checkIdentity =
    node<PatientIdentity, Unit>("check_identity") { context, patient ->
      if (patient.name.equals("Jane Doe", ignoreCase = true)) {
        // No route matches, so `otherwise` takes the patient on to their orders.
        emit(Content.fromText(Role.MODEL, "Hello ${patient.name}! Let me look up your orders."))
      } else {
        val reply = "Could not find matching records for ${patient.name}. Let's try again."
        emit(Content.fromText(Role.MODEL, reply))
        context.routes = listOf(Route.Tag("retry"))
      }
    }
}

/**
 * Runs the workflow with a patient who first gives an unknown name, then Jane Doe's details, and
 * approves the orders lookup, printing each message and tool call. Needs `GOOGLE_CLOUD_PROJECT` and
 * Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    AgentInWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "task_in_workflow", rootNode = workflow))
  // A retried intake agent starts a new conversation, so Jane Doe gives both details at once.
  val replies = ArrayDeque(listOf("My name is John Doe", "555-1234", "Jane Doe, 555-1234"))
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

private fun intakeAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "intake_agent",
    model = model,
    // Holds a conversation until its finish_task call matches the output schema.
    mode = AgentMode.TASK,
    outputSchema = PATIENT_IDENTITY_SCHEMA,
    instruction =
      Instruction(
        """
        You are a medical lab intake assistant. Your job is to chat with
        the user to get their full name and phone number. Do not make up
        information. Once you have both, finish your task.
        If identity check failed, ask for another name.
        """
          .trimIndent()
      ),
  )

private fun generateInstructionAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generate_instruction",
    model = model,
    // KSP generates FindOrdersTool from the @Tool function LabOrders.findOrders.
    tools = listOf(FindOrdersTool(LabOrders())),
    instruction =
      Instruction(
        """
        Use the find_orders tool to get the patient's orders.
        List the orders found, and then generate a concise instruction about how to prepare
        based on those orders.
        """
          .trimIndent()
      ),
  )

/** The schema of the patient's identity, with the fields of adk-python's `PatientIdentity`. */
private val PATIENT_IDENTITY_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "name" to Schema(type = Type.STRING, description = "The patient's full name."),
        "phone_number" to Schema(type = Type.STRING, description = "The patient's phone number."),
      ),
    required = listOf("name", "phone_number"),
  )

/** A mock lab system whose orders lookup waits for the user to approve each call. */
class LabOrders {

  /** Finds orders for the patient. */
  @Tool(name = "find_orders", requireConfirmation = true)
  fun findOrders(): List<String> = listOf("CBC (Complete Blood Count)", "Lipid Panel")
}
