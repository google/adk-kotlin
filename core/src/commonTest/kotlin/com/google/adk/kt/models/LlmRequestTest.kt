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

@file:OptIn(com.google.adk.kt.annotations.ExperimentalContextCachingFeature::class)

package com.google.adk.kt.models

import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmRequestTest {

  // Dummy BaseTool implementation for testing
  class TestTool(name: String) : BaseTool(name, "description") {
    override fun declaration(): FunctionDeclaration? {
      return FunctionDeclaration(name = name, description = "desc")
    }

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      return "test"
    }
  }

  @Test
  fun appendTools_newTool_addsTool() {
    var request = LlmRequest()
    val tool1 = TestTool("tool1")

    request = request.appendTools(listOf(tool1))

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    val fds = tools.get(0).functionDeclarations
    assertNotNull(fds)
    assertEquals(1, fds.size)
    // On JVM we can check delegate or just use the wrappers logic if we expose it
  }

  @Test
  fun appendContent_contentProvided_addsToContents() {
    var request = LlmRequest()
    val content = userMessage("Hello")

    request = request.appendContent(content)

    assertEquals(1, request.contents.size)
    assertEquals(Role.USER, request.contents[0].role)
    assertEquals("Hello", request.contents[0].parts[0].text)
  }

  @Test
  fun appendInstructions_textOnly_setsSystemInstruction() {
    var request = LlmRequest()
    val instructions = Content(parts = listOf(Part(text = "Test instruction")))

    request = request.appendInstructions(instructions)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    assertEquals("Test instruction", systemInstruction.parts[0].text)
  }

  @Test
  fun appendInstructions_mixedParts_updatesSystemInstructionAndContents() {
    var request = LlmRequest()
    val blob =
      Blob(
        mimeType = "image/png",
        displayName = "image.png",
        data = "fake data".encodeToByteArray(),
      )

    val instructions = Content(parts = listOf(Part(text = "Text part"), Part(inlineData = blob)))

    request = request.appendInstructions(instructions)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    val sysText = systemInstruction.parts[0].text
    assertNotNull(sysText)
    assertTrue(sysText.contains("Text part"))
    assertTrue(sysText.contains("[Reference to inline binary data: inline_data_0"))

    assertEquals(1, request.contents.size)
    assertTrue(
      request.contents[0].parts[0].text?.contains("Referenced inline binary data: inline_data_0") ==
        true
    )
    assertNotNull(request.contents[0].parts[1].inlineData)
  }

  @Test
  fun appendTools_existingTools_updatesExistingTools() {
    var request = LlmRequest()
    val tool1 = TestTool("tool1")
    request = request.appendTools(listOf(tool1))

    val tool2 = TestTool("tool2")
    request = request.appendTools(listOf(tool2))

    val tools = request.config.tools
    assertNotNull(tools)
    assertEquals(1, tools.size)
    val fds = tools.get(0).functionDeclarations
    assertNotNull(fds)
    assertEquals(2, fds.size)
    assertEquals("tool1", fds[0].name)
    assertEquals("tool2", fds[1].name)
  }

  @Test
  fun appendTools_emptyList_doesNotUpdateTools() {
    var request = LlmRequest()
    request = request.appendTools(listOf())
    assertEquals(null, request.config.tools)
  }

  @Test
  fun appendTools_noDeclaration_doesNotUpdateTools() {
    var request = LlmRequest()
    val tool =
      object : BaseTool("noDecl", "desc") {
        override fun declaration(): FunctionDeclaration? = null

        override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = "noOp"
      }
    request = request.appendTools(listOf(tool))
    assertEquals(null, request.config.tools)
  }

  @Test
  fun appendInstructions_fileData_updatesSystemInstructionAndContents() {
    var request = LlmRequest()
    val fileData = FileData(mimeType = "image/jpeg", fileUri = "http://image.jpg")
    val instructions =
      Content(parts = listOf(Part(text = "See attached"), Part(fileData = fileData)))

    request = request.appendInstructions(instructions)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    val sysText = systemInstruction.parts[0].text
    assertNotNull(sysText)
    assertTrue(sysText.contains("See attached"))
    assertTrue(sysText.contains("[Reference to file data: file_data_0"))

    assertEquals(1, request.contents.size)
    assertTrue(
      request.contents[0].parts[0].text?.contains("Referenced file data: file_data_0") == true
    )
    assertNotNull(request.contents[0].parts[1].fileData)
  }

  @Test
  fun appendInstructions_multipleTextParts_joinsText() {
    var request = LlmRequest()
    val instructions = Content(parts = listOf(Part(text = "Part 1"), Part(text = "Part 2")))

    request = request.appendInstructions(instructions)

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    val sysText = systemInstruction.parts[0].text
    assertEquals("Part 1\n\nPart 2", sysText)
  }

  @Test
  fun appendInstructions_existingSystemInstruction_appendsToExisting() {
    var request = LlmRequest()
    request = request.appendInstructions(Content(parts = listOf(Part(text = "Initial"))))
    request = request.appendInstructions(Content(parts = listOf(Part(text = "Appended"))))

    val systemInstruction = request.config.systemInstruction
    assertNotNull(systemInstruction)
    val sysText = systemInstruction.parts.joinToString("") { it.text ?: "" }
    assertEquals("Initial\n\nAppended", sysText)
  }

  @Test
  fun cacheFields_defaultToNull() {
    val request = LlmRequest()

    assertNull(request.cacheConfig)
    assertNull(request.cacheMetadata)
    assertNull(request.cacheableContentsTokenCount)
  }

  @Test
  fun cacheFields_canBeSet() {
    val config = ContextCacheConfig()
    val metadata = CacheMetadata(fingerprint = "abc", contentsCount = 2)

    val request =
      LlmRequest(cacheConfig = config, cacheMetadata = metadata, cacheableContentsTokenCount = 5000)

    assertEquals(config, request.cacheConfig)
    assertEquals(metadata, request.cacheMetadata)
    assertEquals(5000, request.cacheableContentsTokenCount)
  }
}
