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

package com.google.adk.kt.litertlm

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall as AdkFunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.ai.edge.litertlm.Contents as LiteRtLmContents
import com.google.ai.edge.litertlm.Message as LiteRtLmMessage
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolCall as LiteRtLmToolCall
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LiteRtLmModelTest {

  @Test
  fun generateContent_streamFalse_returnsResponse() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    val expectedLiteRtLmResponse =
      LiteRtLmMessage.model(LiteRtLmContents.of("Expected response text"))
    whenever(mockConversation.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(expectedLiteRtLmResponse)

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hello"))))
      )

    val responses = model.generateContent(request, stream = false).toList()

    assertEquals(1, responses.size)
    assertEquals("Expected response text", responses[0].content?.parts?.get(0)?.text)
    verify(mockConversation, never()).close()
    model.close()
    verify(mockConversation).close()
  }

  /**
   * Non-streaming: the response reports STOP, so Event.finishReason and the call_llm span are
   * populated, matching the Gemini backend.
   */
  @Test
  fun generateContent_streamFalse_responseReportsStopFinishReason() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)
    whenever(mockConversation.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val response = model.generateContent(request, stream = false).toList().single()

    assertEquals(FinishReason.STOP, response.finishReason)

    model.close()
  }

  /** Non-streaming: a generation failure propagates and discards the conversation. */
  @Test
  fun generateContent_streamFalse_sendMessageThrows_throwsAndDiscardsConversation() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation1 = mock<LiteRtLmConversation>()
    val mockConversation2 = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation1, mockConversation2)
    whenever(mockConversation1.sendMessage(any<LiteRtLmMessage>()))
      .thenThrow(RuntimeException("send failed"))
    whenever(mockConversation2.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(LiteRtLmMessage.model(LiteRtLmContents.of("Recovered")))

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val error =
      assertFailsWith<RuntimeException> { model.generateContent(request, stream = false).toList() }
    assertEquals("send failed", error.message)
    verify(mockConversation1).close()

    // The failure discarded the conversation, so an identical request must recreate it.
    model.generateContent(request, stream = false).toList()
    verify(mockEngine, times(2)).createConversation(any())

    model.close()
  }

  /**
   * Non-streaming: emitting outside the try lets a collector's own exception propagate unmasked.
   */
  @Test
  fun generateContent_streamFalse_downstreamCollectorThrows_propagatesOriginalException() =
    runTest {
      val mockEngine = mock<LiteRtLmEngine>()
      val mockConversation = mock<LiteRtLmConversation>()
      whenever(mockEngine.isInitialized()).thenReturn(true)
      whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)
      whenever(mockConversation.sendMessage(any<LiteRtLmMessage>()))
        .thenReturn(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))

      val model = LiteRtLmModel.create(mockEngine)
      val request =
        LlmRequest(
          contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi"))))
        )

      // The response is emitted outside the try, so a throwing consumer sees its own exception
      // rather than a masked "Flow exception transparency is violated" error.
      val thrown =
        assertFailsWith<IllegalStateException> {
          model.generateContent(request, stream = false).collect {
            throw IllegalStateException("boom from consumer")
          }
        }
      assertEquals("boom from consumer", thrown.message)

      model.close()
    }

  /** Non-streaming: a failure creating the conversation propagates, not swallowed. */
  @Test
  fun generateContent_streamFalse_conversationCreationFails_propagatesException() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenThrow(RuntimeException("create failed"))

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val error =
      assertFailsWith<RuntimeException> { model.generateContent(request, stream = false).toList() }
    assertEquals("create failed", error.message)

    model.close()
  }

  @Test
  fun generateContent_streamTrue_emitsStreamingResponses() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of(" world")))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val responses = model.generateContent(request, stream = true).toList()

    assertEquals(3, responses.size)
    assertEquals("Hello", responses[0].content?.parts?.get(0)?.text)
    assertTrue(responses[0].partial)
    assertEquals(" world", responses[1].content?.parts?.get(0)?.text)
    assertTrue(responses[1].partial)
    assertEquals("Hello world", responses[2].content?.parts?.get(0)?.text)
    assertFalse(responses[2].partial)

    verify(mockConversation, never()).close()
    model.close()
    verify(mockConversation).close()
  }

  /**
   * Streaming: the aggregated final reports STOP, so Event.finishReason and the call_llm span are
   * populated. Partials carry no finish reason; only the terminal does, matching the Gemini
   * backend.
   */
  @Test
  fun generateContent_streamTrue_finalResponseReportsStopFinishReason() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val responses = model.generateContent(request, stream = true).toList()

    val finalResponse = responses.last()
    assertFalse(finalResponse.partial)
    assertEquals(FinishReason.STOP, finalResponse.finishReason)
    assertNull(responses.first().finishReason)

    model.close()
  }

  @Test
  fun generateContent_streamTrue_cancelledMidGeneration_cancelsThenClosesConversation() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    // Emits one chunk and then never finishes, like a long reply abandoned mid-stream.
    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("thinking")))
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val collecting = launch { model.generateContent(request, stream = true).collect {} }
    // Let the first chunk arrive, so the turn is genuinely in flight when cancelled.
    advanceUntilIdle()
    collecting.cancelAndJoin()

    // Cancel must precede close, and close must happen once.
    val order = inOrder(mockConversation)
    order.verify(mockConversation).cancelProcess()
    order.verify(mockConversation).close()
    verify(mockConversation, times(1)).close()
  }

  /** Streaming: a collector that throws sees its own exception, not a masked transparency error. */
  @Test
  fun generateContent_streamTrue_downstreamCollectorThrows_propagatesOriginalException() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    // A consumer that throws while collecting must see its own exception, not a masked
    // "Flow exception transparency is violated" error from the model re-emitting after the failure.
    val thrown =
      assertFailsWith<IllegalStateException> {
        model.generateContent(request, stream = true).collect {
          throw IllegalStateException("boom from consumer")
        }
      }
    assertEquals("boom from consumer", thrown.message)

    model.close()
  }

  /** Streaming: a content-free turn discards the conversation, so a repeat request starts fresh. */
  @Test
  fun generateContent_streamTrue_contentFreeTurn_discardsConversation() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation1 = mock<LiteRtLmConversation>()
    val mockConversation2 = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation1, mockConversation2)

    // A content-free turn: the stream terminates via onDone without producing any content.
    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onDone()
        null
      }
      .whenever(mockConversation1)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Recovered")))
        callback.onDone()
        null
      }
      .whenever(mockConversation2)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    assertTrue(model.generateContent(request, stream = true).toList().isEmpty())
    // The content-free turn must discard the conversation (its native state is ambiguous), so an
    // identical follow-up must NOT reuse the stale cache key -- it must create a fresh
    // conversation.
    model.generateContent(request, stream = true).toList()

    verify(mockEngine, times(2)).createConversation(any())
    verify(mockConversation1).close()

    model.close()
  }

  /** Streaming: a generation error propagates after earlier partials, not swallowed. */
  @Test
  fun generateContent_streamTrue_onError_emitsPartialsThenThrows() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Partial")))
        callback.onError(RuntimeException("boom"))
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    // A generation error propagates to the collector (matching Gemini/ML Kit) so the agent's
    // onModelError handling runs, rather than being swallowed into a terminal error response.
    val received = mutableListOf<LlmResponse>()
    val thrown =
      assertFailsWith<RuntimeException> {
        model.generateContent(request, stream = true).collect { received.add(it) }
      }
    assertEquals("boom", thrown.message)

    // The partial that arrived before the failure is still delivered.
    assertEquals(1, received.size)
    assertEquals("Partial", received[0].content?.parts?.get(0)?.text)
    assertTrue(received[0].partial)

    model.close()
  }

  /** Streaming: a generation error discards the conversation, so the next turn starts fresh. */
  @Test
  fun generateContent_streamTrue_onError_discardsConversation() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation1 = mock<LiteRtLmConversation>()
    val mockConversation2 = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation1, mockConversation2)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onError(RuntimeException("boom"))
        null
      }
      .whenever(mockConversation1)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Recovered")))
        callback.onDone()
        null
      }
      .whenever(mockConversation2)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    // The generation error propagates; the conversation is still discarded on the way out.
    assertFailsWith<RuntimeException> { model.generateContent(request, stream = true).toList() }
    // Same single-content request: a cached conversation would be reused, but the error discarded
    // it, forcing a fresh conversation.
    model.generateContent(request, stream = true).toList()

    verify(mockEngine, times(2)).createConversation(any())
    verify(mockConversation1).close()

    model.close()
  }

  /** Streaming: a single tool call appears exactly once in the aggregated final response. */
  @Test
  fun generateContent_streamTrue_functionCall_appearsOnceInFinalResponse() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(
          LiteRtLmMessage.model(
            LiteRtLmContents.of("Calling tool"),
            listOf(LiteRtLmToolCall("get_weather", mapOf("city" to "Paris"))),
          )
        )
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Weather?"))))
      )

    val responses = model.generateContent(request, stream = true).toList()

    val finalResponse = responses.last()
    assertFalse(finalResponse.partial)
    val functionCalls = finalResponse.content?.parts?.mapNotNull { it.functionCall } ?: emptyList()
    assertEquals(1, functionCalls.size)
    assertEquals("get_weather", functionCalls[0].name)

    model.close()
  }

  /** Streaming: tool calls in separate chunks are all kept, not just the last chunk's. */
  @Test
  fun generateContent_streamTrue_distinctFunctionCallsAcrossChunks_areAllPreserved() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(
          LiteRtLmMessage.model(
            LiteRtLmContents.of("Calling first"),
            listOf(LiteRtLmToolCall("get_weather", mapOf("city" to "Paris"))),
          )
        )
        callback.onMessage(
          LiteRtLmMessage.model(
            LiteRtLmContents.of("Calling second"),
            listOf(LiteRtLmToolCall("get_time", mapOf("zone" to "CET"))),
          )
        )
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Weather?"))))
      )

    val finalResponse = model.generateContent(request, stream = true).toList().last()

    // Both calls are kept: the old path dropped calls from every chunk but the last.
    val functionCalls = finalResponse.content?.parts?.mapNotNull { it.functionCall } ?: emptyList()
    assertEquals(listOf("get_weather", "get_time"), functionCalls.map { it.name })

    model.close()
  }

  /** Streaming: the shared aggregator does not de-duplicate a call repeated across chunks. */
  @Test
  fun generateContent_streamTrue_repeatedFunctionCallAcrossChunks_isNotDeduplicated() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        repeat(2) {
          callback.onMessage(
            LiteRtLmMessage.model(
              LiteRtLmContents.of("Calling tool"),
              listOf(LiteRtLmToolCall("get_weather", mapOf("city" to "Paris"))),
            )
          )
        }
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Weather?"))))
      )

    val finalResponse = model.generateContent(request, stream = true).toList().last()

    // The shared aggregator appends every call without de-duplicating, so a call repeated across
    // chunks is kept twice. The runtime emits each call once, so this cannot occur in practice; the
    // test pins the aggregator's contract rather than adding a LiteRT-LM-only guard.
    val functionCalls = finalResponse.content?.parts?.mapNotNull { it.functionCall } ?: emptyList()
    assertEquals(listOf("get_weather", "get_weather"), functionCalls.map { it.name })

    model.close()
  }

  /** Streaming: a follow-up matching the aggregated history reuses the cached conversation. */
  @Test
  fun generateContent_streamTrue_afterSuccess_reusesConversationOnCacheHit() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Response 1")))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)

    val request1 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 1"))))
      )
    model.generateContent(request1, stream = true).toList()

    // Follow-up whose history equals request1 + the aggregated model response; a cache hit here
    // proves the post-aggregate cache update used the aggregated content as the key.
    val request2 =
      LlmRequest(
        contents =
          listOf(
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 1"))),
            AdkContent(role = "model", parts = listOf(AdkPart(text = "Response 1"))),
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 2"))),
          )
      )
    model.generateContent(request2, stream = true).toList()

    verify(mockEngine, times(1)).createConversation(any())

    model.close()
  }

  /**
   * Streaming: a follow-up after a tool call reuses the cached conversation. The framework sends
   * function call ids back stripped, so the cache key must not keep the id the aggregator adds.
   */
  @Test
  fun generateContent_streamTrue_afterToolCall_reusesConversationOnCacheHit() = runBlocking {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(
          LiteRtLmMessage.model(
            LiteRtLmContents.of(emptyList()),
            listOf(LiteRtLmToolCall("get_weather", mapOf("city" to "Paris"))),
          )
        )
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request1 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Weather?"))))
      )
    model.generateContent(request1, stream = true).toList()

    // The history the framework sends back: the same tool call, with the generated id removed.
    val request2 =
      LlmRequest(
        contents =
          listOf(
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Weather?"))),
            AdkContent(
              role = "model",
              parts =
                listOf(
                  AdkPart(
                    functionCall =
                      AdkFunctionCall(name = "get_weather", args = mapOf("city" to "Paris"))
                  )
                ),
            ),
            AdkContent(role = "user", parts = listOf(AdkPart(text = "And tomorrow?"))),
          )
      )
    model.generateContent(request2, stream = true).toList()

    verify(mockEngine, times(1)).createConversation(any())

    model.close()
  }

  /** Streaming: cancelling mid-stream discards the incomplete conversation. */
  @Test
  fun generateContent_streamTrue_cancelledMidStream_discardsConversation() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation1 = mock<LiteRtLmConversation>()
    val mockConversation2 = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation1, mockConversation2)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("First")))
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Second")))
        callback.onDone()
        null
      }
      .whenever(mockConversation1)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Recovered")))
        callback.onDone()
        null
      }
      .whenever(mockConversation2)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    // Take only the first partial, cancelling the flow before the stream completes.
    val firstOnly = model.generateContent(request, stream = true).take(1).toList()
    assertEquals(1, firstOnly.size)

    // Same single-content request: the cancellation discarded the incomplete conversation, so a
    // fresh one must be created.
    model.generateContent(request, stream = true).toList()

    verify(mockEngine, times(2)).createConversation(any())
    verify(mockConversation1).close()

    model.close()
  }

  /** Streaming: a failure creating the conversation propagates, not swallowed. */
  @Test
  fun generateContent_streamTrue_conversationCreationFails_propagatesException() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenThrow(RuntimeException("init failed"))

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hi")))))

    val error =
      assertFailsWith<RuntimeException> { model.generateContent(request, stream = true).toList() }
    assertEquals("init failed", error.message)

    model.close()
  }

  /**
   * End-to-end through [InMemoryRunner]: when a turn produces no message chunks, the agent stops
   * after a single model call and stores no event. This matches the other backends, which also emit
   * nothing when a stream carries no chunks to aggregate.
   */
  @Test
  fun streamingTurnWithNoChunks_endsAfterOneModelCallAndStoresNoEvent() = runBlocking {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    // A turn that produces nothing, as the runtime reports it: onDone with no preceding onMessage.
    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val runner =
      InMemoryRunner(agent = LlmAgent(name = "litertlm-agent", model = model, maxSteps = 3))

    runner
      .runAsync(
        userId = "user1",
        sessionId = "session1",
        newMessage = AdkContent(role = "user", parts = listOf(AdkPart(text = "hi"))),
        runConfig = RunConfig(streamingMode = StreamingMode.SSE, maxLlmCalls = 5),
      )
      .toList()

    // The agent loop ends here rather than asking the model again; maxSteps caps a runaway loop so
    // this fails as a wrong count instead of hanging.
    verify(mockEngine, times(1)).createConversation(any())
    // There is no response to store.
    val agentEvents =
      runner.sessionService
        .getSession(SessionKey(runner.appName, "user1", "session1"))!!
        .events
        .filter { it.author == "litertlm-agent" }
    assertEquals(0, agentEvents.size)

    model.close()
  }

  /**
   * End-to-end through [InMemoryRunner]: a turn whose chunks all carry no content ends after a
   * single model call and stores no event, exactly like a turn with no chunks. Dropping the
   * content-free chunks keeps the aggregated final null, so the turn stores nothing rather than an
   * empty final carrying only a STOP finish reason.
   */
  @Test
  fun streamingTurnWithOnlyEmptyChunks_endsAfterOneModelCallAndStoresNoEvent() = runBlocking {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    // Chunks arrive, but none carry text or a tool call.
    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of(emptyList())))
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of(emptyList())))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val runner =
      InMemoryRunner(agent = LlmAgent(name = "litertlm-agent", model = model, maxSteps = 3))

    runner
      .runAsync(
        userId = "user1",
        sessionId = "session1",
        newMessage = AdkContent(role = "user", parts = listOf(AdkPart(text = "hi"))),
        runConfig = RunConfig(streamingMode = StreamingMode.SSE, maxLlmCalls = 5),
      )
      .toList()

    // A single model call and no stored event: dropping the content-free chunks keeps the turn
    // empty, so nothing is stored (maxSteps caps a regression as a wrong count rather than a hang).
    verify(mockEngine, times(1)).createConversation(any())
    val agentEvents =
      runner.sessionService
        .getSession(SessionKey(runner.appName, "user1", "session1"))!!
        .events
        .filter { it.author == "litertlm-agent" }
    assertEquals(0, agentEvents.size)

    model.close()
  }

  /**
   * End-to-end through [InMemoryRunner]: a completed streaming turn stores a final event whose
   * finishReason is STOP, so downstream consumers see the same clean terminal the other backends
   * report.
   */
  @Test
  fun streamingTurn_storesFinalEventWithStopFinishReason() = runBlocking {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Hello")))
        callback.onDone()
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val runner =
      InMemoryRunner(agent = LlmAgent(name = "litertlm-agent", model = model, maxSteps = 3))

    runner
      .runAsync(
        userId = "user1",
        sessionId = "session1",
        newMessage = AdkContent(role = "user", parts = listOf(AdkPart(text = "hi"))),
        runConfig = RunConfig(streamingMode = StreamingMode.SSE, maxLlmCalls = 5),
      )
      .toList()

    val storedEvents =
      runner.sessionService
        .getSession(SessionKey(runner.appName, "user1", "session1"))!!
        .events
        .filter { it.author == "litertlm-agent" }
    assertEquals(FinishReason.STOP, storedEvents.last().finishReason)

    model.close()
  }

  @Test
  fun generateContent_streamFalse_reusesConversationOnCacheHit() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    val expectedLiteRtLmResponse1 = LiteRtLmMessage.model(LiteRtLmContents.of("Response 1"))
    val expectedLiteRtLmResponse2 = LiteRtLmMessage.model(LiteRtLmContents.of("Response 2"))
    whenever(mockConversation.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(expectedLiteRtLmResponse1, expectedLiteRtLmResponse2)

    val model = LiteRtLmModel.create(mockEngine)

    // First call (Turn 1)
    val request1 =
      LlmRequest(
        contents =
          listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 1 request"))))
      )
    val responses1 = model.generateContent(request1, stream = false).toList()
    assertEquals("Response 1", responses1[0].content?.parts?.get(0)?.text)

    // Second call (Turn 2) with cache hit
    val request2 =
      LlmRequest(
        contents =
          listOf(
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 1 request"))),
            AdkContent(role = "model", parts = listOf(AdkPart(text = "Response 1"))),
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 2 request"))),
          )
      )
    val responses2 = model.generateContent(request2, stream = false).toList()
    assertEquals("Response 2", responses2[0].content?.parts?.get(0)?.text)

    // Verify conversation was created only once and sendMessage was called twice
    verify(mockEngine, times(1)).createConversation(any())
    verify(mockConversation, times(2)).sendMessage(any<LiteRtLmMessage>())
    verify(mockConversation, never()).close()

    model.close()
    verify(mockConversation).close()
  }

  @Test
  fun generateContent_streamFalse_closesAndRecreatesConversationOnCacheMiss() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation1 = mock<LiteRtLmConversation>()
    val mockConversation2 = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation1, mockConversation2)

    val expectedLiteRtLmResponse1 = LiteRtLmMessage.model(LiteRtLmContents.of("Response 1"))
    val expectedLiteRtLmResponse2 = LiteRtLmMessage.model(LiteRtLmContents.of("Response 2"))
    whenever(mockConversation1.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(expectedLiteRtLmResponse1)
    whenever(mockConversation2.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(expectedLiteRtLmResponse2)

    val model = LiteRtLmModel.create(mockEngine)

    // First call (Turn 1)
    val request1 =
      LlmRequest(
        contents =
          listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 1 request"))))
      )
    val responses1 = model.generateContent(request1, stream = false).toList()
    assertEquals("Response 1", responses1[0].content?.parts?.get(0)?.text)

    // Second call with different history (cache miss)
    val request2 =
      LlmRequest(
        contents =
          listOf(
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Different turn 1 request"))),
            AdkContent(role = "model", parts = listOf(AdkPart(text = "Response 1"))),
            AdkContent(role = "user", parts = listOf(AdkPart(text = "Turn 2 request"))),
          )
      )
    val responses2 = model.generateContent(request2, stream = false).toList()
    assertEquals("Response 2", responses2[0].content?.parts?.get(0)?.text)

    // Verify two conversations were created, the first was closed on cache miss
    verify(mockEngine, times(2)).createConversation(any())
    verify(mockConversation1).close()
    verify(mockConversation2, never()).close()

    model.close()
    verify(mockConversation2).close()
  }

  @Test
  fun generateContent_withFunctionResponsePart_mapsToToolRole() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    val expectedLiteRtLmResponse = LiteRtLmMessage.model(LiteRtLmContents.of("Response text"))
    whenever(mockConversation.sendMessage(any<LiteRtLmMessage>()))
      .thenReturn(expectedLiteRtLmResponse)

    val model = LiteRtLmModel.create(mockEngine)
    val request =
      LlmRequest(
        contents =
          listOf(
            AdkContent(
              role = "user",
              parts =
                listOf(
                  AdkPart(
                    functionResponse =
                      com.google.adk.kt.types.FunctionResponse(
                        name = "test_func",
                        response = mapOf("result" to "success"),
                      )
                  )
                ),
            )
          )
      )

    model.generateContent(request, stream = false).toList()

    val messageCaptor = org.mockito.kotlin.argumentCaptor<LiteRtLmMessage>()
    verify(mockConversation).sendMessage(messageCaptor.capture())
    assertEquals(com.google.ai.edge.litertlm.Role.TOOL, messageCaptor.firstValue.role)

    model.close()
  }

  @Test
  fun generateContent_streamTrue_concurrentCallsAreQueued() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        launch {
          delay(1000)
          callback.onMessage(LiteRtLmMessage.model(LiteRtLmContents.of("Response")))
          callback.onDone()
        }
        null
      }
      .whenever(mockConversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())

    val model = LiteRtLmModel.create(mockEngine)
    val request1 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hello 1"))))
      )
    val request2 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hello 2"))))
      )

    val startTime = currentTime
    var endTime1 = 0L
    var endTime2 = 0L

    val job1 = launch {
      model.generateContent(request1, stream = true).toList()
      endTime1 = currentTime
    }
    val job2 = launch {
      model.generateContent(request2, stream = true).toList()
      endTime2 = currentTime
    }

    job1.join()
    job2.join()

    assertEquals(startTime + 1000, endTime1)
    assertEquals(startTime + 2000, endTime2)

    model.close()
  }

  @Test
  fun generateContent_streamFalse_concurrentCallsAreQueued() = runTest {
    val mockEngine = mock<LiteRtLmEngine>()
    val mockConversation = mock<LiteRtLmConversation>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenReturn(mockConversation)

    val expectedLiteRtLmResponse = LiteRtLmMessage.model(LiteRtLmContents.of("Response"))

    var activeInferences = 0
    var maxConcurrentInferences = 0
    val threadLock = Any()

    whenever(mockConversation.sendMessage(any<LiteRtLmMessage>())).thenAnswer {
      synchronized(threadLock) {
        activeInferences++
        if (activeInferences > maxConcurrentInferences) {
          maxConcurrentInferences = activeInferences
        }
      }
      Thread.sleep(50)
      synchronized(threadLock) { activeInferences-- }
      expectedLiteRtLmResponse
    }

    val model = LiteRtLmModel.create(mockEngine)
    val request1 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hello 1"))))
      )
    val request2 =
      LlmRequest(
        contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = "Hello 2"))))
      )

    val executor = Executors.newFixedThreadPool(2)
    val testDispatcher = executor.asCoroutineDispatcher()
    try {
      val deferred1 =
        async(testDispatcher) { model.generateContent(request1, stream = false).toList() }
      val deferred2 =
        async(testDispatcher) { model.generateContent(request2, stream = false).toList() }

      deferred1.await()
      deferred2.await()

      assertEquals(1, maxConcurrentInferences)
    } finally {
      testDispatcher.close()
      executor.shutdown()
      model.close()
    }
  }

  @Test
  fun manualOpenApiTool_execute_throwsUnsupportedOperationException() {
    val declaration = FunctionDeclaration(name = "test_func", description = "Test function")
    val tool = ManualOpenApiTool(declaration)

    assertFailsWith<UnsupportedOperationException> { tool.execute("{}") }
  }

  @Test
  fun manualOpenApiTool_getToolDescriptionJsonString_noParameters() {
    val declaration = FunctionDeclaration(name = "test_func", description = "Test function")
    val tool = ManualOpenApiTool(declaration)

    val expectedJson = """{"name":"test_func","description":"Test function"}"""
    assertEquals(expectedJson, tool.getToolDescriptionJsonString())
  }

  @Test
  fun manualOpenApiTool_getToolDescriptionJsonString_withParameters() {
    val declaration =
      FunctionDeclaration(
        name = "test_func",
        description = "Test function",
        parameters =
          Schema(
            type = Type.OBJECT,
            properties =
              mapOf(
                "param1" to Schema(type = Type.STRING, description = "A string param"),
                "param2" to
                  Schema(
                    type = Type.INTEGER,
                    description = "An int param",
                    enum = listOf("1", "2"),
                  ),
                "param3" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)),
              ),
            required = listOf("param1"),
          ),
      )
    val tool = ManualOpenApiTool(declaration)

    val expectedJson =
      """{"name":"test_func","description":"Test function","parameters":{"type":"object","properties":{"param1":{"type":"string","description":"A string param"},"param2":{"type":"integer","description":"An int param","enum":["1","2"]},"param3":{"type":"array","items":{"type":"string"}}},"required":["param1"]}}"""
    assertEquals(expectedJson, tool.getToolDescriptionJsonString())
  }

  @Test
  fun manualOpenApiTool_getToolDescriptionJsonString_escapesQuotesAndNewlines() {
    val declaration =
      FunctionDeclaration(
        name = "test_func",
        description = "Test \"function\"\nwith newlines and \t tabs.",
      )
    val tool = ManualOpenApiTool(declaration)

    val expectedJson =
      """{"name":"test_func","description":"Test \"function\"\nwith newlines and \t tabs."}"""
    assertEquals(expectedJson, tool.getToolDescriptionJsonString())
  }

  @Test
  fun toMap_untypedUnion_omitsTheTypeKey() {
    // Emitting "type":"string" beside "anyOf" would contradict the alternatives.
    val schema = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))

    val map = schema.toMap()

    assertNull(map["type"])
    assertEquals(listOf(mapOf("type" to "string"), mapOf("type" to "integer")), map["anyOf"])
  }

  @Test
  fun toMap_unspecifiedTypeWithoutAlternatives_fallsBackToString() {
    // Nothing describes this schema, and unlike the union case there are no alternatives to speak
    // for it, so the description keeps the `string` fallback rather than omitting the type.
    val schema = Schema(type = Type.TYPE_UNSPECIFIED)

    assertEquals("string", schema.toMap()["type"])
  }

  @Test
  fun toMap_unspecifiedTypeWithAlternatives_omitsTheTypeKey() {
    // A union spelled with an explicit TYPE_UNSPECIFIED rather than a missing type, which is what
    // the MCP converter produces. The `string` fallback must not apply, or the description would
    // name a type right next to the alternatives that contradict it.
    val schema = Schema(type = Type.TYPE_UNSPECIFIED, anyOf = listOf(Schema(type = Type.STRING)))

    assertNull(schema.toMap()["type"])
  }

  @Test
  fun manualOpenApiTool_getToolDescriptionJsonString_includesResponseSchema() {
    val declaration =
      FunctionDeclaration(
        name = "test_func",
        description = "Test function",
        response = Schema(type = Type.OBJECT, properties = mapOf("ok" to Schema(Type.BOOLEAN))),
      )
    val tool = ManualOpenApiTool(declaration)

    val expectedJson =
      """{"name":"test_func","description":"Test function","response":{"type":"object","properties":{"ok":{"type":"boolean"}}}}"""
    assertEquals(expectedJson, tool.getToolDescriptionJsonString())
  }

  @Test
  fun toMap_constraintFields_areEmitted() {
    // The tool description is plain JSON, so it can carry every constraint a schema expresses.
    val schema =
      Schema(
        type = Type.INTEGER,
        format = "int32",
        nullable = true,
        default = 5,
        title = "Count",
        pattern = "^[a-z]+$",
        minimum = 1.0,
        maximum = 10.0,
        minLength = 2,
        maxLength = 8,
        minItems = 1,
        maxItems = 3,
        minProperties = 4,
        maxProperties = 7,
        anyOf = listOf(Schema(type = Type.STRING)),
      )

    val map = schema.toMap()

    assertEquals("integer", map["type"])
    assertEquals("int32", map["format"])
    assertEquals(true, map["nullable"])
    assertEquals(5, map["default"])
    assertEquals("Count", map["title"])
    assertEquals("^[a-z]+$", map["pattern"])
    assertEquals(1.0, map["minimum"])
    assertEquals(10.0, map["maximum"])
    assertEquals(2L, map["minLength"])
    assertEquals(8L, map["maxLength"])
    assertEquals(1L, map["minItems"])
    assertEquals(3L, map["maxItems"])
    assertEquals(4L, map["minProperties"])
    assertEquals(7L, map["maxProperties"])
    assertEquals(listOf(mapOf("type" to "string")), map["anyOf"])
  }

  /**
   * Streaming: a tool re-entering the same model must not deadlock. The model holds its lock only
   * during generation, not across the caller, so the sub-agent's re-entrant call can acquire it.
   * [withTimeout] makes the regression fail fast instead of hanging.
   */
  @Test
  fun generateContent_streamTrue_toolReentersSameModel_doesNotDeadlock() = runBlocking {
    val callCount = AtomicInteger(0)
    val mockEngine = mock<LiteRtLmEngine>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenAnswer {
      reentrantMockConversation(callCount)
    }

    val model = LiteRtLmModel.create(mockEngine)
    val subAgent =
      LlmAgent(
        name = "sub_agent",
        description = "Replies to the caller",
        model = model,
        maxSteps = 2,
      )
    val outerAgent =
      LlmAgent(
        name = "outer_agent",
        model = model,
        tools = listOf(AgentTool(subAgent)),
        maxSteps = 2,
      )
    val runner = InMemoryRunner(agent = outerAgent)

    // Without the fix, the sub-agent's re-entrant call blocks on the lock the outer turn holds;
    // the timeout surfaces that deadlock as a failure.
    withTimeout(30_000) {
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = AdkContent(role = "user", parts = listOf(AdkPart(text = "hi"))),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE, maxLlmCalls = 5),
        )
        .toList()
    }

    // Ensure the tool actually re-entered the model, so the test cannot pass vacuously.
    assertTrue(callCount.get() >= 2)
    model.close()
  }

  /** Non-streaming (the default mode): the same re-entrant tool call must not deadlock. */
  @Test
  fun generateContent_streamFalse_toolReentersSameModel_doesNotDeadlock() = runBlocking {
    val callCount = AtomicInteger(0)
    val mockEngine = mock<LiteRtLmEngine>()
    whenever(mockEngine.isInitialized()).thenReturn(true)
    whenever(mockEngine.createConversation(any())).thenAnswer {
      reentrantMockConversation(callCount)
    }

    val model = LiteRtLmModel.create(mockEngine)
    val subAgent =
      LlmAgent(
        name = "sub_agent",
        description = "Replies to the caller",
        model = model,
        maxSteps = 2,
      )
    val outerAgent =
      LlmAgent(
        name = "outer_agent",
        model = model,
        tools = listOf(AgentTool(subAgent)),
        maxSteps = 2,
      )
    val runner = InMemoryRunner(agent = outerAgent)

    withTimeout(30_000) {
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = AdkContent(role = "user", parts = listOf(AdkPart(text = "hi"))),
          runConfig = RunConfig(streamingMode = StreamingMode.NONE, maxLlmCalls = 5),
        )
        .toList()
    }

    assertTrue(callCount.get() >= 2)
    model.close()
  }

  /**
   * Mock conversation whose first model call returns a `sub_agent` tool call and whose later calls
   * return plain text, so the outer turn invokes the sub-agent (which shares this model) and then
   * every turn terminates.
   */
  private fun reentrantMockConversation(callCount: AtomicInteger): LiteRtLmConversation {
    fun responseFor(callIndex: Int): LiteRtLmMessage =
      if (callIndex == 1) {
        LiteRtLmMessage.model(
          LiteRtLmContents.of(emptyList()),
          listOf(LiteRtLmToolCall("sub_agent", mapOf("request" to "hi"))),
        )
      } else {
        LiteRtLmMessage.model(LiteRtLmContents.of("done"))
      }

    val conversation = mock<LiteRtLmConversation>()
    whenever(conversation.sendMessage(any<LiteRtLmMessage>())).thenAnswer {
      responseFor(callCount.incrementAndGet())
    }
    doAnswer { invocation ->
        val callback = invocation.getArgument<MessageCallback>(1)
        callback.onMessage(responseFor(callCount.incrementAndGet()))
        callback.onDone()
        null
      }
      .whenever(conversation)
      .sendMessageAsync(any<LiteRtLmMessage>(), any<MessageCallback>())
    return conversation
  }
}
