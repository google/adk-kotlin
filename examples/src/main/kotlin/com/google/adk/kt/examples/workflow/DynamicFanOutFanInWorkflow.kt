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
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `dynamic_fan_out_fan_in` workflow sample: one node splits the user's
 * comma-separated topics, runs a headline agent on each topic concurrently with `runNode`, and
 * collects the headlines into one table. The number of runs comes from the input, not the graph.
 */
object DynamicFanOutFanInWorkflow {

  /** Builds the workflow, with the agent on [model]. */
  fun create(model: Model): Workflow =
    workflow("dynamic_fan_out_fan_in") { Start then orchestrator(generatorAgent(model)) }

  /** Returns the node that runs [generator] once per topic and tabulates the headlines. */
  // runNode takes concurrent calls on one context, as long as each run has its own ID.
  @Suppress("UnsafeCoroutineCrossing")
  private fun orchestrator(generator: Node): Node =
    // A node that calls runNode must rerun on resume, to collect a child's output after a pause.
    node<String, Unit>("orchestrator", rerunOnResume = true) { context, input ->
      val topics = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      emit(Content.fromText(Role.MODEL, "Processing ${topics.size} topics in parallel."))
      // Fans out: each run gets its own sub-branch, so it sees only its own topic.
      val headlines = coroutineScope {
        topics
          .mapIndexed { index, topic ->
            // Concurrent runs need explicit run IDs, which must contain a non-digit.
            val runId = "topic_${index + 1}"
            async { context.runNode(generator, topic, runId = runId, useSubBranch = true) }
          }
          .awaitAll()
      }
      // Fans in: one table row per topic.
      val table = buildString {
        append("### Aggregated Headlines\n\n| Topic | Headline |\n| :--- | :--- |\n")
        for ((topic, headline) in topics.zip(headlines)) {
          // A run with no output leaves its cell empty.
          append("| $topic | ${headline ?: ""} |\n")
        }
      }
      emit(Content.fromText(Role.MODEL, table))
    }
}

/**
 * Runs the workflow on three topics and prints the table. Needs `GOOGLE_CLOUD_PROJECT` and
 * Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    DynamicFanOutFanInWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "dynamic_fan_out_fan_in", rootNode = workflow))
  val message = Content.fromText(Role.USER, "AI, Cloud Computing, Quantum Computing")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
  }
}

private fun generatorAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generator",
    model = model,
    instruction =
      Instruction("Write a catchy one-line headline about the topic provided in the user message."),
  )
