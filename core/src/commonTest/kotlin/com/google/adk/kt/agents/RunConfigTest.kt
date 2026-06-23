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

class RunConfigTest {

  @Test
  fun testRunConfigDefaults() {
    val config = RunConfig()
    assertEquals(StreamingMode.NONE, config.streamingMode)
    assertEquals(500, config.maxLlmCalls)
    assertEquals(null, config.customMetadata)
  }

  @Test
  fun maxLlmCalls_customValue_isRetained() {
    assertEquals(42, RunConfig(maxLlmCalls = 42).maxLlmCalls)
  }

  @Test
  fun maxLlmCalls_intMaxValue_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { RunConfig(maxLlmCalls = Int.MAX_VALUE) }
  }

  @Test
  fun maxLlmCalls_nonPositiveValue_isAllowedAndDisablesEnforcement() {
    // Non-positive values are valid (a warning is logged) and disable the cap.
    assertEquals(0, RunConfig(maxLlmCalls = 0).maxLlmCalls)
    assertEquals(-1, RunConfig(maxLlmCalls = -1).maxLlmCalls)
  }
}
