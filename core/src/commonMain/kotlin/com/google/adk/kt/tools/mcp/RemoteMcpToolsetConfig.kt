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
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Transport configurations supported by the common JVM/Android MCP API. */
sealed interface McpTransportConfig {
  /**
   * Streamable HTTP settings supported by both JVM and Android MCP clients.
   *
   * HTTPS is allowed by default. An `http` endpoint requires [allowInsecureHttp] to be explicitly
   * enabled on every platform. Android 9 / API 28 and newer normally block cleartext traffic as
   * well, so the application must configure `networkSecurityConfig` or `usesCleartextTraffic`
   * itself. A library cannot override that operating-system policy.
   *
   * @property url The Streamable HTTP MCP endpoint. A URL with no path, or with path `/`, uses
   *   `/mcp` on JVM and Android (for example `https://host` becomes `https://host/mcp`). Any other
   *   path is used as-is.
   * @property headers Headers included on every request. Values returned by the headerProvider
   *   passed to [RemoteMcpToolsetConfig.toToolset] override same-named entries.
   * @property connectTimeout Timeout for establishing the HTTP connection.
   * @property requestTimeout Timeout for a single MCP request.
   * @property allowInsecureHttp When `true`, an `http` URL is accepted. HTTPS does not need this.
   */
  data class StreamableHttp(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeout: Duration = 5.seconds,
    val requestTimeout: Duration = 5.minutes,
    val allowInsecureHttp: Boolean = false,
  ) : McpTransportConfig {
    init {
      require(connectTimeout.isPositive()) { "MCP connect timeout must be positive." }
      require(requestTimeout.isPositive()) { "MCP request timeout must be positive." }
      val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
      require(scheme == "https" || (scheme == "http" && allowInsecureHttp)) {
        "MCP endpoints must use HTTPS. Set allowInsecureHttp = true to explicitly opt in to HTTP."
      }
    }

    /** Returns [connectTimeout] in whole milliseconds. Java cannot read [connectTimeout]. */
    fun connectTimeoutMillis(): Long = connectTimeout.inWholeMilliseconds

    /** Returns [requestTimeout] in whole milliseconds. Java cannot read [requestTimeout]. */
    fun requestTimeoutMillis(): Long = requestTimeout.inWholeMilliseconds

    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    fun toBuilder(): Builder =
      Builder()
        .url(url)
        .headers(headers)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .allowInsecureHttp(allowInsecureHttp)

    /**
     * Fluent builder for [StreamableHttp], provided primarily for Java callers. Any property left
     * unset falls back to the same default as the constructor. Timeouts are set in milliseconds
     * from Java because [Duration] is a value class hidden from Java.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var url: String? = null
      private var headers: Map<String, String> = emptyMap()
      private var connectTimeout: Duration = 5.seconds
      private var requestTimeout: Duration = 5.minutes
      private var allowInsecureHttp: Boolean = false

      fun url(url: String): Builder = apply { this.url = url }

      fun headers(headers: Map<String, String>): Builder = apply { this.headers = headers }

      // Lets toBuilder copy the timeout exactly; connectTimeoutMillis would truncate it.
      internal fun connectTimeout(connectTimeout: Duration): Builder = apply {
        this.connectTimeout = connectTimeout
      }

      internal fun requestTimeout(requestTimeout: Duration): Builder = apply {
        this.requestTimeout = requestTimeout
      }

      fun connectTimeoutMillis(connectTimeoutMillis: Long): Builder = apply {
        this.connectTimeout = connectTimeoutMillis.milliseconds
      }

      fun requestTimeoutMillis(requestTimeoutMillis: Long): Builder = apply {
        this.requestTimeout = requestTimeoutMillis.milliseconds
      }

      fun allowInsecureHttp(allowInsecureHttp: Boolean): Builder = apply {
        this.allowInsecureHttp = allowInsecureHttp
      }

      fun build(): StreamableHttp =
        StreamableHttp(
          url = checkNotNull(url) { "StreamableHttp.Builder requires url to be set." },
          headers = headers,
          connectTimeout = connectTimeout,
          requestTimeout = requestTimeout,
          allowInsecureHttp = allowInsecureHttp,
        )
    }

    companion object {
      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }
}

/** A progress notification emitted by an MCP tool invocation. */
data class McpProgressUpdate(val progress: Double, val total: Double?, val message: String?)

/**
 * Common configuration for a remote Streamable HTTP MCP toolset.
 *
 * This is the portable MCP entry point for JVM and Android. Platform-specific transports and MCP
 * SDKs are selected internally. JVM callers do not add an extra MCP SDK. On Android, ADK declares
 * `kotlin-sdk-client` and `ktor-client-okhttp` as `compileOnly`, so the app must add those
 * artifacts itself. JVM-only stdio and legacy SSE continue to use `McpToolset.McpToolsetConfig`.
 *
 * A configured `headerProvider` is invoked before tool discovery and again before each tool call
 * (matching ADK Python). An [IllegalArgumentException] from that provider or from a
 * [ToolFilter.Predicate] during [Toolset.getTools] is wrapped in
 * `McpToolException.McpToolLoadingException`.
 *
 * ```
 * val toolset =
 *   RemoteMcpToolsetConfig(
 *       transport = McpTransportConfig.StreamableHttp("https://example.com/mcp"),
 *     )
 *     .toToolset(headerProvider = { mapOf("Authorization" to "Bearer token") })
 * ```
 */
data class RemoteMcpToolsetConfig(
  val transport: McpTransportConfig,
  val toolFilter: ToolFilter? = null,
  val useMcpResources: Boolean = false,
  val maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
) {
  init {
    require(maxMcpResourceLength > 0) { "MCP resource length limit must be positive." }
  }

  /**
   * Creates an MCP [Toolset].
   *
   * [headerProvider] is resolved for the current ADK context before an operation, including before
   * each tool call (matching ADK Python). It is intended for application-managed credentials such
   * as an already refreshed bearer token; OAuth UI, token storage, and refresh policy remain
   * application concerns. Headers returned by the provider override same-named static
   * [McpTransportConfig.StreamableHttp.headers]. The provider can be called again when an operation
   * is retried, so it should be safe to invoke repeatedly.
   *
   * An [IllegalArgumentException] thrown by [headerProvider] or by a [ToolFilter.Predicate] during
   * [Toolset.getTools] is wrapped in `McpToolException.McpToolLoadingException`.
   *
   * Session reuse differs by platform. JVM keeps one session per distinct header set, so two users
   * with different tokens do not share a connection. Android keeps one session for the toolset and
   * applies the latest headers on that session: a second user's call can reuse the first user's
   * HTTP connection. Multi-account Android apps must not overlap calls for different users on the
   * same toolset if those calls must not share a connection.
   *
   * Tool discovery failures surface as `McpToolException.McpToolLoadingException`. ADK
   * resource-tool failures surface as `McpToolException.McpToolExecutionException` on both
   * platforms. Server-tool `run` differs by platform: Android wraps the failure as
   * `McpToolException.McpToolExecutionException` with the SDK or transport error as
   * [Throwable.cause]; JVM rethrows that underlying error so existing callers keep catching
   * SDK/transport types.
   *
   * On Android, ADK declares the Kotlin MCP SDK and Ktor OkHttp engine as `compileOnly`. An app
   * that uses this toolset must add those dependencies itself. google-genai-kotlin 1.4.0 still
   * depends on Ktor 2, so an Android app cannot use Gemini and MCP in the same process until
   * genai-kotlin moves to Ktor 3.
   */
  @JvmOverloads
  fun toToolset(
    headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
    progressConsumers: List<(McpProgressUpdate) -> Unit> = emptyList(),
  ): Toolset = createPlatformMcpToolset(this, headerProvider, progressConsumers)

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .transport(transport)
      .toolFilter(toolFilter)
      .useMcpResources(useMcpResources)
      .maxMcpResourceLength(maxMcpResourceLength)

  /**
   * Fluent builder for [RemoteMcpToolsetConfig], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var transport: McpTransportConfig? = null
    private var toolFilter: ToolFilter? = null
    private var useMcpResources: Boolean = false
    private var maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH

    fun transport(transport: McpTransportConfig): Builder = apply { this.transport = transport }

    fun toolFilter(toolFilter: ToolFilter?): Builder = apply { this.toolFilter = toolFilter }

    fun useMcpResources(useMcpResources: Boolean): Builder = apply {
      this.useMcpResources = useMcpResources
    }

    fun maxMcpResourceLength(maxMcpResourceLength: Int): Builder = apply {
      this.maxMcpResourceLength = maxMcpResourceLength
    }

    fun build(): RemoteMcpToolsetConfig =
      RemoteMcpToolsetConfig(
        transport =
          checkNotNull(transport) {
            "RemoteMcpToolsetConfig.Builder requires transport to be set."
          },
        toolFilter = toolFilter,
        useMcpResources = useMcpResources,
        maxMcpResourceLength = maxMcpResourceLength,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/** Platform adapter for the common public configuration. */
internal expect fun createPlatformMcpToolset(
  config: RemoteMcpToolsetConfig,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  progressConsumers: List<(McpProgressUpdate) -> Unit>,
): Toolset

internal const val DEFAULT_MAX_MCP_RESOURCE_LENGTH = 10_000

/**
 * Uses `/mcp` when [url] has no path or only `/`, matching JVM
 * `McpConnectionParameters.StreamableHttp`.
 */
internal fun streamableHttpUrlWithDefaultPath(url: String): String {
  val schemeSep = url.indexOf("://")
  if (schemeSep < 0) return url
  val afterScheme = url.substring(schemeSep + 3)
  val authorityEnd =
    afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { index ->
      if (index < 0) afterScheme.length else index
    }
  if (authorityEnd == 0) return url
  val remainder = afterScheme.substring(authorityEnd)
  val path = remainder.takeWhile { it != '?' && it != '#' }
  if (path.isNotEmpty() && path != "/") return url
  val origin = url.substring(0, schemeSep + 3) + afterScheme.substring(0, authorityEnd)
  val suffix = remainder.drop(path.length)
  return "$origin/mcp$suffix"
}
