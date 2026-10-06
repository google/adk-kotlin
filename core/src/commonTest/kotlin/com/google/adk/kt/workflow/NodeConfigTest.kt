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

@file:OptIn(ExperimentalWorkflowApi::class, AdkJavaInteropApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

class NodeConfigTest {

  @Test
  fun toBuilder_matchesCopy() {
    val config = NodeConfig(retryConfig = RetryConfig(maxAttempts = 2), timeout = 1500.microseconds)

    assertEquals(config.copy(), config.toBuilder().build())
  }

  @Test
  fun builder_setsTimeoutInMillis() {
    val retry = RetryConfig(maxAttempts = 3)

    val config = NodeConfig.builder().retryConfig(retry).timeoutMillis(30_000).build()

    assertEquals(NodeConfig(retryConfig = retry, timeout = 30.seconds), config)
    assertEquals(30_000L, config.timeoutMillis())
    assertEquals(NodeConfig(), NodeConfig.builder().build())
  }

  @Test
  fun builder_clearsTimeoutWithNullMillis() {
    val cleared = NodeConfig(timeout = 30.seconds).toBuilder().timeoutMillis(null).build()

    assertEquals(NodeConfig(), cleared)
    assertNull(cleared.timeoutMillis())
  }

  @Test
  fun builder_rejectsANonPositiveTimeout() {
    assertFailsWith<IllegalArgumentException> { NodeConfig.builder().timeoutMillis(0).build() }
  }
}
