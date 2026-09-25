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

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.util.Utils
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DefaultMcpTransportBuilderTest {

  private val transportBuilder = DefaultMcpTransportBuilder()

  @Test
  fun build_withSseServerParameters_returnsSseTransport() {
    val params = McpConnectionParameters.Sse(url = "http://localhost:1234")
    val transport = transportBuilder.build(params)

    assertIs<HttpClientSseClientTransport>(transport)
  }

  @Test
  fun build_withStreamableHttpServerParameters_returnsStreamableHttpTransport() {
    val params = McpConnectionParameters.StreamableHttp(url = "http://localhost:1234")

    val transport = transportBuilder.build(params)

    assertIs<HttpClientStreamableHttpTransport>(transport)
  }

  @Test
  fun build_withStdioServerParameters_returnsStdioTransport() {
    val params =
      McpConnectionParameters.Stdio(
        io.modelcontextprotocol.client.transport.ServerParameters.builder("cmd").build()
      )

    val transport = transportBuilder.build(params)

    assertIs<StdioClientTransport>(transport)
  }

  @Test
  fun build_withSseServerParametersAndHeaders_returnsSseTransport() {
    val params =
      McpConnectionParameters.Sse(
        url = "http://localhost:1234",
        headers = mapOf("header1" to "value1"),
      )

    val transport = transportBuilder.build(params)

    assertIs<HttpClientSseClientTransport>(transport)
  }

  @Test
  fun build_withStreamableHttpServerParametersAndHeaders_returnsStreamableHttpTransport() {
    val params =
      McpConnectionParameters.StreamableHttp(
        url = "http://localhost:1234",
        headers = mapOf("header1" to "value1"),
      )

    val transport = transportBuilder.build(params)

    assertIs<HttpClientStreamableHttpTransport>(transport)
  }

  @Test
  fun build_withSseServerParametersAndEmptyHeaders_returnsSseTransport() {
    val params = McpConnectionParameters.Sse(url = "http://localhost:1234", headers = emptyMap())

    val transport = transportBuilder.build(params)

    assertIs<HttpClientSseClientTransport>(transport)
  }

  @Test
  fun build_withStreamableHttpServerParametersAndEmptyHeaders_returnsStreamableHttpTransport() {
    val params =
      McpConnectionParameters.StreamableHttp(url = "http://localhost:1234", headers = emptyMap())

    val transport = transportBuilder.build(params)

    assertIs<HttpClientStreamableHttpTransport>(transport)
  }

  @Test
  fun build_withStreamableHttpWithHeadersAndTimeout_returnsStreamableHttpTransport() {
    val params =
      McpConnectionParameters.StreamableHttp(
        url = "http://localhost:1234",
        headers = mapOf("header1" to "value1"),
        timeout = Duration.ofSeconds(10),
      )

    val transport = transportBuilder.build(params)

    assertIs<HttpClientStreamableHttpTransport>(transport)
  }

  @Test
  fun build_withStdioServerParametersAndTimeout_returnsStdioTransport() {
    val params =
      McpConnectionParameters.Stdio(
        io.modelcontextprotocol.client.transport.ServerParameters.builder("cmd").build(),
        Duration.ofSeconds(10),
      )

    val transport = transportBuilder.build(params)

    assertIs<StdioClientTransport>(transport)
  }

  @Test
  fun streamableHttpEndpoint_withPath_isTheUrl() {
    assertEquals(
      "https://gateway.example.com/tenant/service/mcp",
      streamableHttpEndpoint("https://gateway.example.com/tenant/service/mcp"),
    )
  }

  @Test
  fun streamableHttpEndpoint_withDefaultMcpPath_isTheUrl() {
    assertEquals(
      "https://host.example.com/mcp",
      streamableHttpEndpoint("https://host.example.com/mcp"),
    )
  }

  @Test
  fun streamableHttpEndpoint_withNoPath_isNull() {
    assertNull(streamableHttpEndpoint("http://localhost:1234"))
  }

  @Test
  fun streamableHttpEndpoint_withRootPath_isNull() {
    assertNull(streamableHttpEndpoint("http://localhost:1234/"))
  }

  @Test
  fun streamableHttpEndpoint_withoutAuthority_isNull() {
    // An authority-less base would NPE the SDK's endpoint check, so it must fall back to `/mcp`.
    assertNull(streamableHttpEndpoint("http:/mcp"))
  }

  @Test
  fun streamableHttpEndpoint_withUnparseableUrl_isNull() {
    // A space is illegal in a URI; the check must not throw, it reports no endpoint path.
    assertNull(streamableHttpEndpoint("http://host/a b/mcp"))
  }

  // Feed the ADK-chosen endpoint (falling back to the SDK default) through the SDK's own resolver
  // and
  // assert it reproduces the configured URL -- the actual request target, across the shapes it must
  // preserve. Fails if streamableHttpEndpoint stops honoring the path.
  @Test
  fun streamableHttpEndpoint_resolvesBackToConfiguredUrl() {
    for (url in
      listOf(
        "https://gateway.example.com/tenant/service/mcp",
        "https://host.example.com/mcp",
        "https://host.example.com/mcp?tenant=acme",
        "http://user:pass@localhost:1234/api/mcp",
        "http://[::1]:8080/mcp",
      )) {
      assertEquals(URI(url), resolvedRequestUri(url), "for url=$url")
    }
  }

  // Regression for the host-confusion case: a path beginning with `//` must NOT change the host.
  @Test
  fun streamableHttpEndpoint_withDoubleSlashPath_keepsOriginalHost() {
    val url = "http://good.example.com//evil.example.com/mcp"

    val resolved = resolvedRequestUri(url)

    assertEquals("good.example.com", resolved.host)
    assertEquals(URI(url), resolved)
  }

  private companion object {
    /** The SDK transport's default endpoint, used when [streamableHttpEndpoint] returns `null`. */
    private const val SDK_DEFAULT_ENDPOINT = "/mcp"

    /** Where the transport POSTs: `Utils.resolveUri(base=url, ADK endpoint or the SDK default)`. */
    private fun resolvedRequestUri(url: String): URI =
      Utils.resolveUri(URI(url), streamableHttpEndpoint(url) ?: SDK_DEFAULT_ENDPOINT)
  }
}
