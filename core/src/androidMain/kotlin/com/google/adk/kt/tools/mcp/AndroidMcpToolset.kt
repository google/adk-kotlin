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

@file:OptIn(com.google.adk.kt.annotations.FrameworkInternalApi::class)

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.toAny
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Annotations
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
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
 * Android implementation behind the common [RemoteMcpToolsetConfig] public API.
 *
 * It owns one lazily connected Kotlin MCP SDK client and reuses it for discovery, tool calls, and
 * optional resource access. Dynamic headers are applied to every request on that session, for the
 * current Android user. This implementation intentionally supports remote Streamable HTTP only;
 * stdio, legacy SSE, and OAuth flows remain application concerns.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class AndroidMcpToolset
private constructor(
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
   * @param useMcpResources Whether to expose ADK's `list_mcp_resources`, `load_mcp_resource`, and
   *   `list_mcp_resource_templates` tools when the server reports the MCP `resources` capability.
   * @param maxMcpResourceLength Maximum number of text characters returned per resource content
   *   item before a truncation marker is added.
   * @param progressConsumers Callbacks for MCP progress notifications emitted while a tool call is
   *   in flight. Supplying at least one consumer also asks the server for progress notifications.
   * @param headerProvider Optional suspending callback that returns request headers for the current
   *   ADK context. It is invoked before tool discovery and again before each tool call (matching
   *   ADK Python). Use it to mint or refresh credentials for the current Android user. Returned
   *   headers are applied to each request on the shared MCP session. An [IllegalArgumentException]
   *   from this provider or from a [ToolFilter.Predicate] during [getTools] is wrapped in
   *   [McpToolException.McpToolLoadingException]. OAuth UI, token storage, refresh policy, and
   *   account switching remain app concerns; close this toolset when the configured account
   *   changes.
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
  ) : this(
    serverUrl,
    headers,
    toolFilter,
    timeouts,
    useMcpResources,
    maxMcpResourceLength,
    progressConsumers,
    headerProvider,
    {
      HttpClient(OkHttp) {
        install(SSE)
        install(HttpTimeout) {
          connectTimeoutMillis = timeouts.connect.inWholeMilliseconds
          requestTimeoutMillis = timeouts.request.inWholeMilliseconds
          socketTimeoutMillis = timeouts.socket.inWholeMilliseconds
        }
      }
    },
  )

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

  private val androidToolFactory = McpToolFactory { definition, invocation ->
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

  /**
   * Discovers tools advertised by the MCP server.
   *
   * When a [headerProvider] is configured it is invoked for [readonlyContext] before listing tools,
   * and again before each later tool call.
   *
   * @throws McpToolException.McpToolLoadingException if discovery fails, including when the header
   *   provider or a [ToolFilter.Predicate] throws [IllegalArgumentException].
   */
  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    check(!closed) { "AndroidMcpToolset is closed." }
    try {
      return sharedCore.getTools(readonlyContext)
    } catch (error: IllegalArgumentException) {
      throw McpToolException.McpToolLoadingException(
        "Invalid argument encountered during tool loading.",
        error,
      )
    } catch (error: McpToolsetCoreException) {
      throw McpToolException.McpToolLoadingException("Failed to load tools.", error.cause ?: error)
    }
  }

  /** Android counterpart to `JvmMcpClientSessionManager`. */
  private inner class AndroidMcpClientSessionManager : McpClientSessionManager {
    override val hasProgressConsumers: Boolean
      get() = progressConsumers.isNotEmpty()

    override val onProgress: ((McpProgressUpdate) -> Unit)?
      get() =
        progressConsumers
          .takeIf { it.isNotEmpty() }
          ?.let { consumers -> { update -> for (consumer in consumers) consumer(update) } }

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
    connection.load()
      ?: createConnection().also { newConnection ->
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
    var newHttpClient: HttpClient? = null
    return try {
      val httpClient = httpClientFactory()
      newHttpClient = httpClient
      val httpTransport =
        StreamableHttpClientTransport(httpClient, serverUrl) {
          for ((name, value) in currentRequestHeaders) {
            headers.append(name, value)
          }
        }
      val client = Client(Implementation("google-adk-kotlin-android", "0.1"), ClientOptions())
      withLocalTimeout(timeouts.connect, "MCP connect timed out after ${timeouts.connect}.") {
        client.connect(ProtocolVersionTransport(httpTransport))
      }
      Connection(client, httpTransport, httpClient, timeouts.request)
    } catch (error: CancellationException) {
      newHttpClient?.close()
      throw error
    } catch (error: Exception) {
      newHttpClient?.close()
      throw error.withoutSensitivePayload()
    }
  }

  private suspend fun invalidateConnection(expected: Connection? = null) {
    val closing = connectionMutex.withLock {
      val active = connection.load()
      if (active != null && (expected == null || active === expected)) {
        active.takeIf { connection.compareAndSet(active, null) }
      } else null
    }
    closing?.let { stale ->
      try {
        stale.transport.terminateSession()
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {} finally {
        withContext(NonCancellable) { stale.close() }
      }
    }
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
    // Independent of the caller Job so UI is not blocked. NonCancellable still finishes SDK
    // close if closeScope is cancelled while client.close() is in flight.
    closeScope.launch { withContext(NonCancellable) { closing.client.close() } }
  }

  private fun updateRequestHeaders(dynamicHeaders: Map<String, String>) {
    // Dynamic headers override fixed endpoint headers, matching JVM's merge order. Copy the map
    // before publication so the transport never observes a caller-owned mutable map in flight.
    currentRequestHeaders = (headers + dynamicHeaders).toMap()
  }

  /** Android implementation of the common SDK-neutral session boundary. */
  private inner class Connection(
    val client: Client,
    val transport: StreamableHttpClientTransport,
    val httpClient: HttpClient,
    val requestTimeout: Duration,
  ) : McpResourceClientSession {
    @Volatile
    var closed: Boolean = false
      private set

    private val sdkRequestOptions = RequestOptions(timeout = requestTimeout)

    override val supportsResources: Boolean
      get() = client.serverCapabilities?.resources != null

    override suspend fun listTools(): List<McpToolDefinition> = awaitSdk {
      client.listAllTools(sdkRequestOptions).map { tool ->
        McpToolDefinition(
          name = tool.name,
          description = tool.description.orEmpty(),
          // Schema conversion stays in AndroidMcpTool.declaration() so a malformed server schema
          // does not fail tools/list for every otherwise valid tool.
          annotations = tool.annotations?.toClientToolAnnotations(),
          meta = tool.meta?.toAnyMap(),
          platformTool = tool,
        )
      }
    }

    override suspend fun callTool(
      name: String,
      arguments: Map<String, Any?>,
      options: McpToolCallOptions,
    ): Map<String, Any?> {
      val result = awaitSdk { client.callTool(name, arguments, options = sdkCallOptions(options)) }
      return try {
        result.toJsonNativeMap()
      } catch (error: RuntimeException) {
        throw McpResultMappingException("Failed to convert MCP tool result.", error)
      }
    }

    override suspend fun listResources(cursor: String?): McpClientResourcePage {
      val request =
        if (cursor == null) ListResourcesRequest()
        else ListResourcesRequest(PaginatedRequestParams(cursor))
      val result = awaitSdk { client.listResources(request, sdkRequestOptions) }
      return try {
        result.toMcpClientResourcePage()
      } catch (error: RuntimeException) {
        throw McpResultMappingException("Failed to convert MCP resource listing.", error)
      }
    }

    override suspend fun listResourceTemplates(cursor: String?): McpClientResourceTemplatePage {
      val request =
        if (cursor == null) ListResourceTemplatesRequest()
        else ListResourceTemplatesRequest(PaginatedRequestParams(cursor))
      val result = awaitSdk { client.listResourceTemplates(request, sdkRequestOptions) }
      return try {
        result.toMcpClientResourceTemplatePage()
      } catch (error: RuntimeException) {
        throw McpResultMappingException("Failed to convert MCP resource template listing.", error)
      }
    }

    override suspend fun readResource(uri: String): List<McpClientResourceContent> {
      val result = awaitSdk {
        client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri)), sdkRequestOptions)
      }
      return try {
        result.toMcpClientResourceContents()
      } catch (error: RuntimeException) {
        throw McpResultMappingException("Failed to convert MCP resource contents.", error)
      }
    }

    private fun sdkCallOptions(options: McpToolCallOptions): RequestOptions {
      val listener = options.onProgress ?: return sdkRequestOptions
      return RequestOptions(
        timeout = requestTimeout,
        onProgress = { progress ->
          listener(McpProgressUpdate(progress.progress, progress.total, progress.message))
        },
      )
    }

    private suspend fun <T> awaitSdk(block: suspend () -> T): T =
      try {
        withLocalTimeout(requestTimeout, "MCP request timed out after $requestTimeout.", block)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        if (closed && error.isLocallyClosedProtocolError()) {
          throw IllegalStateException("MCP connection closed")
        }
        throw error.toMcpServerRejectedExceptionOrNull() ?: error.withoutSensitivePayload()
      }

    override suspend fun close() {
      closed = true
      try {
        withContext(NonCancellable) { client.close() }
      } finally {
        httpClient.close()
      }
    }
  }
}

internal class AndroidMcpTool(private val tool: Tool, private val invocation: McpToolInvocation) :
  BaseTool(tool.name, tool.description.orEmpty()) {
  private val convertedDeclaration: FunctionDeclaration by lazy {
    try {
      FunctionDeclaration(
        name,
        description,
        tool.inputSchema.toAdkSchema(),
        tool.outputSchema?.toAdkResponseSchema(),
      )
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
    } catch (error: McpResultMappingException) {
      throw McpToolException.McpToolExecutionException(
        "Unable to call MCP tool \"$name\".",
        error.cause ?: error,
      )
    } catch (error: McpToolsetCoreException) {
      throw McpToolException.McpToolExecutionException(
        "Unable to call MCP tool \"$name\".",
        error.cause ?: error,
      )
    }

  @FrameworkInternalApi
  val annotations: McpToolAnnotations?
    get() = tool.annotations?.toClientToolAnnotations()

  internal val meta: Map<String, Any?>?
    get() = tool.meta?.toAnyMap()
}

private fun ListResourcesResult.toMcpClientResourcePage() =
  McpClientResourcePage(
    resources =
      resources.map { resource ->
        McpClientResource(
          resource.name,
          resource.uri,
          resource.title,
          resource.description,
          resource.mimeType,
          resource.size,
          resource.annotations?.toClientAnnotations(),
          resource.meta?.toAnyMap(),
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
          template.title,
          template.description,
          template.mimeType,
          template.annotations?.toClientAnnotations(),
          template.meta?.toAnyMap(),
        )
      },
    nextCursor = nextCursor,
  )

private fun ReadResourceResult.toMcpClientResourceContents(): List<McpClientResourceContent> =
  contents.mapNotNull { content ->
    when (content) {
      is TextResourceContents ->
        McpClientResourceContent.Text(
          content.uri,
          content.mimeType,
          content.text,
          content.meta?.toAnyMap(),
        )
      is BlobResourceContents ->
        McpClientResourceContent.Blob(
          content.uri,
          content.mimeType,
          content.blob,
          content.meta?.toAnyMap(),
        )
      else -> null
    }
  }

private fun ToolAnnotations.toClientToolAnnotations() =
  McpToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint)

private fun Annotations.toClientAnnotations() =
  McpClientAnnotations(audience.orEmpty().map { it.name.lowercase() }, priority, lastModified)

@Suppress("UNCHECKED_CAST")
private fun JsonObject.toAnyMap(): Map<String, Any?> = toAny() as Map<String, Any?>

private fun Throwable.toMcpServerRejectedExceptionOrNull(): McpServerRejectedException? {
  if (this is McpServerRejectedException) return this
  // 0.15.0 wraps transport failures in McpException(cause=...). Only a cause-less McpException is
  // a JSON-RPC server rejection; the rest stay retryable.
  if (this is McpException && cause == null) {
    return McpServerRejectedException(message ?: "MCP request rejected", this, code)
  }
  return null
}

private fun Throwable.withoutSensitivePayload(): Throwable {
  if (this is CancellationException) return this
  var httpCode: Int? = null
  var malformed = false
  var connectTimeout = false
  var requestTimeout = false
  var socketTimeout = false
  var current: Throwable? = this
  while (current != null) {
    when (current) {
      is StreamableHttpError -> if (httpCode == null) httpCode = current.code
      is SerializationException -> malformed = true
    }
    when (current::class.simpleName) {
      "ConnectTimeoutException" -> connectTimeout = true
      "HttpRequestTimeoutException" -> requestTimeout = true
      "SocketTimeoutException" -> socketTimeout = true
    }
    current = current.cause
  }
  if (httpCode != null) return StreamableHttpError(httpCode, "HTTP $httpCode")
  if (malformed) return IllegalStateException("malformed message")
  if (connectTimeout) return IllegalStateException("MCP connection timed out")
  if (requestTimeout) return IllegalStateException("MCP request timed out")
  if (socketTimeout) return IllegalStateException("MCP socket timed out")
  return this
}

private fun Throwable.isHttpUnauthorized(): Boolean = findHttpStatus() == 401

private fun Throwable.isHttp401OrSession404(): Boolean = findHttpStatus() in setOf(401, 404)

private fun Throwable.findHttpStatus(): Int? {
  var current: Throwable? = this
  while (current != null) {
    if (current is StreamableHttpError) return current.code
    current = current.cause
  }
  return null
}

private fun Throwable.isLocallyClosedProtocolError(): Boolean {
  var current: Throwable? = this
  while (current != null) {
    if (
      current is McpException &&
        current.cause == null &&
        current.code in
          setOf(RPCError.ErrorCode.CONNECTION_CLOSED, RPCError.ErrorCode.REQUEST_TIMEOUT)
    ) {
      return true
    }
    current = current.cause
  }
  return false
}

/**
 * Uses the MCP Kotlin SDK serializer so polymorphic content keeps its wire-format discriminator.
 */
private fun CallToolResult.toJsonNativeMap(): Map<String, Any?> {
  @Suppress("UNCHECKED_CAST")
  return McpJson.encodeToJsonElement(CallToolResult.serializer(), this).toAny() as Map<String, Any?>
}

private suspend fun Client.listAllTools(options: RequestOptions): List<Tool> {
  val result = mutableListOf<Tool>()
  var cursor: String? = null
  val seenCursors = mutableSetOf<String>()
  repeat(MAX_TOOL_LIST_PAGES) {
    val page =
      if (cursor == null) listTools(options = options)
      else listTools(ListToolsRequest(PaginatedRequestParams(cursor)), options)
    result.addAll(page.tools)
    val nextCursor = page.nextCursor ?: return result
    check(seenCursors.add(nextCursor)) { "MCP server repeated a tools/list cursor." }
    cursor = nextCursor
  }
  error("MCP server paginated tools/list past $MAX_TOOL_LIST_PAGES pages.")
}

private const val MAX_TOOL_LIST_PAGES = 100

/**
 * 0.15.0 never copies [InitializeResult.protocolVersion] onto the transport, so later requests
 * would omit `MCP-Protocol-Version`. Capture it from the initialize JSON-RPC result.
 */
private class ProtocolVersionTransport(private val http: StreamableHttpClientTransport) :
  Transport {
  override suspend fun start() = http.start()

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) =
    http.send(message, options)

  override suspend fun close() = http.close()

  override fun onClose(block: () -> Unit) = http.onClose(block)

  override fun onError(block: (Throwable) -> Unit) = http.onError(block)

  override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) = http.onMessage { message ->
    val result = (message as? JSONRPCResponse)?.result
    if (result is InitializeResult) {
      http.protocolVersion = result.protocolVersion
    }
    block(message)
  }
}

/**
 * Converts only this layer's [withTimeout] into an ordinary failure. A cancelled parent coroutine
 * also surfaces as [TimeoutCancellationException]; [ensureActive] rethrows that so [McpToolsetCore]
 * does not retry or reconnect after an Agent/Runner cancel.
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
