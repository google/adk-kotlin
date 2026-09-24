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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.microseconds

class HttpOptionsTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val options =
      HttpOptions(
        baseUrl = "https://example.com",
        apiVersion = "v1",
        headers = mapOf("X-Test" to "1"),
        // Sub-millisecond, so a copy made through timeoutMillis would not match.
        timeout = 1500.microseconds,
      )

    assertEquals(options.copy(), options.toBuilder().build())
    assertEquals(options.copy(apiVersion = "v2"), options.toBuilder().apiVersion("v2").build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_nullTimeoutMillis_matchesCopy() {
    val options = HttpOptions(baseUrl = "https://example.com", timeout = 1500.microseconds)

    assertEquals(options.copy(timeout = null), options.toBuilder().timeoutMillis(null).build())
  }
}
