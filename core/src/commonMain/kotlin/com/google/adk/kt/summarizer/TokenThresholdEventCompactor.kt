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
import com.google.adk.kt.events.applyRewinds
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Role

/**
 * An [EventCompactor] that compacts events once the most recent prompt reaches a token threshold,
 * keeping a tail of recent events raw (tail retention).
 *
 * It is intended to run intra-invocation, before each model call: when the most recently observed
 * prompt token count reaches `tokenThreshold`, every event older than the last `eventRetentionSize`
 * events (since the previous compaction) is summarized into a single event, while the most recent
 * events are preserved raw to maintain continuity.
 *
 * ## Safety net
 *
 * The window never strands an open interaction: the window is shrunk to its
 * [longestSelfContainedPrefix].
 *
 * @param config The compaction configuration; must have token-threshold fields set.
 * @param agentName The current agent name, used when estimating the prompt token count.
 * @param branch The current invocation branch, used when estimating the prompt token count.
 */
class TokenThresholdEventCompactor(
  private val config: EventsCompactionConfig,
  private val agentName: String,
  private val branch: String?,
) : EventCompactor {

  private companion object {
    val logger = LoggerFactory.getLogger(TokenThresholdEventCompactor::class)
  }

  override suspend fun compact(session: Session, sessionService: SessionService) {
    if (!config.hasTokenThresholdConfig()) return
    val summarizer =
      requireNotNull(config.summarizer) { "Missing EventSummarizer for event compaction." }
    val tokenThreshold = config.tokenThreshold ?: return
    val eventRetentionSize = config.eventRetentionSize ?: return

    // Drop rewound invocations first so the summary covers only live events. This keeps the
    // compactor consistent with prompt building (HistoryRewriterProcessor also applies rewinds).
    val liveEvents = applyRewinds(session.events)

    val promptTokenCount = latestPromptTokenCount(liveEvents, agentName, branch) ?: return
    if (promptTokenCount < tokenThreshold) return

    val compactionWindow = selectTailRetentionWindow(liveEvents, eventRetentionSize) ?: return
    val compactionEvent = summarizer.summarizeEvents(compactionWindow) ?: return
    val appendedEvent = sessionService.appendEvent(session, compactionEvent)
    logger.debug {
      "Token-threshold compaction summarized ${compactionWindow.size} events into " +
        "${appendedEvent.id}."
    }
  }

  /**
   * Returns the events to compact, or `null` when there is nothing to compact.
   *
   * Takes the events since the last compaction except the most recent `eventRetentionSize` (kept
   * raw), trimmed to a self-contained prefix.
   *
   * If a previous compaction exists, include its summary as the first event so the new summary
   * covers and replaces it.
   */
  private fun selectTailRetentionWindow(
    events: List<Event>,
    eventRetentionSize: Int,
  ): List<Event>? {
    val latestCompaction = latestCompactionEvent(events)
    val lastCompactedEndTimestamp = latestCompaction?.actions?.compaction?.endTimestamp ?: 0L

    val candidateEvents = events.filter {
      !it.isCompactionEvent() && it.timestamp > lastCompactedEndTimestamp
    }
    if (candidateEvents.size <= eventRetentionSize) return null

    // Where the kept-raw tail begins; candidates before it are compacted.
    var firstRetainedIndex = candidateEvents.size
    if (eventRetentionSize > 0) {
      firstRetainedIndex -= eventRetentionSize
      val retentionBoundaryTimestamp = candidateEvents[firstRetainedIndex].timestamp

      // Move the cut back so it never splits a same-timestamp group; the summary's endTimestamp
      // must stay below every retained event, or coverage would drop the event that ties it.
      while (
        firstRetainedIndex > 0 &&
          candidateEvents[firstRetainedIndex - 1].timestamp >= retentionBoundaryTimestamp
      ) {
        firstRetainedIndex--
      }
    }

    val eventsToCompact = longestSelfContainedPrefix(candidateEvents.subList(0, firstRetainedIndex))
    if (eventsToCompact.isEmpty()) return null

    // Prepend the previous summary so the new compaction covers it.
    val previousCompaction = latestCompaction?.actions?.compaction
    if (previousCompaction != null) {
      val previousCompactionEvent =
        Event(
          author = Role.MODEL,
          content = previousCompaction.compactedContent,
          branch = latestCompaction.branch,
          timestamp = previousCompaction.startTimestamp,
        )
      return listOf(previousCompactionEvent) + eventsToCompact
    }
    return eventsToCompact
  }
}

/**
 * Returns the latest non-subsumed compaction event in [events] by stream order, or `null` if
 * [events] contains no compaction events.
 *
 * A compaction is *subsumed* when another compaction fully contains its `[startTimestamp,
 * endTimestamp]` range — a strictly larger range, or an identical range appearing later in the
 * stream. Among the compactions that survive that filter, the one appearing latest in [events] is
 * returned.
 *
 * Selecting by stream order rather than by maximum `endTimestamp` mirrors Python ADK's
 * `_latest_compaction_event`.
 */
internal fun latestCompactionEvent(events: List<Event>): Event? {
  val compactions = events.withIndex().filter { it.value.isCompactionEvent() }
  return compactions.lastOrNull { !isCompactionSubsumed(it, compactions) }?.value
}

/**
 * Returns whether [candidate]'s compaction range is fully contained by another compaction in
 * [compactions]. Identical ranges are ordered by stream position: the earlier event is treated as
 * subsumed by the later one.
 */
private fun isCompactionSubsumed(
  candidate: IndexedValue<Event>,
  compactions: List<IndexedValue<Event>>,
): Boolean {
  val range = candidate.value.actions.compaction!!
  return compactions.any { other ->
    if (other.index == candidate.index) return@any false
    val otherRange = other.value.actions.compaction!!
    otherRange.startTimestamp <= range.startTimestamp &&
      otherRange.endTimestamp >= range.endTimestamp &&
      (otherRange.startTimestamp < range.startTimestamp ||
        otherRange.endTimestamp > range.endTimestamp ||
        other.index > candidate.index)
  }
}
