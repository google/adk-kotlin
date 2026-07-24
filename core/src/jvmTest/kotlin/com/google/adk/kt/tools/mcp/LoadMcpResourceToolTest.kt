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

import com.google.adk.kt.testing.testToolContext
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LoadMcpResourceToolTest {

  private fun createMcpToolset(mockMcpSession: McpAsyncClient): McpToolset {
    // The toolset fetches the pooled session from the manager; hand it the mock session.
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
    return McpToolset(mockSessionManager)
  }

  private fun resource(name: String, uri: String, mimeType: String? = null): McpSchema.Resource {
    val builder = McpSchema.Resource.builder().name(name).uri(uri)
    mimeType?.let { builder.mimeType(it) }
    return builder.build()
  }

  /** Stubs the full resource listing returned by the server. */
  private fun stubResources(session: McpAsyncClient, vararg resources: McpSchema.Resource) {
    // listAllResources delegates to the no-arg listResources(), which aggregates every page.
    whenever(session.listResources()) doReturn
      mono { McpSchema.ListResourcesResult(resources.toList(), null) }
  }

  @Test
  fun run_withTextContents_returnsConcatenatedText() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents =
      listOf(
        McpSchema.TextResourceContents("uri1", "text/plain", "Part 1 "),
        McpSchema.TextResourceContents("uri1", "text/plain", "Part 2"),
      )
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult(contents) }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    assertThat(result).isEqualTo("Part 1 \n\nPart 2")
  }

  @Test
  fun run_resolvesNameToUri_acrossFullListing() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    // The target is not the first resource: the tool must scan the whole listing to resolve it.
    stubResources(mockMcpSession, resource("other", "uriOther"), resource("target", "uriTarget"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono {
        McpSchema.ReadResourceResult(
          listOf(McpSchema.TextResourceContents("uriTarget", "text/plain", "hello"))
        )
      }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "target"))

    assertThat(result).isEqualTo("hello")
    // The resolved URI (not the name) is what gets read from the server.
    val captor = argumentCaptor<McpSchema.ReadResourceRequest>()
    verify(mockMcpSession).readResource(captor.capture())
    assertThat(captor.firstValue.uri()).isEqualTo("uriTarget")
  }

  @Test
  fun run_withTextContents_truncatesWhenExceedingMax() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 5)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents = listOf(McpSchema.TextResourceContents("uri1", "text/plain", "HelloWorld"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult(contents) }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    val resultStr = result as String
    assertThat(resultStr).startsWith("Hello")
    assertThat(resultStr).contains("[Content truncated due to size limit]")
  }

  @Test
  fun run_withBlobContents_returnsWarning() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    val contents =
      listOf(
        McpSchema.BlobResourceContents("uri1", "application/octet-stream", "binary_data_base64")
      )
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult(contents) }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    val resultStr = result as String
    assertThat(resultStr)
      .contains("[Warning: Binary data found at this URI, cannot display raw content]")
  }

  @Test
  fun run_withNoContents_returnsEmptyString() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { McpSchema.ReadResourceResult(emptyList()) }

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "res1"))

    assertThat(result).isEqualTo("")
  }

  @Test
  fun run_unknownName_returnsNotFoundMessage() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "missing")) as String

    assertThat(result).contains("No resource named")
    assertThat(result).contains("missing")
    assertThat(result).contains("list_mcp_resources")
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_ambiguousName_returnsErrorListingCandidates() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("dup", "uriA"), resource("dup", "uriB"))

    val context = testToolContext()
    val result = tool.run(context, mapOf("name" to "dup")) as String

    assertThat(result).contains("ambiguous")
    assertThat(result).contains("uriA")
    assertThat(result).contains("uriB")
    // An ambiguous name must never be read: it cannot be resolved to a single URI.
    verify(mockMcpSession, never()).readResource(any<McpSchema.ReadResourceRequest>())
  }

  @Test
  fun run_missingName_throwsMcpToolExecutionException() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    val context = testToolContext()
    val ex =
      assertFailsWith<McpToolException.McpToolExecutionException> { tool.run(context, emptyMap()) }
    assertThat(ex.cause).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mcpToolset = createMcpToolset(mockMcpSession)
    val tool = LoadMcpResourceTool(mcpToolset, maxMcpResourceLength = 1000)

    stubResources(mockMcpSession, resource("res1", "uri1"))
    whenever(mockMcpSession.readResource(any<McpSchema.ReadResourceRequest>())) doReturn
      mono { throw RuntimeException("Server error") }

    val context = testToolContext()

    val ex =
      assertFailsWith<McpToolException.McpToolExecutionException> {
        tool.run(context, mapOf("name" to "res1"))
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
    assertThat(declaration.description).isEqualTo("Load a resource from the MCP server by name.")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("name")).isTrue()
    assertThat(declaration.parameters?.required).containsExactly("name")
  }
}
