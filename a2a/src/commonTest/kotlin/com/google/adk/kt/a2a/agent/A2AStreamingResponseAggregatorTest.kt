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

import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class A2AStreamingResponseAggregatorTest {

  private val invocationId = "test-invocation"
  private val agentName = "test-agent"
  private val aggregator = A2AStreamingResponseAggregator(invocationId, agentName)

  @Test
  fun processEvent_completedAndBufferEmpty_returnsSameEvent() {
    val event = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        event,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(event), result)
  }

  @Test
  fun processEvent_completedAndBufferNotEmpty_mergesBuffer() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Hello"),
      )
    val setupResult =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(partialEvent), setupResult)

    val completedEvent = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(1, result.size)
    val merged = result[0]
    assertEquals("true", merged.customMetadata?.get(A2A_METADATA_AGGREGATED))
    assertEquals("Hello", merged.content?.parts?.get(0)?.text)
  }

  @Test
  fun processEvent_partialBuffersContent() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("World"),
      )
    val result =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )

    assertEquals(listOf(partialEvent), result)
  }

  @Test
  fun processEvent_partialResetsBuffer() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Old"),
      )
    val setupResult =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(partialEvent), setupResult)

    val resetEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("New"),
      )
    val result =
      aggregator.processEvent(
        resetEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = true,
      )

    assertEquals(listOf(resetEvent), result)
  }

  @Test
  fun processEvent_completedAndBufferNotEmptyAndEventHasContent_emitsAggregatedFirst() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Hello"),
      )
    val setupResult =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(partialEvent), setupResult)

    val completedEvent =
      Event(author = agentName, invocationId = invocationId, content = modelMessage("Final"))
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(2, result.size)
    assertEquals("Hello", result[0].content?.parts?.get(0)?.text)
    assertEquals("Final", result[1].content?.parts?.get(0)?.text)
  }

  @Test
  fun processEvent_partialNotBufferedFlushesBuffer() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Buffered"),
      )
    val setupResult =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(partialEvent), setupResult)

    val toolCallEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = Content(role = Role.MODEL, parts = emptyList()),
      )
    val result =
      aggregator.processEvent(
        toolCallEvent,
        isCompleted = false,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(2, result.size)
    assertEquals("Buffered", result[0].content?.parts?.get(0)?.text)
    assertEquals(toolCallEvent, result[1])
  }

  @Test
  fun processEvent_nonPartialFlushesBuffer() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Part 1"),
      )
    val setupResult =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(partialEvent), setupResult)

    val nonPartialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = false,
        content = modelMessage("Part 2"),
      )
    val result =
      aggregator.processEvent(
        nonPartialEvent,
        isCompleted = false,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(2, result.size)
    assertEquals("Part 1", result[0].content?.parts?.get(0)?.text)
    assertEquals(nonPartialEvent, result[1])
  }

  @Test
  fun processEvent_nonPartialResetsBufferInsteadOfFlushing() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Part 1"),
      )
    val unused =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )

    val nonPartialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = false,
        content = modelMessage("Part 2"),
      )
    val result =
      aggregator.processEvent(
        nonPartialEvent,
        isCompleted = false,
        shouldBuffer = false,
        shouldResetBuffer = true,
      )

    assertEquals(listOf(nonPartialEvent), result)
  }

  @Test
  fun processEvent_buffersThoughts() {
    val thoughtEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(Part(text = "Thinking...", thought = true)),
      )
    val setupResult =
      aggregator.processEvent(
        thoughtEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(thoughtEvent), setupResult)

    val completedEvent = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(1, result.size)
    val merged = result[0]
    assertEquals("Thinking...", merged.content?.parts?.get(0)?.text)
    assertEquals(true, merged.content?.parts?.get(0)?.thought)
  }

  @Test
  fun processEvent_buffersInterleavedThoughtsAndText() {
    val thoughtEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(Part(text = "Thinking...", thought = true)),
      )
    val r1 =
      aggregator.processEvent(
        thoughtEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(thoughtEvent), r1)

    val textEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Hello"),
      )
    val r2 =
      aggregator.processEvent(
        textEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(textEvent), r2)

    val moreThoughtEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(Part(text = "More thinking...", thought = true)),
      )
    val r3 =
      aggregator.processEvent(
        moreThoughtEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(moreThoughtEvent), r3)

    val completedEvent = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(1, result.size)
    val merged = result[0]
    val parts = merged.content?.parts.orEmpty()
    assertEquals(3, parts.size)
    assertEquals("Thinking...", parts[0].text)
    assertEquals(true, parts[0].thought)
    assertEquals("Hello", parts[1].text)
    assertEquals("More thinking...", parts[2].text)
    assertEquals(true, parts[2].thought)
  }

  @Test
  fun processEvent_buffersConsecutiveTextParts() {
    val event1 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Hello"),
      )
    val r1 =
      aggregator.processEvent(
        event1,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(event1), r1)

    val event2 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(" World"),
      )
    val r2 =
      aggregator.processEvent(
        event2,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(event2), r2)

    val completedEvent = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(1, result.size)
    val merged = result[0]
    assertEquals("Hello World", merged.content?.parts?.get(0)?.text)
  }

  @Test
  fun processEvent_buffersConsecutiveThoughtParts() {
    val event1 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(Part(text = "Thinking", thought = true)),
      )
    val r1 =
      aggregator.processEvent(
        event1,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(event1), r1)

    val event2 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage(Part(text = " more...", thought = true)),
      )
    val r2 =
      aggregator.processEvent(
        event2,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )
    assertEquals(listOf(event2), r2)

    val completedEvent = Event(author = agentName, invocationId = invocationId)
    val result =
      aggregator.processEvent(
        completedEvent,
        isCompleted = true,
        shouldBuffer = false,
        shouldResetBuffer = false,
      )

    assertEquals(1, result.size)
    val merged = result[0]
    assertEquals("Thinking more...", merged.content?.parts?.get(0)?.text)
    assertEquals(true, merged.content?.parts?.get(0)?.thought)
  }

  @Test
  fun processEvent_partialResetsBufferAndNotBufferedDoesNotEmitEmptyEvent() {
    val partialEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Old"),
      )
    val unused =
      aggregator.processEvent(
        partialEvent,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
      )

    val resetEvent =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = Content(role = Role.MODEL, parts = emptyList()),
      )
    val result =
      aggregator.processEvent(
        resetEvent,
        isCompleted = false,
        shouldBuffer = false,
        shouldResetBuffer = true,
      )

    assertEquals(listOf(resetEvent), result)
  }

  @Test
  fun processEvent_partialLastChunkFlushesBuffer() {
    val partialEvent1 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Part 1"),
      )
    val unused1 =
      aggregator.processEvent(
        partialEvent1,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
        isLastChunk = false,
      )

    val partialEvent2 =
      Event(
        author = agentName,
        invocationId = invocationId,
        partial = true,
        content = modelMessage("Part 2"),
      )
    val result =
      aggregator.processEvent(
        partialEvent2,
        isCompleted = false,
        shouldBuffer = true,
        shouldResetBuffer = false,
        isLastChunk = true, // This triggers the flush!
      )

    assertEquals(2, result.size)
    assertEquals(partialEvent2, result[0])
    assertEquals("Part 1Part 2", result[1].content?.parts?.get(0)?.text)
  }
}
