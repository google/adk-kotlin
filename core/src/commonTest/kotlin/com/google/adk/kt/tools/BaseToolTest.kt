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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BaseToolTest {

  private class DefaultTool : BaseTool("default_tool", "A tool with default arguments") {
    override fun declaration(): FunctionDeclaration? = null

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit
  }

  private class ToolWithDeclaration : BaseTool("tool_with_decl", "A tool") {
    override fun declaration(): FunctionDeclaration =
      FunctionDeclaration("tool_with_decl", "A tool")

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit
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
