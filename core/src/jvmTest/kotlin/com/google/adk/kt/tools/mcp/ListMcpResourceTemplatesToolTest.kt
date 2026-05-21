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

class ListMcpResourceTemplatesToolTest {

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
  fun run_withNoCursor_returnsTemplates() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(mockMcpSession)

    val templateList =
      listOf(
        McpSchema.ResourceTemplate.builder()
          .name("tpl1")
          .uriTemplate("uri1/{id}")
          .description("template 1")
          .mimeType("text/plain")
          .build(),
        McpSchema.ResourceTemplate.builder().name("tpl2").uriTemplate("uri2/{id}").build(),
      )
    val listTemplatesResult = McpSchema.ListResourceTemplatesResult(templateList, "cursor123")
    whenever(mockMcpSession.listResourceTemplates()) doReturn mono { listTemplatesResult }

    val context = createToolContext()

    val result = tool.run(context, emptyMap())

    val resultMap = result as Map<*, *>
    val templates = resultMap["resourceTemplates"] as List<*>
    val nextCursor = resultMap["nextCursor"] as String

    assertThat(templates).hasSize(2)
    val tpl1 = templates[0] as Map<*, *>
    assertThat(tpl1["name"]).isEqualTo("tpl1")
    assertThat(tpl1["uriTemplate"]).isEqualTo("uri1/{id}")
    assertThat(tpl1["description"]).isEqualTo("template 1")
    assertThat(tpl1["mimeType"]).isEqualTo("text/plain")

    val tpl2 = templates[1] as Map<*, *>
    assertThat(tpl2["name"]).isEqualTo("tpl2")
    assertThat(tpl2["uriTemplate"]).isEqualTo("uri2/{id}")
    assertThat(tpl2).doesNotContainKey("description")
    assertThat(tpl2).doesNotContainKey("mimeType")

    assertThat(nextCursor).isEqualTo("cursor123")
  }

  @Test
  fun run_withCursor_queriesWithCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(mockMcpSession)

    val templateList = emptyList<McpSchema.ResourceTemplate>()
    val listTemplatesResult = McpSchema.ListResourceTemplatesResult(templateList, null)
    whenever(mockMcpSession.listResourceTemplates("myCursor")) doReturn mono { listTemplatesResult }

    val context = createToolContext()

    val result = tool.run(context, mapOf("cursor" to "myCursor"))

    val resultMap = result as Map<*, *>
    val templates = resultMap["resourceTemplates"] as List<*>

    assertThat(templates).isEmpty()
    assertThat(resultMap).doesNotContainKey("nextCursor")
  }

  @Test
  fun run_withCursor_returnsNextCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(mockMcpSession)

    val templateList = emptyList<McpSchema.ResourceTemplate>()
    val listTemplatesResult = McpSchema.ListResourceTemplatesResult(templateList, "next-cursor")
    whenever(mockMcpSession.listResourceTemplates("my-cursor")) doReturn
      mono { listTemplatesResult }

    val context = createToolContext()

    val result = tool.run(context, mapOf("cursor" to "my-cursor"))

    val resultMap = result as Map<*, *>
    val templates = resultMap["resourceTemplates"] as List<*>
    val nextCursor = resultMap["nextCursor"] as String

    assertThat(templates).isEmpty()
    assertThat(nextCursor).isEqualTo("next-cursor")
  }

  @Test
  fun run_throwsMcpToolExecutionExceptionOnFailure() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(mockMcpSession)

    whenever(mockMcpSession.listResourceTemplates()) doReturn
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
    val tool = ListMcpResourceTemplatesTool(mockMcpSession)

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("list_mcp_resource_templates")
    assertThat(declaration.description)
      .isEqualTo("List resource templates available on the MCP server.")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("cursor")).isTrue()
  }
}
