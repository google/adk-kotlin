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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LoadMcpResourceToolTest {

  private val mockInitializeResult =
    McpSchema.InitializeResult(
      "1.0",
      McpSchema.ServerCapabilities(null, null, null, null, null, null),
      McpSchema.Implementation("test-server", "1.0", null),
      "instructions",
      null,
    )

  private suspend fun createMcpToolset(mockMcpSession: McpAsyncClient): McpToolset {
    whenever(mockMcpSession.initialize()) doReturn mono { mockInitializeResult }
    val mockSessionManager = mock<SessionManager>()
    // Mock for loadTools which might be called if we don't ensure it is initialized
    // But getOrInitSession handles it.
    whenever(mockSessionManager.createAsyncSession()) doReturn mockMcpSession
    return McpToolset(mockSessionManager)
  }

  private fun createToolContext(): ToolContext {
    val session = testSession()
    val testAgent =
      object : BaseAgent(name = "test-agent") {
        override fun runAsyncImpl(context: InvocationContext): Flow<Event> = emptyFlow()
      }
    val invocationContext =
      InvocationContext(session = session, runConfig = null, agent = testAgent)
    return ToolContext(invocationContext = invocationContext)
  }

  @Test
  fun run_withTextContents_returnsConcatenatedText() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val contents =
      listOf(
        McpSchema.TextResourceContents("uri1", "text/plain", "Part 1 "),
        McpSchema.TextResourceContents("uri1", "text/plain", "Part 2"),
      )
    val readResourceResult = McpSchema.ReadResourceResult(contents)
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { readResourceResult }

    val context = createToolContext()
    val result = tool.run(context, mapOf("uri" to "uri1"))

    assertThat(result).isEqualTo("Part 1 \n\nPart 2")
  }

  @Test
  fun run_withTextContents_truncatesWhenExceedingMax() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 5)

    val contents = listOf(McpSchema.TextResourceContents("uri1", "text/plain", "HelloWorld"))
    val readResourceResult = McpSchema.ReadResourceResult(contents)
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { readResourceResult }

    val context = createToolContext()
    val result = tool.run(context, mapOf("uri" to "uri1"))

    val resultStr = result as String
    assertThat(resultStr).startsWith("Hello")
    assertThat(resultStr).contains("[Content truncated due to size limit]")
  }

  @Test
  fun run_withBlobContents_returnsWarning() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val contents =
      listOf(
        McpSchema.BlobResourceContents("uri1", "application/octet-stream", "binary_data_base64")
      )
    val readResourceResult = McpSchema.ReadResourceResult(contents)
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { readResourceResult }

    val context = createToolContext()
    val result = tool.run(context, mapOf("uri" to "uri1"))

    val resultStr = result as String
    assertThat(resultStr)
      .contains("[Warning: Binary data found at this URI, cannot display raw content]")
  }

  @Test
  fun run_withNoContents_returnsWarningMessage() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val readResourceResult = McpSchema.ReadResourceResult(emptyList())
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { readResourceResult }

    val context = createToolContext()
    val result = tool.run(context, mapOf("uri" to "uri1"))

    assertThat(result).isEqualTo("")
  }

  @Test
  fun run_missingUri_throwsMcpToolExecutionException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val context = createToolContext()
    val ex =
      assertFailsWith<McpToolException.McpToolExecutionException> { tool.run(context, emptyMap()) }
    assertThat(ex.cause).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { throw RuntimeException("Server error") }

    val context = createToolContext()

    val ex =
      assertFailsWith<McpToolException.McpToolExecutionException> {
        tool.run(context, mapOf("uri" to "uri1"))
      }
    assertThat(ex.message).contains("Server error")
  }

  @Test
  fun declaration_returnsCorrectSchema() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("load_mcp_resource")
    assertThat(declaration.description).isEqualTo("Load a resource from the MCP server by URI.")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("uri")).isTrue()
  }
}
