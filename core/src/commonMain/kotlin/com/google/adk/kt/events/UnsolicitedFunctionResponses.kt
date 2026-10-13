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

package com.google.adk.kt.events

import com.google.adk.kt.types.Role

/**
 * Returns [events], each kept in place, minus unsolicited function-response parts: user-authored
 * responses to calls that were not long-running, since only a call in its event's
 * [Event.longRunningToolIds] takes a result from the client.
 *
 * Each response is judged by the latest earlier call with its id that no rewind removed; one with
 * no such call is kept. Prompt building and context compaction must both apply this, as with
 * [applyRewinds].
 */
internal fun dropUnsolicitedFunctionResponses(events: List<Event>): List<Event> {
  // Ids whose latest call so far was not long-running, so the client may not answer them.
  val regularCallIds = mutableSetOf<String>()
  // Calls in rewound invocations do not count, since the prompt never shows them.
  val liveEventIds = applyRewinds(events).mapTo(mutableSetOf()) { it.id }
  return buildList {
    for (event in events) {
      val content = event.content
      // Only user events carry client input; the framework records tool results as the agent.
      if (event.author != Role.USER || content == null) {
        add(event)
      } else {
        val keptParts = content.parts.filterNot { it.functionResponse?.id in regularCallIds }
        // A trimmed event stays, even with no parts left, so rewinds still find its invocation.
        add(
          if (keptParts.size == content.parts.size) event
          else event.copy(content = content.copy(parts = keptParts))
        )
      }
      if (event.id !in liveEventIds) continue
      // Recorded after the check above, so a response is judged only by calls that precede it.
      for (call in event.functionCalls()) {
        val id = call.id ?: continue
        if (id in event.longRunningToolIds) regularCallIds -= id else regularCallIds += id
      }
    }
  }
}
