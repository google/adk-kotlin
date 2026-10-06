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
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * A port of adk-python's `node_output` workflow sample: a function node's return value becomes its
 * output, a returned [Event] passes through as is, and an agent with an output schema answers with
 * structured data. An [LlmAgent] node answers the conversation rather than its node input and emits
 * no output, so the last node reads the agent's answer from state, where Python passes it as input.
 */
object NodeOutputWorkflow {

  /** Builds the workflow, with the agent on [model]. */
  fun create(model: Model): Workflow {
    val generateStructuredOutput = topicAgent(model)

    return workflow("node_output") {
      chain(
        Start,
        generateStringOutput,
        generateEventOutput,
        generateStructuredOutput,
        consumeStructuredOutput,
      )
    }
  }

  /** Returns a string, which becomes the node's output. */
  private val generateStringOutput =
    node<Content, String>("generate_string_output") { _, message ->
      // START hands the first node the user's message as Content.
      "Processed input: ${message.text()}"
    }

  /** Returns an [Event], which passes through as is and could also carry routes or state. */
  private val generateEventOutput =
    node<String, Event>("generate_event_output") { _, text ->
      Event(output = "Event wrapped output: $text")
    }

  /** Formats the agent's structured answer, which the agent left in state. */
  private val consumeStructuredOutput =
    node<Any?, String>("consume_structured_output") { context, _ ->
      val topic = (context.state[TOPIC_KEY] as? Map<*, *>).orEmpty()
      """
      |Received structured output!
      |Title: ${topic["title"]}
      |Description: ${topic["description"]}
      |Category: ${topic["category"]}
      """
        .trimMargin()
    }

  private fun topicAgent(model: Model): LlmAgent =
    LlmAgent(
      name = "generate_structured_output",
      model = model,
      instruction = Instruction("Generate a creative topic based on the user's input."),
      outputSchema = TOPIC_SCHEMA,
      outputKey = TOPIC_KEY,
    )

  private const val TOPIC_KEY = "topic_details"

  /** The agent's structured answer, with the fields of adk-python's `TopicDetails` model. */
  private val TOPIC_SCHEMA =
    Schema(
      type = Type.OBJECT,
      properties =
        mapOf(
          "title" to Schema(type = Type.STRING, description = "The title of the generated topic."),
          "description" to
            Schema(type = Type.STRING, description = "A short description of the topic."),
          "category" to Schema(type = Type.STRING, description = "The broad category of the topic."),
        ),
      required = listOf("title", "description", "category"),
    )
}

/** Runs the workflow on one topic and prints what each node produced. Needs `GOOGLE_API_KEY`. */
fun main() = runBlocking {
  val workflow = NodeOutputWorkflow.create(Gemini(name = MODEL_NAME))
  val runner = InMemoryRunner(app = App(appName = "node_output", rootNode = workflow))
  val message = Content.fromText(Role.USER, "cyberpunk future")
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
