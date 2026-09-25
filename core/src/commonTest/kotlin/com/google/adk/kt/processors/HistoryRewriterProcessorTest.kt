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

import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.compactionEvent
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userEvent
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

  // On models that return a signature for every part, it arrives on parts holding nothing else.
  // Dropping those as "empty" loses the reasoning the model expects back on the next turn.
  @Test
  fun rewrite_contentFreeThoughtSignatureEvent_isKept() {
    val signature = byteArrayOf(7, 7, 7)
    val events =
      listOf(userEvent("Summarize the video."), modelPartEvent(Part(thoughtSignature = signature)))

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(2, contents.size)
    assertNotNull(contents[1].parts[0].thoughtSignature)
  }

  // A thought part carrying a signature is kept for the same reason, though a bare thought is not.
  @Test
  fun rewrite_thoughtWithSignatureEvent_isKept() {
    val events =
      listOf(
        userEvent("Summarize the video."),
        modelPartEvent(
          Part(thought = true, text = "Checking 0:05.", thoughtSignature = byteArrayOf(1, 2, 3))
        ),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(2, contents.size)
    assertNotNull(contents[1].parts[0].thoughtSignature)
  }

  // The model runs server-side tools itself and requires the caller to echo the parts back on the
  // next request. Dropping them as "empty" makes the model redo the work, or fail because a call
  // has no matching response.
  @Test
  fun rewrite_serverSideToolCallAndResponseEvents_areKept() {
    val events =
      listOf(
        userEvent("Summarize the linked page."),
        modelPartEvent(
          Part(
            toolCall =
              ToolCall(
                id = "tc1",
                toolType = ToolType.URL_CONTEXT,
                args = mapOf("url" to "https://example.com"),
              )
          )
        ),
        modelPartEvent(
          Part(
            toolResponse =
              ToolResponse(
                id = "tc1",
                toolType = ToolType.URL_CONTEXT,
                response = mapOf("content" to "page text"),
              )
          )
        ),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(3, contents.size)
    assertEquals("tc1", contents[1].parts[0].toolCall?.id)
    val toolResponse = contents[2].parts[0].toolResponse
    assertNotNull(toolResponse)
    assertEquals("tc1", toolResponse.id)
    assertEquals(mapOf("content" to "page text"), toolResponse.response)
  }

  // The echo-back contract holds regardless of how the model labels the part, so a thought marking
  // must not drop it.
  @Test
  fun rewrite_serverSideToolCallMarkedAsThought_isKept() {
    val events =
      listOf(
        userEvent("Summarize the linked page."),
        modelPartEvent(
          Part(thought = true, toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT))
        ),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(2, contents.size)
    assertEquals("tc1", contents[1].parts[0].toolCall?.id)
  }

  // A server-side call belongs to the model instance that made it, so the other-agent path keeps
  // dropping it rather than claiming the call on this agent's behalf.
  @Test
  fun rewrite_serverSideToolCallFromOtherAgent_isDropped() {
    val events =
      listOf(
        userEvent("Summarize the linked page."),
        Event(
          author = "other_agent",
          content =
            // The text keeps the event past the visibility filter, so the assertions below are
            // about the narration refusing the call, not about the event vanishing.
            modelMessage(
              Part(text = "Here is the summary."),
              Part(toolCall = ToolCall(id = "tc1", toolType = ToolType.URL_CONTEXT)),
            ),
        ),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(2, contents.size)
    assertEquals(
      listOf("For context:", "[other_agent] said: Here is the summary."),
      contents[1].parts.map { it.text },
    )
    assertTrue(contents[1].parts.none { it.toolCall != null })
  }

  // A signature carrier is visible but has nothing to narrate, so it must not start the current
  // turn: doing so truncates history at an event that then contributes nothing, leaving no request.
  @Test
  fun rewrite_includeContentsNone_doesNotStartTheTurnOnASignatureOnlyEvent() {
    val events =
      listOf(
        userEvent("Real question."),
        modelPartEvent(Part(thoughtSignature = byteArrayOf(7, 7, 7)), author = "other_agent"),
      )

    val contents =
      HistoryRewriterProcessor()
        .rewrite(
          events,
          agentName = "agent",
          currentBranch = null,
          includeContents = IncludeContents.NONE,
        )

    assertEquals(1, contents.size)
    assertEquals("Real question.", contents[0].parts[0].text)
  }

  // The signature carrier the aggregator emits carries no text to attribute, so the other-agent
  // path must not narrate a bare "said:" for it and keep the event alive on that alone.
  @Test
  fun rewrite_emptyTextSignaturePartFromOtherAgent_isNotNarrated() {
    val events =
      listOf(
        userEvent("Summarize the video."),
        modelPartEvent(Part(text = "", thoughtSignature = byteArrayOf(7, 7, 7)), "other_agent"),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(1, contents.size)
    assertEquals("Summarize the video.", contents[0].parts[0].text)
  }

  // Whitespace-only text is not content either, so the carrier that carries it must not be narrated
  // as a bare "said:". Matches what the emptiness rule already treats as blank.
  @Test
  fun rewrite_blankTextSignaturePartFromOtherAgent_isNotNarrated() {
    val events =
      listOf(
        userEvent("Summarize the video."),
        modelPartEvent(Part(text = "   ", thoughtSignature = byteArrayOf(7, 7, 7)), "other_agent"),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(1, contents.size)
    assertEquals("Summarize the video.", contents[0].parts[0].text)
  }

  // Another agent's reasoning belongs to that agent and is never narrated: only the answer text
  // beside it may be attributed.
  @Test
  fun rewrite_thoughtTextFromOtherAgent_isNotNarrated() {
    val events =
      listOf(
        userEvent("Where is it?"),
        Event(
          author = "other_agent",
          content =
            modelMessage(
              Part(text = "Let me check the map.", thought = true),
              Part(text = "Paris."),
            ),
        ),
      )

    val contents =
      HistoryRewriterProcessor().rewrite(events, agentName = "agent", currentBranch = null)

    assertEquals(2, contents.size)
    assertEquals(
      listOf("For context:", "[other_agent] said: Paris."),
      contents[1].parts.map { it.text },
    )
  }

  private fun modelPartEvent(part: Part, author: String = "agent"): Event =
    Event(author = author, content = modelMessage(part))
}
