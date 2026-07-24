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

import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doReturnConsecutively
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class McpToolsetTest {

  private val noResourcesCapabilities =
    McpSchema.ServerCapabilities(null, null, null, null, null, null)

  private val withResourcesCapabilities =
    McpSchema.ServerCapabilities.builder().resources(false, false).build()

  @Test
  fun getTools_retrievesAndFiltersTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()

    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").inputSchema(null).build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").inputSchema(null).build(),
        McpSchema.Tool.builder().name("tool3").description("desc 3").inputSchema(null).build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    // Create Toolset with a filter that only allows "tool1" and "tool3"
    val filter: (com.google.adk.kt.tools.BaseTool) -> Boolean = { tool ->
      tool.name == "tool1" || tool.name == "tool3"
    }

    val mcpToolset = McpToolset(mockSessionManager, filter)

    val tools = mcpToolset.getTools()
    assertEquals(2, tools.size)
    assertEquals("tool1", tools[0].name)
    assertEquals("tool3", tools[1].name)

    // The toolset fetches the pooled session from the manager and lists tools on it.
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
    verify(mockMcpSession, times(1)).listTools()
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndServerSupport_includesResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn withResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = true)

    val tools = mcpToolset.getTools()

    assertEquals(3, tools.size)
    assertEquals("list_mcp_resources", tools[0].name)
    assertEquals("load_mcp_resource", tools[1].name)
    assertEquals("list_mcp_resource_templates", tools[2].name)
  }

  @Test
  fun loadTools_withUseMcpResourcesTrueAndNoServerSupport_omitsResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn noResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = true)

    val tools = mcpToolset.getTools()

    assertEquals(0, tools.size)
  }

  @Test
  fun loadTools_withUseMcpResourcesFalse_omitsResourceTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.serverCapabilities) doReturn withResourcesCapabilities

    val toolsResponse = McpSchema.ListToolsResult(emptyList(), null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager, useMcpResources = false)

    val tools = mcpToolset.getTools()

    assertEquals(0, tools.size)
  }

  @Test
  fun getTools_retriesOnFailureAndSucceeds() = runTest {
    // The first pooled session fails to list tools; the toolset asks the manager for a fresh
    // session (passing the failed one as `stale`) and the replacement succeeds.
    val failingSession = mock<McpAsyncClient>()
    whenever(failingSession.listTools()).thenThrow(RuntimeException("list failed"))

    val recoveringSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(McpSchema.Tool.builder().name("tool1").description("desc 1").inputSchema(null).build())
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(recoveringSession.listTools()) doReturn mono { toolsResponse }

    val mockSessionManager =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) } doReturnConsecutively
          listOf(failingSession, recoveringSession)
      }

    val mcpToolset = McpToolset(mockSessionManager)
    val tools = mcpToolset.getTools()

    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
    // Two attempts: the initial fetch plus one recovery fetch.
    verifyBlocking(mockSessionManager, times(2)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_throwsMcpToolLoadingException_whenRetriesExhausted() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(RuntimeException("list failed always"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<McpToolLoadingException> { mcpToolset.getTools() }
    // Three attempts: the initial fetch plus two recovery fetches.
    verifyBlocking(mockSessionManager, times(3)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_throwsMcpToolLoadingException_onIllegalArgumentException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(IllegalArgumentException("illegal argument"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<McpToolLoadingException> { mcpToolset.getTools() }
    // IllegalArgumentException is not retried.
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun getTools_rethrowsCancellationException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    whenever(mockMcpSession.listTools()).thenThrow(CancellationException("cancelled"))

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<CancellationException> { mcpToolset.getTools() }
    verifyBlocking(mockSessionManager, times(1)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun close_closesAllSessionsViaManager() = runTest {
    val mockSessionManager = mock<SessionManager>()

    val mcpToolset = McpToolset(mockSessionManager)
    mcpToolset.close()

    // The toolset delegates teardown to the manager, which owns every session it created.
    verify(mockSessionManager, times(1)).closeAll()
  }

  @Test
  fun mcpToolsetConfig_toToolset_appliesFilterCorrectly() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").inputSchema(null).build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").inputSchema(null).build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val config =
      McpToolset.McpToolsetConfig(
        // sseConnectionParams are required for the public toToolset() to pass validation,
        // but are not used when a sessionManager is provided.
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        toolFilter = listOf("tool1"),
      )

    val toolset = config.toToolset(mockSessionManager)

    val tools = toolset.getTools()
    assertEquals(1, tools.size)
    assertEquals("tool1", tools[0].name)
  }

  @Test
  fun mcpToolsetConfig_toToolset_throwsExceptionIfMultipleParamsSet() {
    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        stdioConnectionParams =
          McpConnectionParameters.Stdio(
            serverParameters =
              io.modelcontextprotocol.client.transport.ServerParameters.builder("cmd").build()
          ),
      )

    assertFailsWith<IllegalArgumentException> { config.toToolset() }
  }

  @Test
  fun mcpToolsetConfig_toToolset_throwsExceptionIfNoParamsSet() {
    val config = McpToolset.McpToolsetConfig()
    assertFailsWith<IllegalArgumentException> { config.toToolset() }
  }

  @Test
  fun fromConfig_createsToolsetFromConfig() {
    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234")
      )

    val toolset = config.toToolset()
    assertNotNull(toolset)
  }

  @Test
  fun listResources_returnsResourceNames() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()

    val resourceList =
      listOf(
        McpSchema.Resource.builder().name("resource1").uri("uri1").build(),
        McpSchema.Resource.builder().name("resource2").uri("uri2").build(),
      )
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, null)
    whenever(mockMcpSession.listResources()) doReturn mono { listResourcesResult }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)
    val resources = mcpToolset.listResources()

    assertEquals(listOf("resource1", "resource2"), resources)
    verify(mockMcpSession, times(1)).listResources()
  }

  @Test
  fun readResource_returnsResourceContents() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()

    val textContents = McpSchema.TextResourceContents("file contents", "text/plain", "uri1")
    val readResourceResult = McpSchema.ReadResourceResult(listOf(textContents))
    whenever(mockMcpSession.readResource(McpSchema.ReadResourceRequest("uri1"))) doReturn
      mono { readResourceResult }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)
    val contents = mcpToolset.readResource("uri1")

    assertEquals(listOf(textContents), contents)
    verify(mockMcpSession, times(1)).readResource(McpSchema.ReadResourceRequest("uri1"))
  }

  @Test
  fun readResource_throwsIllegalArgumentException_whenResourceNotFound() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()

    whenever(
      mockMcpSession.readResource(McpSchema.ReadResourceRequest("nonexistent_resource"))
    ) doReturn mono { throw IllegalArgumentException("Resource not found") }

    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val mcpToolset = McpToolset(mockSessionManager)

    assertFailsWith<IllegalArgumentException> { mcpToolset.readResource("nonexistent_resource") }
  }

  @Test
  fun mcpToolsetConfig_toToolset_withEmptyFilter_returnsNoTools() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val toolsList =
      listOf(
        McpSchema.Tool.builder().name("tool1").description("desc 1").inputSchema(null).build(),
        McpSchema.Tool.builder().name("tool2").description("desc 2").inputSchema(null).build(),
      )
    val toolsResponse = McpSchema.ListToolsResult(toolsList, null)
    whenever(mockMcpSession.listTools()) doReturn mono { toolsResponse }
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }

    val config =
      McpToolset.McpToolsetConfig(
        sseConnectionParams = McpConnectionParameters.Sse(url = "http://localhost:1234"),
        toolFilter = emptyList(),
      )

    val toolset = config.toToolset(mockSessionManager)

    val tools = toolset.getTools()
    assertEquals(0, tools.size)
  }
}
