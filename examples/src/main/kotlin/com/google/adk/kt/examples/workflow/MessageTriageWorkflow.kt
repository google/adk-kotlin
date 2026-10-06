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

import com.google.adk.kt.agents.Context
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
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * A port of the message-triage graph from the ADK docs: an agent labels a message as a bug, a
 * customer-support request, a logistics question, or several of these, and a custom node emits one
 * route per label so each matching handler runs.
 */
object MessageTriageWorkflow {

  /** Builds the workflow, with the classifier on [model]. */
  fun create(model: Model): Workflow {
    val processMessage = processMessageAgent(model)

    return workflow("routing_workflow") {
      chain(Start, processMessage, MessageCategories()).route {
        on("BUG") then Responder("response_1_bug", "Handling bug...")
        on("CUSTOMER_SUPPORT") then Responder("response_2_support", "Handling customer support...")
        on("LOGISTICS") then Responder("response_3_logistics", "Handling logistics...")
      }
    }
  }
}

/** Runs the workflow on one message and prints each reply. Needs `GOOGLE_API_KEY`. */
fun main() = runBlocking {
  val workflow = MessageTriageWorkflow.create(Gemini(name = MODEL_NAME))
  val runner = InMemoryRunner(app = App(appName = "routing_workflow", rootNode = workflow))
  val message =
    Content.fromText(Role.USER, "The app crashes when I open my order, and my parcel is late.")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = message).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.author}] $text")
  }
}

private fun processMessageAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "process_message",
    model = model,
    instruction =
      Instruction(
        """
        Classify user message into either "BUG", "CUSTOMER_SUPPORT",
        or "LOGISTICS". If you think a message applies to more than one category,
        reply with a comma separated list of categories.
        """
          .trimIndent()
      ),
    outputKey = "categories",
  )

/** Emits a route for each label the classifier left in state; several routes fan out. */
private class MessageCategories : Node {
  override val name = "message_categories"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val labels = (context.state["categories"] as? String).orEmpty().split(",")
    context.routes = labels.map { Route.Tag(it.trim()) }
  }
}

/** Replies that the message is being handled. */
private class Responder(override val name: String, private val reply: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(author = "", content = Content.fromText(Role.MODEL, reply)))
  }
}
