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

package com.google.adk.kt.plugins.agentanalytics

import com.google.adk.kt.annotations.AdkJavaInteropApi
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryConfigTest {

  @Test
  fun defaults_matchPythonAdk() {
    assertEquals(
      RetryConfig(
        maxRetries = 3,
        initialDelay = Duration.ofSeconds(1),
        multiplier = 2.0,
        maxDelay = Duration.ofSeconds(10),
      ),
      RetryConfig(),
    )
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_defaultsMatchTheConstructor() {
    assertEquals(RetryConfig(), RetryConfig.builder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val config =
      RetryConfig(
        maxRetries = 5,
        initialDelay = Duration.ofMillis(250),
        multiplier = 1.5,
        maxDelay = Duration.ofSeconds(30),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(maxRetries = 0), config.toBuilder().maxRetries(0).build())
  }

  @Test
  fun zeroDelays_areAccepted() {
    val config = RetryConfig(maxRetries = 0, initialDelay = Duration.ZERO, maxDelay = Duration.ZERO)

    assertEquals(Duration.ZERO, config.maxDelay)
  }

  @Test
  fun invalidValues_areRejected() {
    assertFailsWith<IllegalArgumentException> { RetryConfig(maxRetries = -1) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(initialDelay = Duration.ofMillis(-1)) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(multiplier = 0.5) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(multiplier = Double.NaN) }
    assertFailsWith<IllegalArgumentException> { RetryConfig(multiplier = Double.POSITIVE_INFINITY) }
    assertFailsWith<IllegalArgumentException> {
      RetryConfig(initialDelay = Duration.ofSeconds(5), maxDelay = Duration.ofSeconds(1))
    }
  }
}
