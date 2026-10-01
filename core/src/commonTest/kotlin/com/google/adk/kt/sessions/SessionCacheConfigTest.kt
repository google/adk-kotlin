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

package com.google.adk.kt.sessions

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class SessionCacheConfigTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetProperties_matchConstructorDefaults() {
    assertEquals(SessionCacheConfig(), SessionCacheConfig.builder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val config = SessionCacheConfig(ttl = 1500.microseconds, maxSessions = 7)

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(maxSessions = 3), config.toBuilder().maxSessions(3).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun ttlMillis_maxValue_meansNoExpiry() {
    val config = SessionCacheConfig.builder().ttlMillis(Long.MAX_VALUE).build()

    assertEquals(Duration.INFINITE, config.ttl)
    assertEquals(Long.MAX_VALUE, config.ttlMillis())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun ttlMillis_convertsMilliseconds() {
    assertEquals(1500.milliseconds, SessionCacheConfig.builder().ttlMillis(1500).build().ttl)
    assertEquals(1500L, SessionCacheConfig(ttl = 1500.milliseconds).ttlMillis())
  }

  @Test
  fun init_nonPositiveTtl_throws() {
    assertFailsWith<IllegalArgumentException> { SessionCacheConfig(ttl = Duration.ZERO) }
  }

  @Test
  fun init_noSessions_throws() {
    assertFailsWith<IllegalArgumentException> { SessionCacheConfig(maxSessions = 0) }
  }
}
