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

/** Returns true when this event carries a context-compaction summary. */
internal fun Event.isCompactionEvent(): Boolean = actions.compaction != null

/**
 * Returns the longest prefix of the given window that can be summarized without splitting a
 * function-call/response pair or leaving a tool-confirmation (HITL) request unresolved.
 *
 * Performs a single left-to-right pass tracking "open" obligations keyed by call ID: a function
 * call or a tool-confirmation request opens one, and a function response with the same ID closes
 * it. The prefix is safe to summarize exactly at the points where no obligation is open, so the
 * longest prefix ending at such a balanced point is returned (empty if the window never reaches a
 * balanced point).
 */
internal fun longestSelfContainedPrefix(events: List<Event>): List<Event> {
  val openIds = mutableSetOf<String>()
  var safeLength = 0
  for ((index, event) in events.withIndex()) {
    for (response in event.functionResponses()) {
      response.id?.let(openIds::remove)
    }
    for (call in event.functionCalls()) {
      call.id?.let(openIds::add)
    }
    openIds.addAll(event.actions.requestedToolConfirmations.keys)
    if (openIds.isEmpty()) safeLength = index + 1
  }
  return events.subList(0, safeLength)
}
