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
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.toAny
import com.google.adk.kt.types.toJsonElement
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.ListResourceTemplatesResult
import io.modelcontextprotocol.kotlin.sdk.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.McpError
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Android-only transport timeouts used by the internal MCP implementation. */
internal data class AndroidMcpTimeouts(
  val connect: Duration = 5.seconds,
  val request: Duration = 5.minutes,
  val socket: Duration = 5.minutes,
) {
  init {
    require(connect.isPositive()) { "MCP connect timeout must be positive." }
    require(request.isPositive()) { "MCP request timeout must be positive." }
    require(socket.isPositive()) { "MCP socket timeout must be positive." }
  }
}

/**
 * Android implementation behind the common [McpToolsetConfig] public API.
 *
 * It owns one lazily connected Kotlin MCP SDK client and reuses it for discovery, tool calls, and
 * optional resource access. Dynamic headers are applied to every request on that session, for the
 * current Android user. This implementation intentionally supports remote Streamable HTTP only;
 * stdio, legacy SSE, and OAuth flows remain application concerns.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class AndroidMcpToolset private constructor(
  private val serverUrl: String,
  private val headers: Map<String, String>,
  private val toolFilter: ToolFilter?,
  private val timeouts: AndroidMcpTimeouts,
  private val useMcpResources: Boolean,
  private val maxMcpResourceLength: Int,
  private val progressConsumers: List<(McpProgressUpdate) -> Unit>,
  private val headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  private val httpClientFactory: () -> HttpClient,
) : Toolset {
  /**
   * Creates an Android MCP toolset.
   *
   * @param serverUrl HTTP(S) URL of the remote Streamable HTTP MCP endpoint.
   * @param headers Static HTTP headers applied to every transport request. Values returned by
   *   [headerProvider] override headers with the same name.
   * @param toolFilter Optional selector for tools advertised by the MCP server. It does not filter
   *   ADK-owned resource tools enabled by [useMcpResources].
   * @param timeouts Connection, request, and socket timeouts for the transport.
   * @param useMcpResources Whether to expose ADK's `list_mcp_resources`,
   *   `load_mcp_resource`, and `list_mcp_resource_templates` tools when the server reports the
   *   MCP `resources` capability.
   * @param maxMcpResourceLength Maximum number of text characters returned per resource content
   *   item before a truncation marker is added.
   * @param progressConsumers Callbacks for MCP progress notifications emitted while a tool call is
   *   in flight. Supplying at least one consumer also asks the server for progress notifications.
   * @param headerProvider Optional suspending callback that returns request headers for the current
   *   ADK context. Use it to mint or refresh credentials for the current Android user. Returned
   *   headers are applied to each request on the shared MCP session. OAuth UI, token storage,
   *   refresh policy, and account switching remain app concerns; close this toolset when the
   *   configured account changes.
   */
  constructor(
    serverUrl: String,
    headers: Map<String, String> = emptyMap(),
    toolFilter: ToolFilter? = null,
    timeouts: AndroidMcpTimeouts = AndroidMcpTimeouts(),
    useMcpResources: Boolean = false,
    maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
    progressConsumers: List<(McpProgressUpdate) -> Unit> = emptyList(),
    headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  ) : this(serverUrl, headers, toolFilter, timeouts, useMcpResources, maxMcpResourceLength, progressConsumers, headerProvider, {
    HttpClient(OkHttp) {
      install(SSE)
      install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connect.inWholeMilliseconds
        requestTimeoutMillis = timeouts.request.inWholeMilliseconds
        socketTimeoutMillis = timeouts.socket.inWholeMilliseconds
      }
    }
  })

  companion object {
    internal fun forTesting(
      serverUrl: String,
      headers: Map<String, String> = emptyMap(),
      toolFilter: ToolFilter? = null,
      timeouts: AndroidMcpTimeouts = AndroidMcpTimeouts(),
      useMcpResources: Boolean = false,
      maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
      progressConsumers: List<(McpProgressUpdate) -> Unit> = emptyList(),
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
      httpClientFactory: () -> HttpClient,
    ): AndroidMcpToolset =
      AndroidMcpToolset(
        serverUrl,
        headers,
        toolFilter,
        timeouts,
        useMcpResources,
        maxMcpResourceLength,
        progressConsumers,
        headerProvider,
        httpClientFactory,
      )
  }
  private val connectionMutex = Mutex()
  private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val connection = AtomicReference<Connection?>(null)
  // The Streamable HTTP transport invokes its request builder for every POST, SSE GET, and DELETE.
  // A volatile immutable snapshot lets a refreshed credential take effect on the next HTTP request
  // without tearing down the current MCP session. AndroidMcpToolset is intentionally scoped to one
  // current app account; account switches must close this toolset and create another one.
  //
  // This is deliberately different from JVM's SessionManager, which keys a pool by headers to
  // support multiple independent contexts in one long-running process. Do not turn this into an
  // Android header-keyed pool without also defining retirement and in-flight-call lifecycle rules.
  @Volatile private var currentRequestHeaders: Map<String, String> = headers.toMap()
  @Volatile private var closed = false

  private val androidToolFactory =
    McpToolFactory { definition, invocation ->
      val tool =
        definition.platformTool as? Tool
          ?: error("Android MCP tool definition is missing its Kotlin SDK tool.")
      AndroidMcpTool(tool = tool, invocation = invocation)
    }

  // This is the platform boundary below the shared McpToolsetCore. The core resolves the optional
  // headerProvider for each ADK context and passes the result to getSession on every platform.
  // Android applies that result to subsequent HTTP requests on one session; JVM uses it as a
  // session-pool key. Keeping the difference here preserves one shared discovery/call/resource
  // flow while retaining Android's single-current-user lifecycle.
  private val sessionManager = AndroidMcpClientSessionManager()

  private val sharedCore =
    McpToolsetCore(
      sessionManager = sessionManager,
      toolFilter = toolFilter,
      headerProvider = headerProvider,
      useMcpResources = useMcpResources,
      maxMcpResourceLength = maxMcpResourceLength,
      toolFactory = androidToolFactory,
    )

  init {
    require(maxMcpResourceLength > 0) { "MCP resource length limit must be positive." }
  }

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    check(!closed) { "AndroidMcpToolset is closed." }
    try {
      return sharedCore.getTools(readonlyContext)
    } catch (error: McpToolsetCoreException) {
      throw McpToolException.McpToolLoadingException(
        "Unable to initialize MCP server at $serverUrl",
        error.cause ?: error,
      )
    }
  }

  /** Android counterpart to [JvmMcpClientSessionManager]. */
  private inner class AndroidMcpClientSessionManager : McpClientSessionManager {
    override val hasProgressConsumers: Boolean
      get() = progressConsumers.isNotEmpty()

    override val onProgress: ((McpProgressUpdate) -> Unit)?
      get() =
        progressConsumers.takeIf { it.isNotEmpty() }?.let { consumers ->
          { update -> consumers.forEach { consumer -> consumer(update) } }
        }

    override suspend fun getSession(
      headers: Map<String, String>?,
      stale: McpClientSession?,
    ): McpClientSession {
      // `getTools(null)` has no ADK context from which a dynamic credential can be resolved.
      // Retain the last good snapshot instead of silently stripping Authorization from the shared
      // Android session. An explicit empty map from a provider still intentionally clears it.
      headers?.let(::updateRequestHeaders)
      (stale as? Connection)?.let { staleConnection -> invalidateConnection(staleConnection) }
      return activeConnection()
    }

    override fun shouldInvalidateSession(error: Throwable): Boolean = error.isHttp401OrSession404()

    override fun shouldRefreshHeaders(error: Throwable): Boolean = error.isHttpUnauthorized()

    override fun close() = closeConnection()
  }

  private suspend fun activeConnection(): Connection = connectionMutex.withLock {
      check(!closed) { "AndroidMcpToolset is closed." }
      connection.load() ?: createConnection().also { newConnection ->
        if (closed) {
          closeAfterToolsetClose(newConnection)
          throw IllegalStateException("AndroidMcpToolset is closed.")
        }
        connection.store(newConnection)
        // close() may set `closed` after the check above but before publication. Detach and close
        // this just-created client so it cannot outlive the closed toolset.
        if (closed && connection.compareAndSet(newConnection, null)) {
          closeAfterToolsetClose(newConnection)
          throw IllegalStateException("AndroidMcpToolset is closed.")
        }
      }
    }

  private suspend fun createConnection(): Connection {
    val newHttpClient = httpClientFactory()
    val transport = LegacyStreamableHttpClientTransport(
      newHttpClient,
      serverUrl,
      requestProgressForToolCalls = progressConsumers.isNotEmpty(),
      onProgress = { progress, total, message ->
        progressConsumers.forEach { consumer ->
          consumer(McpProgressUpdate(progress, total, message))
        }
      },
    ) {
      // This lambda is run by the Kotlin MCP SDK for every transport request, rather than only at
      // connection setup. Reading the volatile snapshot is what permits access-token refreshes to
      // reuse a valid Streamable HTTP session on Android.
      currentRequestHeaders.forEach { (name, value) -> headers.append(name, value) }
    }
    return try {
      Client(
        Implementation("google-adk-kotlin-android", "0.1"),
        ClientOptions(),
      )
        .also { client ->
          withLocalTimeout(timeouts.connect, "MCP connect timed out after ${timeouts.connect}.") {
            client.connect(transport)
          }
        }
        .let { Connection(it, newHttpClient, timeouts.request) }
    } catch (error: Exception) {
      newHttpClient.close()
      throw error
    }
  }

  private suspend fun invalidateConnection(expected: Connection? = null) {
    val closing = connectionMutex.withLock {
      val active = connection.load()
      if (active != null && (expected == null || active === expected)) {
        active.takeIf { connection.compareAndSet(active, null) }
      } else null
    }
    closing?.close()
  }

  override fun close() {
    // Keep lifecycle state and cached-tool cleanup aligned with the shared JVM/Android core.
    // The Kotlin MCP client's close operation is suspending, so the Android session manager
    // schedules physical transport shutdown without blocking a caller such as the UI thread.
    sharedCore.close()
  }

  private fun closeConnection() {
    if (closed) return
    closed = true
    // Ktor's close is synchronous: it cancels HTTP calls and the SSE connection before close()
    // returns. The SDK client close is suspending, so finish its transport bookkeeping below.
    connection.exchange(null)?.let(::closeAfterToolsetClose)
  }

  private fun closeAfterToolsetClose(closing: Connection) {
    closing.httpClient.close()
    closeScope.launch {
      closing.client.close()
    }
  }

  private fun updateRequestHeaders(dynamicHeaders: Map<String, String>) {
    // Dynamic headers override fixed endpoint headers, matching JVM's merge order. Copy the map
    // before publication so the transport never observes a caller-owned mutable map in flight.
    currentRequestHeaders = (headers + dynamicHeaders).toMap()
  }

  /** Android implementation of the common SDK-neutral session boundary. */
  private data class Connection(
    val client: Client,
    val httpClient: HttpClient,
    val requestTimeout: Duration,
  ) : McpResourceClientSession {
    // 0.5.0's Protocol.request() ignores ClientOptions.timeout and falls back to 60s unless
    // RequestOptions is passed per call. Local withTimeout is wrapped only when this coroutine
    // is still active, so a parent cancel is not turned into a retried failure.
    private val sdkRequestOptions = RequestOptions(timeout = requestTimeout)

    override val supportsResources: Boolean
      get() = client.serverCapabilities?.resources != null

    override suspend fun listTools(): List<McpToolDefinition> = awaitSdk {
      client.listAllTools(sdkRequestOptions).map { tool ->
        McpToolDefinition(
          name = tool.name,
          description = tool.description.orEmpty(),
          // AndroidMcpToolFactory retains the Kotlin SDK Tool and converts this lazily, preserving
          // McpToolDeclarationException for malformed server schemas.
          inputSchema = null,
          outputSchema = null,
          annotations = null,
          meta = null,
          platformTool = tool,
        )
      }
    }

    override suspend fun callTool(
      name: String,
      arguments: Map<String, Any?>,
      @Suppress("UNUSED_PARAMETER") options: McpToolCallOptions,
    ): Map<String, Any?> =
      awaitSdk {
        // The 0.5 SDK Map overload stringifies nested objects/arrays. Encode JSON ourselves.
        // Progress tokens are still injected by the private compatibility transport.
        client
          .callTool(
            CallToolRequest(name = name, arguments = arguments.toMcpArguments()),
            compatibility = false,
            options = sdkRequestOptions,
          )
          ?.toJsonNativeMap()
          ?: mapOf("error" to "MCP framework error: CallToolResult was null")
      }

    override suspend fun listResources(cursor: String?): McpClientResourcePage =
      awaitSdk {
        client
          .listResources(ListResourcesRequest(cursor), sdkRequestOptions)
          ?.toMcpClientResourcePage()
          ?: McpClientResourcePage(emptyList())
      }

    override suspend fun listResourceTemplates(cursor: String?): McpClientResourceTemplatePage =
      awaitSdk {
        client
          .listResourceTemplates(ListResourceTemplatesRequest(cursor), sdkRequestOptions)
          ?.toMcpClientResourceTemplatePage()
          ?: McpClientResourceTemplatePage(emptyList())
      }

    override suspend fun readResource(uri: String): List<McpClientResourceContent> =
      awaitSdk {
        client
          .readResource(ReadResourceRequest(uri), sdkRequestOptions)
          ?.toMcpClientResourceContents()
          .orEmpty()
      }

    private suspend fun <T> awaitSdk(block: suspend () -> T): T =
      try {
        withLocalTimeout(requestTimeout, "MCP request timed out after $requestTimeout.", block)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        throw error.toMcpServerRejectedExceptionOrNull() ?: error
      }

    override suspend fun close() {
      try {
        client.close()
      } finally {
        httpClient.close()
      }
    }

  }
}

internal class AndroidMcpTool(
  private val tool: Tool,
  private val invocation: McpToolInvocation,
) :
  BaseTool(tool.name, tool.description.orEmpty()) {
  private val convertedDeclaration: FunctionDeclaration by lazy {
    try {
      FunctionDeclaration(name, description, tool.inputSchema.toAdkSchema())
    } catch (error: RuntimeException) {
      throw McpToolException.McpToolDeclarationException(
        "MCP tool \"$name\" failed to build its declaration.",
        error,
      )
    }
  }

  override fun declaration(): FunctionDeclaration = convertedDeclaration

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    try {
      invocation.invoke(context, args)
    } catch (error: McpToolsetCoreException) {
      throw McpToolException.McpToolExecutionException(
        "Unable to call MCP tool \"$name\".",
        error.cause ?: error,
      )
    }

}

private fun ListResourcesResult.toMcpClientResourcePage() =
  McpClientResourcePage(
    resources =
      resources.map { resource ->
        McpClientResource(
          resource.name,
          resource.uri,
          null,
          resource.description,
          resource.mimeType,
          null,
          null,
          null,
        )
      },
    nextCursor = nextCursor,
  )

private fun ListResourceTemplatesResult.toMcpClientResourceTemplatePage() =
  McpClientResourceTemplatePage(
    resourceTemplates =
      resourceTemplates.map { template ->
        McpClientResourceTemplate(
          template.name,
          template.uriTemplate,
          null,
          template.description,
          template.mimeType,
          null,
          null,
        )
      },
    nextCursor = nextCursor,
  )

private fun ReadResourceResult.toMcpClientResourceContents(): List<McpClientResourceContent> =
  contents.mapNotNull { content ->
    when (content) {
      is TextResourceContents ->
        McpClientResourceContent.Text(content.uri, content.mimeType, content.text, null)
      is BlobResourceContents ->
        McpClientResourceContent.Blob(content.uri, content.mimeType, content.blob, null)
      else -> null
    }
  }

internal fun Throwable.isResourceNotFound(): Boolean {
  var current: Throwable? = this
  while (current != null) {
    if (current is McpError && current.code == -32002) return true
    if (current is McpServerRejectedException && current.jsonRpcCode == -32002) return true
    // 0.5.0 wraps JSON-RPC response errors in IllegalStateException instead of McpError.
    if (current is IllegalStateException && current.message?.contains("code=-32002") == true) {
      return true
    }
    current = current.cause
  }
  return false
}

private fun Throwable.toMcpServerRejectedExceptionOrNull(): McpServerRejectedException? {
  if (this is McpServerRejectedException) return this
  if (this is McpError) {
    return McpServerRejectedException(message ?: "MCP request rejected", this, code)
  }
  if (this is IllegalStateException && findStreamableHttpStatus() == null) {
    val code = Regex("""code=(-?\d+)""").find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (code != null) {
      return McpServerRejectedException(message ?: "MCP request rejected", this, code)
    }
  }
  return null
}

private fun Throwable.isHttpUnauthorized(): Boolean = findStreamableHttpStatus() == 401

private fun Throwable.isHttp401OrSession404(): Boolean =
  findStreamableHttpStatus() in setOf(401, 404)

// 0.5.0 Client.connect wraps transport failures as IllegalStateException without a cause, so the
// HTTP status must also be recoverable from the message produced by LegacyStreamableHttpError.
private val WRAPPED_HTTP_STATUS = Regex("""Streamable HTTP error: HTTP (\d{3})""")

private fun Throwable.findStreamableHttpStatus(): Int? {
  var current: Throwable? = this
  while (current != null) {
    if (current is LegacyStreamableHttpError) return current.code
    WRAPPED_HTTP_STATUS.find(current.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let {
      return it
    }
    current = current.cause
  }
  return null
}

@OptIn(ExperimentalSerializationApi::class)
private val mcpResultJson =
  Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
    explicitNulls = false
  }

/** Uses the MCP Kotlin SDK serializer so polymorphic content keeps its wire-format discriminator. */
private fun CallToolResultBase.toJsonNativeMap(): Map<String, Any?> {
  @Suppress("UNCHECKED_CAST")
  return mcpResultJson.encodeToJsonElement(CallToolResultBase.serializer(), this).toAny() as Map<String, Any?>
}

private fun Map<String, Any?>.toMcpArguments(): JsonObject =
  JsonObject(mapValues { (_, value) -> value.toJsonElement() })

private suspend fun Client.listAllTools(options: RequestOptions): List<Tool> {
  val result = mutableListOf<Tool>()
  var cursor: String? = null
  val seenCursors = mutableSetOf<String>()
  repeat(MAX_TOOL_LIST_PAGES) {
    val page =
      if (cursor == null) listTools(options = options) else listTools(ListToolsRequest(cursor), options)
    if (page == null) return result
    result.addAll(page.tools)
    val nextCursor = page.nextCursor ?: return result
    check(seenCursors.add(nextCursor)) { "MCP server repeated a tools/list cursor." }
    cursor = nextCursor
  }
  error("MCP server paginated tools/list past $MAX_TOOL_LIST_PAGES pages.")
}

private const val MAX_TOOL_LIST_PAGES = 100

/**
 * Converts only this layer's [withTimeout] into an ordinary failure. A cancelled parent coroutine
 * also surfaces as [TimeoutCancellationException]; [ensureActive] rethrows that so
 * [McpToolsetCore] does not retry or reconnect after an Agent/Runner cancel.
 */
private suspend fun <T> withLocalTimeout(
  timeout: Duration,
  message: String,
  block: suspend () -> T,
): T {
  try {
    return withTimeout(timeout) { block() }
  } catch (error: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw IllegalStateException(message, error)
  }
}
