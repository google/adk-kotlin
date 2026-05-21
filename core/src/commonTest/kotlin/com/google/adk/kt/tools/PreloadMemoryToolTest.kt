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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyMemoryService
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PreloadMemoryToolTest {

  @Test
  fun declaration_returnsNull() {
    val tool = PreloadMemoryTool()
    assertNull(tool.declaration())
  }

  @Test
  fun run_throwsUnsupportedOperationException() = runTest {
    val tool = PreloadMemoryTool()
    val context = ToolContext(invocationContext = getTestInvocationContext())

    assertFailsWith<UnsupportedOperationException> { tool.run(context, emptyMap()) }
  }

  @Test
  fun processLlmRequest_memoryWithAuthorAndTimestamp_injectsFormattedInstruction() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(
                MemoryEntry(
                  content = Content(parts = listOf(Part(text = "I like blue color"))),
                  author = "user",
                  timestamp = "2026-05-04T10:00:00Z",
                )
              )
          )
      }
    val userContent = userMessage("What is my favorite color?")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    val systemInstruction = updatedRequest.config.systemInstruction
    assertNotNull(systemInstruction)
    val text = systemInstruction.parts.joinToString("\n") { it.text ?: "" }
    assertTrue(text.contains("<PAST_CONVERSATIONS>"))
    assertTrue(text.contains("Time: 2026-05-04T10:00:00Z"))
    assertTrue(text.contains("user: I like blue color"))
    assertTrue(text.contains("</PAST_CONVERSATIONS>"))
  }

  @Test
  fun processLlmRequest_missingUserContent_returnsUnalteredRequest() = runTest {
    val tool = PreloadMemoryTool()
    val context = ToolContext(invocationContext = getTestInvocationContext(DummyMemoryService()))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals(baseRequest, updatedRequest)
  }

  @Test
  fun processLlmRequest_missingMemoryService_returnsUnalteredRequest() = runTest {
    val tool = PreloadMemoryTool()
    val userContent = userMessage("Hello")
    val context =
      ToolContext(
        invocationContext =
          getTestInvocationContext(memoryService = null, userContent = userContent)
      )
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals(baseRequest, updatedRequest)
  }

  @Test
  fun processLlmRequest_emptyMemories_returnsUnalteredRequest() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply { searchMemoryResponse = SearchMemoryResponse(emptyList()) }
    val userContent = userMessage("Hello")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals(baseRequest, updatedRequest)
  }

  @Test
  fun processLlmRequest_memoryWithMultipleTextParts_joinsAllTextsWithSpace() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(
                MemoryEntry(
                  content = Content(parts = listOf(Part(text = "Hello"), Part(text = "world"))),
                  author = "user",
                )
              )
          )
      }
    val userContent = userMessage("Anything?")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    val systemInstruction = updatedRequest.config.systemInstruction
    assertNotNull(systemInstruction)
    val text = systemInstruction.parts.joinToString("\n") { it.text ?: "" }
    assertTrue(text.contains("user: Hello world"))
  }

  @Test
  fun processLlmRequest_memoryWithoutAuthorOrTimestamp_formatsTextOnly() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(MemoryEntry(content = Content(parts = listOf(Part(text = "bare memory")))))
          )
      }
    val userContent = userMessage("query")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    val systemInstruction = updatedRequest.config.systemInstruction
    assertNotNull(systemInstruction)
    val text = systemInstruction.parts.joinToString("\n") { it.text ?: "" }
    assertTrue(text.contains("bare memory"))
    assertFalse(text.contains("Time:"))
    assertFalse(text.contains(": bare memory"))
  }

  @Test
  fun processLlmRequest_emptyQueryText_skipsMemoryServiceCall() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(MemoryEntry(content = Content(parts = listOf(Part(text = "stale memory")))))
          )
      }
    val userContent = userMessage("")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals(baseRequest, updatedRequest)
  }

  @Test
  fun processLlmRequest_searchMemoryThrows_propagatesException() = runTest {
    val tool = PreloadMemoryTool()
    val throwingService =
      object : MemoryService {
        override suspend fun addSessionToMemory(session: Session) {}

        override suspend fun searchMemory(
          appName: String,
          userId: String,
          query: String,
        ): SearchMemoryResponse = throw IllegalStateException("boom")
      }
    val userContent = userMessage("query")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(throwingService, userContent))
    val baseRequest = LlmRequest()

    assertFailsWith<IllegalStateException> { tool.processLlmRequest(context, baseRequest) }
  }

  @Test
  fun processLlmRequest_memoriesWithNoUsableContent_returnsUnalteredRequest() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories = listOf(MemoryEntry(content = Content(parts = listOf(Part(text = "")))))
          )
      }
    val userContent = userMessage("query")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals(baseRequest, updatedRequest)
  }

  @Test
  fun processLlmRequest_memoryWithTimestampButEmptyText_includesTimestampLine() = runTest {
    val tool = PreloadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(
                MemoryEntry(
                  content = Content(parts = listOf(Part(text = ""))),
                  timestamp = "2026-05-04T10:00:00Z",
                )
              )
          )
      }
    val userContent = userMessage("query")
    val context =
      ToolContext(invocationContext = getTestInvocationContext(memoryService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    val systemInstruction = updatedRequest.config.systemInstruction
    assertNotNull(systemInstruction)
    val text = systemInstruction.parts.joinToString("\n") { it.text ?: "" }
    assertTrue(text.contains("<PAST_CONVERSATIONS>"))
    assertTrue(text.contains("Time: 2026-05-04T10:00:00Z"))
    assertFalse(text.contains(": \n"))
    assertFalse(text.contains(": </PAST_CONVERSATIONS>"))
  }

  @Test
  fun processLlmRequest_userContentWithMultipleTextParts_searchesWithJoinedQuery() = runTest {
    val tool = PreloadMemoryTool()
    var capturedQuery: String? = null
    val recordingService =
      object : MemoryService {
        override suspend fun addSessionToMemory(session: Session) {}

        override suspend fun searchMemory(
          appName: String,
          userId: String,
          query: String,
        ): SearchMemoryResponse {
          capturedQuery = query
          return SearchMemoryResponse(emptyList())
        }
      }
    val userContent =
      Content(
        role = Role.USER,
        parts = listOf(Part(text = "What is"), Part(text = "my favorite color?")),
      )
    val context =
      ToolContext(invocationContext = getTestInvocationContext(recordingService, userContent))
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    assertEquals("What is my favorite color?", capturedQuery)
    assertEquals(baseRequest, updatedRequest)
  }

  private fun getTestInvocationContext(
    memoryService: MemoryService? = null,
    userContent: Content? = null,
  ) =
    InvocationContext(
      session = testSession(),
      runConfig = RunConfig(),
      agent = DummyAgent("test-agent"),
      sessionService = InMemorySessionService(),
      memoryService = memoryService,
      userContent = userContent,
      invocationId = "test-invocation-id",
    )
}
