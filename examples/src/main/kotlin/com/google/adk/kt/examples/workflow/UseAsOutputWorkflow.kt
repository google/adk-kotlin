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
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `use_as_output` workflow sample: a node runs an agent with `runNode` and
 * `useAsOutput = true`, so the agent's summary becomes the node's own output and flows on to the
 * next node.
 */
object UseAsOutputWorkflow {

  /** Builds the workflow, with the agent on [model]. */
  fun create(model: Model): Workflow =
    workflow("use_as_output") { chain(Start, orchestrate(summarizerAgent(model)), finalize) }

  /** Returns the node that runs [summarizer] on its input and makes the summary its output. */
  private fun orchestrate(summarizer: Node): Node =
    // A node that calls runNode must rerun on resume, to collect a child's output after a pause.
    node<String, String>("orchestrate", rerunOnResume = true) { context, text ->
      // The summary is already this node's output, so returning it emits no second output.
      context.runNode(summarizer, nodeInput = text, useAsOutput = true) as String
    }

  /** Receives the summary as its input, as if `orchestrate` had produced it. */
  private val finalize = node<String, String>("finalize") { _, summary -> "final: $summary" }
}

/**
 * Runs the workflow on one sentence and prints what each node produced. Needs
 * `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    UseAsOutputWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "use_as_output", rootNode = workflow))
  val text =
    "The quick brown fox jumped over the lazy dog near the riverbank on a warm summer afternoon"
  val message = Content.fromText(Role.USER, text)
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val path = event.nodeInfo?.path
    val reply = event.contentText(" ")
    if (reply.isNotBlank()) {
      println("[$path] $reply")
    } else {
      event.output?.let { println("[$path] $it") }
    }
  }
}

private fun summarizerAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "summarizer",
    model = model,
    instruction = Instruction("Summarize the following text in one sentence."),
  )
