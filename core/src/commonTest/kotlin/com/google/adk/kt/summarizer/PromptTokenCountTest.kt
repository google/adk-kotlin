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
import com.google.adk.kt.testing.eventWithFunctionCall
import com.google.adk.kt.testing.eventWithFunctionResponse
import com.google.adk.kt.testing.modelEvent
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
  fun latestPromptTokenCount_skipsUserEvents() {
    val events =
      listOf(
        modelEventWithPromptTokens(promptTokenCount = 30, timestamp = 100L, invocationId = "inv_1"),
        userEvent("follow up", timestamp = 200L, invocationId = "inv_2"),
      )

    assertEquals(30, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_skipsEventsWithoutUsageMetadata() {
    val events =
      listOf(
        modelEventWithPromptTokens(promptTokenCount = 30, timestamp = 100L, invocationId = "inv_1"),
        // Newer agent-authored event without usage metadata must be skipped in favor of the last
        // reported count.
        modelEvent("follow up", timestamp = 200L, invocationId = "inv_2", author = "agent"),
      )

    assertEquals(30, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_skipsCountsFromOtherAgents() {
    // A turn that ends in a small sub-agent: its count describes the sub-agent's prompt, not this
    // agent's, so the agent's own (much larger) count is the one that must be measured.
    val events =
      listOf(
        modelEventWithPromptTokens(
          promptTokenCount = 5000,
          timestamp = 100L,
          invocationId = "inv_1",
        ),
        modelEventWithPromptTokens(
          promptTokenCount = 100,
          timestamp = 200L,
          invocationId = "inv_1",
          author = "formatter",
        ),
      )

    assertEquals(5000, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_emptyAgentName_usesLatestCountFromAnyAuthor() {
    // An empty name scopes to no agent, so a caller driving the compactor outside an agent context
    // keeps reading real counts instead of silently dropping to the estimate.
    val events =
      listOf(
        modelEventWithPromptTokens(
          promptTokenCount = 5000,
          timestamp = 100L,
          invocationId = "inv_1",
        ),
        modelEventWithPromptTokens(
          promptTokenCount = 100,
          timestamp = 200L,
          invocationId = "inv_1",
          author = "formatter",
        ),
      )

    assertEquals(100, latestPromptTokenCount(events, agentName = "", branch = null))
  }

  @Test
  fun latestPromptTokenCount_onlyOtherAgentsReportedCounts_fallsBackToEstimate() {
    // The sole reported count belongs to another agent, so it is ignored rather than borrowed and
    // the estimate covers the rewritten prompt instead.
    val events =
      listOf(
        userEvent("a".repeat(100), timestamp = 100L, invocationId = "inv_1"),
        modelEventWithPromptTokens(
          promptTokenCount = 5000,
          timestamp = 200L,
          invocationId = "inv_1",
          author = "formatter",
        ),
      )

    // 100-char user message plus the sub-agent's reply presented as context ("For context:" +
    // "[formatter] said: ok" = 32 chars) -> 132 / 4 = 33 tokens, nowhere near the ignored 5000.
    assertEquals(33, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_countRecordedBeforeCompaction_fallsBackToEstimate() {
    // The only reported count measures the prompt the summary replaced, so it is dropped and the
    // estimate describes the compacted history instead.
    val events =
      listOf(
        modelEventWithPromptTokens(
          promptTokenCount = 5000,
          timestamp = 100L,
          invocationId = "inv_1",
        ),
        compactionEvent(startTs = 100L, endTs = 100L, timestamp = 110L, summary = "s".repeat(40)),
      )

    // Rewritten prompt is the 40-char summary -> 10 tokens, nowhere near the dropped 5000.
    assertEquals(10, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_countRecordedAfterCompaction_isUsed() {
    // A count recorded after the summarization measures the compacted prompt, so the scan returns
    // it rather than stopping at the compaction event.
    val events =
      listOf(
        modelEventWithPromptTokens(
          promptTokenCount = 5000,
          timestamp = 100L,
          invocationId = "inv_1",
        ),
        compactionEvent(startTs = 100L, endTs = 100L, timestamp = 110L),
        modelEventWithPromptTokens(promptTokenCount = 120, timestamp = 200L, invocationId = "inv_2"),
      )

    assertEquals(120, latestPromptTokenCount(events, agentName = "agent", branch = null))
  }

  @Test
  fun latestPromptTokenCount_compactionEventReportsOwnCount_fallsBackToEstimate() {
    // A summarizer attaches the usage of its own model call to the compaction event it authors. If
    // that event were also authored as the agent, its prompt size would be read as the agent's.
    val events =
      listOf(
        userEvent("a".repeat(100), timestamp = 100L, invocationId = "inv_1"),
        compactionEvent(
          startTs = 100L,
          endTs = 100L,
          timestamp = 110L,
          summary = "s".repeat(40),
          author = "agent",
          promptTokenCount = 90_000,
        ),
      )

    // Rewritten prompt is the 40-char summary -> 10 tokens, not the summarizer's own 90_000.
    assertEquals(10, latestPromptTokenCount(events, agentName = "agent", branch = null))
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
  fun latestPromptTokenCount_estimateCountsToolTraffic() {
    val events =
      listOf(
        eventWithFunctionCall(
          invocationId = "inv_1",
          timestamp = 100L,
          callName = "search",
          callId = "call_1",
          args = mapOf("query" to "a".repeat(100)),
        ),
        eventWithFunctionResponse(
          invocationId = "inv_1",
          timestamp = 110L,
          name = "search",
          callId = "call_1",
          response = mapOf("result" to "b".repeat(100)),
        ),
      )
    // Call: "search" (6) + {"query":"a*100"} (112).
    // Response: "search" (6) + {"result":"b*100"} (113).
    // Total: 237 / 4 = 59.
    assertEquals(59, latestPromptTokenCount(events, agentName = "model", branch = null))
  }

  @Test
  fun latestPromptTokenCount_estimateWithEmptyToolPayloads_countsOnlyToolNames() {
    // An empty payload contributes 0 chars (rather than 2 for "{}"), matching Python and Go.
    // Call name "search" (6) + response name "search" (6) = 12 chars, and 12 / 4 = 3.
    val events =
      listOf(
        eventWithFunctionCall(
          invocationId = "inv_1",
          timestamp = 100L,
          callName = "search",
          callId = "call_1",
        ),
        eventWithFunctionResponse(
          invocationId = "inv_1",
          timestamp = 110L,
          name = "search",
          callId = "call_1",
        ),
      )

    assertEquals(3, latestPromptTokenCount(events, agentName = "model", branch = null))
  }

  @Test
  fun latestPromptTokenCount_estimateFallsBackWhenPayloadIsNotJsonNative() {
    // The estimate runs before a model call, so a payload the JSON encoder rejects must not fail
    // the turn. Name "t" (1) + the map's toString "{x=XXXX}" (8) = 9, and 9 / 4 = 2.
    val events =
      listOf(
        eventWithFunctionCall(
          invocationId = "inv_1",
          timestamp = 100L,
          callName = "t",
          callId = "call_1",
          args = mapOf("x" to Unserializable()),
        )
      )

    assertEquals(2, latestPromptTokenCount(events, agentName = "model", branch = null))
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

  /** A payload value the JSON encoder rejects, with a stable rendering for the fallback count. */
  private class Unserializable {
    override fun toString(): String = "XXXX"
  }
}
