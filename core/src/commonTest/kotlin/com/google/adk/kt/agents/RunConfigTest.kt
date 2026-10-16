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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Opts in to the Java-interop builders under test and to the experimental live settings.
@OptIn(AdkJavaInteropApi::class, ExperimentalLiveApi::class)
class RunConfigTest {

  @Test
  fun testRunConfigDefaults() {
    val config = RunConfig()
    assertEquals(StreamingMode.NONE, config.streamingMode)
    assertEquals(500, config.maxLlmCalls)
    assertEquals(null, config.customMetadata)
    assertEquals(LiveRunConfig(), config.live)
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

  @Test
  fun toBuilder_matchesCopy() {
    val config =
      RunConfig(
        streamingMode = StreamingMode.SSE,
        maxLlmCalls = 42,
        customMetadata = mapOf("key" to "value"),
        live = LiveRunConfig(saveLiveBlob = true),
      )

    assertEquals(config.copy(), config.toBuilder().build())
    assertEquals(config.copy(maxLlmCalls = 7), config.toBuilder().maxLlmCalls(7).build())
  }

  @Test
  fun defaults_toString_enumeratesEveryProperty() {
    // A tripwire for new RunConfig properties; the nested live text is derived.
    assertEquals(
      "RunConfig(streamingMode=NONE, maxLlmCalls=500, customMetadata=null, live=${LiveRunConfig()})",
      RunConfig().toString(),
    )
  }

  @Test
  fun builder_noPropertySet_matchesConstructorDefaults() {
    // Data-class equality compares every property, so one assertion catches a drift.
    assertEquals(RunConfig(), RunConfig.builder().build())
  }

  @Test
  fun builder_everyPropertySet_matchesConstructor() {
    // Enumerated by hand because reflection is banned repo-wide; non-defaults catch drops.
    val customMetadata = mapOf<String, Any>("key" to "value")
    val live = LiveRunConfig(saveLiveBlob = true)

    val built =
      RunConfig.builder()
        .streamingMode(StreamingMode.BIDI)
        .maxLlmCalls(7)
        .customMetadata(customMetadata)
        .live(live)
        .build()

    assertEquals(
      RunConfig(
        streamingMode = StreamingMode.BIDI,
        maxLlmCalls = 7,
        customMetadata = customMetadata,
        live = live,
      ),
      built,
    )
  }
}
