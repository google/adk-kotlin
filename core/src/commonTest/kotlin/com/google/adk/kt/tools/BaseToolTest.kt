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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.FunctionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BaseToolTest {

  private class DefaultTool : BaseTool("default_tool", "A tool with default arguments") {
    override fun declaration(): FunctionDeclaration? = null

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      return "default result"
    }
  }

  private class ExtendedTool :
    BaseTool(
      name = "extended_tool",
      description = "A generic extended tool",
      customMetadata = mapOf("key" to "value"),
    ) {
    override val isLongRunning = true
    val expectedDeclaration = FunctionDeclaration("extended_tool", "A generic extended tool")

    override fun declaration(): FunctionDeclaration? = expectedDeclaration

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      return args
    }
  }

  private class MinimalTool : BaseTool("minimal_tool", "A minimal tool") {
    override fun declaration(): FunctionDeclaration? = null

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
      throw NotImplementedError("Tool execution is not implemented for tool: minimal_tool")
    }
  }

  private class ToolWithDeclaration : BaseTool("tool_with_decl", "A tool") {
    override fun declaration(): FunctionDeclaration =
      FunctionDeclaration("tool_with_decl", "A tool")

    override suspend fun run(context: ToolContext, args: Map<String, Any>): Any = Unit
  }

  @Test
  fun tool_defaultTool_hasDefaultProperties() {
    val tool = DefaultTool()

    assertEquals("default_tool", tool.name)
    assertEquals("A tool with default arguments", tool.description)
    assertFalse(tool.isLongRunning)
    assertTrue(tool.customMetadata.isEmpty())
  }

  @Test
  fun tool_extendedTool_hasCorrectProperties() {
    val tool = ExtendedTool()

    assertEquals("extended_tool", tool.name)
    assertEquals("A generic extended tool", tool.description)
    assertTrue(tool.isLongRunning)
    assertEquals(mapOf("key" to "value"), tool.customMetadata)
  }

  @Test
  fun declaration_onDefaultTool_returnsNull() {
    val tool = DefaultTool()

    assertNull(tool.declaration())
  }

  @Test
  fun declaration_onExtendedTool_returnsTool() {
    val tool = ExtendedTool()

    assertSame(tool.expectedDeclaration, tool.declaration())
  }

  @Test
  fun declaration_onToolWithDeclaration_returnsTool() {
    val tool = ToolWithDeclaration()

    val declaration = tool.declaration()
    assertNotNull(declaration)
    assertEquals("tool_with_decl", declaration.name)
  }

  @Test
  fun run_onDefaultTool_returnsDefaultResult() = runTest {
    val tool = DefaultTool()
    val toolContext = testToolContext()
    val args = emptyMap<String, Any>()

    val result = tool.run(toolContext, args)

    assertEquals("default result", result)
  }

  @Test
  fun run_onExtendedTool_returnsArguments() = runTest {
    val tool = ExtendedTool()
    val toolContext = testToolContext()
    val args = mapOf("arg1" to "value1", "arg2" to 42)

    val result = tool.run(toolContext, args)

    assertEquals(args, result)
  }

  @Test
  fun declaration_onMinimalTool_returnsNull() {
    val tool = MinimalTool()

    assertNull(tool.declaration())
  }

  @Test
  fun run_onMinimalTool_throwsNotImplementedError() = runTest {
    val tool = MinimalTool()
    val toolContext = testToolContext()

    val exception = assertFailsWith<NotImplementedError> { tool.run(toolContext, emptyMap()) }
    assertEquals("Tool execution is not implemented for tool: minimal_tool", exception.message)
  }

  @Test
  fun processLlmRequest_withDeclaration_addsTool() = runTest {
    val tool = ToolWithDeclaration()
    val toolContext = testToolContext()
    var llmRequest = LlmRequest()

    llmRequest = tool.processLlmRequest(toolContext, llmRequest)

    val configTools = llmRequest.config.tools
    assertNotNull(configTools)
    assertEquals(1, configTools.size)
    val addedTool = configTools.first()
    assertEquals(1, addedTool.functionDeclarations?.size)
    assertEquals("tool_with_decl", addedTool.functionDeclarations?.first()?.name)
  }
}
