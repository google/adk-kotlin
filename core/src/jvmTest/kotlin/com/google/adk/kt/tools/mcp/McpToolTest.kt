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
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class McpToolTest {
  private val mcpSchemaTool = McpSchema.Tool.builder().name("testTool").build()
  private val mcpTool = newTool(mcpSchemaTool)

  @Test
  fun annotations_returnsAnnotations() {
    val annotations = McpSchema.ToolAnnotations("title", null, null, null, null, null)
    val mcpSchemaToolWithAnnotations =
      McpSchema.Tool.builder().name("testTool").annotations(annotations).build()
    val tool = newTool(mcpSchemaToolWithAnnotations)
    assertEquals(annotations, tool.annotations)
  }

  @Test
  fun meta_returnsMeta() {
    val meta = mapOf("key" to "value")
    val mcpSchemaToolWithMeta = McpSchema.Tool.builder().name("testTool").meta(meta).build()
    val tool = newTool(mcpSchemaToolWithMeta)
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
    val badSchema =
      McpSchema.JsonSchema(
        "object",
        mapOf("x" to mapOf("type" to "nonsense")),
        null,
        null,
        null,
        null,
      )
    val tool =
      newTool(McpSchema.Tool.builder().name("badTool").inputSchema(badSchema).build())

    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  @Test
  fun run_delegatesToTheSharedInvocation() = runTest {
    val tool =
      newTool(mcpSchemaTool) { _, arguments ->
        mapOf("echoed" to arguments)
      }

    assertEquals(
      mapOf("echoed" to mapOf("message" to "hello")),
      tool.run(toolContext, mapOf("message" to "hello")),
    )
  }

  @Test
  fun run_preservesTheUnderlyingExecutionFailure() = runTest {
    val tool =
      newTool(mcpSchemaTool) { _, _ ->
        throw McpToolsetCoreException("call failed", IllegalStateException("transport failed"))
      }

    val error = assertFailsWith<IllegalStateException> { tool.run(toolContext, emptyMap()) }

    assertEquals("transport failed", error.message)
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
    val tool = newTool(malformedMcpSchemaTool)
    assertFailsWith<McpToolException.McpToolDeclarationException> { tool.declaration() }
  }

  private fun newTool(
    schemaTool: McpSchema.Tool,
    invocation: McpToolInvocation = McpToolInvocation { _, _ -> emptyMap<String, Any?>() },
  ) = McpTool(schemaTool.name(), schemaTool.description().orEmpty(), schemaTool, invocation)

  private val toolContext = testToolContext()
}
