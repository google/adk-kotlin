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

@file:OptIn(ExperimentalClassifierApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.examples.workflow

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalClassifierApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.classifiers.Binary
import com.google.adk.kt.classifiers.Choice
import com.google.adk.kt.classifiers.ClassifierNode
import com.google.adk.kt.classifiers.LlmClassifier
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.adk.kt.workflow.emit
import com.google.adk.kt.workflow.node
import com.google.adk.kt.workflow.workflow
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"
private const val URGENCY_KEY = "urgency_answer"

/**
 * A port of adk-python's `classifier_node` workflow sample: a [ClassifierNode] asks which team
 * should handle a support ticket and routes the ticket to that team. Technical tickets go to a
 * second classifier node, which asks whether the ticket is urgent and either pages the on-call
 * engineer or files it in the engineering queue.
 */
object ClassifierNodeWorkflow {

  /** Builds the workflow, with the classifier and both agents on [model]. */
  fun create(model: Model): Workflow {
    // Any Classifier works here, such as a dedicated decision model.
    val classifier = LlmClassifier(model)
    val triage =
      ClassifierNode(
        name = "triage",
        classifier = classifier,
        question =
          Choice(
            instructions = "Which team should handle this support ticket?",
            criteria =
              mapOf(
                "billing" to "Invoices, refunds, or subscription charges",
                "technical" to "Bugs, errors, outages, or API problems",
                "general" to "Product questions and anything else",
              ),
          ),
        answerKey = "triage_answer",
      )
    val isUrgent =
      ClassifierNode(
        name = "is_urgent",
        classifier = classifier,
        question = Binary("Is this an outage, or does it block the customer's production use?"),
        threshold = 0.7,
        answerKey = URGENCY_KEY,
      )
    return workflow("classifier_node") {
      chain(Start, processInput, triage).route {
        on("billing") then billingAgent(model)
        on("technical") then isUrgent
        on("general") then faqAgent(model)
      }
      isUrgent.route {
        on(true) then pageOncall
        on(false) then engQueue
      }
    }
  }

  /** Stores the ticket in state for the agents' instructions to read, and outputs it to triage. */
  private val processInput =
    node<String, String>("process_input") { context, ticket ->
      // START hands the first node the user's message, which a String input receives as text.
      context.updateState("ticket", ticket)
      ticket
    }

  /** Reports the page with the urgency that `is_urgent` stored in state. */
  private val pageOncall =
    node<String, Unit>("page_oncall") { context, ticket ->
      val answer = context.state[URGENCY_KEY] as Map<*, *>
      val urgency = NumberFormat.getPercentInstance(Locale.ROOT).format(answer["probability"])
      emit(Content.fromText(Role.MODEL, "Paged the on-call engineer (urgency $urgency): $ticket"))
    }

  private val engQueue =
    node<String, Unit>("eng_queue") { _, ticket ->
      emit(Content.fromText(Role.MODEL, "Filed in the engineering queue: $ticket"))
    }
}

/**
 * Runs the workflow on four tickets, each in its own session, and prints each reply. Needs
 * `GOOGLE_CLOUD_PROJECT` and Application Default Credentials.
 */
fun main() = runBlocking {
  val workflow =
    ClassifierNodeWorkflow.create(
      Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(location = "global"))
    )
  val runner = InMemoryRunner(app = App(appName = "classifier_node", rootNode = workflow))
  val tickets =
    listOf(
      "My invoice charged me twice this month.",
      "The checkout API returns 500 for every request and production is down.",
      "There is a typo on the pricing page.",
      "Do you have a dark mode?",
    )
  for ((index, ticket) in tickets.withIndex()) {
    println("\n=== $ticket")
    val message = Content.fromText(Role.USER, ticket)
    runner.runAsync(userId = "user", sessionId = "session_$index", newMessage = message).collect {
      event ->
      val text = event.contentText(" ")
      if (text.isNotBlank()) println("[${event.author}] $text")
    }
  }
}

private fun billingAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "billing_agent",
    model = model,
    instruction = Instruction("You are a billing specialist. Reply to this ticket: {ticket}"),
  )

private fun faqAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "faq_agent",
    model = model,
    instruction = Instruction("You answer general product questions. Reply to: {ticket}"),
  )
