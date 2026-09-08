@file:OptIn(FrameworkInternalApi::class)

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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.testing.DummyMemoryService
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LoadMemoryToolTest {

  @Test
  fun declaration_returnsCorrectToolDefinition() {
    val tool = LoadMemoryTool()

    val declaration = tool.declaration()

    assertEquals("load_memory", declaration.name)
    assertEquals("Loads the memory for the current user.", declaration.description)

    val properties = declaration.parameters?.properties ?: emptyMap()
    assertTrue(properties.containsKey("query"))
    assertEquals(listOf("query"), declaration.parameters?.required)
  }

  @Test
  fun run_withQuery_returnsJsonNativeMap() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(
                MemoryEntry(
                  content = Content(parts = listOf(Part(text = "remembered fact"))),
                  author = "user",
                  timestamp = "2026-01-01T00:00:00Z",
                  customMetadata = mapOf("source" to "chat"),
                )
              ),
            nextPageToken = "page2",
          )
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val result = tool.run(context, mapOf("query" to "test-query"))

    assertTrue(result is Map<*, *>)
    @Suppress("UNCHECKED_CAST") val resultMap = result as Map<String, Any?>

    val memories = resultMap["memories"] as List<*>
    assertEquals(1, memories.size)

    @Suppress("UNCHECKED_CAST") val entry = memories[0] as Map<String, Any?>
    assertEquals("remembered fact", entry["text"])
    assertEquals("user", entry["author"])
    assertEquals("2026-01-01T00:00:00Z", entry["timestamp"])
    assertEquals(mapOf("source" to "chat"), entry["metadata"])

    assertEquals("page2", resultMap["nextPageToken"])
  }

  @Test
  fun run_withQuery_resultSerializesWithAnySerializer() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(MemoryEntry(content = Content(parts = listOf(Part(text = "test-memory")))))
          )
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val result = tool.run(context, mapOf("query" to "test-query"))

    // Must not throw — this is the exact code path that was broken before the fix.
    anyToJsonElement(result)
  }

  @Test
  fun run_emptyMemories_returnsEmptyList() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse = SearchMemoryResponse(memories = emptyList())
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val result = tool.run(context, mapOf("query" to "nothing"))

    assertTrue(result is Map<*, *>)
    @Suppress("UNCHECKED_CAST") val resultMap = result as Map<String, Any?>
    assertEquals(emptyList<Any>(), resultMap["memories"])
    assertFalse(resultMap.containsKey("nextPageToken"))

    anyToJsonElement(result)
  }

  @Test
  fun run_entryWithEmptyParts_returnsEmptyText() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories = listOf(MemoryEntry(content = Content(parts = emptyList())))
          )
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val result = tool.run(context, mapOf("query" to "test"))

    @Suppress("UNCHECKED_CAST") val resultMap = result as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val memories = resultMap["memories"] as List<Map<String, Any?>>
    assertEquals("", memories[0]["text"])
    assertFalse(memories[0].containsKey("author"))
    assertFalse(memories[0].containsKey("timestamp"))
    assertFalse(memories[0].containsKey("metadata"))

    anyToJsonElement(result)
  }

  @Test
  fun run_multipleTextParts_joinsWithNewline() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(
                MemoryEntry(
                  content = Content(parts = listOf(Part(text = "line1"), Part(text = "line2")))
                )
              )
          )
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val result = tool.run(context, mapOf("query" to "test"))

    @Suppress("UNCHECKED_CAST") val resultMap = result as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val memories = resultMap["memories"] as List<Map<String, Any?>>
    assertEquals("line1\nline2", memories[0]["text"])
  }

  @Test
  fun run_missingQuery_returnsErrorMap() = runTest {
    val tool = LoadMemoryTool()
    val context = testToolContext(testInvocationContext(memoryService = DummyMemoryService()))

    val result = tool.run(context, emptyMap())

    assertTrue(result is Map<*, *>)
    assertEquals("Missing 'query' parameter.", result["error"])
    assertEquals("INVALID_ARGUMENTS", result["error_code"])
  }

  @Test
  fun run_missingMemoryService_returnsErrorMap() = runTest {
    val tool = LoadMemoryTool()
    // Do not provide memory service
    val context = testToolContext()

    val args = mapOf("query" to "test-query")
    val result = tool.run(context, args)

    assertTrue(result is Map<*, *>)
    assertEquals("MemoryService is not configured.", result["error"])
    assertEquals("UNCONFIGURED", result["error_code"])
  }

  @Test
  fun processLlmRequest_injectsMemoryInstruction() = runTest {
    val tool = LoadMemoryTool()
    val context = testToolContext()
    val baseRequest = LlmRequest()

    val updatedRequest = tool.processLlmRequest(context, baseRequest)

    val systemInstruction = updatedRequest.config.systemInstruction
    assertTrue(systemInstruction != null)
    assertTrue(systemInstruction.parts.any { it.text?.contains("You have memory.") == true })
    assertTrue(
      systemInstruction.parts.any {
        it.text?.contains("call load_memory function with a query") == true
      }
    )
  }
}
