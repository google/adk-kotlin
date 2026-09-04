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

import com.google.adk.kt.tools.Toolset
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration

class McpToolsetConfigTest {
  @Test
  fun createsPortableToolsetForStreamableHttp() {
    val toolset =
      McpToolsetConfig(
          transport = McpTransportConfig.StreamableHttp("https://example.test/mcp"),
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
    McpTransportConfig.StreamableHttp(
      "http://example.test/mcp",
      allowInsecureHttp = true,
    )
  }
}
