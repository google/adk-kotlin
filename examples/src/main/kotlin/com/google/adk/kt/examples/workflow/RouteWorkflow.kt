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
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `route` workflow sample: an agent classifies the user's input as a
 * question, a statement, or something else, and a function node routes it to the agent or node that
 * handles that category.
 */
object RouteWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow =
    workflow("route") {
      chain(Start, processInput, classifyInputAgent(model), routeOnCategory).route {
        on("question") then answerQuestionAgent(model)
        on("statement") then commentOnStatementAgent(model)
        on("other") then handleOther
      }
    }

  /** Stores the user's input in state, where the agents' instructions read it. */
  private val processInput =
    node<String, Unit>("process_input") { context, input ->
      // START hands the first node the user's message, which a String input receives as text.
      context.updateState("input", input)
    }

  /** Routes on the classifier's category, decoded from the node input into [InputCategory]. */
  private val routeOnCategory =
    node<InputCategory, Unit>("route_on_category") { context, category ->
      context.routes = listOf(Route.Tag(category.category))
    }

  private val handleOther =
    node<Any?, Unit>("handle_other") { _, _ ->
      val reply = "Sorry I can only answer questions or comment on statements."
      emit(Content.fromText(Role.MODEL, reply))
    }
}

/**
 * Runs the workflow on a question, a statement, and a request, each in its own session, and prints
 * each reply. Needs `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    RouteWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "route", rootNode = workflow))
  val inputs =
    listOf(
      "What is the capital of France?",
      "The weather is very nice today.",
      "Translate bonjour to english",
    )
  for ((index, input) in inputs.withIndex()) {
    println("\n=== $input")
    val message = Content.fromText(Role.USER, input)
    runner.runAsync(userId = "user", sessionId = "session_$index", newMessage = message).collect {
      event ->
      val text = event.contentText(" ")
      if (text.isNotBlank()) println("[${event.author}] $text")
    }
  }
}

private fun classifyInputAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "classify_input",
    model = model,
    instruction = Instruction("Based on this input, decide which category it belongs to: {input}"),
    outputSchema = INPUT_CATEGORY_SCHEMA,
    outputKey = "category",
  )

private fun answerQuestionAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "answer_question",
    model = model,
    instruction = Instruction("Answer the question: {input}"),
  )

private fun commentOnStatementAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "comment_on_statement",
    model = model,
    instruction = Instruction("Comment on the statement: {input}"),
  )

/** The classifier's structured answer, with the field of adk-python's `InputCategory` model. */
@Serializable private data class InputCategory(val category: String)

/** The schema of [InputCategory], which the classifier's answer must match. */
private val INPUT_CATEGORY_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "category" to Schema(type = Type.STRING, enum = listOf("question", "statement", "other"))
      ),
    required = listOf("category"),
  )
