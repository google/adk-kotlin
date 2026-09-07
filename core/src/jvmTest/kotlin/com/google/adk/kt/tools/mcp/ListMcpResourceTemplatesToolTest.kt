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
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class ListMcpResourceTemplatesToolTest {

  private fun createMcpToolset(mockMcpSession: McpAsyncClient): McpToolset {
    // The toolset fetches the pooled session from the manager; hand it the mock session.
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
    return McpToolset(mockSessionManager)
  }

  @Test
  fun run_reResolvesTheSessionOnEveryCall() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val mockSessionManager =
      mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
    val tool = ListMcpResourceTemplatesTool(McpToolset(mockSessionManager))

    whenever(mockMcpSession.listResourceTemplates(isNull())) doReturn
      mono { McpSchema.ListResourceTemplatesResult.builder(emptyList()).build() }

    val context = testToolContext()
    val unusedFirst = tool.run(context, emptyMap())
    val unusedSecond = tool.run(context, emptyMap())

    // Capturing a client at load time would keep calling a dead one after the manager replaced
    // it, so template discovery must go back to the manager on each call.
    verifyBlocking(mockSessionManager, times(2)) { getSession(any(), anyOrNull()) }
  }

  @Test
  fun run_withNoCursor_returnsTemplates() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(createMcpToolset(mockMcpSession))

    val templateList =
      listOf(
        McpSchema.ResourceTemplate.builder("uri1/{id}", "tpl1")
          .description("template 1")
          .mimeType("text/plain")
          .build(),
        McpSchema.ResourceTemplate.builder("uri2/{id}", "tpl2").build(),
      )
    val listTemplatesResult =
      McpSchema.ListResourceTemplatesResult.builder(templateList).nextCursor("cursor123").build()
    whenever(mockMcpSession.listResourceTemplates(isNull())) doReturn mono { listTemplatesResult }

    val context = testToolContext()

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
    val tool = ListMcpResourceTemplatesTool(createMcpToolset(mockMcpSession))

    val templateList = emptyList<McpSchema.ResourceTemplate>()
    val listTemplatesResult = McpSchema.ListResourceTemplatesResult.builder(templateList).build()
    whenever(mockMcpSession.listResourceTemplates("myCursor")) doReturn mono { listTemplatesResult }

    val context = testToolContext()

    val result = tool.run(context, mapOf("cursor" to "myCursor"))

    val resultMap = result as Map<*, *>
    val templates = resultMap["resourceTemplates"] as List<*>

    assertThat(templates).isEmpty()
    assertThat(resultMap).doesNotContainKey("nextCursor")
  }

  @Test
  fun run_withCursor_returnsNextCursor() = runTest {
    val mockMcpSession = mock<McpAsyncClient>()
    val tool = ListMcpResourceTemplatesTool(createMcpToolset(mockMcpSession))

    val templateList = emptyList<McpSchema.ResourceTemplate>()
    val listTemplatesResult =
      McpSchema.ListResourceTemplatesResult.builder(templateList).nextCursor("next-cursor").build()
    whenever(mockMcpSession.listResourceTemplates("my-cursor")) doReturn
      mono { listTemplatesResult }

    val context = testToolContext()

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
    val tool = ListMcpResourceTemplatesTool(createMcpToolset(mockMcpSession))

    whenever(mockMcpSession.listResourceTemplates(isNull())) doReturn
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
    val tool = ListMcpResourceTemplatesTool(createMcpToolset(mockMcpSession))

    val declaration = tool.declaration()

    assertThat(declaration).isNotNull()
    assertThat(declaration.name).isEqualTo("list_mcp_resource_templates")
    assertThat(declaration.description)
      .startsWith("List resource templates available on the MCP server.")
    // The description must close the loop for the model: a listing it cannot act on is what made
    // this tool look inert. It has to say expand the uriTemplate, then read it by uri.
    assertThat(declaration.description).contains("uriTemplate")
    assertThat(declaration.description).contains("load_mcp_resource")
    assertThat(declaration.description).contains("'uri'")

    val properties = declaration.parameters?.properties
    assertThat(properties?.containsKey("cursor")).isTrue()
  }
}
