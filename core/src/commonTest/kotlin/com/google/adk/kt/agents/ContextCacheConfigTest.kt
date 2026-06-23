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

package com.google.adk.kt.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ContextCacheConfigTest {

  @Test
  fun construct_defaults_usesDocumentedValues() {
    val config = ContextCacheConfig()

    assertEquals(10, config.maxInvocations)
    assertEquals(1800.seconds, config.ttl)
    assertEquals(0, config.minTokens)
  }

  @Test
  fun ttlString_default_formatsSeconds() {
    assertEquals("1800s", ContextCacheConfig().ttlString)
  }

  @Test
  fun ttlString_customTtl_formatsWholeSeconds() {
    assertEquals("60s", ContextCacheConfig(ttl = 60.seconds).ttlString)
  }

  @Test
  fun construct_customValues_exposesProperties() {
    val config = ContextCacheConfig(maxInvocations = 5, ttl = 120.seconds, minTokens = 4096)

    assertEquals(5, config.maxInvocations)
    assertEquals(120.seconds, config.ttl)
    assertEquals(4096, config.minTokens)
  }

  @Test
  fun construct_maxInvocationsTooLow_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { ContextCacheConfig(maxInvocations = 0) }
  }

  @Test
  fun construct_maxInvocationsTooHigh_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { ContextCacheConfig(maxInvocations = 101) }
  }

  @Test
  fun construct_nonPositiveTtl_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { ContextCacheConfig(ttl = 0.seconds) }
  }

  @Test
  fun construct_negativeMinTokens_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { ContextCacheConfig(minTokens = -1) }
  }
}
