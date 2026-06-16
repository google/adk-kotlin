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
package com.google.adk.kt.testing

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * Reduces a list of [Event]s to `(author, simplifiedContent)` pairs that are easy to compare in
 * tests with `assertEquals(expected, simplifyEvents(actual))`.
 *
 * Events without [Event.content] are skipped, so model-only signaling events (e.g. agent
 * end-of-turn markers without payload) do not appear in the output.
 */
fun simplifyEvents(events: List<Event>): List<Pair<String, Any>> = events.mapNotNull { event ->
  event.content?.let { event.author to simplifyContent(it) }
}

/** Constants for resumability event simplification. */
object ResumableEvents {
  /** Sentinel emitted by [simplifyResumableEvents] for an end-of-agent marker event. */
  const val END_OF_AGENT: String = "end_of_agent"
}

/**
 * Reduces events to `(author, payload)` pairs for asserting resumability flows, mirroring Python
 * ADK 1.x `testing_utils.simplify_resumable_app_events`. Per event, in priority order: content ->
 * simplified content; else an end-of-agent marker -> [ResumableEvents.END_OF_AGENT]; else an
 * agent-state checkpoint -> the state value ([com.google.adk.kt.agents.TypedData]).
 */
fun simplifyResumableEvents(events: List<Event>): List<Pair<String, Any>> =
  events.mapNotNull { event ->
    val content = event.content
    val agentState = event.actions.agentState
    when {
      content != null -> event.author to simplifyContent(content)
      event.actions.endOfAgent -> event.author to ResumableEvents.END_OF_AGENT
      agentState != null -> event.author to agentState
      else -> null
    }
  }

/**
 * Reduces a [Content] to a comparable form:
 * - one text part → its trimmed text as [String];
 * - one non-text part → that [Part] with any function-call/response id cleared;
 * - multiple parts → the list of parts with ids cleared.
 *
 * Ids are dropped because they are non-deterministic in most flows and would make exact-match
 * assertions brittle.
 */
fun simplifyContent(content: Content): Any {
  val parts = content.parts.map { it.withoutFunctionIds() }
  if (parts.size == 1) {
    val only = parts.single()
    return only.text?.trim() ?: only
  }
  return parts
}

private fun Part.withoutFunctionIds(): Part {
  val call = functionCall
  val response = functionResponse
  return when {
    call?.id != null -> copy(functionCall = call.copy(id = null))
    response?.id != null -> copy(functionResponse = response.copy(id = null))
    else -> this
  }
}
