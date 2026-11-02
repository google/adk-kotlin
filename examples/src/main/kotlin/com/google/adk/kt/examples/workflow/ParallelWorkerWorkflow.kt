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
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.parallelNode
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `parallel_worker` workflow sample: an agent lists three topics related to
 * the user's topic, a function node uppercases each one, an agent explains how each relates to the
 * original topic, and a last node joins the explanations. The two middle nodes are parallel
 * workers, which run once per item of their list input, concurrently.
 */
object ParallelWorkerWorkflow {

  /** Builds the workflow, with both agents on [model]. */
  fun create(model: Model): Workflow =
    workflow("parallel_worker") {
      chain(
        Start,
        processInput,
        findRelatedTopicsAgent(model),
        makeUpperCase,
        // parallelNode runs any node, an agent included, as a parallel worker.
        parallelNode(explainTopicAgent(model)),
        aggregate,
      )
    }

  /** Stores the user's topic in state, where both agents' instructions read it. */
  private val processInput =
    node<String, Unit>("process_input") { context, topic ->
      // START hands the first node the user's message, which a String input receives as text.
      context.updateState("topic", topic)
    }

  /** Uppercases each topic of its list input, in one concurrent run per topic. */
  private val makeUpperCase =
    parallelNode<String, String>("make_upper_case") { _, topic -> topic.uppercase() }

  /** Joins the explanations, which the parallel agent outputs as a list in input order. */
  private val aggregate =
    node<List<TopicExplanation>, Unit>("aggregate") { _, explanations ->
      val message = explanations.joinToString("\n\n---\n\n") { "${it.topic}: ${it.explanation}" }
      emit(Content.fromText(Role.MODEL, message))
    }
}

/**
 * Runs the workflow on one topic and prints what each node produced. Needs `GOOGLE_CLOUD_PROJECT`
 * and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    ParallelWorkerWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "parallel_worker", rootNode = workflow))
  val message = Content.fromText(Role.USER, "machine learning")
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

private fun findRelatedTopicsAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "find_related_topics",
    model = model,
    instruction =
      Instruction("Given the specific topic \"{topic}\", generate a list of 3 related topics."),
    // A workflow node takes a list schema, which works here because the agent has no tools.
    outputSchema = Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)),
  )

private fun explainTopicAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "explain_topic",
    model = model,
    instruction =
      Instruction("Explain how the following topic relates to the original topic: \"{topic}\"."),
    outputSchema = TOPIC_EXPLANATION_SCHEMA,
  )

/** An explanation from the agent, with the fields of adk-python's `TopicExplanation` model. */
@Serializable private data class TopicExplanation(val topic: String, val explanation: String)

/** The schema of [TopicExplanation], which each run of the agent must match. */
private val TOPIC_EXPLANATION_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf("topic" to Schema(type = Type.STRING), "explanation" to Schema(type = Type.STRING)),
    required = listOf("topic", "explanation"),
  )
