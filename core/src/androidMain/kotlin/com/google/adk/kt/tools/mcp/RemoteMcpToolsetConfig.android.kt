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
import com.google.adk.kt.tools.Toolset

internal const val ANDROID_MCP_RUNTIME_MISSING =
  "Android MCP requires io.modelcontextprotocol:kotlin-sdk-client and io.ktor:ktor-client-okhttp " +
    "on the runtime classpath. ADK declares them as compileOnly so apps that do not use MCP are " +
    "not forced onto Ktor 3 (which currently conflicts with google-genai-kotlin's Ktor 2). " +
    "An app that uses both MCP and Gemini on Android will still conflict until genai-kotlin " +
    "moves to Ktor 3."

internal fun ensureAndroidMcpRuntime(loadClass: (String) -> Class<*> = { Class.forName(it) }) {
  try {
    loadClass("io.modelcontextprotocol.kotlin.sdk.client.Client")
    loadClass("io.ktor.client.engine.okhttp.OkHttp")
  } catch (error: ClassNotFoundException) {
    throw IllegalStateException(ANDROID_MCP_RUNTIME_MISSING, error)
  }
}

/** Android adapter: the public common config is backed by the Kotlin MCP SDK transport. */
internal actual fun createPlatformMcpToolset(
  config: RemoteMcpToolsetConfig,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  progressConsumers: List<(McpProgressUpdate) -> Unit>,
): Toolset {
  ensureAndroidMcpRuntime()
  return when (val transport = config.transport) {
    is McpTransportConfig.StreamableHttp ->
      AndroidMcpToolset(
        serverUrl = streamableHttpUrlWithDefaultPath(transport.url),
        headers = transport.headers,
        toolFilter = config.toolFilter,
        timeouts =
          AndroidMcpTimeouts(
            connect = transport.connectTimeout,
            request = transport.requestTimeout,
            socket = transport.requestTimeout,
          ),
        useMcpResources = config.useMcpResources,
        maxMcpResourceLength = config.maxMcpResourceLength,
        progressConsumers = progressConsumers,
        headerProvider = headerProvider,
      )
  }
}
