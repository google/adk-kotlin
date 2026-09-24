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
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.EventCompaction
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelEvent
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userEvent
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class LlmEventSummarizerTest {

  @Test
  fun construct_promptTemplateMissingPlaceholder_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      LlmEventSummarizer(model = DummyModel("test-model"), promptTemplate = "no placeholder here")
    }
  }

  @Test
  fun summarizeEvents_emptyList_returnsNullWithoutCallingModel() = runTest {
    var calls = 0
    val model =
      DummyModel("test-model") {
        calls++
        emptyFlow()
      }
    val summarizer = LlmEventSummarizer(model = model)

    val result = summarizer.summarizeEvents(emptyList())

    assertNull(result)
    assertEquals(0, calls)
  }

  @Test
  fun summarizeEvents_modelReturnsResponseWithNoContent_returnsNull() = runTest {
    val summarizer =
      LlmEventSummarizer(model = DummyModel("test-model") { flowOf(LlmResponse(content = null)) })

    val result = summarizer.summarizeEvents(listOf(userEvent("Hello", timestamp = 1L)))

    assertNull(result)
  }

  @Test
  fun summarizeEvents_modelReturnsSummary_buildsEventWithCompactionCoveringRange() = runTest {
    val summaryContent = Content(parts = listOf(Part(text = "summary"))) // no role on input
    val usage = UsageMetadata(promptTokenCount = 7, candidatesTokenCount = 3, totalTokenCount = 10)
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") {
            flowOf(LlmResponse(content = summaryContent, usageMetadata = usage))
          }
      )

    val result =
      summarizer.summarizeEvents(
        listOf(
          userEvent("Hello", timestamp = 100L),
          modelEvent("Hi there!", timestamp = 150L),
          userEvent("Goodbye", timestamp = 200L),
          modelEvent("See you!", timestamp = 250L),
        )
      )

    assertNotNull(result)
    assertNotNull(result.invocationId)
    assertEquals(Role.USER, result.author)
    assertEquals(usage, result.usageMetadata)
    val compaction = result.actions.compaction
    assertNotNull(compaction)
    assertEquals(100L, compaction.startTimestamp)
    assertEquals(250L, compaction.endTimestamp)
    assertEquals(Role.MODEL, compaction.compactedContent.role)
    assertEquals(listOf(Part(text = "summary")), compaction.compactedContent.parts)
  }

  @Test
  fun summarizeEvents_singleEvent_compactionStartEqualsEnd() = runTest {
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") {
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val result = summarizer.summarizeEvents(listOf(userEvent("Hello", timestamp = 42L)))

    val compaction = assertNotNull(result).actions.compaction
    assertNotNull(compaction)
    assertEquals(42L, compaction.startTimestamp)
    assertEquals(42L, compaction.endTimestamp)
  }

  @Test
  fun summarizeEvents_formatsConversationHistoryCorrectly() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val events =
      listOf(
        userEvent("User says...", timestamp = 1L),
        modelEvent("Model replies...", timestamp = 2L),
        userEvent("Another user input", timestamp = 3L),
        modelEvent("More model text", timestamp = 4L),
        // Event with no content — silently skipped.
        Event(author = "user", content = null, timestamp = 5L),
        // Event with an empty-text part — silently skipped by the isNotEmpty filter.
        Event(author = "model", content = modelMessage(""), timestamp = 6L),
        // Event with a function call — rendered as a tool-call line.
        Event(
          author = "model",
          content = modelFunctionCall(name = "tool", args = mapOf("k" to "v")),
          timestamp = 7L,
        ),
        // Event with a function response — rendered as a tool-response line.
        Event(
          author = "model",
          content =
            modelMessage(
              Part(functionResponse = FunctionResponse(name = "tool", response = mapOf("a" to "b")))
            ),
          timestamp = 8L,
        ),
      )

    assertNotNull(summarizer.summarizeEvents(events))

    val expectedHistory =
      "user: User says...\nmodel: Model replies...\nuser: Another user input\nmodel: More model" +
        " text\nmodel called tool: tool({k=v})\nTool response from tool: {a=b}"
    val expectedPrompt =
      "The following is a conversation history between a user and an AI agent. It may or may not " +
        "start from a compacted history. Please identify and reiterate the user request, " +
        "summarize the context so far, focusing on key decisions made and information obtained, " +
        "as well as any unresolved questions or tasks. The summary should be concise and capture " +
        "the essence of the interaction.\n\n$expectedHistory"
    assertEquals(expectedPrompt, captured.single().contents.single().parts.single().text)
  }

  @Test
  fun summarizeEvents_labelUsesAuthorNotContentRole() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    // Author ("weather_agent") differs from the content role ("model"); the label uses the author.
    val event = Event(author = "weather_agent", content = modelMessage("sunny"), timestamp = 1L)

    assertNotNull(summarizer.summarizeEvents(listOf(event)))

    val promptText = captured.single().contents.single().parts.single().text
    assertNotNull(promptText)
    assertEquals(true, promptText.endsWith("\nweather_agent: sunny"))
  }

  @Test
  fun summarizeEvents_contentRoleMissing_labelIsAuthor() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val event =
      Event(
        author = "weather_tool",
        content = Content(role = null, parts = listOf(Part(text = "65F"))),
        timestamp = 1L,
      )

    assertNotNull(summarizer.summarizeEvents(listOf(event)))

    val promptText = captured.single().contents.single().parts.single().text
    assertNotNull(promptText)
    assertEquals(true, promptText.endsWith("\nweather_tool: 65F"))
  }

  @Test
  fun summarizeEvents_includesThoughts() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val events =
      listOf(
        userEvent("What is the weather?", timestamp = 1L),
        Event(
          author = "model",
          content =
            modelMessage(
              Part(text = "Let me check the tool output.", thought = true),
              Part(text = "It is sunny."),
            ),
          timestamp = 2L,
        ),
      )

    assertNotNull(summarizer.summarizeEvents(events))

    val promptText = captured.single().contents.single().parts.single().text
    assertNotNull(promptText)
    assertEquals(
      true,
      promptText.endsWith(
        "user: What is the weather?\nmodel (thought): Let me check the tool output.\n" +
          "model: It is sunny."
      ),
    )
  }

  @Test
  fun summarizeEvents_skipsCompactionEventThought() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val events =
      listOf(
        Event(
          author = "model",
          content =
            modelMessage(
              Part(text = "Stale summarizer reasoning.", thought = true),
              Part(text = "Prior summary."),
            ),
          actions =
            EventActions(
              compaction =
                EventCompaction(
                  startTimestamp = 0L,
                  endTimestamp = 1L,
                  compactedContent = modelMessage("Prior"),
                )
            ),
          timestamp = 1L,
        ),
        userEvent("New user input", timestamp = 2L),
      )

    assertNotNull(summarizer.summarizeEvents(events))

    val promptText = captured.single().contents.single().parts.single().text
    assertNotNull(promptText)
    assertEquals(true, promptText.endsWith("model: Prior summary.\nuser: New user input"))
    // The compaction event's own thought must not leak into the next summary.
    assertEquals(false, promptText.contains("Stale summarizer reasoning."))
  }

  @Test
  fun summarizeEvents_truncatesLargeToolResponse() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          }
      )

    val largeValue = "x".repeat(2500)
    val events =
      listOf(
        Event(
          author = "model",
          content =
            modelMessage(
              Part(
                functionResponse =
                  FunctionResponse(name = "search", response = mapOf("data" to largeValue))
              )
            ),
          timestamp = 1L,
        )
      )

    assertNotNull(summarizer.summarizeEvents(events))

    val promptText = captured.single().contents.single().parts.single().text
    assertNotNull(promptText)

    val rendered = mapOf("data" to largeValue).toString()
    assertEquals(
      true,
      promptText.endsWith(
        "Tool response from search: ${rendered.take(2000)}... [truncated ${rendered.length - 2000} chars]"
      ),
    )
    assertEquals(false, promptText.contains(largeValue))
  }

  @Test
  fun summarizeEvents_customPromptTemplate_isUsed() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val summarizer =
      LlmEventSummarizer(
        model =
          DummyModel("test-model") { request ->
            captured.add(request)
            flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "summary")))))
          },
        promptTemplate = "RECAP:\n{conversation_history}\nEND",
      )

    assertNotNull(summarizer.summarizeEvents(listOf(userEvent("hello", timestamp = 1L))))

    val promptText = captured.single().contents.single().parts.single().text
    assertEquals("RECAP:\nuser: hello\nEND", promptText)
  }

  @Test
  fun summarizeEvents_sentRequestUsesProvidedModelAndUserRole() = runTest {
    val captured = mutableListOf<LlmRequest>()
    val model =
      DummyModel("test-model") { request ->
        captured.add(request)
        flowOf(LlmResponse(content = Content(parts = listOf(Part(text = "s")))))
      }
    val summarizer = LlmEventSummarizer(model = model)

    assertNotNull(summarizer.summarizeEvents(listOf(userEvent("hi", timestamp = 1L))))

    val request = captured.single()
    assertEquals("test-model", request.model?.name)
    assertEquals(Role.USER, request.contents.single().role)
  }
}
