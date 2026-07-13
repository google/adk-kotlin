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

import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.modelEventWithPromptTokens
import com.google.adk.kt.testing.rewindEvent
import com.google.adk.kt.testing.userEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptTokenCountTest {

  @Test
  fun latestPromptTokenCount_returnsMostRecentUsageMetadata() {
    val events =
      listOf(
        modelEventWithPromptTokens(promptTokenCount = 10, timestamp = 100L, invocationId = "inv_1"),
        modelEventWithPromptTokens(promptTokenCount = 20, timestamp = 200L, invocationId = "inv_2"),
      )

    assertEquals(20, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_skipsEventsWithoutUsageMetadata() {
    val events =
      listOf(
        modelEventWithPromptTokens(promptTokenCount = 30, timestamp = 100L, invocationId = "inv_1"),
        // Newer events without usage metadata must be skipped in favor of the last reported count.
        userEvent("follow up", timestamp = 200L, invocationId = "inv_2"),
      )

    assertEquals(30, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_noUsageMetadata_fallsBackToCharEstimate() {
    // 100 text chars with no usage metadata -> 100 / 4 = 25 estimated tokens.
    val events = listOf(userEvent("a".repeat(100), timestamp = 100L, invocationId = "inv_1"))

    assertEquals(25, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_prefersUsageMetadataOverEstimate() {
    // A large text body would estimate high, but the reported usage metadata wins.
    val events =
      listOf(
        userEvent("a".repeat(400), timestamp = 100L, invocationId = "inv_1"),
        modelEventWithPromptTokens(promptTokenCount = 7, timestamp = 110L, invocationId = "inv_1"),
      )

    assertEquals(7, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_noUsageMetadataAndNoText_returnsNull() {
    assertNull(latestPromptTokenCount(emptyList(), agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_estimateReflectsCompactedRange() {
    // A large raw event covered by a compaction with a short summary. The estimate is built from
    // the rewritten prompt (the summary replaces the covered event), not the raw characters --
    // exercising the routing through HistoryRewriterProcessor.
    val rawEvent = userEvent("a".repeat(400), invocationId = "inv_1", timestamp = 100L)
    val compaction =
      compactionEvent(startTs = 100L, endTs = 100L, timestamp = 110L, summary = "s".repeat(40))
    val events = listOf(rawEvent, compaction)

    // Rewritten prompt is the 40-char summary -> 10 tokens; counting the raw 400 chars would give
    // 100.
    assertEquals(10, latestPromptTokenCount(events, agentName = "model", branch = null))
  }

  @Test
  fun latestPromptTokenCount_estimateReflectsRewinds() {
    // inv_2's large event is rewound away, leaving only inv_1's small event. The estimate is built
    // from the post-rewind prompt, not the raw characters -- exercising the routing through
    // HistoryRewriterProcessor.
    val kept = userEvent("a".repeat(40), invocationId = "inv_1", timestamp = 100L)
    val rewound = userEvent("b".repeat(400), invocationId = "inv_2", timestamp = 200L)
    val rewind =
      rewindEvent(invocationId = "inv_3", rewoundInvocationId = "inv_2", timestamp = 300L)
    val events = listOf(kept, rewound, rewind)

    // Only the 40-char kept event survives -> 10 tokens; counting all raw chars would give 110.
    assertEquals(10, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }
}
