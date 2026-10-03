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
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.State
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.adk.kt.workflow.JoinNode
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
 * A research workflow built with the workflow DSL. A planner fans out to two researchers that
 * search the web concurrently, a [JoinNode] waits for both before the writer drafts a brief, and a
 * reviewer sends the brief back for another round until it is complete or the round cap is reached.
 */
object ResearchWorkflow {

  /** Builds the workflow, with every agent on [model]. */
  fun create(model: Model): Workflow {
    val plan = plannerAgent(model)
    // Branches don't share events, so each researcher leaves its notes in state.
    val searchWeb = webResearcherAgent(model)
    val searchDocs = docsResearcherAgent(model)
    val gather = JoinNode("gather")
    val write = writerAgent(model)
    val review = reviewerAgent(model)
    val gate = ReviewGate(maxRounds = 3)

    return workflow("research") {
      Start.then(plan).then(searchWeb, searchDocs) // Fans out: both researchers run concurrently.
      gather.joinFrom(searchWeb, searchDocs).then(write).then(review).then(gate) // Waits for both.
      gate.routeOn("needs-more").to(plan) // Loops back while the reviewer asks for more.
      gate.routeOn("done").to(Publisher())
    }
  }
}

/** Runs the workflow on one question and prints the published brief. Needs `GOOGLE_API_KEY`. */
fun main() = runBlocking {
  val workflow = ResearchWorkflow.create(Gemini(name = MODEL_NAME))
  val runner = InMemoryRunner(app = App(appName = "research", rootNode = workflow))
  val question = Content.fromText(Role.USER, "Should a small team adopt Kotlin Multiplatform?")
  runner.runAsync(userId = "user", sessionId = "session", newMessage = question).collect { event ->
    val text = event.contentText(" ")
    if (text.isNotBlank()) println("[${event.nodeInfo?.path}] $text")
    if (event.nodeInfo?.path == "research@1/publisher@1") println("\nBrief: ${event.output}")
  }
}

private fun plannerAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "planner",
    model = model,
    instruction =
      Instruction(
        """
        Split the user's research question into two focused sub-questions: the first about
        real-world adoption and experience, the second about what the official documentation
        says. If this review of an earlier brief asks for more, target the gap it names:
        {review?}
        """
          .trimIndent()
      ),
  )

private fun webResearcherAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "web_researcher",
    model = model,
    instruction =
      Instruction(
        "Research the first sub-question with Google Search. Reply with at most three" +
          " bullets, each ending with its source."
      ),
    tools = listOf(GoogleSearchTool()),
    outputKey = "web_notes",
  )

private fun docsResearcherAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "docs_researcher",
    model = model,
    instruction =
      Instruction(
        "Research the second sub-question with Google Search, preferring official" +
          " documentation. Reply with at most three bullets, each ending with its source."
      ),
    tools = listOf(GoogleSearchTool()),
    outputKey = "docs_notes",
  )

private fun writerAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "writer",
    model = model,
    instruction =
      Instruction(
        """
        Write a one-paragraph brief answering the user's question from these notes, and cite
        the sources you use:
        {web_notes?}
        {docs_notes?}
        """
          .trimIndent()
      ),
    outputKey = "brief",
  )

private fun reviewerAgent(model: Model): LlmAgent =
  LlmAgent(
    name = "reviewer",
    model = model,
    instruction =
      Instruction(
        """
        Review this brief against the user's question and the research notes.
        Brief: {brief?}
        Notes: {web_notes?}
        {docs_notes?}
        """
          .trimIndent()
      ),
    outputSchema = REVIEW_SCHEMA,
    outputKey = "review",
  )

/** The reviewer's structured verdict, which [ReviewGate] routes on. */
private val REVIEW_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "verdict" to
          Schema(
            type = Type.STRING,
            enum = listOf("done", "needs-more"),
            description =
              "needs-more if the brief leaves part of the question unanswered or makes a claim" +
                " the notes do not support; otherwise done.",
          ),
        "feedback" to
          Schema(type = Type.STRING, description = "The gap to research next, or empty if done."),
      ),
    required = listOf("verdict", "feedback"),
  )

/**
 * Routes on the reviewer's verdict, since an [LlmAgent] node emits no route. Stops the loop after
 * [maxRounds] research rounds even if the reviewer still asks for more.
 */
private class ReviewGate(private val maxRounds: Int) : Node {
  override val name = "review_gate"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val verdict = (context.state["review"] as? Map<*, *>)?.get("verdict")
    // The run id counts this node's runs in the current invocation, so it is the round number.
    val round = context.runId.toInt()
    val route = if (verdict == "needs-more" && round < maxRounds) "needs-more" else "done"
    // Clears the review and notes when the loop ends, so the next question starts fresh.
    if (route == "done") {
      for (key in listOf("review", "web_notes", "docs_notes")) {
        context.updateState(key, State.REMOVED)
      }
    }
    context.routes = listOf(Route.Tag(route))
  }
}

/** Outputs the brief the writer stored, which makes it the workflow's output. */
private class Publisher : Node {
  override val name = "publisher"

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val brief = context.state["brief"]
    // Consumes the brief, so a later question never publishes this one's answer.
    context.updateState("brief", State.REMOVED)
    if (brief != null) emit(brief)
  }
}
