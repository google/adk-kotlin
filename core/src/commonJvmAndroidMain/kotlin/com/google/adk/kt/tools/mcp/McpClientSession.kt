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

import com.google.adk.kt.types.Schema

/**
 * SDK-neutral view of an MCP tool advertised by a server.
 *
 * The Java and Kotlin MCP SDKs use different client and schema types. Keeping that difference
 * behind this internal model lets the ADK toolset behavior be shared by JVM and Android without
 * exposing either SDK from the common source set.
 */
internal data class McpToolDefinition(
  val name: String,
  val description: String,
  val inputSchema: Schema?,
  val outputSchema: Schema? = null,
  val annotations: McpToolAnnotations? = null,
  val meta: Map<String, Any?>? = null,
  /** Platform SDK object retained only for an internal platform [McpToolFactory]. */
  val platformTool: Any? = null,
)

/** SDK-neutral MCP tool annotations preserved during platform adaptation. */
internal data class McpToolAnnotations(
  val title: String? = null,
  val readOnlyHint: Boolean? = null,
  val destructiveHint: Boolean? = null,
  val idempotentHint: Boolean? = null,
  val openWorldHint: Boolean? = null,
)

/** Creates a platform-compatible ADK tool while the core owns discovery and caching. */
internal fun interface McpToolFactory {
  fun create(definition: McpToolDefinition, invocation: McpToolInvocation): com.google.adk.kt.tools.BaseTool
}

/** Shared execution callback supplied to a platform-created MCP tool. */
internal fun interface McpToolInvocation {
  suspend fun invoke(
    context: com.google.adk.kt.tools.ToolContext,
    arguments: Map<String, Any?>,
  ): Any
}

/** Per-invocation protocol options shared by the platform client adapters. */
internal data class McpToolCallOptions(
  val progressToken: String? = null,
  val onProgress: ((McpProgressUpdate) -> Unit)? = null,
)

/**
 * A connected MCP client session, independent of the SDK used to implement it.
 *
 * Platform adapters own conversion between their MCP SDK types and the ADK-owned values here.
 */
internal interface McpClientSession {
  val supportsResources: Boolean

  suspend fun listTools(): List<McpToolDefinition>

  suspend fun callTool(
    name: String,
    arguments: Map<String, Any?>,
    options: McpToolCallOptions = McpToolCallOptions(),
  ): Map<String, Any?>

  suspend fun close()
}

/**
 * Owns SDK-neutral MCP sessions for a toolset.
 *
 * The [stale] identity lets a caller invalidate only the connection that failed, rather than a
 * concurrently created replacement. Each platform adapter keeps its own pooling and transport
 * lifecycle details behind this boundary.
 */
internal interface McpClientSessionManager {
  /** Whether this platform session manager has listeners that require an MCP progress token. */
  val hasProgressConsumers: Boolean
    get() = false

  /** Listener a platform adapter should receive for progress emitted by the current invocation. */
  val onProgress: ((McpProgressUpdate) -> Unit)?
    get() = null

  suspend fun getSession(
    /** Null means that the caller has no new dynamic-header snapshot to apply. */
    headers: Map<String, String>? = null,
    stale: McpClientSession? = null,
  ): McpClientSession

  /** Whether a failed operation makes its [McpClientSession] unsafe to reuse on the next retry. */
  fun shouldInvalidateSession(error: Throwable): Boolean = true

  /** Whether an authentication failure should resolve [McpToolsetCore]'s header provider again. */
  fun shouldRefreshHeaders(error: Throwable): Boolean = false

  fun close()
}
