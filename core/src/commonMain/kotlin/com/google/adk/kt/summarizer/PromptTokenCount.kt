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
 * Returns the most recently observed prompt token count from [events], or `null` when one cannot be
 * determined.
 *
 * Walks [events] from newest to oldest and returns the first non-null
 * [Event.usageMetadata]`.promptTokenCount` it finds (the token count the model reported for the
 * most recent LLM call). When no event carries usage metadata yet -- e.g. before the first model
 * response of a session -- it falls back to [estimatePromptTokenCount].
 *
 * @param events The session events to inspect.
 * @param agentName The current agent name, used by the estimate fallback to build effective prompt
 *   contents.
 * @param branch The current invocation branch, used by the estimate fallback.
 */
internal fun latestPromptTokenCount(events: List<Event>, agentName: String, branch: String?): Int? {
  for (event in events.asReversed()) {
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
