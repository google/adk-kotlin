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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doReturnConsecutively
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class McpToolTest {
  private val mockMcpSession = mock<McpAsyncClient>()
  // The tool fetches its session from the manager on each run; hand it the shared mock session.
  private val mockSessionManager =
    mock<SessionManager> { onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession }
  private val mcpSchemaTool = McpSchema.Tool.builder("testTool", mapOf("type" to "object")).build()
  private val mcpTool = McpTool("testTool", "description", mcpSchemaTool, mockSessionManager)
  private val toolContext = testToolContext()

  @Test
  fun annotations_returnsAnnotations() {
    val annotations = McpSchema.ToolAnnotations("title", null, null, null, null, null)
    val mcpSchemaToolWithAnnotations =
      McpSchema.Tool.builder("testTool", mapOf("type" to "object")).annotations(annotations).build()
    val tool = McpTool("testTool", "description", mcpSchemaToolWithAnnotations, mockSessionManager)
    assertEquals(annotations, tool.annotations)
  }

  @Test
  fun meta_returnsMeta() {
    val meta = mapOf("key" to "value")
    val mcpSchemaToolWithMeta =
      McpSchema.Tool.builder("testTool", mapOf("type" to "object")).meta(meta).build()
    val tool = McpTool("testTool", "description", mcpSchemaToolWithMeta, mockSessionManager)
    assertEquals(meta, tool.meta)
  }

  @Test
  fun declaration_returnsDeclaration() {
    val declaration = mcpTool.declaration()
    assertNotNull(declaration)
    assertEquals("testTool", declaration.name)
  }

  @Test
  fun declaration_calledTwice_convertsOnlyOnce() {
    // Every model request asks each tool for its declaration, and converting a schema walks the
    // whole thing. The answer cannot change for a given tool, so it is built once -- which also
    // keeps any warning raised during conversion from repeating on every request.
    val first = mcpTool.declaration()
    val second = mcpTool.declaration()

    assertSame(first, second)
  }

  @Test
  fun declaration_failingConversion_throwsEveryTime() {
    // A cached value must not turn a permanent failure into a one-off: `lazy` leaves itself
    // uninitialized when the initializer throws, so each call retries and rethrows.
    val badSchema: Map<String, Any> =
      mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "nonsense")))
    val tool =
      McpTool(
        "badTool",
        "description",
        McpSchema.Tool.builder("badTool", badSchema).build(),
        mockSessionManager,
      )

    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  @Test
  fun run_convertsCallToolResultToJsonNativeMap() = runTest {
    val responseContent = McpSchema.TextContent.builder("test result").build()
    val invokeResponse = McpSchema.CallToolResult.builder().content(listOf(responseContent)).build()
    whenever(mockMcpSession.callTool(any())) doReturn mono { invokeResponse }

    val result = mcpTool.run(toolContext, emptyMap())

    assertSingleTextResult(result, "test result")
  }

  @Test
  fun run_withProgressConsumers_sendsFunctionCallIdAsProgressToken() = runTest {
    whenever(mockMcpSession.callTool(any())) doReturn mono { textResult("ok") }
    val sessionManagerWithConsumers =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession
        on { hasProgressConsumers } doReturn true
      }
    val tool = McpTool("testTool", "description", mcpSchemaTool, sessionManagerWithConsumers)

    val unused = tool.run(testToolContext(functionCallId = "fc1"), emptyMap())

    val request = argumentCaptor<McpSchema.CallToolRequest>()
    verify(mockMcpSession).callTool(request.capture())
    assertEquals("fc1", request.firstValue.progressToken())
  }

  @Test
  fun run_withProgressConsumersAndNoFunctionCallId_sendsGeneratedProgressToken() = runTest {
    whenever(mockMcpSession.callTool(any())) doReturn mono { textResult("ok") }
    val sessionManagerWithConsumers =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) } doReturn mockMcpSession
        on { hasProgressConsumers } doReturn true
      }
    val tool = McpTool("testTool", "description", mcpSchemaTool, sessionManagerWithConsumers)

    val unused = tool.run(testToolContext(functionCallId = null), emptyMap())

    val request = argumentCaptor<McpSchema.CallToolRequest>()
    verify(mockMcpSession).callTool(request.capture())
    val token = request.firstValue.progressToken()
    assertNotNull(token)
    assertTrue(token.toString().isNotBlank())
  }

  @Test
  fun run_withoutProgressConsumers_omitsRequestMeta() = runTest {
    whenever(mockMcpSession.callTool(any())) doReturn mono { textResult("ok") }

    // mcpTool's session manager has no progress consumers registered.
    val unused = mcpTool.run(testToolContext(functionCallId = "fc1"), emptyMap())

    val request = argumentCaptor<McpSchema.CallToolRequest>()
    verify(mockMcpSession).callTool(request.capture())
    assertNull(request.firstValue.meta())
  }

  @Test
  fun mcpSchemaConverter_convertsMcpSchemaToolToAdkFunctionDeclaration() {
    val mcpInputSchema: Map<String, Any> =
      mapOf(
        "type" to "object",
        "properties" to
          mapOf("param1" to mapOf("type" to "string", "description" to "param1 description")),
        "required" to listOf("param1"),
        "additionalProperties" to false,
      )
    val mcpOutputSchema =
      mapOf(
        "type" to "object",
        "properties" to
          mapOf("result" to mapOf("type" to "integer", "description" to "result description")),
      )

    val mcpToolSchema =
      McpSchema.Tool.builder("myTool", mcpInputSchema)
        .description("my tool description")
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
      McpSchema.Tool.builder(
          "malformedTool",
          mapOf("type" to "invalid-type", "additionalProperties" to false),
        )
        .build()
    val tool = McpTool("malformedTool", "description", malformedMcpSchemaTool, mockSessionManager)
    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  @Test
  fun run_retriesOnSessionErrorAndSucceedsOnLastTry() = runTest {
    val responseContent = McpSchema.TextContent.builder("test result").build()
    val invokeResponse = McpSchema.CallToolResult.builder().content(listOf(responseContent)).build()

    // The initial (pooled) session always fails; each failure re-fetches from the manager (passing
    // the failed session as `stale`), which yields a recovering session that fails twice more
    // before finally succeeding.
    val mockRecoveringSession = mock<McpAsyncClient>()
    whenever(mockRecoveringSession.callTool(any()))
      .thenThrow(RuntimeException("new session fail 1"))
      .thenThrow(RuntimeException("new session fail 2"))
      .thenReturn(mono { invokeResponse })
    whenever(mockMcpSession.callTool(any())).thenThrow(RuntimeException("old session fail"))

    // First fetch (stale=null) returns the failing session; subsequent stale-driven fetches return
    // the recovering one.
    val mockSessionManager =
      mock<SessionManager> {
        onBlocking { getSession(any(), anyOrNull()) } doReturnConsecutively
          listOf(mockMcpSession, mockRecoveringSession)
      }

    val mcpToolWithRetry = McpTool("testTool", "description", mcpSchemaTool, mockSessionManager)

    val result = mcpToolWithRetry.run(toolContext, emptyMap())
    assertSingleTextResult(result, "test result")
    // Four call attempts (initial + 3 retries) ⇒ four session fetches through the manager.
    verifyBlocking(mockSessionManager, times(4)) { getSession(any(), anyOrNull()) }
  }

  /** A minimal successful tool result carrying a single text content. */
  private fun textResult(text: String): McpSchema.CallToolResult =
    McpSchema.CallToolResult.builder().addTextContent(text).build()

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
