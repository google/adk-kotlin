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
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.testing.DummyMemoryService
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
  fun run_withQuery_callsMemoryService() = runTest {
    val tool = LoadMemoryTool()
    val memoryService =
      DummyMemoryService().apply {
        searchMemoryResponse =
          SearchMemoryResponse(
            memories =
              listOf(MemoryEntry(content = Content(parts = listOf(Part(text = "test-query")))))
          )
      }
    val context = testToolContext(testInvocationContext(memoryService = memoryService))

    val args = mapOf("query" to "test-query")
    val result = tool.run(context, args)

    // The tool result must be JSON-native, so the entries arrive as maps rather than as
    // SearchMemoryResponse.
    assertTrue(result is Map<*, *>)
    val memories = (result["result"] as Map<*, *>)["memories"] as List<*>
    assertEquals(1, memories.size)
    val content = (memories[0] as Map<*, *>)["content"] as Map<*, *>
    assertEquals("test-query", ((content["parts"] as List<*>)[0] as Map<*, *>)["text"])
  }

  @Test
  fun run_memoryServiceReturnsEntries_resultIsJsonNative() = runBlocking {
    val tool = LoadMemoryTool()
    val entry =
      MemoryEntry(content = Content(parts = listOf(Part(text = "Paris."))), author = "user")
    val context = testToolContext(testInvocationContext(memoryService = serviceReturning(entry)))

    val result = tool.run(context, mapOf("query" to "capital"))

    // Unset fields are omitted, so id, timestamp and the empty custom_metadata are all absent.
    assertEquals(
      mapOf(
        "result" to
          mapOf(
            "memories" to
              listOf(
                mapOf(
                  "content" to mapOf("parts" to listOf(mapOf("text" to "Paris."))),
                  "author" to "user",
                )
              )
          )
      ),
      result,
    )
  }

  @Test
  fun run_entryWithEveryFieldSet_mapsEveryField() = runBlocking {
    val tool = LoadMemoryTool()
    val context =
      testToolContext(testInvocationContext(memoryService = serviceReturning(FULL_ENTRY)))

    val result = tool.run(context, mapOf("query" to "capital"))

    assertEquals(mapOf("result" to mapOf("memories" to listOf(FULL_ENTRY_AS_MAP))), result)
  }

  @Test
  fun run_customMetadataWithNonStringValues_encodesEachValueType() = runBlocking {
    // customMetadata is Map<String, @Contextual Any>: the one field on a memory entry whose value
    // type is open, and so the one place a non-JSON-native value could still reach the serializer.
    val entry =
      MemoryEntry(
        content = Content(parts = listOf(Part(text = "Paris."))),
        customMetadata = mapOf("source" to "chat", "score" to 0.9, "verified" to true),
      )
    val tool = LoadMemoryTool()
    val context = testToolContext(testInvocationContext(memoryService = serviceReturning(entry)))

    val result = tool.run(context, mapOf("query" to "capital"))

    assertEquals(
      mapOf(
        "result" to
          mapOf(
            "memories" to
              listOf(
                mapOf(
                  "content" to mapOf("parts" to listOf(mapOf("text" to "Paris."))),
                  "custom_metadata" to mapOf("source" to "chat", "score" to 0.9, "verified" to true),
                )
              )
          )
      ),
      result,
    )
  }

  @Test
  fun run_memoryServiceReturnsNoMatches_resultHasEmptyMemoryList() = runBlocking {
    val tool = LoadMemoryTool()
    val context = testToolContext(testInvocationContext(memoryService = DummyMemoryService()))

    val result = tool.run(context, mapOf("query" to "capital"))

    assertEquals(mapOf("result" to mapOf("memories" to emptyList<Any>())), result)
  }

  @Test
  @OptIn(FrameworkInternalApi::class)
  fun handleFunctionCalls_loadMemoryResponse_survivesEventSerialization() = runBlocking {
    val tool = LoadMemoryTool()
    val invocationContext = testInvocationContext(memoryService = serviceReturning(FULL_ENTRY))

    val event =
      invocationContext.handleFunctionCalls(
        functionCalls =
          listOf(
            FunctionCall(name = "load_memory", args = mapOf("query" to "capital"), id = "call-1")
          ),
        tools = mapOf("load_memory" to tool),
      )

    // Serializing the function-response event is the step that used to throw, so this asserts the
    // payload survives the round trip rather than re-checking the shape the tool returned.
    assertNotNull(event)
    val restored =
      adkJson.decodeFromString(
        Event.serializer(),
        adkJson.encodeToString(Event.serializer(), event),
      )
    assertEquals(
      mapOf("result" to mapOf("memories" to listOf(FULL_ENTRY_AS_MAP))),
      restored.content?.parts?.single()?.functionResponse?.response,
    )
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

  private fun serviceReturning(vararg entries: MemoryEntry) =
    DummyMemoryService().apply {
      searchMemoryResponse = SearchMemoryResponse(memories = entries.toList())
    }

  private companion object {
    /**
     * An entry with every optional field set, so none of the omit-when-unset branches is skipped.
     */
    val FULL_ENTRY =
      MemoryEntry(
        content = Content(role = Role.USER, parts = listOf(Part(text = "Paris."))),
        id = "memory-1",
        author = "user",
        timestamp = "2026-01-02T03:04:05Z",
        customMetadata = mapOf("source" to "chat"),
      )

    val FULL_ENTRY_AS_MAP =
      mapOf(
        "content" to mapOf("role" to "user", "parts" to listOf(mapOf("text" to "Paris."))),
        "id" to "memory-1",
        "author" to "user",
        "timestamp" to "2026-01-02T03:04:05Z",
        "custom_metadata" to mapOf("source" to "chat"),
      )
  }
}
