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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

class RemoteMcpToolsetConfigTest {
  @Test
  fun createsPortableToolsetForStreamableHttp() {
    val toolset =
      RemoteMcpToolsetConfig(
          transport = McpTransportConfig.StreamableHttp("https://example.test/mcp")
        )
        .toToolset()

    assertIs<Toolset>(toolset)
    toolset.close()
  }

  @Test
  fun rejectsNonPositivePortableTimeouts() {
    assertFailsWith<IllegalArgumentException> {
      McpTransportConfig.StreamableHttp("https://example.test/mcp", connectTimeout = Duration.ZERO)
    }
    assertFailsWith<IllegalArgumentException> {
      McpTransportConfig.StreamableHttp("https://example.test/mcp", requestTimeout = Duration.ZERO)
    }
  }

  @Test
  fun requiresExplicitOptInForInsecureHttpOnEveryPlatform() {
    assertFailsWith<IllegalArgumentException> {
      McpTransportConfig.StreamableHttp("http://example.test/mcp")
    }
    McpTransportConfig.StreamableHttp("http://example.test/mcp", allowInsecureHttp = true)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun javaBuilders_setTimeoutsInMilliseconds() {
    val transport =
      McpTransportConfig.StreamableHttp.builder()
        .url("https://example.test/mcp")
        .connectTimeoutMillis(1_000)
        .requestTimeoutMillis(2_000)
        .build()
    val config = RemoteMcpToolsetConfig.builder().transport(transport).useMcpResources(true).build()

    assertEquals(1.seconds, transport.connectTimeout)
    assertEquals(2.seconds, transport.requestTimeout)
    assertEquals(true, config.useMcpResources)
    val rebuilt = config.toBuilder().build()
    assertEquals(transport.url, (rebuilt.transport as McpTransportConfig.StreamableHttp).url)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val transport =
      McpTransportConfig.StreamableHttp(
        url = "https://gateway.example.test/tenant/mcp",
        headers = mapOf("X-Test" to "1"),
        connectTimeout = 500.microseconds,
        requestTimeout = 1500.microseconds,
        allowInsecureHttp = true,
      )
    val config =
      RemoteMcpToolsetConfig(
        transport = transport,
        toolFilter = ToolFilter.allowList("echo"),
        useMcpResources = true,
        maxMcpResourceLength = 42,
      )

    assertEquals(transport.copy(), transport.toBuilder().build())
    assertEquals(config.copy(), config.toBuilder().build())
  }

  @Test
  fun streamableHttp_urlWithoutPath_usesDefaultMcpEndpoint() {
    assertEquals(
      "https://host.example.com/mcp",
      streamableHttpUrlWithDefaultPath("https://host.example.com"),
    )
    assertEquals(
      "https://host.example.com/mcp",
      streamableHttpUrlWithDefaultPath("https://host.example.com/"),
    )
    assertEquals(
      "https://host.example.com/mcp?token=1",
      streamableHttpUrlWithDefaultPath("https://host.example.com?token=1"),
    )
    assertEquals(
      "https://host.example.com/tenant/service/mcp",
      streamableHttpUrlWithDefaultPath("https://host.example.com/tenant/service/mcp"),
    )
  }
}
