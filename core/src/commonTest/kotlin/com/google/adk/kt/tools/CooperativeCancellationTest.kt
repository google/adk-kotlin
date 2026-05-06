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

package com.google.adk.kt.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class CooperativeCancellationTest {

  @Test
  fun ensureActive_cancelledCoroutine_throwsCancellationException() = runTest {
    val tool =
      object : BaseTool("test_tool", "Test description") {
        override fun declaration() = null

        override suspend fun run(context: ToolContext, args: Map<String, Any>) = Unit
      }
    var reachedAfter = false
    val job = launch {
      cancel()
      tool.ensureActive()
      reachedAfter = true
    }

    job.join()

    assertFalse(reachedAfter)
    assertTrue(job.isCancelled)
  }

  @Test
  fun timeout_defaultProperty_isInfinite() {
    val tool =
      object : BaseTool("test", "description") {
        override fun declaration() = null

        override suspend fun run(context: ToolContext, args: Map<String, Any>) = Unit
      }

    val actualTimeout = tool.timeout

    assertEquals(Duration.INFINITE, actualTimeout)
  }

  @Test
  fun timeout_setProperty_returnsSetValue() {
    val expectedTimeout = 10.seconds
    val tool =
      object : BaseTool("test", "description") {
        override fun declaration() = null
        override val timeout = expectedTimeout

        override suspend fun run(context: ToolContext, args: Map<String, Any>) = Unit
      }

    assertEquals(expectedTimeout, tool.timeout)
  }

  @Test
  fun isLongRunning_defaultProperty_isFalse() {
    val tool =
      object : BaseTool("test", "description") {
        override fun declaration() = null

        override suspend fun run(context: ToolContext, args: Map<String, Any>) = Unit
      }

    assertEquals(false, tool.isLongRunning)
  }

  @Test
  fun isLongRunning_setProperty_isTrue() {
    val tool =
      object : BaseTool("test", "description") {
        override fun declaration() = null
        override val isLongRunning = true

        override suspend fun run(context: ToolContext, args: Map<String, Any>) = Unit
      }

    assertEquals(true, tool.isLongRunning)
  }
}
