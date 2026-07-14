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
package com.google.adk.kt.processors

import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.userEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryRewriterProcessorTest {

  @Test
  fun rewrite_laterEventSharingCompactionEndTimestamp_isKept() {
    // Forward same-millisecond tie: a summary covers [100, 200], and a later raw event shares the
    // summary's endTimestamp (200). Because it appears after the summary in the stream it is not
    // covered by it, so it must survive in the rebuilt context -- while the earlier event it does
    // cover is replaced by the summary.
    val events =
      listOf(
        userEvent("covered", timestamp = 100L),
        compactionEvent(startTs = 100L, endTs = 200L, summary = "SUM"),
        userEvent("later", timestamp = 200L),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)
    val texts = contents.flatMap { it.parts }.mapNotNull { it.text }

    assertEquals(listOf("SUM", "later"), texts)
  }

  @Test
  fun rewrite_retainedEventsAboveSummaryRange_areKept() {
    // Token-threshold tail-retention layout: the summary is appended last, after the retained tail,
    // and covers only [100, 100]. The retained events (200) precede the summary in the stream but
    // sit above its range, so they must be kept, and the summary is placed at its endTimestamp.
    val events =
      listOf(
        userEvent("covered", timestamp = 100L),
        userEvent("a", timestamp = 200L),
        userEvent("b", timestamp = 200L),
        compactionEvent(startTs = 100L, endTs = 100L, summary = "SUM"),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)
    val texts = contents.flatMap { it.parts }.mapNotNull { it.text }

    assertEquals(listOf("SUM", "a", "b"), texts)
  }

  @Test
  fun rewrite_multipleSummaries_keepBothAndDropCoveredEvents() {
    // Two summaries with a forward tie: S1 covers [100, 200] and S2 covers [200, 300]. The event at
    // 200 that follows S1 ties S1's endTimestamp but is covered by S2 (not S1). Both summaries are
    // kept and every covered raw event is dropped.
    val events =
      listOf(
        userEvent("u1", timestamp = 100L),
        userEvent("u2", timestamp = 200L),
        compactionEvent(startTs = 100L, endTs = 200L, summary = "S1"),
        userEvent("u3", timestamp = 200L),
        userEvent("u4", timestamp = 300L),
        compactionEvent(startTs = 200L, endTs = 300L, summary = "S2"),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)
    val texts = contents.flatMap { it.parts }.mapNotNull { it.text }

    assertEquals(listOf("S1", "S2"), texts)
  }
}
