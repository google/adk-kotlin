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

package com.google.adk.kt.summarizer

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.firstOrNull

/**
 * An LLM-based event summarizer for context compaction.
 *
 * This class is responsible for summarizing a provided list of events into a single compacted
 * event. It is designed to be used as part of a context-compaction process such as sliding-window
 * or tail-retention compaction.
 *
 * When [summarizeEvents] is called with a list of events, this class formats the events, generates
 * a summary using the [model], and returns a new [Event] containing the summary within a
 * [com.google.adk.kt.events.EventCompaction] on its [Event.actions].
 *
 * @property model The LLM used for summarization.
 * @property promptTemplate An optional template string for the summarization prompt. If not
 *   provided, a default template will be used. The template must contain a
 *   `"{conversation_history}"` placeholder, which is replaced with the formatted event history.
 */
class LlmEventSummarizer(val model: Model, val promptTemplate: String = DEFAULT_PROMPT_TEMPLATE) :
  EventSummarizer {

  private companion object {
    val logger = LoggerFactory.getLogger(LlmEventSummarizer::class)
    const val CONVERSATION_HISTORY_PLACEHOLDER = "{conversation_history}"
    const val DEFAULT_PROMPT_TEMPLATE =
      "The following is a conversation history between a user and an AI agent. Please summarize " +
        "the conversation, focusing on key information and decisions made, as well as any " +
        "unresolved questions or tasks. The summary should be concise and capture the essence of " +
        "the interaction.\n\n$CONVERSATION_HISTORY_PLACEHOLDER"
  }

  init {
    require(promptTemplate.contains(CONVERSATION_HISTORY_PLACEHOLDER)) {
      "promptTemplate must contain the placeholder '$CONVERSATION_HISTORY_PLACEHOLDER'."
    }
  }

  override suspend fun summarizeEvents(events: List<Event>): Event? {
    if (events.isEmpty()) return null

    val prompt = promptTemplate.replace(CONVERSATION_HISTORY_PLACEHOLDER, formatEvents(events))
    val request =
      LlmRequest(
        model = model,
        contents = listOf(Content.fromText(role = Role.USER, text = prompt)),
      )

    val response: LlmResponse? = model.generateContent(request, stream = false).firstOrNull()
    if (response == null) {
      logger.warn { "Summarization produced no result: model returned no responses." }
      return null
    }
    val summary: Content? = response.content
    if (summary == null) {
      logger.warn {
        "Summarization produced no result: response had no content " +
          "(finishReason=${response.finishReason})."
      }
      return null
    }

    val compaction =
      EventCompaction(
        startTimestamp = events.first().timestamp,
        endTimestamp = events.last().timestamp,
        compactedContent = summary.copy(role = Role.MODEL),
      )

    return Event(
      author = Role.USER,
      actions = EventActions(compaction = compaction),
      usageMetadata = response.usageMetadata,
    )
  }

  /**
   * Formats the conversation as `<label>: <text>` lines, one per text part. The label includes both
   * the event's author and the content role when they differ, so multi-agent sessions preserve
   * attribution (`weather_agent (model): ...`). Non-text parts are skipped.
   */
  private fun formatEvents(events: List<Event>): String =
    events
      .asSequence()
      .flatMap { event ->
        val label = formatLabel(author = event.author, role = event.content?.role)
        event.content?.parts.orEmpty().asSequence().mapNotNull { part ->
          part.text?.takeIf { it.isNotEmpty() }?.let { "$label: $it" }
        }
      }
      .joinToString(separator = "\n")

  /**
   * Builds the per-line label. Includes both [author] and [role] when both are meaningful and
   * differ, so multi-agent sessions retain agent attribution; collapses to a single value when they
   * are redundant or when [role] is missing.
   */
  private fun formatLabel(author: String, role: String?): String =
    if (role == null || role == author) author else "$author ($role)"
}
