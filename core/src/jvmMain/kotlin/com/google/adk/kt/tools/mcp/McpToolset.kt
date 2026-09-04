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
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool as McpSchemaTool
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

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
 *           serverParameters =
 *             ServerParameters.builder("npx")
 *               .args("-y", "@modelcontextprotocol/server-filesystem")
 *               .build()
 *         ),
 *       toolFilter = ToolFilter.allowList("read_file", "list_directory"),
 *     )
 *     .toToolset()
 * ```
 *
 * The constructor is `internal`; user code should use [McpToolsetConfig.toToolset] instead.
 */
class McpToolset
internal constructor(
  mcpSessionManager: SessionManager,
  toolFilter: ToolFilter? = null,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  useMcpResources: Boolean = false,
  maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
) : Toolset {

  /** Shared JVM/Android discovery, execution, and resource path. */
  private val sharedCore =
    McpToolsetCore(
      sessionManager = JvmMcpClientSessionManager(mcpSessionManager),
      toolFilter = toolFilter,
      headerProvider = headerProvider,
      useMcpResources = useMcpResources,
      maxMcpResourceLength = maxMcpResourceLength,
      onResourcesUnsupported = {
        logger.warn {
          "useMcpResources is enabled, but the MCP server did not report the \"resources\" " +
            "capability, so list_mcp_resources, load_mcp_resource, list_mcp_resource_templates " +
            "are not exposed to the agent."
        }
      },
      toolFactory = McpToolFactory { definition, invocation ->
        val tool =
          definition.platformTool as? McpSchemaTool
            ?: error("JVM MCP tool definition is missing its Java SDK tool.")
        McpTool(
          name = definition.name,
          description = definition.description,
          mcpSchemaTool = tool,
          invocation = invocation,
        )
      },
    )

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    try {
      sharedCore.getTools(readonlyContext)
    } catch (error: IllegalArgumentException) {
      // This has historically been part of the JVM loading error contract.
      throw McpToolLoadingException("Invalid argument encountered during tool loading.", error)
    } catch (error: McpToolsetCoreException) {
      // Preserve the established JVM failure type while the mechanics live in shared code.
      throw McpToolLoadingException(LOAD_TOOLS_FAILURE_MESSAGE, error.cause ?: error)
    }

  override fun close() {
    sharedCore.close()
  }

  companion object {
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
   * @property toolFilter Optional filter selecting which of the tools advertised by the server are
   *   exposed to the agent. Use [ToolFilter.AllowList] (or the [ToolFilter.allowList] helper) to
   *   keep tools by name, or [ToolFilter.Predicate] for context-aware selection that can consult
   *   the [ReadonlyContext]. When `null`, all tools advertised by the server are exposed. The
   *   resource tools added by [useMcpResources] are ADK's own and are not filtered.
   * @property useMcpResources When `true`, resource-related tools (`list_mcp_resources`,
   *   `list_mcp_resource_templates`, `load_mcp_resource`) are added to the toolset, granting the
   *   agent access to MCP resources exposed by the server. They are added only if the server
   *   reports the `resources` capability during the handshake; against a server that does not, they
   *   are omitted and a warning is logged, because the MCP client rejects the requests those tools
   *   would make. [toolFilter] does not apply to them: it selects among the tools the server
   *   advertises, so enabling this flag and filtering the server's tools are independent choices.
   *   Defaults to `false`.
   * @property maxMcpResourceLength Maximum length, in characters, of a single resource payload
   *   returned by `load_mcp_resource`. Longer payloads are truncated.
   */
  data class McpToolsetConfig(
    val stdioConnectionParams: McpConnectionParameters.Stdio? = null,
    val sseConnectionParams: McpConnectionParameters.Sse? = null,
    val streamableHttpConnectionParams: McpConnectionParameters.StreamableHttp? = null,
    val toolFilter: ToolFilter? = null,
    val useMcpResources: Boolean = false,
    val maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
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
    @JvmOverloads
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

      return McpToolset(
        McpSessionManager(connectionParams, progressConsumers = progressConsumers),
        toolFilter,
        headerProvider,
        useMcpResources,
        maxMcpResourceLength,
      )
    }

    /** Creates a McpToolset instance from the configuration with a specific SessionManager. */
    internal fun toToolset(
      sessionManager: SessionManager,
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
    ): McpToolset =
      McpToolset(sessionManager, toolFilter, headerProvider, useMcpResources, maxMcpResourceLength)

    /**
     * Fluent builder for [McpToolsetConfig], provided primarily for Java callers. Any property left
     * unset falls back to the same default as the constructor.
     */
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var stdioConnectionParams: McpConnectionParameters.Stdio? = null
      private var sseConnectionParams: McpConnectionParameters.Sse? = null
      private var streamableHttpConnectionParams: McpConnectionParameters.StreamableHttp? = null
      private var toolFilter: ToolFilter? = null
      private var useMcpResources: Boolean = false
      private var maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH

      fun stdioConnectionParams(params: McpConnectionParameters.Stdio?): Builder = apply {
        this.stdioConnectionParams = params
      }

      fun sseConnectionParams(params: McpConnectionParameters.Sse?): Builder = apply {
        this.sseConnectionParams = params
      }

      fun streamableHttpConnectionParams(params: McpConnectionParameters.StreamableHttp?): Builder =
        apply {
          this.streamableHttpConnectionParams = params
        }

      fun toolFilter(toolFilter: ToolFilter?): Builder = apply { this.toolFilter = toolFilter }

      fun useMcpResources(useMcpResources: Boolean): Builder = apply {
        this.useMcpResources = useMcpResources
      }

      fun maxMcpResourceLength(maxMcpResourceLength: Int): Builder = apply {
        this.maxMcpResourceLength = maxMcpResourceLength
      }

      fun build(): McpToolsetConfig =
        McpToolsetConfig(
          stdioConnectionParams = stdioConnectionParams,
          sseConnectionParams = sseConnectionParams,
          streamableHttpConnectionParams = streamableHttpConnectionParams,
          toolFilter = toolFilter,
          useMcpResources = useMcpResources,
          maxMcpResourceLength = maxMcpResourceLength,
        )
    }

    companion object {
      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }
}
