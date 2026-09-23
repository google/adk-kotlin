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

import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.events.Event
import com.google.adk.kt.processors.HistoryRewriterProcessor

/** Approximate number of characters per token used by [estimatePromptTokenCount]. */
private const val CHARS_PER_TOKEN = 4

/**
 * Returns the most recent [Event.usageMetadata]`.promptTokenCount` that [agentName] recorded in
 * [events], or the [estimatePromptTokenCount] fallback when no such count is available.
 *
 * The scan stops at a compaction event, because a count recorded at or before a summarization
 * measures the prompt that summary replaced.
 *
 * When [agentName] is given, only counts recorded by that agent are considered. A count belongs to
 * whichever agent made the model call, so reading another agent's is reading another context: a
 * multi-agent app whose turn ends in a small sub-agent otherwise measures that sub-agent forever
 * and never reaches its threshold.
 *
 * @param events The session events to inspect.
 * @param agentName The current agent name. When non-empty, only counts from events with a matching
 *   [Event.author] are considered; also used by the estimate fallback to build effective prompt
 *   contents.
 * @param branch The current invocation branch, used by the estimate fallback.
 */
internal fun latestPromptTokenCount(events: List<Event>, agentName: String, branch: String?): Int? {
  for (event in events.asReversed()) {
    if (event.isCompactionEvent()) break
    if (agentName.isNotEmpty() && event.author != agentName) continue
    if (event.usageMetadata?.promptTokenCount != null) return event.usageMetadata.promptTokenCount
  }
  return estimatePromptTokenCount(events, agentName, branch)
}

/**
 * Returns an approximate prompt token count from session events, or `null` when the prompt has no
 * text.
 *
 * Builds the same contents the [HistoryRewriterProcessor] would produce for the model (so compacted
 * ranges and rewinds are reflected), sums the characters across all text parts, and divides by
 * [CHARS_PER_TOKEN].
 */
private fun estimatePromptTokenCount(
  events: List<Event>,
  agentName: String,
  branch: String?,
): Int? {
  val contents =
    HistoryRewriterProcessor()
      .rewrite(
        events = events,
        agentName = agentName,
        currentBranch = branch,
        includeContents = IncludeContents.DEFAULT,
      )
  var totalChars = 0
  for (content in contents) {
    for (part in content.parts) {
      totalChars += part.text?.length ?: 0
    }
  }
  if (totalChars <= 0) return null
  return totalChars / CHARS_PER_TOKEN
}
