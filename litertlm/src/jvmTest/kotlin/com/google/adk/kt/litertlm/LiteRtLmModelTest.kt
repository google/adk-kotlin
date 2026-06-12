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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.ai.edge.litertlm.Contents as LiteRtLmContents
import com.google.ai.edge.litertlm.Message as LiteRtLmMessage
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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
    assertTrue(!responses[2].partial)

    verify(mockConversation, never()).close()
    model.close()
    verify(mockConversation).close()
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
}
