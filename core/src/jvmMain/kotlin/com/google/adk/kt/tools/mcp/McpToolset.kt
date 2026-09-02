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
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.isToolSelected
import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.spec.McpError
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
  private val mcpSessionManager: SessionManager,
  private val toolFilter: ToolFilter? = null,
  private val headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  private val useMcpResources: Boolean = false,
  private val maxMcpResourceLength: Int = DEFAULT_MAX_RESOURCE_LENGTH,
) : Toolset {

  private val toolsMutex = Mutex()
  private var cachedTools: LoadedTools? = null

  /** Guarded by [toolsMutex]; keeps the missing-capability warning to one per toolset. */
  private var warnedResourcesUnsupported = false

  /**
   * The server's tools, which [toolFilter] selects from, and the resource tools, which it skips.
   */
  private class LoadedTools(val serverTools: List<BaseTool>, val resourceTools: List<BaseTool>)

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    val loaded = initAndGetTools(readonlyContext)
    // Appended after filtering so toolFilter cannot silently undo useMcpResources, as in Python's
    // get_tools (mcp_toolset.py).
    return loaded.serverTools.filter { toolFilter.isToolSelected(it, readonlyContext) } +
      loaded.resourceTools
  }

  private suspend fun initAndGetTools(readonlyContext: ReadonlyContext?): LoadedTools =
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
  ): LoadedTools {
    val headers = readonlyContext?.let { headerProvider?.invoke(it) } ?: emptyMap()
    var session: McpAsyncClient? = null
    for (attempt in 1..times) {
      try {
        // First attempt fetches the pooled session (stale=null); later attempts pass the failed
        // session so the manager replaces it in place and the whole toolset recovers together.
        // getSession is inside the try because opening a session initializes it over the network,
        // which can fail (I/O, timeout) -- those failures must retry like loadTools failures do.
        session = mcpSessionManager.getSession(headers, stale = session)
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
  ): LoadedTools {
    val toolsResponse = session.listTools().awaitSingle()
    val serverTools: List<BaseTool> =
      toolsResponse.tools().map {
        McpTool(
          name = it.name(),
          description = it.description() ?: "",
          mcpSchemaTool = it,
          mcpSessionManager = mcpSessionManager,
          headers = headers,
        )
      }

    if (!useMcpResources) {
      return LoadedTools(serverTools, emptyList())
    }

    // Built before the capability check so the warning can name them without repeating literals.
    val resourceTools =
      listOf(
        ListMcpResourcesTool(this),
        LoadMcpResourceTool(this, maxMcpResourceLength),
        ListMcpResourceTemplatesTool(this),
      )

    // Withheld, not exposed-and-broken: the MCP client rejects every resource call against such a
    // server, and each one costs three attempts that evict the pooled session.
    if (session.serverCapabilities?.resources() != null) {
      return LoadedTools(serverTools, resourceTools)
    }

    // Once only: with a headerProvider the tools reload every turn, and the answer never changes.
    if (!warnedResourcesUnsupported) {
      warnedResourcesUnsupported = true
      // "did not report", not "does not advertise": null capabilities means the server never
      // answered, which is not the same as declining.
      logger.warn {
        "useMcpResources is enabled, but the MCP server did not report the \"resources\" " +
          "capability, so ${resourceTools.joinToString { it.name }} are not exposed to the agent."
      }
    }
    return LoadedTools(serverTools, emptyList())
  }

  /**
   * Lists a page of resources advertised by the MCP server.
   *
   * @param cursor An opaque pagination cursor from a previous [McpResourceListing.nextCursor], or
   *   `null` to fetch the first page.
   */
  internal suspend fun listResources(
    cursor: String? = null,
    readonlyContext: ReadonlyContext? = null,
  ): McpResourceListing {
    val result =
      withSession(readonlyContext) { session ->
        // Always use the cursor-based overload (McpSchema.FIRST_PAGE == null) so a single page is
        // fetched and the server's nextCursor is surfaced to the caller. The no-arg overload would
        // auto-follow every cursor and collapse the whole catalog into one response, defeating
        // page-by-page browsing (and blowing up context on servers with large resource catalogs).
        session.listResources(cursor).awaitSingle()
      }
    return McpResourceListing(
      resources = result.resources().map { it.toResourceInfo() },
      nextCursor = result.nextCursor(),
    )
  }

  /**
   * Fetches every resource advertised by the MCP server, following pagination cursors until the
   * server reports no further pages.
   *
   * This is the full-scan counterpart to the paged [listResources]: MCP keys resources by `uri`, so
   * resolving a [McpResourceInfo.name] means scanning the catalog, and names are not required to be
   * unique. It costs one round trip per page of the whole catalog.
   *
   * Kept `internal` like the rest of the resource surface for 1.0. Promoting any of it to `public`
   * later is purely additive.
   */
  internal suspend fun listAllResources(
    readonlyContext: ReadonlyContext? = null
  ): List<McpResourceInfo> {
    // Paged here rather than through the SDK's no-arg overload, which chains pages with expand()
    // under no page cap, no cycle detection and no aggregate deadline: a server returning a
    // constant nextCursor would loop until the process died, and that is reachable from a
    // model-chosen load_mcp_resource call. The cap converts that into a bounded, loud failure.
    val all = mutableListOf<McpResourceInfo>()
    var cursor: String? = null
    repeat(MAX_FULL_SCAN_PAGES) {
      val result =
        withSession(readonlyContext) { session -> session.listResources(cursor).awaitSingle() }
      all += result.resources().map { it.toResourceInfo() }
      cursor = result.nextCursor() ?: return all
    }
    // Truncating instead would silently corrupt name resolution: a missing page reads as
    // "no such resource", or hides a collision that should have been reported as ambiguous.
    throw McpToolException.McpToolExecutionException(
      "MCP server kept paginating resources/list past $MAX_FULL_SCAN_PAGES pages; giving up " +
        "rather than scanning forever."
    )
  }

  /**
   * Lists a page of resource templates advertised by the MCP server.
   *
   * @param cursor An opaque pagination cursor from a previous
   *   [McpResourceTemplateListing.nextCursor], or `null` to fetch the first page.
   */
  internal suspend fun listResourceTemplates(
    cursor: String? = null,
    readonlyContext: ReadonlyContext? = null,
  ): McpResourceTemplateListing {
    val result =
      withSession(readonlyContext) { session ->
        // Single-page cursor overload, mirroring [listResources]; see the rationale there.
        session.listResourceTemplates(cursor).awaitSingle()
      }
    return McpResourceTemplateListing(
      resourceTemplates = result.resourceTemplates().map { it.toResourceTemplateInfo() },
      nextCursor = result.nextCursor(),
    )
  }

  /** Fetches and returns the contents of the resource with the given [uri]. */
  internal suspend fun readResource(
    uri: String,
    readonlyContext: ReadonlyContext? = null,
  ): List<McpResourceContent> {
    val readResult =
      withSession(readonlyContext) { session ->
        session.readResource(McpSchema.ReadResourceRequest(uri)).awaitSingle()
      }
    return readResult.contents().map { it.toResourceContent() }
  }

  /**
   * Runs [block] against a pooled MCP session, retrying a failed call on a replaced session.
   *
   * Like [McpTool]'s retry (which allows one more attempt than the [DEFAULT_RETRY_TIMES] used
   * here), a session that failed is handed back as `stale` so the manager evicts and recreates it
   * in place, which every caller sharing that session benefits from. Without this a dead pooled
   * session is never replaced and every later resource call keeps failing.
   *
   * [IllegalArgumentException] is not retried, matching [handleLoadError]: the server rejected the
   * request itself, so the retries repeat an identical round trip and only delay the error. An
   * unknown resource uri is the common case, and a model guessing one in `load_mcp_resource` is
   * routine.
   *
   * A session is marked stale only after a failure that is not attributable to the request, so a
   * rejected request no longer evicts a healthy session out from under everyone sharing it. On
   * stdio that eviction kills and respawns the server child process.
   *
   * [SessionManager.getSession] is inside the `try` because opening a session initializes it over
   * the network, which can fail on its own and must retry like the call itself does.
   *
   * Only the round trip is retried; mapping the result into ADK types happens at the call site, so
   * a mapping bug is never mistaken for a transient failure.
   */
  private suspend fun <T> withSession(
    readonlyContext: ReadonlyContext?,
    block: suspend (McpAsyncClient) -> T,
  ): T {
    val headers = readonlyContext?.let { headerProvider?.invoke(it) } ?: emptyMap()
    var stale: McpAsyncClient? = null
    for (attempt in 1..DEFAULT_RETRY_TIMES) {
      var session: McpAsyncClient? = null
      try {
        session = mcpSessionManager.getSession(headers, stale = stale)
        return block(session)
      } catch (e: CancellationException) {
        throw e
      } catch (e: IllegalArgumentException) {
        throw e
      } catch (e: McpError) {
        // Same reasoning: a resource the server does not have is a rejected request, not a dead
        // session, so retrying repeats it and evicting punishes everyone sharing the session.
        if (e.jsonRpcError?.code() == McpSchema.ErrorCodes.RESOURCE_NOT_FOUND) throw e
        if (attempt == DEFAULT_RETRY_TIMES) throw e
        stale = session
        logger.warn(e) { "Retrying MCP resource call, attempt $attempt: ${e.message}" }
        delay(DEFAULT_RETRY_DELAY_MS)
      } catch (e: Exception) {
        if (attempt == DEFAULT_RETRY_TIMES) {
          throw e
        }
        // Null when getSession itself failed, which leaves nothing to evict.
        stale = session
        logger.warn(e) { "Retrying MCP resource call, attempt $attempt: ${e.message}" }
        delay(DEFAULT_RETRY_DELAY_MS)
      }
    }
    error("Exhausted retries without returning or throwing")
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
    mcpSessionManager.close()
    cachedTools = null
  }

  companion object {
    /** Bound on [listAllResources]; ample for a real catalog, fatal only for a looping server. */
    private const val MAX_FULL_SCAN_PAGES = 100

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
  }
}

// The SDK records are plain Jackson bindings with no non-null validation, so a server may omit
// any field. The non-null properties below default to "" rather than letting a Kotlin
// platform-type assignment throw NPE from inside the mapper.

private fun McpSchema.Resource.toResourceInfo(): McpResourceInfo =
  McpResourceInfo(
    name = name().orEmpty(),
    uri = uri().orEmpty(),
    title = title(),
    description = description(),
    mimeType = mimeType(),
    size = size(),
    annotations = annotations()?.toAnnotations(),
    meta = meta(),
  )

private fun McpSchema.ResourceTemplate.toResourceTemplateInfo(): McpResourceTemplateInfo =
  McpResourceTemplateInfo(
    name = name().orEmpty(),
    uriTemplate = uriTemplate().orEmpty(),
    title = title(),
    description = description(),
    mimeType = mimeType(),
    annotations = annotations()?.toAnnotations(),
    meta = meta(),
  )

private fun McpSchema.Annotations.toAnnotations(): McpAnnotations =
  McpAnnotations(
    // `audience` is optional in the schema: annotations may carry only a priority.
    audience = audience().orEmpty().map { McpRole(it.name.lowercase()) },
    priority = priority(),
    lastModified = lastModified(),
  )

// No else branch below: McpSchema.ResourceContents is a sealed interface permitting exactly the
// two subtypes handled here, so the compiler proves the `when` exhaustive. An SDK upgrade that
// adds a third subtype turns that proof into a compile error here, which is the signal we want.
private fun McpSchema.ResourceContents.toResourceContent(): McpResourceContent =
  when (this) {
    is McpSchema.TextResourceContents ->
      McpResourceContent.Text(
        uri = uri().orEmpty(),
        mimeType = mimeType(),
        text = text().orEmpty(),
        meta = meta(),
      )
    is McpSchema.BlobResourceContents ->
      McpResourceContent.Blob(
        uri = uri().orEmpty(),
        mimeType = mimeType(),
        blobBase64 = blob().orEmpty(),
        meta = meta(),
      )
  }
