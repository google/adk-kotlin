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
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class McpConnectionParametersTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun streamableHttp_toBuilder_matchesCopy() {
    val params =
      McpConnectionParameters.StreamableHttp(
        url = "http://localhost:5678",
        headers = mapOf("X-Test" to "1"),
        timeout = Duration.ofSeconds(7),
        readTimeout = Duration.ofMinutes(9),
      )

    assertEquals(params.copy(), params.toBuilder().build())
    assertEquals(
      params.copy(url = "http://localhost:9999"),
      params.toBuilder().url("http://localhost:9999").build(),
    )
  }
}
