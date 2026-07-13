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
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

/** Token count that triggers compaction, and how many recent events are kept raw. */
private const val TOKEN_THRESHOLD = 50
private const val EVENT_RETENTION_SIZE = 2

/**
 * Interactive end-to-end demo of token-threshold (tail-retention) context compaction.
 *
 * Chat with the agent in your terminal. Before each model call, ADK measures the most recent prompt
 * token count and, once it reaches [TOKEN_THRESHOLD], compacts everything older than the last
 * [EVENT_RETENTION_SIZE] events into a single summary while keeping the recent events raw. Both the
 * agent's model and the compaction summarizer's model are wrapped in a [LabeledPrintingModel] that prints
 * every prompt, so you can watch the history grow and then collapse into a summary once the
 * threshold is crossed.
 *
 * The prompt token count comes from the model's reported usage metadata. Requires `GEMINI_API_KEY`
 * or `GOOGLE_API_KEY` to be set. Type `exit` (or an empty line) to quit; a summary of the stored
 * session events is printed on exit.
 */
private class LabeledPrintingModel(private val label: String, private val delegate: Model) : Model {
  override val name: String = delegate.name

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    println("\n  >>> $label prompt (${request.contents.size} content(s)):")
    request.contents.forEachIndexed { index, content ->
      val text = content.parts.mapNotNull { it.text }.joinToString(" ").ifEmpty { "<non-text>" }
      println("        [$index] ${content.role}: $text")
    }
    emitAll(delegate.generateContent(request, stream))
  }
}

fun main() = runBlocking {
  val agentModel = LabeledPrintingModel("AGENT LLM", Gemini(name = MODEL_NAME))
  val summarizerModel = LabeledPrintingModel("SUMMARIZER LLM", Gemini(name = MODEL_NAME))

  val app =
    App(
      appName = "token_threshold_compaction_demo",
      rootAgent = LlmAgent(name = "assistant", model = agentModel),
      // Compact when the prompt reaches TOKEN_THRESHOLD tokens, keeping the last
      // EVENT_RETENTION_SIZE events raw, using the LLM summarizer above.
      eventsCompactionConfig =
        EventsCompactionConfig(
          tokenThreshold = TOKEN_THRESHOLD,
          eventRetentionSize = EVENT_RETENTION_SIZE,
          summarizer = LlmEventSummarizer(summarizerModel),
        ),
    )
  val runner = InMemoryRunner(app = app)
  val userId = "demo-user"
  val sessionId = "demo-session"

  println(
    "Token-threshold compaction demo (threshold=$TOKEN_THRESHOLD tokens, " +
      "retain=$EVENT_RETENTION_SIZE events). Type a message; 'exit' or an empty line quits."
  )

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
        val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
        if (text.isNotBlank()) println("\nassistant > $text")
      }
  }

  println("\n========== SESSION EVENTS (raw events are kept; summaries are appended) ==========")
  val session =
    runner.sessionService.getSession(
      SessionKey("token_threshold_compaction_demo", userId, sessionId)
    ) ?: return@runBlocking
  session.events.forEachIndexed { index, event ->
    val compaction = event.actions.compaction
    val description =
      if (compaction != null) {
        val summary = compaction.compactedContent.parts.mapNotNull { it.text }.joinToString(" ")
        "COMPACTION SUMMARY covering [${compaction.startTimestamp}..${compaction.endTimestamp}]: " +
          summary
      } else {
        val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
        "${event.author}: $text"
      }
    println("  [$index] $description")
  }
}
