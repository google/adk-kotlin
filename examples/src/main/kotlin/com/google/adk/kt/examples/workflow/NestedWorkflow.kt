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
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * A port of adk-python's `nested_workflow` sample: for the year the user names, a nested workflow
 * finds a famous person born that year and writes their bio while an agent describes an event from
 * that year, and a [JoinNode] waits for both branches before one message combines them.
 */
object NestedWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow {
    val findName = findNameAgent(model)
    val generateBio = generateBioAgent(model)
    // A Workflow is a Node, so a whole workflow can be one branch of another.
    val findFamousPerson = workflow("find_famous_person") { chain(Start, findName, generateBio) }
    val findHistoricalEvent = findHistoricalEventAgent(model)
    val join = JoinNode("join_for_aggregation")

    return workflow("nested_workflow") {
      Start.then(processInput)
        .then(nodes(findFamousPerson, findHistoricalEvent))
        .joinTo(join)
        .then(aggregateResults)
    }
  }

  /** Stores the year from the user's message in state, and fails the run if there is none. */
  private val processInput =
    node<Content, Unit>("process_input") { context, message ->
      // START hands the first node the user's message as Content.
      val year = Regex("""\b\d{4}\b""").find(message.text())?.value
      if (year == null) {
        val reply = "Please provide a valid 4-digit year (e.g., 1955)."
        emit(Event(content = Content.fromText(Role.MODEL, reply)))
        throw IllegalArgumentException("Invalid year format.")
      }
      context.updateState("year", year)
    }

  /** Combines both branches' answers, which [LlmAgent] nodes leave in state rather than output. */
  private val aggregateResults =
    node<Any?, Content>("aggregate_results") { context, _ ->
      val message =
        "# Year: ${context.state["year"]}\n\n" +
          "## Famous Person Bio:\n\n${context.state["bio"]}\n\n" +
          "## Historical Event:\n\n${context.state["historical_event"]}"
      Content.fromText(Role.MODEL, message)
    }
}

/** Runs the workflow on one year and prints each reply. Needs `GOOGLE_API_KEY`. */
fun main() = runBlocking {
  val workflow = NestedWorkflow.create(Gemini(name = MODEL_NAME))
  val runner = InMemoryRunner(app = App(appName = "nested_workflow", rootNode = workflow))
  val message = Content.fromText(Role.USER, "1984")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
  }
}

private fun findNameAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "find_name",
    model = model,
    instruction =
      Instruction(
        """
        Find the name of one famous person who was born in this year: {year}.
        Return ONLY their name, nothing else.
        """
          .trimIndent()
      ),
  )

private fun generateBioAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generate_bio",
    model = model,
    instruction =
      Instruction("Write a short, engaging 3-sentence biography for the specified person."),
    outputKey = "bio",
  )

private fun findHistoricalEventAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "find_historical_event",
    model = model,
    instruction =
      Instruction(
        """
        Describe one highly significant historical event that occurred in this year: {year}.
        Keep the description to 2 sentences.
        """
          .trimIndent()
      ),
    outputKey = "historical_event",
  )
