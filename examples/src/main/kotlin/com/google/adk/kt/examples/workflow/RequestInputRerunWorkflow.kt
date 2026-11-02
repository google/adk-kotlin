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
import com.google.adk.kt.workflow.RequestInput
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.requestInput
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `request_input_rerun` workflow sample: the `request_input` sample with one
 * review node instead of two. The node sets `rerunOnResume = true`, so when the human answers, it
 * runs again and reads the answer from its context.
 */
object RequestInputRerunWorkflow {

  /** Builds the workflow, with the drafting agent on [model]. */
  fun create(model: Model): Workflow {
    val draftEmail = draftEmailAgent(model)

    return workflow("request_input_rerun") {
      chain(Start, processInput, draftEmail, humanReview).route {
        on("revise") then draftEmail
        on("approved") then sendEmail
        on("rejected") then rejectEmail
      }
    }
  }

  /**
   * Stores the complaint in state, where the agent's instruction reads it, with no feedback yet.
   */
  private val processInput =
    node<String, Unit>("process_input") { context, complaint ->
      // START hands the first node the user's message, which a String input receives as text.
      context.updateState("complaint", complaint)
      context.updateState("feedback", "")
    }

  /**
   * Asks a human to review the draft on its first run, and routes on the answer when it runs again.
   */
  private val humanReview =
    node<String, Unit>("human_review", rerunOnResume = true) { context, draft ->
      // The id changes per turn and loop round, and stays the same on a rerun.
      val interruptId = "human_review_${context.invocationId}_${context.runId}"
      // An empty answer asks again, as in adk-python.
      val answer =
        context.resumeInputs[interruptId]?.takeUnless { it == "" }
          ?: requestInput(
            interruptId = interruptId,
            message =
              "Please review the following draft email and provide 'approve', 'reject', or" +
                " feedback to revise.\n\n---\n$draft\n---",
          )
      val route =
        when (answer) {
          "reject" -> "rejected"
          "approve" -> "approved"
          else -> {
            context.updateState("feedback", answer)
            "revise"
          }
        }
      context.routes = listOf(Route.Tag(route))
    }

  private val rejectEmail =
    node<Any?, Unit>("reject_email") { _, _ ->
      emit(Content.fromText(Role.MODEL, "Draft rejected."))
    }

  private val sendEmail =
    node<Any?, Unit>("send_email") { _, _ ->
      emit(Content.fromText(Role.MODEL, "Draft approved and sent successfully."))
    }
}

/**
 * Runs the workflow on one complaint, asks for a revision, and approves the revised draft, printing
 * each draft and question. Needs `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    RequestInputRerunWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "request_input_rerun", rootNode = workflow))
  var interruptId = runner.send(Content.fromText(Role.USER, "The delivery was a week late"))
  // Each answer reruns the review node, which asks again after a revision.
  for (answer in listOf("Make it shorter and offer a discount on the next order.", "approve")) {
    val id = interruptId ?: break
    interruptId = runner.send(reviewAnswer(id, answer))
  }
}

/**
 * Sends [message], prints the messages and the question the workflow sends back, and returns the id
 * of the review request the turn ends with, or null if it ends without one.
 */
private suspend fun InMemoryRunner.send(message: Content): String? {
  var interruptId: String? = null
  runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
    for (call in event.functionCalls()) {
      if (call.name != RequestInput.FUNCTION_CALL_NAME) continue
      println("[${event.author}] asks: ${call.args["message"]}")
      interruptId = call.id
    }
  }
  return interruptId
}

/** The reviewer's [answer] to the review request [interruptId]. */
private fun reviewAnswer(interruptId: String, answer: String): Content =
  Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              id = interruptId,
              name = RequestInput.FUNCTION_CALL_NAME,
              response = mapOf("result" to answer),
            )
        )
      ),
  )

private fun draftEmailAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "draft_email",
    model = model,
    instruction =
      Instruction(
        """
        Please write a polite, helpful response email to the following customer complaint:
        "{complaint}"

        If there is any feedback from the manager to revise the draft, please incorporate it:
        "{feedback?}"
        """
          .trimIndent()
      ),
    outputKey = "draft",
  )
