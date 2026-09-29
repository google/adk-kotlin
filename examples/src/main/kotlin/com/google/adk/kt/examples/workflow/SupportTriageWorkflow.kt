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
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
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
 * A support-triage workflow built with the workflow DSL. A classifier labels the request, a routing
 * map sends it to a billing or tech specialist who looks up the facts with a tool, and a summarizer
 * writes the reply. Any other request goes straight to the summarizer through `otherwise`.
 */
object SupportTriageWorkflow {

  /**
   * Builds the workflow, with every agent on [model]. The billing specialist reads the customer's
   * account ID from the `account_id` session state key, so pass it with each request.
   */
  fun create(model: Model): Workflow {
    // An LlmAgent is a Node, so it goes straight into the graph.
    val classify = classifierAgent(model)
    val billing = billingAgent(model)
    val tech = techAgent(model)
    val summarize = summaryAgent(model)

    return workflow("support_triage") {
      Start.then(classify).then(CategoryRouter()).thenRoute {
        "billing" routesTo billing
        "tech" routesTo tech
        otherwise(summarize)
      }
      billing.then(summarize)
      tech.then(summarize)
    }
  }
}

/**
 * Runs the workflow on one request from a signed-in customer and prints what each node said. Needs
 * `GOOGLE_API_KEY`.
 */
fun main() = runBlocking {
  val workflow = SupportTriageWorkflow.create(Gemini(name = MODEL_NAME))
  val runner = InMemoryRunner(app = App(appName = "support_triage", rootNode = workflow))
  val request = Content.fromText(Role.USER, "I was charged twice for my subscription this month.")
  // The support app knows who is signed in, so it passes the account ID as session state.
  val account = mapOf("account_id" to "ACC-1042")
  runner
    .runAsync(userId = "user", sessionId = "session", newMessage = request, stateDelta = account)
    .collect { event ->
      val text = event.contentText(" ")
      if (text.isNotBlank()) println("[${event.nodeInfo?.path}] $text")
    }
}

private fun classifierAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "classifier",
    model = model,
    instruction = Instruction("Classify the customer's latest message."),
    outputSchema = TRIAGE_SCHEMA,
    outputKey = TRIAGE_KEY,
  )

private fun billingAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "billing",
    model = model,
    instruction =
      Instruction(
        """
        You are a billing specialist. The customer's account ID is {account_id}.
        Look up their recent charges, then note in two bullets what happened and how to fix it.
        """
          .trimIndent()
      ),
    tools = BillingSystem().generatedTools(),
  )

private fun techAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "tech",
    model = model,
    instruction =
      Instruction(
        "You are a support engineer. Check the service status, then note in two bullets the" +
          " likely cause and the fix or workaround."
      ),
    tools = StatusPage().generatedTools(),
  )

private fun summaryAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "summary",
    model = model,
    instruction =
      Instruction(
        "Write the reply to the customer in at most four sentences, based on any specialist" +
          " notes above. Do not mention internal tools or IDs other than the customer's."
      ),
  )

private const val TRIAGE_KEY = "triage"

/** The classifier's structured answer, so routing never depends on parsing free text. */
private val TRIAGE_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "category" to
          Schema(
            type = Type.STRING,
            enum = listOf("billing", "tech", "other"),
            description =
              "billing: charges, refunds or plans. tech: errors, outages or app problems." +
                " other: anything else.",
          )
      ),
    required = listOf("category"),
  )

/** A charge on a customer's account. */
data class Charge(
  val id: String,
  val date: String,
  val description: String,
  val amountCents: Int,
  val currency: String,
)

/** A mock billing backend that the billing specialist queries through a tool. */
class BillingSystem {

  /** Lists the charges on an account from the last 60 days, newest first. */
  @Tool
  fun listRecentCharges(
    @Param("The customer's account ID, such as ACC-1042") accountId: String
  ): List<Charge> =
    if (accountId != "ACC-1042") {
      emptyList()
    } else {
      listOf(
        Charge("ch_302", "2026-09-01", "Pro plan, monthly", 1200, "USD"),
        Charge("ch_301", "2026-09-01", "Pro plan, monthly", 1200, "USD"),
        Charge("ch_214", "2026-08-01", "Pro plan, monthly", 1200, "USD"),
      )
    }
}

/** A mock status page that the support engineer checks through a tool. */
class StatusPage {

  /** Returns the current status of each service. */
  @Tool
  fun getServiceStatus(): Map<String, String> =
    mapOf(
      "sync" to "Degraded since 09:10 UTC. A fix is rolling out.",
      "login" to "Operational",
      "billing" to "Operational",
    )
}

/** Routes on the category the classifier returned, since an [LlmAgent] node emits no route. */
private class CategoryRouter : Node {
  override val name = "category_router"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val category = (context.state[TRIAGE_KEY] as? Map<*, *>)?.get("category") as? String
    // Consumes the answer, so a later turn never routes on this turn's category.
    context.updateState(TRIAGE_KEY, State.REMOVED)
    // Without a category the router emits no route, so `otherwise` takes the request.
    if (category != null) context.routes = listOf(Route.Tag(category))
  }
}
