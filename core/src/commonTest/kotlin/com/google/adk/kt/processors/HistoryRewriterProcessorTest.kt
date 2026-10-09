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
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.ToolCall
import com.google.adk.kt.types.ToolResponse
import com.google.adk.kt.types.ToolType
import com.google.adk.kt.types.Transcription
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

  // A live session stores what was said as transcriptions only, and a new session replays them.
  @Test
  fun rewrite_transcriptionOnlyEvents_becomeUserAndModelText() {
    val events = listOf(heard("My name "), heard("is Ada."), spoken("Hi "), spoken("Ada!"))

    assertEquals(listOf("user" to "My name is Ada.", "model" to "Hi Ada!"), turns(rewrite(events)))
  }

  @Test
  fun rewrite_typedAndSpokenTurns_keepTheirOrder() {
    val events =
      listOf(
        userEvent("Hello."),
        modelPartEvent(Part(text = "Hi, who is this?")),
        heard("It is Ada."),
        spoken("Nice to meet you, "),
        spoken("Ada."),
        userEvent("What is my name?"),
      )

    assertEquals(
      listOf(
        "user" to "Hello.",
        "model" to "Hi, who is this?",
        "user" to "It is Ada.",
        "model" to "Nice to meet you, Ada.",
        "user" to "What is my name?",
      ),
      turns(rewrite(events)),
    )
  }

  @Test
  fun rewrite_alternatingSpokenTurns_staySeparate() {
    val events = listOf(heard("a"), spoken("b"), heard("c"), spoken("d"))

    assertEquals(
      listOf("user" to "a", "model" to "b", "user" to "c", "model" to "d"),
      turns(rewrite(events)),
    )
  }

  @Test
  fun rewrite_inputThenOutputChunkFromOneAuthor_stayApart() {
    val events =
      listOf(heard("a"), Event(author = "user", outputTranscription = Transcription("b")))

    assertEquals(listOf("user" to "a", "model" to "b"), turns(rewrite(events)))
  }

  @Test
  fun rewrite_spokenRunsSplitByATypedTurn_stayApart() {
    val events = listOf(heard("a"), userEvent("typed"), heard("b"))

    assertEquals(listOf("user" to "a", "user" to "typed", "user" to "b"), turns(rewrite(events)))
  }

  @Test
  fun rewrite_runBeforeAnEventWithContent_isFlushedFirst() {
    // The event with content is kept as is, so the run before it must not carry over past it.
    val typed =
      Event(
        author = "user",
        content = userMessage("typed"),
        inputTranscription = Transcription("x"),
      )
    val events = listOf(heard("a"), typed, heard("b"))

    assertEquals(listOf("user" to "a", "user" to "typed", "user" to "b"), turns(rewrite(events)))
  }

  @Test
  fun rewrite_eventWithContentAndTranscription_keepsOnlyItsContent() {
    val event =
      Event(
        author = "user",
        content = userMessage("typed"),
        inputTranscription = Transcription("x"),
      )

    assertEquals(listOf("user" to "typed"), turns(rewrite(listOf(event))))
  }

  @Test
  fun rewrite_emptyChunkInsideRun_doesNotSplitIt() {
    val noText = Event(author = "user", inputTranscription = Transcription())
    val events = listOf(heard("My "), heard(""), noText, heard("name."))

    assertEquals(listOf("user" to "My name."), turns(rewrite(events)))
  }

  @Test
  fun rewrite_emptyTranscription_isDropped() {
    val events = listOf(heard(""), userEvent("Hello."))

    assertEquals(listOf("user" to "Hello."), turns(rewrite(events)))
  }

  @Test
  fun rewrite_otherAgentsSpokenReply_isPresentedAsContext() {
    val events =
      listOf(
        heard("Is it sunny?"),
        spoken("Yes, ", author = "weather_agent"),
        spoken("sunny.", author = "weather_agent"),
      )

    val contents = rewrite(events, agentName = "router")

    assertEquals(
      listOf(
        "user" to listOf("Is it sunny?"),
        "user" to listOf("For context:", "[weather_agent] said: Yes, sunny."),
      ),
      contents.map { content -> content.role to content.parts.map { it.text } },
    )
  }

  @Test
  fun rewrite_spokenRepliesFromTwoAgents_stayAttributedToEach() {
    val events =
      listOf(
        heard("Weather and time?"),
        spoken("Sunny.", author = "weather_agent"),
        spoken("Noon.", author = "clock_agent"),
      )

    val contents = rewrite(events, agentName = "router")

    assertEquals(
      listOf(
        listOf("Weather and time?"),
        listOf("For context:", "[weather_agent] said: Sunny."),
        listOf("For context:", "[clock_agent] said: Noon."),
      ),
      texts(contents),
    )
  }

  @Test
  fun rewrite_includeContentsNone_keepsTheWholeSpokenRun() {
    val events =
      listOf(
        userEvent("Earlier question."),
        modelPartEvent(Part(text = "Earlier answer.")),
        heard("My name "),
        heard("is Ada."),
      )

    val contents = rewrite(events, includeContents = IncludeContents.NONE)

    assertEquals(listOf("user" to "My name is Ada."), turns(contents))
  }

  @Test
  fun rewrite_includeContentsNone_keepsAnotherAgentsWholeSpokenRun() {
    val events =
      listOf(
        userEvent("Is it sunny?"),
        spoken("Yes, ", author = "weather_agent"),
        spoken("sunny.", author = "weather_agent"),
      )

    val contents = rewrite(events, agentName = "router", includeContents = IncludeContents.NONE)

    assertEquals(
      listOf(listOf("For context:", "[weather_agent] said: Yes, sunny.")),
      texts(contents),
    )
  }

  @Test
  fun rewrite_includeContentsNone_otherAgentsSpokenRunWithThoughts_startsTheTurn() {
    fun chunk(text: String) =
      Event(
        author = "weather_agent",
        content = modelMessage(Part(text = "Look it up.", thought = true)),
        outputTranscription = Transcription(text),
      )
    val events = listOf(userEvent("Is it sunny?"), chunk("Yes, "), chunk("sunny."))

    val contents = rewrite(events, agentName = "router", includeContents = IncludeContents.NONE)

    assertEquals(
      listOf(listOf("For context:", "[weather_agent] said: Yes, sunny.")),
      texts(contents),
    )
  }

  @Test
  fun rewrite_includeContentsNone_trailingEmptyChunk_keepsTheTurn() {
    val events = listOf(heard("Hello."), heard(""))

    val contents = rewrite(events, includeContents = IncludeContents.NONE)

    assertEquals(listOf("user" to "Hello."), turns(contents))
  }

  @Test
  fun rewrite_compactionEndingInsideASpokenRun_keepsOnlyTheUncoveredChunks() {
    val events =
      listOf(
        heard("one ", timestamp = 100L),
        heard("two", timestamp = 200L),
        compactionEvent(startTs = 100L, endTs = 150L, summary = "SUM"),
      )

    assertEquals(listOf("model" to "SUM", "user" to "two"), turns(rewrite(events)))
  }

  @Test
  fun rewrite_otherAgentsCompactedLongRunningCall_isRecoveredAndNarrated() {
    val call =
      Event(
        author = "weather_agent",
        content = modelMessage(Part(functionCall = FunctionCall(name = "lookup", id = "c1"))),
        longRunningToolIds = setOf("c1"),
        timestamp = 100L,
      )
    val response =
      Event(
        author = "weather_agent",
        content =
          Content(
            role = "user",
            parts = listOf(Part(functionResponse = FunctionResponse(name = "lookup", id = "c1"))),
          ),
        timestamp = 200L,
      )
    val events =
      listOf(call, compactionEvent(startTs = 100L, endTs = 150L, summary = "SUM"), response)

    val contents = rewrite(events, agentName = "router")

    assertEquals(
      listOf(
        listOf("SUM"),
        listOf("For context:", "[weather_agent] called tool `lookup` with parameters: {}"),
        listOf("For context:", "[weather_agent] `lookup` tool returned result: {}"),
      ),
      texts(contents),
    )
  }

  @Test
  fun rewrite_includeContentsNone_consecutiveUserTurns_keepsOnlyTheLast() {
    val events = listOf(userEvent("Turn 1"), userEvent("Turn 2"))

    val contents = rewrite(events, includeContents = IncludeContents.NONE)

    assertEquals(listOf("user" to "Turn 2"), turns(contents))
  }

  @Test
  fun rewrite_includeContentsNone_otherAgentsTextReplies_keepsOnlyTheLast() {
    val events =
      listOf(
        userEvent("Is it sunny?"),
        modelPartEvent(Part(text = "Checking."), "weather_agent"),
        modelPartEvent(Part(text = "Sunny."), "weather_agent"),
      )

    val contents = rewrite(events, agentName = "router", includeContents = IncludeContents.NONE)

    assertEquals(listOf(listOf("For context:", "[weather_agent] said: Sunny.")), texts(contents))
  }

  @Test
  fun rewrite_includeContentsNone_runWithAnExcludedChunk_staysWhole() {
    val events = listOf(userEvent("Earlier question."), heard("My "), heard(""), heard("name."))

    val contents = rewrite(events, includeContents = IncludeContents.NONE)

    assertEquals(listOf("user" to "My name."), turns(contents))
  }

  @Test
  fun rewrite_emptyContentWithTranscription_isMergedAsTranscription() {
    val event =
      Event(
        author = "user",
        content = Content(role = "user", parts = emptyList()),
        inputTranscription = Transcription(text = "Hi."),
      )

    assertEquals(listOf("user" to "Hi."), turns(rewrite(listOf(event))))
  }

  @Test
  fun rewrite_thoughtOnlyContentWithTranscription_isMergedAsTranscription() {
    val event =
      Event(
        author = "agent",
        content = modelMessage(Part(text = "Greet them.", thought = true)),
        outputTranscription = Transcription(text = "Hello."),
      )

    assertEquals(listOf("model" to "Hello."), turns(rewrite(listOf(event))))
  }

  @Test
  fun rewrite_spaceChunkBetweenWords_isKept() {
    val events = listOf(heard("Hello"), heard(" "), heard("world."))

    assertEquals(listOf("user" to "Hello world."), turns(rewrite(events)))
  }

  @Test
  fun rewrite_blankSpokenRun_isDropped() {
    val events = listOf(heard("Hi."), spoken(" "), spoken("  "))

    assertEquals(listOf("user" to "Hi."), turns(rewrite(events)))
  }

  @Test
  fun rewrite_includeContentsNone_blankSpokenRun_doesNotBlankTheTurn() {
    val events = listOf(userEvent("Is it sunny?"), spoken("   ", author = "weather_agent"))

    val contents = rewrite(events, agentName = "router", includeContents = IncludeContents.NONE)

    assertEquals(listOf("user" to "Is it sunny?"), turns(contents))
  }

  private fun rewrite(
    events: List<Event>,
    agentName: String = "agent",
    includeContents: IncludeContents = IncludeContents.DEFAULT,
  ): List<Content> =
    HistoryRewriterProcessor().rewrite(events, agentName, currentBranch = null, includeContents)

  private fun turns(contents: List<Content>): List<Pair<String?, String?>> = contents.map {
    it.role to it.parts.single().text
  }

  private fun modelPartEvent(part: Part, author: String = "agent"): Event =
    Event(author = author, content = modelMessage(part))

  /** A stored transcription of the user's speech. */
  private fun heard(text: String, timestamp: Long = 0L): Event =
    Event(author = "user", inputTranscription = Transcription(text), timestamp = timestamp)

  /** A stored transcription of an agent's speech. */
  private fun spoken(text: String, author: String = "agent"): Event =
    Event(author = author, outputTranscription = Transcription(text))

  private fun texts(contents: List<Content>): List<List<String?>> = contents.map { content ->
    content.parts.map { it.text }
  }
}
