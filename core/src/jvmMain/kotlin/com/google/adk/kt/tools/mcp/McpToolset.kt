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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connects to an MCP Server and exposes the server's MCP tools to an agent as ADK [BaseTool]s.
 *
 * `McpToolset` manages the lifecycle of the connection to a single MCP server and lazily fetches
 * the server's tool list on first use. The instance can then be passed directly to an `LlmAgent`'s
 * `toolsets`.
 *
 * Instances are created via [McpToolsetConfig.toToolset], for example:
 * ```
 * val toolset =
 *   McpToolset.McpToolsetConfig(
 *       stdioConnectionParams =
 *         McpConnectionParameters.Stdio(
 *           command = "npx",
 *           args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
 *         ),
 *       toolFilter = listOf("read_file", "list_directory"),
 *     )
 *     .toToolset()
 * ```
 *
 * The constructor is `internal`; user code should use [McpToolsetConfig.toToolset] instead.
 */
class McpToolset
internal constructor(
  private val mcpSessionManager: SessionManager,
  private val toolFilter: ((BaseTool) -> Boolean)? = null,
  private val headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  private val useMcpResources: Boolean = false,
  private val maxMcpResourceLength: Int = DEFAULT_MAX_RESOURCE_LENGTH,
) : Toolset {

  private val toolsMutex = Mutex()
  private var cachedTools: List<BaseTool>? = null

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    initAndGetTools(readonlyContext).filter { toolFilter?.invoke(it) ?: true }

  private suspend fun initAndGetTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    toolsMutex.withLock {
      if (headerProvider == null) {
        // Cache tools only if headers are static (headerProvider is null).
        cachedTools ?: initToolsWithRetries(readonlyContext).also { cachedTools = it }
      } else {
        // If headers are dynamic, always load tools.
        initToolsWithRetries(readonlyContext)
      }
    }

  private suspend fun initToolsWithRetries(
    readonlyContext: ReadonlyContext?,
    times: Int = DEFAULT_RETRY_TIMES,
    delayMs: Long = DEFAULT_RETRY_DELAY_MS,
  ): List<BaseTool> {
    val headers = readonlyContext?.let { headerProvider?.invoke(it) } ?: emptyMap()
    var session: McpAsyncClient? = null
    for (attempt in 1..times) {
      // First attempt fetches the pooled session (stale=null); later attempts pass the failed
      // session so the manager replaces it in place and the whole toolset recovers together.
      session = mcpSessionManager.getSession(headers, stale = session)
      try {
        return loadTools(session, headers)
      } catch (e: Exception) {
        handleLoadError(e, attempt)
        if (attempt == times) {
          throw McpToolLoadingException(LOAD_TOOLS_FAILURE_MESSAGE, e)
        }
        delay(delayMs)
      }
    }
    error("Exhausted retries without returning or throwing")
  }

  private suspend fun loadTools(
    session: McpAsyncClient,
    headers: Map<String, String>,
  ): List<BaseTool> {
    val toolsResponse = session.listTools().awaitSingle()
    val tools: MutableList<BaseTool> =
      toolsResponse
        .tools()
        .map {
          McpTool(
            name = it.name(),
            description = it.description() ?: "",
            mcpSchemaTool = it,
            mcpSessionManager = mcpSessionManager,
            headers = headers,
          )
        }
        .toMutableList()

    val capabilities = session.serverCapabilities

    if (useMcpResources && capabilities?.resources() != null) {
      tools.add(ListMcpResourcesTool(session))
      tools.add(LoadMcpResourceTool(this, maxMcpResourceLength))
      tools.add(ListMcpResourceTemplatesTool(session))
    }
    return tools
  }

  /** Returns a list of resource names available on the MCP server. */
  suspend fun listResources(readonlyContext: ReadonlyContext? = null): List<String> {
    val headers = readonlyContext?.let { headerProvider?.invoke(it) } ?: emptyMap()
    val session = mcpSessionManager.getSession(headers)
    val result = session.listResources().awaitSingle()
    return result.resources().map { it.name() }
  }

  /** Fetches and returns a list of contents of the resource with the given URI. */
  suspend fun readResource(uri: String, readonlyContext: ReadonlyContext? = null): Any {
    val headers = readonlyContext?.let { headerProvider?.invoke(it) } ?: emptyMap()
    val session = mcpSessionManager.getSession(headers)
    val readResult = session.readResource(McpSchema.ReadResourceRequest(uri)).awaitSingle()
    return readResult.contents()
  }

  private fun handleLoadError(e: Exception, attempt: Int) {
    when (e) {
      is CancellationException -> throw e
      is IllegalArgumentException -> {
        logger.error(e) { "Invalid argument encountered during tool loading." }
        throw McpToolLoadingException("Invalid argument encountered during tool loading.", e)
      }
    }

    logger.error(e) { "Unexpected error during tool loading, retry attempt $attempt" }
  }

  override fun close() {
    mcpSessionManager.closeAll()
    cachedTools = null
  }

  companion object {
    private const val DEFAULT_RETRY_TIMES = 3
    private const val DEFAULT_RETRY_DELAY_MS = 100L
    private const val DEFAULT_MAX_RESOURCE_LENGTH = 10000
    private const val LOAD_TOOLS_FAILURE_MESSAGE = "Failed to load tools."

    private val logger = LoggerFactory.getLogger(McpToolset::class)
  }

  /**
   * Configuration for an [McpToolset], used to construct one via [toToolset].
   *
   * Exactly one of [stdioConnectionParams], [sseConnectionParams], or
   * [streamableHttpConnectionParams] must be set; [toToolset] throws if zero or more than one are
   * provided.
   *
   * @property stdioConnectionParams Connection parameters for a local MCP server reached over stdio
   *   (e.g. one launched via `npx` or `python3`).
   * @property sseConnectionParams Connection parameters for an MCP server reached over SSE.
   * @property streamableHttpConnectionParams Connection parameters for an MCP server reached over
   *   the Streamable HTTP transport.
   * @property toolFilter Optional allowlist of tool names; when set, only tools whose name appears
   *   in the list will be exposed to the agent. When `null`, all tools advertised by the server are
   *   exposed.
   * @property useMcpResources When `true`, resource-related tools (`list_mcp_resources`,
   *   `list_mcp_resource_templates`, `load_mcp_resource`) are added to the toolset, granting the
   *   agent access to MCP resources exposed by the server. Defaults to `false`.
   * @property maxMcpResourceLength Maximum length, in characters, of a single resource payload
   *   returned by `load_mcp_resource`. Longer payloads are truncated.
   */
  data class McpToolsetConfig(
    val stdioConnectionParams: McpConnectionParameters.Stdio? = null,
    val sseConnectionParams: McpConnectionParameters.Sse? = null,
    val streamableHttpConnectionParams: McpConnectionParameters.StreamableHttp? = null,
    val toolFilter: List<String>? = null,
    val useMcpResources: Boolean = false,
    val maxMcpResourceLength: Int = DEFAULT_MAX_RESOURCE_LENGTH,
  ) {
    /**
     * Creates an [McpToolset] from this configuration.
     *
     * @param headerProvider Optional suspending callback that, given a [ReadonlyContext], returns a
     *   map of HTTP headers to attach to each MCP session. Because it is a `suspend` function,
     *   headers or tokens can be minted asynchronously at request time (e.g. fetching an OAuth
     *   bearer token) without blocking a thread. When non-`null`, sessions are not cached across
     *   invocations so that headers can vary per-context (e.g. per-user authentication). When
     *   `null`, a single session is opened lazily and reused.
     * @param progressConsumers Callbacks invoked for every
     *   [McpSchema.ProgressNotification][io.modelcontextprotocol.spec.McpSchema.ProgressNotification]
     *   received from the MCP server during long-running tool executions.
     * @throws IllegalArgumentException if zero or more than one of [stdioConnectionParams],
     *   [sseConnectionParams], and [streamableHttpConnectionParams] is set.
     */
    fun toToolset(
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
      progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
    ): McpToolset {
      val params =
        listOfNotNull(stdioConnectionParams, sseConnectionParams, streamableHttpConnectionParams)

      require(params.size == 1) {
        "Exactly one of stdioConnectionParams, sseConnectionParams or streamableHttpConnectionParams must be set"
      }

      val connectionParams = params.first()

      val filter: ((BaseTool) -> Boolean)? = toolFilter?.let { filterList ->
        { tool: BaseTool -> tool.name in filterList }
      }

      return McpToolset(
        McpSessionManager(connectionParams, progressConsumers = progressConsumers),
        filter,
        headerProvider,
        useMcpResources,
        maxMcpResourceLength,
      )
    }

    /** Creates a McpToolset instance from the configuration with a specific SessionManager. */
    internal fun toToolset(
      sessionManager: SessionManager,
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
    ): McpToolset {
      val filter: ((BaseTool) -> Boolean)? = toolFilter?.let { filterList ->
        { tool: BaseTool -> tool.name in filterList }
      }

      return McpToolset(
        sessionManager,
        filter,
        headerProvider,
        useMcpResources,
        maxMcpResourceLength,
      )
    }
  }
}
