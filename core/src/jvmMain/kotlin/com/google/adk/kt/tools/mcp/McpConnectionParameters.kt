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

import io.modelcontextprotocol.client.transport.ServerParameters
import java.time.Duration
import kotlin.jvm.JvmStatic

/** Sealed class for holding MCP connection parameters. */
sealed class McpConnectionParameters {
  /**
   * Parameters for establishing a MCP Stdio connection.
   *
   * @property serverParameters Parameters for the MCP Stdio server.
   * @property timeoutDuration Timeout for establishing the connection to the MCP stdio server.
   */
  data class Stdio(
    val serverParameters: ServerParameters,
    val timeoutDuration: Duration = Duration.ofSeconds(5),
  ) : McpConnectionParameters()

  /**
   * Parameters for establishing a MCP Server-Sent Events (SSE) connection.
   *
   * @property url The URL of the SSE server.
   * @property sseEndpoint The SSE endpoint.
   * @property headers The headers to include in the request.
   * @property timeout The connection timeout.
   * @property sseReadTimeout The SSE read timeout.
   */
  data class Sse(
    val url: String,
    val sseEndpoint: String = "sse",
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(5),
    val sseReadTimeout: Duration = Duration.ofMinutes(5),
  ) : McpConnectionParameters()

  /**
   * Parameters for establishing a MCP Streamable HTTP connection.
   *
   * @property url The URL of the HTTP server.
   * @property headers The headers to include in the request.
   * @property timeout The connection timeout.
   * @property readTimeout The read timeout.
   */
  data class StreamableHttp(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofMinutes(5),
  ) : McpConnectionParameters() {

    /**
     * Fluent builder for [StreamableHttp], provided primarily for Java callers. Any property left
     * unset falls back to the same default as the constructor.
     */
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var url: String? = null
      private var headers: Map<String, String> = emptyMap()
      private var timeout: Duration = Duration.ofSeconds(5)
      private var readTimeout: Duration = Duration.ofMinutes(5)

      fun url(url: String): Builder = apply { this.url = url }

      fun headers(headers: Map<String, String>): Builder = apply { this.headers = headers }

      fun timeout(timeout: Duration): Builder = apply { this.timeout = timeout }

      fun readTimeout(readTimeout: Duration): Builder = apply { this.readTimeout = readTimeout }

      fun build(): StreamableHttp =
        StreamableHttp(
          url = checkNotNull(url) { "StreamableHttp.Builder requires url to be set." },
          headers = headers,
          timeout = timeout,
          readTimeout = readTimeout,
        )
    }

    companion object {
      @JvmStatic fun builder(): Builder = Builder()
    }
  }
}
