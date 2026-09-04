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
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import kotlin.time.Duration
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
  }
}

/** A progress notification emitted by an MCP tool invocation. */
data class McpProgressUpdate(
  val progress: Double,
  val total: Double?,
  val message: String?,
)

/**
 * Common configuration for a remote Streamable HTTP MCP toolset.
 *
 * This is the portable MCP entry point for JVM and Android. Platform-specific transports and MCP
 * SDKs are selected internally; callers do not need to depend on either SDK.
 *
 * ```
 * val toolset =
 *   McpToolsetConfig(
 *       transport = McpTransportConfig.StreamableHttp("https://example.com/mcp"),
 *     )
 *     .toToolset(headerProvider = { mapOf("Authorization" to "Bearer token") })
 * ```
 */
data class McpToolsetConfig(
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
   * [headerProvider] is resolved for the current ADK context before an operation. It is intended
   * for application-managed credentials such as an already refreshed bearer token; OAuth UI,
   * token storage, and refresh policy remain application concerns. Headers returned by the
   * provider override same-named static [McpTransportConfig.StreamableHttp.headers]. The provider
   * can be called again when an operation is retried, so it should be safe to invoke repeatedly.
   */
  fun toToolset(
    headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
    progressConsumers: List<(McpProgressUpdate) -> Unit> = emptyList(),
  ): Toolset = createPlatformMcpToolset(this, headerProvider, progressConsumers)
}

/** Platform adapter for the common public configuration. */
internal expect fun createPlatformMcpToolset(
  config: McpToolsetConfig,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  progressConsumers: List<(McpProgressUpdate) -> Unit>,
): Toolset

internal const val DEFAULT_MAX_MCP_RESOURCE_LENGTH = 10_000
