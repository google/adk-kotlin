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
import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkFunctionDeclaration
import com.google.adk.kt.types.Type
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class McpToolTest {
  private val mockMcpSession = mock<McpAsyncClient>()
  private val mcpSchemaTool = McpSchema.Tool.builder().name("testTool").build()
  private val mcpTool =
    McpTool(
      "testTool",
      "description",
      mcpSchemaTool,
      mockMcpSession,
      McpSessionManager(McpConnectionParameters.Sse(url = "http://localhost:1234")),
    )
  private val toolContext = testToolContext()

  @Test
  fun annotations_returnsAnnotations() {
    val annotations = McpSchema.ToolAnnotations("title", null, null, null, null, null)
    val mcpSchemaToolWithAnnotations =
      McpSchema.Tool.builder().name("testTool").annotations(annotations).build()
    val tool =
      McpTool(
        "testTool",
        "description",
        mcpSchemaToolWithAnnotations,
        mockMcpSession,
        McpSessionManager(McpConnectionParameters.Sse(url = "http://localhost:1234")),
      )
    assertEquals(annotations, tool.annotations)
  }

  @Test
  fun meta_returnsMeta() {
    val meta = mapOf("key" to "value")
    val mcpSchemaToolWithMeta = McpSchema.Tool.builder().name("testTool").meta(meta).build()
    val tool =
      McpTool(
        "testTool",
        "description",
        mcpSchemaToolWithMeta,
        mockMcpSession,
        McpSessionManager(McpConnectionParameters.Sse(url = "http://localhost:1234")),
      )
    assertEquals(meta, tool.meta)
  }

  @Test
  fun getMcpSession_returnsSession() {
    assertEquals(mockMcpSession, mcpTool.mcpSessionClient)
  }

  @Test
  fun declaration_returnsDeclaration() {
    val declaration = mcpTool.declaration()
    assertNotNull(declaration)
    assertEquals("testTool", declaration.name)
  }

  @Test
  fun run_convertsCallToolResultToJsonNativeMap() = runTest {
    val responseContent = McpSchema.TextContent("test result")
    val invokeResponse = McpSchema.CallToolResult.builder().content(listOf(responseContent)).build()
    whenever(mockMcpSession.callTool(any())) doReturn mono { invokeResponse }

    val result = mcpTool.run(toolContext, emptyMap())

    assertSingleTextResult(result, "test result")
  }

  @Test
  fun mcpSchemaConverter_convertsMcpSchemaToolToAdkFunctionDeclaration() {
    val mcpInputSchema =
      McpSchema.JsonSchema(
        "object",
        mapOf("param1" to mapOf("type" to "string", "description" to "param1 description")),
        listOf("param1"),
        false,
        null,
        null,
      )
    val mcpOutputSchema =
      mapOf(
        "type" to "object",
        "properties" to
          mapOf("result" to mapOf("type" to "integer", "description" to "result description")),
      )

    val mcpToolSchema =
      McpSchema.Tool.builder()
        .name("myTool")
        .description("my tool description")
        .inputSchema(mcpInputSchema)
        .outputSchema(mcpOutputSchema)
        .build()

    val functionDeclaration = mcpToolSchema.toAdkFunctionDeclaration()

    assertEquals("myTool", functionDeclaration.name)
    assertEquals("my tool description", functionDeclaration.description)
    val parameters = functionDeclaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    val properties = parameters.properties
    assertNotNull(properties)
    assertEquals(1, properties.size)
    assertEquals(Type.STRING, properties["param1"]!!.type)
    assertEquals(listOf("param1"), parameters.required)
  }

  @Test
  fun declaration_throwsMcpToolDeclarationException_onMalformedSchema() {
    val malformedMcpSchemaTool =
      McpSchema.Tool.builder()
        .name("malformedTool")
        .inputSchema(McpSchema.JsonSchema("invalid-type", null, null, false, null, null))
        .build()
    val tool =
      McpTool(
        "malformedTool",
        "description",
        malformedMcpSchemaTool,
        mockMcpSession,
        McpSessionManager(McpConnectionParameters.Sse(url = "http://localhost:1234")),
      )
    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  @Test
  fun run_retriesOnSessionErrorAndSucceedsOnLastTry() = runTest {
    val responseContent = McpSchema.TextContent("test result")
    val invokeResponse = McpSchema.CallToolResult.builder().content(listOf(responseContent)).build()

    val mockInitializeResult =
      McpSchema.InitializeResult(
        "1.0",
        McpSchema.ServerCapabilities(null, null, null, null, null, null),
        McpSchema.Implementation("test-server", "1.0", null),
        "instructions",
        null,
      )

    val mockNewMcpSession = mock<McpAsyncClient>()
    whenever(mockNewMcpSession.initialize()) doReturn mono { mockInitializeResult }
    whenever(mockNewMcpSession.callTool(any()))
      .thenThrow(RuntimeException("new session fail 1"))
      .thenThrow(RuntimeException("new session fail 2"))
      .thenReturn(mono { invokeResponse })

    val mockSessionManager = mock<SessionManager>()
    whenever(mockSessionManager.createAsyncSession()).thenReturn(mockNewMcpSession)

    val mcpToolWithRetry =
      McpTool("testTool", "description", mcpSchemaTool, mockMcpSession, mockSessionManager)
    whenever(mockMcpSession.callTool(any())).thenThrow(RuntimeException("old session fail"))

    val result = mcpToolWithRetry.run(toolContext, emptyMap())
    assertSingleTextResult(result, "test result")
    verify(mockSessionManager, times(3)).createAsyncSession()
  }

  /**
   * Asserts [result] is the JSON-native map McpTool.run produces for a [McpSchema.CallToolResult]
   * carrying a single non-error [McpSchema.TextContent] (the SDK mapper renders it as `{"content":
   * [{"type": "text", "text": ...}], "isError": false}`).
   */
  private fun assertSingleTextResult(result: Any?, expectedText: String) {
    val map = result as Map<*, *>
    assertEquals(false, map["isError"])
    val first = (map["content"] as List<*>).single() as Map<*, *>
    assertEquals("text", first["type"])
    assertEquals(expectedText, first["text"])
  }
}
