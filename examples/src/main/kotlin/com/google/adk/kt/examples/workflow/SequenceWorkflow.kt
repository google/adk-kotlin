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
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * A port of adk-python's `sequence` workflow sample: one agent names a random fruit, and the next
 * agent receives that answer as its input and tells a health benefit of the fruit.
 */
object SequenceWorkflow {

  /** Builds the workflow, with both agents on [model]. */
  fun create(model: Model): Workflow =
    workflow("sequence") { chain(Start, generateFruitAgent(model), generateBenefitAgent(model)) }
}

/**
 * Runs the workflow once and prints each agent's answer. Needs `GOOGLE_CLOUD_PROJECT` and
 * Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    SequenceWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "sequence", rootNode = workflow))
  // The workflow needs no input, so any message starts it.
  val message = Content.fromText(Role.USER, "go")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
  }
}

private fun generateFruitAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generate_fruit_agent",
    model = model,
    instruction =
      Instruction(
        """
        Return the name of a random fruit.
        Return only the name, nothing else.
        """
          .trimIndent()
      ),
  )

private fun generateBenefitAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "generate_benefit_agent",
    model = model,
    instruction = Instruction("Tell me a health benefit about the specified fruit."),
  )
