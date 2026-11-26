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

package com.google.adk.kt.examples.live

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.serialization.Serializable

/**
 * A workflow of live voice agents that runs as one call: the first confirms who is calling, the
 * second checks their date of birth with a tool, and the third tells them about their appointment.
 * Each agent is a task that talks until it finishes; the first two hand a typed result to the next.
 * Only a caller whose date of birth matched reaches the third agent; any other caller is told they
 * could not be verified, and the call ends.
 */
object LiveWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow =
    workflow(
      "workflow",
      description =
        "A Workflow of live voice agents: confirm the caller, verify their date of birth, then" +
          " share the call details.",
    ) {
      chain(Start, greeterAgent(model), dobVerifierAgent(model), routeOnVerification()).route {
        on("verified") then goalsAgent(model)
        on("unverified") then closingAgent(model)
      }
    }
}

/**
 * Serves the workflow over the ADK web API, so a browser can hold the call through the bundled dev
 * UI's microphone button: typed text goes to `/run_sse`, which a live-only model rejects.
 */
private class LiveWorkflowCommand : CliktCommand(name = "live-workflow") {

  override fun help(context: Context): String =
    "Serves the live workflow and the bundled dev UI on loopback, with no authentication."

  private val port: Int by
    option("--port", help = "Port to listen on.").int().default(AdkServerConfig.DEFAULT_PORT)

  override fun run() {
    // Live models are served from a region, not the global endpoint other examples use.
    val model =
      Gemini(
        name = "gemini-3.8-live",
        vertexCredentials = VertexCredentials(location = "us-central1"),
      )
    val app = App(appName = "workflow", rootNode = LiveWorkflow.create(model))
    println("Serving ${app.appName} at http://localhost:$port/dev-ui")
    AdkDevServer(AdkServerConfig.inMemory(app, port)).start(wait = true)
  }
}

fun main(args: Array<String>) {
  LiveWorkflowCommand().main(args)
}

/** A mock patient record system for the date-of-birth check. */
class PatientRecords {

  /** Validates a confirmed date of birth against the patient's records (mocked). */
  @Tool(name = "validate_date_of_birth")
  fun validateDateOfBirth(
    context: ToolContext,
    @Param("The patient's date of birth in YYYY-MM-DD format.") dob: String,
  ): Map<String, Boolean> {
    val match = dob == "1985-07-12" // The demo persona's record.
    context.updateState("dob_verified", match)
    return mapOf("match" to match)
  }
}

private fun greeterAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "greeter_agent",
    model = model,
    description = "Greets on a recorded line and confirms the right person.",
    // Talks until its finish_task call matches the output schema, then hands that output on.
    mode = AgentMode.TASK,
    outputSchema = resultSchema("The confirmed name of the person on the line."),
    instruction =
      Instruction(
        """
        You are Sam, a friendly care-team assistant.
        Greet the caller and confirm you are speaking with John Doe before
        sharing anything else. Ask one question per turn. Once the name is
        confirmed, briefly acknowledge it and complete your task, passing the
        confirmed name as 'result'.
        """
          .trimIndent()
      ),
  )

private fun dobVerifierAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "dob_verifier_agent",
    model = model,
    description = "Captures and validates the date of birth before serving.",
    mode = AgentMode.TASK,
    outputSchema = resultSchema("Identity verification result, e.g. \"verified\"."),
    tools = PatientRecords().generatedTools(),
    instruction =
      Instruction(
        """
        Verify the caller's identity by date of birth. Ask for their date of
        birth, read it back to confirm, then validate it with
        `validate_date_of_birth` using YYYY-MM-DD format. Once it matches, let
        the caller know their identity is verified and complete your task with
        "verified". If it still does not match after two tries, complete your
        task with "unverified". Ask one question per turn.
        """
          .trimIndent()
      ),
  )

/**
 * Sends the caller on to [goalsAgent] only when the verifier reported "verified" and the
 * date-of-birth tool recorded a match, and to [closingAgent] otherwise, handing the next agent that
 * outcome as its opening turn.
 */
private fun routeOnVerification(): Node =
  node<TaskResult, TaskResult>("route_on_verification") { context, verification ->
    val verified = verification.result == "verified" && context.state["dob_verified"] == true
    val result = if (verified) "verified" else "unverified"
    context.routes = listOf(Route.Tag(result))
    TaskResult(result)
  }

private fun goalsAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "goals_agent",
    model = model,
    description = "Delivers the call goals once identity is verified.",
    mode = AgentMode.TASK,
    instruction =
      Instruction(
        """
        Identity is already verified. As soon as it is your turn, proactively
        tell the caller about their upcoming appointment on Tuesday, June 16th at
        3 PM with Dr. Example, and ask if they have any questions for the visit.
        Do not wait to be asked. Answer any questions briefly, ask if there is
        anything else, then wrap up warmly, end with "Goodbye.", and complete
        your task.
        """
          .trimIndent()
      ),
  )

private fun closingAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "closing_agent",
    model = model,
    description = "Ends the call when identity cannot be verified.",
    mode = AgentMode.TASK,
    instruction =
      Instruction(
        """
        The caller's identity could not be verified. As soon as it is your turn,
        tell the caller you could not verify their identity, so you cannot share
        any details on this call, and suggest they call the clinic directly. End
        with "Goodbye." and complete your task.
        """
          .trimIndent()
      ),
  )

/** A task's output, as [resultSchema] describes it. */
@Serializable private data class TaskResult(val result: String = "")

/** A task's output: one optional string, `result`, described by [description]. */
private fun resultSchema(description: String): Schema =
  Schema(
    type = Type.OBJECT,
    properties = mapOf("result" to Schema(type = Type.STRING, description = description)),
  )
