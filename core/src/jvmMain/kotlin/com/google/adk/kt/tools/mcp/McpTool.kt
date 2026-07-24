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

import com.google.adk.kt.logging.Logger
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkFunctionDeclaration
import com.google.adk.kt.tools.mcp.McpToolException.McpToolDeclarationException
import com.google.adk.kt.types.FunctionDeclaration
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool as McpSchemaTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Initializes an MCP tool.
 *
 * This wraps an MCP Tool interface and an active MCP Session. It invokes the MCP Tool through
 * executing the tool from remote MCP Session.
 */
class McpTool
internal constructor(
  name: String,
  description: String,
  private val mcpSchemaTool: McpSchemaTool,
  @Volatile private var mcpSession: McpAsyncClient,
  private val mcpSessionManager: SessionManager,
) : BaseTool(name, description) {
  private val reinitializeMutex = Mutex()

  override fun declaration(): FunctionDeclaration? {
    try {
      return mcpSchemaTool.toAdkFunctionDeclaration()
    } catch (e: RuntimeException) {
      throw McpToolDeclarationException(
        "MCP tool:$name failed to get declaration, inputSchema:${mcpSchemaTool.inputSchema()}. outputSchema: ${mcpSchemaTool.outputSchema()}",
        e,
      )
    }
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    val request = McpSchema.CallToolRequest(name, args)
    val callResult = retrySessionCall { mcpSession.callTool(request).awaitSingleOrNull() }

    return callResult?.toJsonNativeMap()
      ?: mapOf("error" to "MCP framework error: CallToolResult was null")
  }

  private suspend fun <T> retrySessionCall(
    times: Int = 4,
    delayMs: Long = 100,
    block: suspend () -> T,
  ): T {
    for (i in 1 until times) {
      try {
        return block()
      } catch (e: Exception) {
        if (e is CancellationException) {
          throw e
        }
        delay(delayMs)
        logger.warn(e) { "Retrying callTool due to: ${e.message}" }
        reinitializeSession()
      }
    }
    return block()
  }

  val annotations: McpSchema.ToolAnnotations?
    get() = mcpSchemaTool.annotations()

  val meta: Map<String, Any>?
    get() = mcpSchemaTool.meta()

  val mcpSessionClient: McpAsyncClient
    get() = mcpSession

  private suspend fun reinitializeSession() {
    val currentSession = this.mcpSession
    reinitializeMutex.withLock {
      // Check if the session has already been reinitialized by another coroutine
      // while we were waiting for the lock.
      if (this.mcpSession !== currentSession) {
        logger.debug { "Session already reinitialized by another thread." }
        return
      }

      val client = this.mcpSessionManager.createAsyncSession()
      try {
        val initResult = client.initialize().awaitSingle()
        logger.debug { "Initialize McpAsyncClient Result: $initResult" }

        // Close the old session BEFORE replacing it
        currentSession.closeQuietly(logger, "Failed to close old McpAsyncClient session: {}")

        this.mcpSession = client
      } catch (e: Exception) {
        logger.error(e) { "Initialize McpAsyncClient Failed: ${e.message}" }
        // Close the new client if initialization failed
        client.closeQuietly(
          logger,
          "Failed to close new McpAsyncClient after initialization failure: {}",
        )
        throw e
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(McpTool::class)

    private fun McpAsyncClient.closeQuietly(logger: Logger, message: String) {
      try {
        close()
      } catch (e: Exception) {
        logger.warn(e) { message.replace("{}", e.message ?: "null") }
      }
    }
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
