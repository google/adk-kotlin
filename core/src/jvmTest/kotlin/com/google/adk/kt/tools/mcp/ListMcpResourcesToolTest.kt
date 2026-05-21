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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ListMcpResourcesToolTest {

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
  fun run_withNoCursor_returnsResources() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(mockMcpSession)

    val resourceList =
      listOf(
        McpSchema.Resource.builder().name("res1").uri("uri1").mimeType("text/plain").build(),
        McpSchema.Resource.builder().name("res2").uri("uri2").build(),
      )
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, "cursor123")
    whenever(mockMcpSession.listResources()) doReturn mono { listResourcesResult }

    val context = createToolContext()

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
    val tool = ListMcpResourcesTool(mockMcpSession)

    val resourceList = emptyList<McpSchema.Resource>()
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, null)
    whenever(mockMcpSession.listResources("myCursor")) doReturn mono { listResourcesResult }

    val context = createToolContext()

    val result = tool.run(context, mapOf("cursor" to "myCursor"))

    val resultMap = result as Map<*, *>
    val resources = resultMap["resources"] as List<*>

    assertThat(resources).isEmpty()
    assertThat(resultMap.containsKey("nextCursor")).isFalse()
  }

  @Test
  fun run_withCursor_returnsNextCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(mockMcpSession)

    val resourceList = emptyList<McpSchema.Resource>()
    val listResourcesResult = McpSchema.ListResourcesResult(resourceList, "next-cursor")
    whenever(mockMcpSession.listResources("my-cursor")) doReturn mono { listResourcesResult }

    val context = createToolContext()

    val result = tool.run(context, mapOf("cursor" to "my-cursor"))

    val resultMap = result as Map<*, *>
    val resources = resultMap["resources"] as List<*>
    val nextCursor = resultMap["nextCursor"] as String

    assertThat(resources).isEmpty()
    assertThat(nextCursor).isEqualTo("next-cursor")
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(mockMcpSession)

    whenever(mockMcpSession.listResources()) doReturn
      mono { throw RuntimeException("Server error") }

    val context = createToolContext()

    val ex =
      kotlin.test.assertFailsWith<McpToolException.McpToolExecutionException> {
        tool.run(context, emptyMap())
      }
    assertThat(ex.message).contains("Server error")
  }

  @Test
  fun declaration_returnsCorrectSchema() {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourcesTool(mockMcpSession)

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("list_mcp_resources")
    assertThat(declaration.description).isEqualTo("List resources available on the MCP server.")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("cursor")).isTrue()
  }
}
