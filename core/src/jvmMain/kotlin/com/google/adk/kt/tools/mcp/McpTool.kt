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

import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkFunctionDeclaration
import com.google.adk.kt.tools.mcp.McpToolException.McpToolDeclarationException
import com.google.adk.kt.types.FunctionDeclaration
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool as McpSchemaTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull

/**
 * Turns an MCP Tool into an ADK [BaseTool].
 *
 * The tool holds no session of its own: it fetches the shared session from [mcpSessionManager] on
 * each call and, on failure, asks the manager to reinitialize it. Because the manager owns the
 * session pool, a reinit is seen by every tool sharing the session and the toolset can close them
 * all via [SessionManager.close].
 */
class McpTool
internal constructor(
  name: String,
  description: String,
  private val mcpSchemaTool: McpSchemaTool,
  private val mcpSessionManager: SessionManager,
  private val headers: Map<String, String> = emptyMap(),
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

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val request = McpSchema.CallToolRequest(name, args, requestMeta(context))
    val callResult =
      try {
        retrySessionCall { callTool(request).awaitSingleOrNull() }
      } catch (e: McpError) {
        // Returned, not thrown: a refused call is the model's to correct.
        logger.warn { "MCP server rejected a tool call with code ${e.jsonRpcError?.code()}." }
        return mapOf("error" to "MCP tool execution failed: ${e.message}")
      }

    return callResult?.toJsonNativeMap()
      ?: mapOf("error" to "MCP framework error: CallToolResult was null")
  }

  /**
   * Builds the request's `_meta`, or `null` to leave `_meta` off the request entirely.
   *
   * Progress reporting is opt-in by the client: a server may only emit progress notifications that
   * reference a progress token supplied on the originating request, so without one a
   * spec-conformant server stays silent and the progress consumers registered on the session are
   * never called. The token is attached only when a consumer is actually listening, so servers are
   * not asked to produce progress that nothing reads.
   *
   * The token is the [ToolContext.functionCallId] of the model function call being served, which
   * lets a consumer attribute a notification back to that call; a call made outside an agent loop
   * has no id and gets a fresh one instead. Python ADK uses the JSON-RPC request id, which the Java
   * MCP SDK does not expose to callers.
   *
   * Built as one map rather than through `CallToolRequest.Builder.progressToken`, which mutates the
   * builder's `meta` in place and therefore either drops entries or throws when combined with
   * `Builder.meta`, depending on call order. Any further `_meta` entries belong in this map.
   */
  private fun requestMeta(context: ToolContext): Map<String, Any>? =
    if (mcpSessionManager.hasProgressConsumers) {
      mapOf(PROGRESS_TOKEN_KEY to (context.functionCallId ?: Uuid.random()))
    } else {
      null
    }

  private suspend fun <T> retrySessionCall(
    times: Int = 4,
    delayMs: Long = 100,
    block: suspend McpAsyncClient.() -> T,
  ): T {
    var session: McpAsyncClient? = null
    for (i in 1 until times) {
      // First pass: stale=null (plain fetch). Later passes: name the failed session so the manager
      // replaces it in place, shared by every tool on that session.
      session = mcpSessionManager.getSession(headers, stale = session)
      try {
        return session.block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: McpError) {
        // A server that answered rejected the request, so retrying repeats an identical round
        // trip -- and each pass evicts the session, which on stdio respawns the server process.
        throw e
      } catch (e: Exception) {
        delay(delayMs)
        logger.warn(e) { "Retrying callTool due to: ${e.message}" }
      }
    }
    return mcpSessionManager.getSession(headers, stale = session).block()
  }

  internal val annotations: McpSchema.ToolAnnotations?
    get() = mcpSchemaTool.annotations()

  internal val meta: Map<String, Any>?
    get() = mcpSchemaTool.meta()

  companion object {
    /** Key under which the MCP spec carries the progress token in a request's `_meta`. */
    private const val PROGRESS_TOKEN_KEY = "progressToken"

    private val logger = LoggerFactory.getLogger(McpTool::class)
  }
}

/**
 * The MCP SDK's own wire JSON mapper, reused to render SDK results in their canonical JSON shape.
 */
private val jsonMapper = McpJsonDefaults.getMapper()

/**
 * Converts the foreign MCP SDK [McpSchema.CallToolResult] into a JSON-native map (only [Map],
 * [List], String, number, Boolean, or null). ADK wraps any non-[Map] tool result as `{"result":
 * <value>}`; left as the raw SDK object that payload is opaque to the model and, because it is not
 * JSON-native, throws when a serializing [com.google.adk.kt.sessions.SessionService] persists the
 * event.
 *
 * Mirrors Python ADK's `CallToolResult.model_dump(exclude_none=True, mode="json")`: it goes through
 * the SDK's own mapper, so polymorphic `content` entries keep their `type` discriminator and absent
 * fields are dropped, matching the result's canonical on-the-wire JSON.
 */
private fun McpSchema.CallToolResult.toJsonNativeMap(): Map<String, Any?> {
  @Suppress("UNCHECKED_CAST")
  return jsonMapper.convertValue(this, Map::class.java) as Map<String, Any?>
}
