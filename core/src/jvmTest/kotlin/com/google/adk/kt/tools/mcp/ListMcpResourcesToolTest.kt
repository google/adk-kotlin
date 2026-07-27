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
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ListMcpResourcesToolTest {

  private fun createMcpToolset(mockMcpSession: McpAsyncClient): McpToolset {
    // The toolset fetches the pooled session from the manager; hand it the mock session.
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
    return McpToolset(mockSessionManager)
  }

  @Test
  fun run_withNoCursor_returnsResources() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    val resourceList =
      listOf(
        McpSchema.Resource.builder().name("res1").uri("uri1").mimeType("text/plain").build(),
        McpSchema.Resource.builder().name("res2").uri("uri2").build(),
      )
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, "cursor123")
    whenever(mockMcpSession.listResources(isNull())) doReturn mono { listResourcesResult }

    val context = testToolContext()

    val result = tool.run(context, emptyMap())

    val resultMap = result as Map<*, *>
    val resources = resultMap["resources"] as List<*>
    val nextCursor = resultMap["nextCursor"] as String

    assertThat(resources).hasSize(2)
    val res1 = resources[0] as Map<*, *>
    assertThat(res1["name"]).isEqualTo("res1")
    assertThat(res1["uri"]).isEqualTo("uri1")
    assertThat(res1["mimeType"]).isEqualTo("text/plain")

    val res2 = resources[1] as Map<*, *>
    assertThat(res2["name"]).isEqualTo("res2")
    assertThat(res2["uri"]).isEqualTo("uri2")
    assertThat(res2).doesNotContainKey("mimeType")

    assertThat(nextCursor).isEqualTo("cursor123")
  }

  @Test
  fun run_withCursor_queriesWithCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    val resourceList = emptyList<McpSchema.Resource>()
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, null)
    whenever(mockMcpSession.listResources("myCursor")) doReturn mono { listResourcesResult }

    val context = testToolContext()

    val result = tool.run(context, mapOf("cursor" to "myCursor"))

    val resultMap = result as Map<*, *>
    val resources = resultMap["resources"] as List<*>

    assertThat(resources).isEmpty()
    assertThat(resultMap.containsKey("nextCursor")).isFalse()
  }

  @Test
  fun run_withCursor_returnsNextCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    val resourceList = emptyList<McpSchema.Resource>()
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, "next-cursor")
    whenever(mockMcpSession.listResources("my-cursor")) doReturn mono { listResourcesResult }

    val context = testToolContext()

    val result = tool.run(context, mapOf("cursor" to "my-cursor"))

    val resultMap = result as Map<*, *>
    val resources = resultMap["resources"] as List<*>
    val nextCursor = resultMap["nextCursor"] as String

    assertThat(resources).isEmpty()
    assertThat(nextCursor).isEqualTo("next-cursor")
  }

  @Test
  fun run_browsesPageByPage_followingNextCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    // First page (null cursor) returns page 1 and a live cursor to the next page.
    whenever(mockMcpSession.listResources(isNull())) doReturn
      mono {
        McpSchema.ListResourcesResult(
          listOf(McpSchema.Resource.builder().name("res1").uri("uri1").build()),
          "page-2",
        )
      }
    // Passing that cursor back returns page 2, with no further pages.
    whenever(mockMcpSession.listResources("page-2")) doReturn
      mono {
        McpSchema.ListResourcesResult(
          listOf(McpSchema.Resource.builder().name("res2").uri("uri2").build()),
          null,
        )
      }

    val context = testToolContext()

    val page1 = tool.run(context, emptyMap()) as Map<*, *>
    assertThat((page1["resources"] as List<*>).map { (it as Map<*, *>)["name"] })
      .containsExactly("res1")
    val cursor = page1["nextCursor"] as String
    assertThat(cursor).isEqualTo("page-2")

    val page2 = tool.run(context, mapOf("cursor" to cursor)) as Map<*, *>
    assertThat((page2["resources"] as List<*>).map { (it as Map<*, *>)["name"] })
      .containsExactly("res2")
    assertThat(page2).doesNotContainKey("nextCursor")
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    whenever(mockMcpSession.listResources(isNull())) doReturn
      mono { throw RuntimeException("Server error") }

    val context = testToolContext()

    val ex =
      kotlin.test.assertFailsWith<McpToolException.McpToolExecutionException> {
        tool.run(context, emptyMap())
      }
    assertThat(ex.message).contains("Server error")
  }

  @Test
  fun declaration_returnsCorrectSchema() {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(createMcpToolset(mockMcpSession))

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("list_mcp_resources")
    assertThat(declaration.description).startsWith("List resources available on the MCP server.")
    // The description must tell the model how to page through results.
    assertThat(declaration.description).contains("nextCursor")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("cursor")).isTrue()
  }
}
