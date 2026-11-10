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
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `dynamic_nodes` workflow sample: the `loop` sample rewritten as one node
 * that runs the headline agents with `runNode` in a loop, instead of routing between them with
 * graph edges.
 */
object DynamicNodesWorkflow {

  /** Builds the workflow, with both agents on [model]. */
  fun create(model: Model): Workflow =
    workflow("dynamic_nodes") {
      Start then orchestrate(generateHeadlineAgent(model), evaluateHeadlineAgent(model))
    }

  /**
   * Returns the node that runs [generateHeadline] and then [evaluateHeadline] on its headline until
   * the grade is `tech-related`, and outputs that headline. As in adk-python, nothing limits the
   * number of rounds.
   */
  private fun orchestrate(generateHeadline: Node, evaluateHeadline: Node): Node =
    // A node that calls runNode must rerun on resume, to collect a child's output after a pause.
    node<String, String>("orchestrate", rerunOnResume = true) { context, topic ->
      // Sends the topic as an event first, so the session holds it when the agents read state.
      emit(Event(actions = EventActions(stateDelta = mutableMapOf("topic" to topic))))
      var headline: String
      do {
        headline = context.runNode(generateHeadline) as String
        // The grader's object output schema makes its reply arrive as a map.
        val feedback = context.runNode(evaluateHeadline, nodeInput = headline) as Map<*, *>
      } while (feedback["grade"] != "tech-related")
      headline
    }
}

/**
 * Runs the workflow on one topic and prints each headline, each grade, and the result. Needs
 * `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    DynamicNodesWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "dynamic_nodes", rootNode = workflow))
  val message = Content.fromText(Role.USER, "flower")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val path = event.nodeInfo?.path
    val text = event.contentText(" ")
    if (text.isNotBlank()) {
      println("[$path] $text")
    } else {
      event.output?.let { println("[$path] $it") }
    }
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

/** The grader's answer, with the fields of adk-python's `Feedback` model. */
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
