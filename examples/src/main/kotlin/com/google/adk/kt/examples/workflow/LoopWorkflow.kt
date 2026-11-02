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
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `loop` workflow sample: an agent writes a headline about the user's topic,
 * a second agent grades whether it is about technology, and the workflow loops back with the
 * grader's feedback until it is.
 */
object LoopWorkflow {

  /** Builds the workflow, with both agents on [model]. */
  fun create(model: Model): Workflow {
    val generateHeadline = generateHeadlineAgent(model)

    return workflow("loop") {
      chain(Start, processInput, generateHeadline, evaluateHeadlineAgent(model), routeHeadline)
        .route { on("unrelated") then generateHeadline }
    }
  }

  /** Stores the user's topic in state, where the headline agent's instruction reads it. */
  private val processInput =
    node<String, Unit>("process_input") { context, topic ->
      // START hands the first node the user's message, which a String input receives as text.
      context.updateState("topic", topic)
    }

  /** Routes on the grade; no edge matches `tech-related`, so that grade ends the loop. */
  private val routeHeadline =
    node<Feedback, Unit>("route_headline") { context, feedback ->
      context.routes = listOf(Route.Tag(feedback.grade))
    }
}

/**
 * Runs the workflow on one topic and prints each headline and grade. Needs `GOOGLE_CLOUD_PROJECT`
 * and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    LoopWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "loop", rootNode = workflow))
  val message = Content.fromText(Role.USER, "flower")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
  }
}

private fun generateHeadlineAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generate_headline",
    model = model,
    instruction =
      Instruction(
        """
        Write a headline about the topic "{topic}".
        If feedback is provided, take it into account.
        The feedback: {feedback?}
        """
          .trimIndent()
      ),
  )

private fun evaluateHeadlineAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "evaluate_headline",
    model = model,
    instruction =
      Instruction("Grade whether the headline is related to technology or software engineering."),
    outputSchema = FEEDBACK_SCHEMA,
    // Stores the grade and feedback for the headline agent's next attempt.
    outputKey = "feedback",
  )

/** The grader's structured answer, with the fields of adk-python's `Feedback` model. */
@Serializable private data class Feedback(val grade: String, val feedback: String)

/** The schema of [Feedback], which the grader's answer must match. */
private val FEEDBACK_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "grade" to
          Schema(
            type = Type.STRING,
            enum = listOf("tech-related", "unrelated"),
            description = "Decide if the headline is related to technology or software engineering.",
          ),
        "feedback" to
          Schema(
            type = Type.STRING,
            description =
              "If the headline is unrelated to technology, provide feedback on how to make it" +
                " more tech-focused.",
          ),
      ),
    required = listOf("grade", "feedback"),
  )
