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
package com.google.adk.kt.a2a.agent

import com.google.adk.kt.a2a.converters.RemoteIdKeys
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

internal const val A2A_METADATA_AGGREGATED = "a2a:aggregated"

/**
 * Custom aggregator for A2A streaming responses.
 *
 * Note: This class is not thread-safe.
 */
internal class A2AStreamingResponseAggregator(
  private val invocationId: String,
  private val agentName: String,
) {
  private val aggregatedParts = mutableListOf<Part>()

  fun processEvent(
    adkEvent: Event,
    isCompleted: Boolean,
    shouldBuffer: Boolean,
    shouldResetBuffer: Boolean,
    isLastChunk: Boolean = false,
  ): List<Event> = buildList {
    if (shouldResetBuffer) {
      clearBuffer()
    }

    val hasBufferedContent = aggregatedParts.isNotEmpty()

    // Terminal event with no content: merge and return early
    if (isCompleted && hasBufferedContent && adkEvent.content?.parts.isNullOrEmpty()) {
      add(
        adkEvent.copy(
          content = Content(role = Role.MODEL, parts = aggregatedParts.toList()),
          customMetadata = adkEvent.customMetadata.orEmpty() + (A2A_METADATA_AGGREGATED to "true"),
        )
      )
      clearBuffer()
      return@buildList
    }

    // Emit aggregated content if needed
    if (adkEvent.partial) {
      val addedToBuffer = bufferContent(adkEvent, shouldBuffer)

      if (isLastChunk && hasBufferedContent) {
        // Emit partial last chunk and follow by the non-partial aggregated event.
        add(adkEvent)
        add(createAggregatedEvent(flushedBy = adkEvent))
        return@buildList
      }

      if (!addedToBuffer && hasBufferedContent) {
        // Partial event with no content to buffer (e.g. tool call).
        // Flush buffer before emitting this event.
        add(createAggregatedEvent(flushedBy = adkEvent))
      }
    } else {
      // Both terminal events with content and intermediate non-partials
      // flush the buffer here if there is buffered content.
      if (hasBufferedContent) {
        add(createAggregatedEvent(flushedBy = adkEvent))
      }
    }

    // Always emit the current event
    add(adkEvent)
  }

  private fun bufferContent(event: Event, shouldBuffer: Boolean): Boolean {
    if (!shouldBuffer) return false

    var updated = false
    for (part in event.content?.parts.orEmpty()) {
      part.text?.let { text ->
        val lastPart = aggregatedParts.lastOrNull()
        if (lastPart != null && lastPart.thought == part.thought) {
          aggregatedParts[aggregatedParts.size - 1] = lastPart.copy(text = lastPart.text + text)
        } else {
          aggregatedParts.add(part.copy(text = text))
        }
        updated = true
      }
    }
    return updated
  }

  private fun clearBuffer() {
    aggregatedParts.clear()
  }

  /** Merges the buffered text into one event in the task and context of [flushedBy]. */
  private fun createAggregatedEvent(flushedBy: Event): Event {
    val parts = aggregatedParts.toList()
    clearBuffer()
    val ids = flushedBy.customMetadata.orEmpty().filterKeys { it in RemoteIdKeys.ALL }
    return Event(
      invocationId = invocationId,
      author = agentName,
      content = Content(role = Role.MODEL, parts = parts),
      turnComplete = false,
      customMetadata = ids + (A2A_METADATA_AGGREGATED to "true"),
    )
  }
}
