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

package com.google.adk.kt.examples.compaction

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.summarizer.EventsCompactionConfig
import com.google.adk.kt.summarizer.LlmEventSummarizer
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import java.util.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/**
 * Interactive end-to-end demo of sliding-window context compaction.
 *
 * Chat with the agent in your terminal. Both the agent's model and the compaction summarizer's
 * model are wrapped in a [PrintingModel] that prints every prompt before it is sent, so you can
 * watch the conversation history grow and then collapse into a single summary once compaction kicks
 * in (configured here to compact every two turns).
 *
 * If `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) is set, it talks to a real Gemini model. Otherwise it
 * falls back to canned replies so the compaction behavior is still fully demonstrable offline. Type
 * `exit` (or an empty line) to quit; a summary of the stored session events is printed on exit.
 */
private class PrintingModel(private val label: String, private val delegate: Model) : Model {
  override val name: String = delegate.name

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    println("\n  >>> $label prompt (${request.contents.size} content(s)):")
    request.contents.forEachIndexed { index, content ->
      val text = content.text(" ").ifEmpty { "<non-text>" }
      println("        [$index] ${content.role}: $text")
    }
    emitAll(delegate.generateContent(request, stream))
  }
}

/**
 * A [Model] that ignores the prompt and always returns [reply]; used when no API key is available.
 */
private class CannedModel(override val name: String, private val reply: String) : Model {
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    flowOf(LlmResponse(content = Content.fromText(Role.MODEL, reply)))
}

private fun hasApiKey(): Boolean =
  !System.getenv("GEMINI_API_KEY").isNullOrBlank() ||
    !System.getenv("GOOGLE_API_KEY").isNullOrBlank()

private fun realOrCanned(cannedName: String, cannedReply: String): Model =
  if (hasApiKey()) Gemini(name = MODEL_NAME) else CannedModel(cannedName, cannedReply)

fun main() = runBlocking {
  val agentModel = PrintingModel("AGENT LLM", realOrCanned("agent", "(canned) Here is an answer."))
  val summarizerModel =
    PrintingModel(
      "SUMMARIZER LLM",
      realOrCanned("summarizer", "<<summary of the earlier conversation>>"),
    )

  val app =
    App(
      appName = "compaction_demo",
      rootAgent = LlmAgent(name = "assistant", model = agentModel),
      // Compact every 2 user invocations, with no overlap, using the LLM summarizer above.
      eventsCompactionConfig =
        EventsCompactionConfig(
          compactionInterval = 2,
          overlapSize = 0,
          summarizer = LlmEventSummarizer(summarizerModel),
        ),
    )
  val runner = InMemoryRunner(app = app)
  val userId = "demo-user"
  val sessionId = "demo-session"

  println("Sliding-window compaction demo. Type a message; 'exit' or an empty line quits.")
  if (!hasApiKey()) {
    println(
      "(No GEMINI_API_KEY/GOOGLE_API_KEY set -- using canned replies; compaction still works.)"
    )
  }

  val scanner = Scanner(System.`in`)
  while (true) {
    print("\nYou > ")
    System.out.flush()
    if (!scanner.hasNextLine()) break
    val input = scanner.nextLine()
    if (input.isBlank() || input.trim().lowercase() in setOf("exit", "quit")) break

    runner
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        newMessage = Content.fromText(Role.USER, input),
      )
      .collect { event ->
        val text = event.contentText(" ")
        if (text.isNotBlank()) println("\nassistant > $text")
      }
  }

  println("\n========== SESSION EVENTS (raw events are kept; summaries are appended) ==========")
  val session =
    runner.sessionService.getSession(SessionKey("compaction_demo", userId, sessionId))
      ?: return@runBlocking
  session.events.forEachIndexed { index, event ->
    val compaction = event.actions.compaction
    val description =
      if (compaction != null) {
        val summary = compaction.compactedContent.text(" ")
        "COMPACTION SUMMARY covering [${compaction.startTimestamp}..${compaction.endTimestamp}]: " +
          summary
      } else {
        val text = event.contentText(" ")
        "${event.author}: $text"
      }
    println("  [$index] $description")
  }
}
