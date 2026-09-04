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

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkFunctionDeclaration
import com.google.adk.kt.tools.mcp.McpToolException.McpToolDeclarationException
import com.google.adk.kt.types.FunctionDeclaration
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool as McpSchemaTool

/**
 * Turns an MCP Tool into an ADK [BaseTool].
 *
 * Its declaration retains the Java MCP SDK schema while execution is delegated to the shared
 * JVM/Android MCP runtime through [invocation].
 */
class McpTool
internal constructor(
  name: String,
  description: String,
  private val mcpSchemaTool: McpSchemaTool,
  private val invocation: McpToolInvocation,
) : BaseTool(name, description) {

  /**
   * The converted declaration, built once.
   *
   * [declaration] is called for every tool on every model request, and converting a schema is not
   * free: it walks each property and resolves each `$ref`. The result cannot change, because
   * [mcpSchemaTool] is an immutable snapshot and a server that changes its tools yields new
   * [McpTool] instances from `McpToolset.loadTools`. Converting once also means a schema that warns
   * on the way through -- one truncated past the depth limit, say -- says so once rather than on
   * every request.
   *
   * A failed conversion still throws on every call: `lazy` leaves the value uninitialized when the
   * initializer throws, so the next access retries and rethrows.
   */
  private val convertedDeclaration: FunctionDeclaration by lazy {
    try {
      mcpSchemaTool.toAdkFunctionDeclaration()
    } catch (e: RuntimeException) {
      throw McpToolDeclarationException(
        "MCP tool:$name failed to get declaration, inputSchema:${mcpSchemaTool.inputSchema()}. outputSchema: ${mcpSchemaTool.outputSchema()}",
        e,
      )
    }
  }

  override fun declaration(): FunctionDeclaration? = convertedDeclaration

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    try {
      invocation.invoke(context, args)
    } catch (error: McpToolsetCoreException) {
      // The original JVM McpTool exposed the transport/SDK failure after its retry budget. Keep
      // that behavior; McpToolExecutionException remains for ADK-owned resource tools.
      throw error.cause ?: error
    }

  internal val annotations: McpSchema.ToolAnnotations?
    get() = mcpSchemaTool.annotations()

  internal val meta: Map<String, Any>?
    get() = mcpSchemaTool.meta()

}
