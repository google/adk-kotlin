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

  /**
   * Creates a summarizer using the default prompt template. Java callers need this because
   * [promptTemplate]'s default is not visible to them and its value is private.
   */
  constructor(model: Model) : this(model, DEFAULT_PROMPT_TEMPLATE)

  private companion object {
    val logger = LoggerFactory.getLogger(LlmEventSummarizer::class)
    const val CONVERSATION_HISTORY_PLACEHOLDER = "{conversation_history}"
    const val DEFAULT_PROMPT_TEMPLATE =
      "The following is a conversation history between a user and an AI agent. It may or may not " +
        "start from a compacted history. Please identify and reiterate the user request, " +
        "summarize the context so far, focusing on key decisions made and information obtained, " +
        "as well as any unresolved questions or tasks. The summary should be concise and capture " +
        "the essence of the interaction.\n\n$CONVERSATION_HISTORY_PLACEHOLDER"

    /**
     * Tool call args and responses can be large (e.g. search results). Cap how much of each is
     * rendered.
     */
    const val MAX_TOOL_CONTENT_CHARS = 2000
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
   * Formats events into prompt text, including thoughts and tool calls.
   *
   * Thoughts carry the agent's analysis of tool responses, and function calls and responses carry
   * the evidence retrieved so far, so all three are included. Thoughts emitted by a compaction
   * event are skipped so a prior summary's reasoning does not leak into the next summary. Function
   * call args and responses are truncated to [MAX_TOOL_CONTENT_CHARS] so compaction does not
   * inflate the very context it exists to shrink. Each line is labeled with the event's
   * [Event.author].
   */
  private fun formatEvents(events: List<Event>): String {
    val formattedHistory = mutableListOf<String>()
    for (event in events) {
      val parts = event.content?.parts
      if (parts.isNullOrEmpty()) continue
      val isCompaction = event.actions.compaction != null
      for (part in parts) {
        if (part.thought == true && !part.text.isNullOrEmpty()) {
          if (!isCompaction) {
            formattedHistory.add("${event.author} (thought): ${part.text}")
          }
        } else if (!part.text.isNullOrEmpty()) {
          formattedHistory.add("${event.author}: ${part.text}")
        }

        if (part.functionCall != null) {
          formattedHistory.add(
            "${event.author} called tool: " +
              "${part.functionCall.name}(${truncate(part.functionCall.args.toString())})"
          )
        }
        if (part.functionResponse != null) {
          formattedHistory.add(
            "Tool response from ${part.functionResponse.name}: " +
              truncate(part.functionResponse.response.toString())
          )
        }
      }
    }
    return formattedHistory.joinToString(separator = "\n")
  }

  /** Caps [text] at [MAX_TOOL_CONTENT_CHARS], marking how many characters were dropped. */
  private fun truncate(text: String): String {
    if (text.length <= MAX_TOOL_CONTENT_CHARS) return text
    return "${text.take(MAX_TOOL_CONTENT_CHARS)}... [truncated ${text.length - MAX_TOOL_CONTENT_CHARS} chars]"
  }
}
