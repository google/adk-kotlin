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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService

/**
 * An [EventCompactor] that compacts events in a sliding window over invocations.
 *
 * Every `compactionInterval` new user-initiated invocations triggers a compaction over a window
 * spanning the new invocations plus `overlapSize` preceding invocations from the prior compaction
 * boundary. The overlap preserves context continuity across consecutive summaries.
 *
 * ## Safety net
 *
 * Before summarizing, the selected window is shrunk to its longest self-contained prefix so a
 * compaction never splits an open interaction. Both a function call and a human-in-the-loop
 * tool-confirmation request open an obligation keyed by the call ID, and a function response with
 * the same ID closes it. The window is cut back to the last point where no obligation is still
 * open, which guarantees:
 * - no function call is summarized without its response,
 * - no tool-confirmation request (`event.actions.requestedToolConfirmations`) without its resolving
 *   response.
 */
class SlidingWindowEventCompactor(private val config: EventsCompactionConfig) : EventCompactor {

  private companion object {
    val logger = LoggerFactory.getLogger(SlidingWindowEventCompactor::class)
  }

  override suspend fun compact(session: Session, sessionService: SessionService) {
    if (!config.hasSlidingWindowConfig()) return
    val summarizer =
      requireNotNull(config.summarizer) { "Missing EventSummarizer for event compaction." }
    val compactionWindow = selectCompactionWindow(session.events) ?: return
    val compactionEvent = summarizer.summarizeEvents(compactionWindow) ?: return
    val appendedEvent = sessionService.appendEvent(session, compactionEvent)
    logger.debug {
      "Sliding-window compaction summarized ${compactionWindow.size} events into ${appendedEvent.id}."
    }
  }

  /**
   * Returns the events to compact — the sliding window of recent invocations, trimmed to its
   * longest self-contained prefix — or `null` when there is nothing to compact.
   *
   * A reverse walk over [events] accumulates unique invocation IDs of non-compaction events. Once
   * it crosses the most recent compaction boundary with at least `compactionInterval` new
   * invocations, it collects up to `overlapSize` additional preceding invocations and stops. The
   * resulting chronological window is then shrunk via [longestSelfContainedPrefix], so a compaction
   * never splits an open function-call / tool-confirmation interaction.
   *
   * Returns `null` when there is nothing to summarize: either fewer than `compactionInterval` new
   * invocations exist, or the selected window has no self-contained prefix.
   */
  private fun selectCompactionWindow(events: List<Event>): List<Event>? {
    val compactionInterval = config.compactionInterval ?: return null
    val overlapSize = config.overlapSize ?: return null

    val eventsToCompact = mutableListOf<Event>()
    val invocationsToCompact = mutableSetOf<String>()
    var lastCompactTimestamp = -1L
    var targetSize = -1

    for (i in events.indices.reversed()) {
      val event = events[i]
      val invocationId = event.invocationId

      if (invocationId != null && !event.isCompactionEvent()) {
        if (invocationId in invocationsToCompact) {
          eventsToCompact.add(event)
          continue
        }
        // Crossing the latest existing compaction boundary: either we already have enough new
        // invocations and should keep going for `overlapSize` more, or we don't and should stop.
        if (event.timestamp <= lastCompactTimestamp) {
          if (invocationsToCompact.size < compactionInterval) break
          if (targetSize < 0) targetSize = invocationsToCompact.size + overlapSize
        }
        if (targetSize < 0 || invocationsToCompact.size < targetSize) {
          eventsToCompact.add(event)
          invocationsToCompact.add(invocationId)
        } else {
          break
        }
      } else if (event.isCompactionEvent()) {
        lastCompactTimestamp =
          maxOf(lastCompactTimestamp, event.actions.compaction?.endTimestamp ?: -1L)
      }
    }

    if (invocationsToCompact.size < compactionInterval) return null
    return eventsToCompact.asReversed().longestSelfContainedPrefix().ifEmpty { null }
  }
}

private fun Event.isCompactionEvent(): Boolean = actions.compaction != null

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
private fun List<Event>.longestSelfContainedPrefix(): List<Event> {
  val openIds = mutableSetOf<String>()
  var safeLength = 0
  for ((index, event) in withIndex()) {
    for (response in event.functionResponses()) {
      response.id?.let(openIds::remove)
    }
    for (call in event.functionCalls()) {
      call.id?.let(openIds::add)
    }
    openIds.addAll(event.actions.requestedToolConfirmations.keys)
    // TODO(b/505630632): Add authentication requests handling.
    if (openIds.isEmpty()) safeLength = index + 1
  }
  return subList(0, safeLength)
}
