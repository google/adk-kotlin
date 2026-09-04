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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.isToolSelected
import com.google.adk.kt.types.FunctionDeclaration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SDK-independent implementation of MCP tool discovery, filtering, caching, and tool execution.
 *
 * JVM and Android supply their own [McpClientSessionManager]; this class deliberately contains no
 * MCP SDK model, transport, or coroutine-adapter type. Optional resource tools use the same
 * session boundary when a server advertises the MCP resources capability.
 */
internal class McpToolsetCore(
  private val sessionManager: McpClientSessionManager,
  private val toolFilter: ToolFilter? = null,
  private val headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  private val useMcpResources: Boolean = false,
  private val maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
  private val onResourcesUnsupported: (() -> Unit)? = null,
  private val toolFactory: McpToolFactory? = null,
) : Toolset {
  private val toolsMutex = Mutex()
  private var cachedTools: LoadedTools? = null
  private var warnedResourcesUnsupported = false

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    val loaded =
      toolsMutex.withLock {
        if (headerProvider == null) {
          cachedTools ?: loadToolsWithRetries(null, emptyMap()).also { cachedTools = it }
        } else {
          // A null context must stay distinguishable from a provider that deliberately returns an
          // empty map: Android retains its current credential snapshot in the former case.
          val headers = readonlyContext?.let { context -> headerProvider(context) }
          loadToolsWithRetries(readonlyContext, headers)
        }
      }
    return loaded.serverTools.filter { toolFilter.isToolSelected(it, readonlyContext) } +
      loaded.resourceTools
  }

  private suspend fun loadToolsWithRetries(
    readonlyContext: ReadonlyContext?,
    initialHeaders: Map<String, String>?,
  ): LoadedTools {
    var headers = initialHeaders
    var stale: McpClientSession? = null
    repeat(LOAD_RETRY_COUNT) { attempt ->
      var active: McpClientSession? = null
      try {
        val session = sessionManager.getSession(headers, stale)
        active = session
        val serverTools = session.listTools().map { definition ->
          val invocation = McpToolInvocation { context, arguments ->
            callTool(definition.name, context, arguments, headers)
          }
          toolFactory?.create(definition, invocation) ?: McpToolCore(definition, invocation)
        }
        if (!useMcpResources) return LoadedTools(serverTools, emptyList())
        if (session !is McpResourceClientSession || !session.supportsResources) {
          if (!warnedResourcesUnsupported) {
            warnedResourcesUnsupported = true
            onResourcesUnsupported?.invoke()
              ?: logger.warn {
                "useMcpResources is enabled, but the MCP server did not report the \"resources\" " +
                  "capability, so resource tools are not exposed to the agent."
              }
          }
          return LoadedTools(serverTools, emptyList())
        }
        return LoadedTools(
          serverTools,
          listOf(
            ListMcpResourcesCoreTool(this),
            LoadMcpResourceCoreTool(this, maxMcpResourceLength),
            ListMcpResourceTemplatesCoreTool(this),
          ),
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: IllegalArgumentException) {
        // The server rejected the request itself; retrying cannot repair a dead session.
        throw error
      } catch (error: Exception) {
        if (attempt + 1 == LOAD_RETRY_COUNT) {
          throw McpToolsetCoreException("Unable to load MCP tools.", error)
        }
        logger.warn(error) { "Retrying MCP tool discovery, attempt ${attempt + 1}." }
        delay(RETRY_DELAY_MS)
        // A platform manager may replace this session only when it is still active, preserving a
        // connection a concurrent caller has already recreated.
        if (sessionManager.shouldRefreshHeaders(error) && readonlyContext != null) {
          headers = headerProvider?.invoke(readonlyContext) ?: headers
        }
        stale = active?.takeIf { sessionManager.shouldInvalidateSession(error) }
      }
    }
    error("Tool discovery retry loop completed without returning or throwing.")
  }

  override fun close() {
    cachedTools = null
    sessionManager.close()
  }

  internal suspend fun listResources(
    readonlyContext: ReadonlyContext?,
    cursor: String?,
  ): McpClientResourcePage = resourceCall(readonlyContext) { session -> session.listResources(cursor) }

  internal suspend fun listResourceTemplates(
    readonlyContext: ReadonlyContext?,
    cursor: String?,
  ): McpClientResourceTemplatePage =
    resourceCall(readonlyContext) { session -> session.listResourceTemplates(cursor) }

  internal suspend fun readResource(
    readonlyContext: ReadonlyContext?,
    uri: String,
  ): List<McpClientResourceContent> = resourceCall(readonlyContext) { session -> session.readResource(uri) }

  internal suspend fun listAllResources(readonlyContext: ReadonlyContext?): List<McpClientResource> {
    val resources = mutableListOf<McpClientResource>()
    var cursor: String? = null
    repeat(MAX_FULL_SCAN_PAGES) {
      val page = listResources(readonlyContext, cursor)
      resources += page.resources
      cursor = page.nextCursor ?: return resources
    }
    throw McpToolsetCoreException(
      "MCP server kept paginating resources/list past $MAX_FULL_SCAN_PAGES pages; giving up " +
        "rather than scanning forever."
    )
  }

  private suspend fun <T> resourceCall(
    readonlyContext: ReadonlyContext?,
    block: suspend (McpResourceClientSession) -> T,
  ): T {
    var headers = readonlyContext?.let { context -> headerProvider?.invoke(context) }
    var stale: McpClientSession? = null
    repeat(LOAD_RETRY_COUNT) { attempt ->
      var active: McpClientSession? = null
      try {
        val session = sessionManager.getSession(headers, stale)
        active = session
        val resourceSession = session as? McpResourceClientSession
          ?: throw McpToolsetCoreException("MCP server does not support resources.")
        return block(resourceSession)
      } catch (error: CancellationException) {
        throw error
      } catch (error: IllegalArgumentException) {
        // A malformed or unknown resource request is rejected by the server; preserving the JVM
        // contract avoids repeating an identical request and evicting a healthy session.
        throw error
      } catch (error: McpResourceNotFoundException) {
        throw error
      } catch (error: Exception) {
        if (attempt + 1 == LOAD_RETRY_COUNT) {
          throw McpToolsetCoreException("MCP resource request failed.", error)
        }
        logger.warn(error) { "Retrying MCP resource request, attempt ${attempt + 1}." }
        delay(RETRY_DELAY_MS)
        if (sessionManager.shouldRefreshHeaders(error) && readonlyContext != null) {
          headers = headerProvider?.invoke(readonlyContext) ?: headers
        }
        stale = active?.takeIf { sessionManager.shouldInvalidateSession(error) }
      }
    }
    error("Resource retry loop completed without returning or throwing.")
  }

  private companion object {
    const val LOAD_RETRY_COUNT = 3
    const val CALL_RETRY_COUNT = 4
    const val RETRY_DELAY_MS = 100L
    const val MAX_FULL_SCAN_PAGES = 100
    val logger = LoggerFactory.getLogger(McpToolsetCore::class)
  }

  private class LoadedTools(
    val serverTools: List<BaseTool>,
    val resourceTools: List<BaseTool>,
  )

  private suspend fun callTool(
    name: String,
    context: ToolContext,
    args: Map<String, Any?>,
    discoveryHeaders: Map<String, String>?,
  ): Any {
    var headers = headerProvider?.invoke(context.context) ?: discoveryHeaders
    var stale: McpClientSession? = null
    repeat(CALL_RETRY_COUNT) { attempt ->
      var active: McpClientSession? = null
      try {
        val session = sessionManager.getSession(headers, stale)
        active = session
        val options =
          if (sessionManager.hasProgressConsumers) {
            McpToolCallOptions(context.functionCallId ?: Uuid.random(), sessionManager.onProgress)
          } else {
            McpToolCallOptions()
          }
        return session.callTool(name, args, options)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (attempt + 1 == CALL_RETRY_COUNT) throw McpToolsetCoreException("Unable to call MCP tool $name.", error)
        logger.warn(error) { "Retrying MCP tool call \"$name\", attempt ${attempt + 1}." }
        delay(RETRY_DELAY_MS)
        if (sessionManager.shouldRefreshHeaders(error)) {
          headers = headerProvider?.invoke(context.context) ?: headers
        }
        stale = active?.takeIf { sessionManager.shouldInvalidateSession(error) }
      }
    }
    error("Tool call retry loop completed without returning or throwing.")
  }

  private class McpToolCore(
    definition: McpToolDefinition,
    private val invocation: McpToolInvocation,
  ) : BaseTool(definition.name, definition.description) {
    private val declaration =
      FunctionDeclaration(
        name = definition.name,
        description = definition.description,
        parameters = definition.inputSchema,
        response = definition.outputSchema,
      )

    override fun declaration(): FunctionDeclaration = declaration

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
      return invocation.invoke(context, args)
    }
  }
}

/** Failure raised by the shared implementation after its bounded retry budget is exhausted. */
internal class McpToolsetCoreException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
