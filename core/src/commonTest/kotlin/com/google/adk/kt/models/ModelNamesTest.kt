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
package com.google.adk.kt.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests model-name parsing and the live-model predicate.
 *
 * Three connect-time settings branch on [isGemini3XLive] in `BasicRequestProcessor` (the response
 * modalities, affective dialog and proactivity), so a wrong answer here reaches the wire as a
 * rejected setup rather than surfacing as a parsing bug.
 */
class ModelNamesTest {

  @Test
  fun extractModelName_plainName_returnsItUnchanged() {
    assertThat(extractModelName("gemini-3.0-flash-live")).isEqualTo("gemini-3.0-flash-live")
  }

  @Test
  fun extractModelName_vertexResourcePath_returnsTheModelId() {
    assertThat(
        extractModelName(
          "projects/p/locations/us-central1/publishers/google/models/gemini-3.0-live"
        )
      )
      .isEqualTo("gemini-3.0-live")
  }

  @Test
  fun extractModelName_apigeePath_returnsTheModelId() {
    assertThat(extractModelName("apigee/org/env/gemini-3.0-live")).isEqualTo("gemini-3.0-live")
  }

  @Test
  fun extractModelName_modelsPrefix_stripsIt() {
    assertThat(extractModelName("models/gemini-3.0-live")).isEqualTo("gemini-3.0-live")
  }

  @Test
  fun extractModelName_malformedProjectsPath_returnsItWhole() {
    // Returning the last segment here would read "locations" as a model id.
    assertThat(extractModelName("projects/p/locations")).isEqualTo("projects/p/locations")
  }

  @Test
  fun extractModelName_modelsPrefixWithNonGeminiName_stripsIt() {
    // Isolates the `models/` branch: the provider-prefix rule only unwraps a Gemini last segment.
    assertThat(extractModelName("models/text-embedding-004")).isEqualTo("text-embedding-004")
  }

  @Test
  fun extractModelName_malformedProjectsPathEndingInGemini_returnsItWhole() {
    // Isolates the `projects/` branch: without it the provider-prefix rule would unwrap the Gemini
    // last segment, matching ADK Python's extract_model_name, which returns this whole.
    assertThat(extractModelName("projects/p/locations/l/models/gemini-2.5-flash"))
      .isEqualTo("projects/p/locations/l/models/gemini-2.5-flash")
  }

  @Test
  fun extractModelName_providerPrefixedGemini_returnsTheModelId() {
    assertThat(extractModelName("gemini/gemini-3.0-live")).isEqualTo("gemini-3.0-live")
    assertThat(extractModelName("openrouter/google/gemini-3.0-live")).isEqualTo("gemini-3.0-live")
  }

  @Test
  fun extractModelName_providerPrefixedNonGemini_returnsItUnchanged() {
    assertThat(extractModelName("openrouter/meta/llama-3")).isEqualTo("openrouter/meta/llama-3")
  }

  @Test
  fun isGemini3XLive_gemini3LiveModel_isTrue() {
    assertThat(isGemini3XLive("gemini-3.0-flash-live")).isTrue()
  }

  @Test
  fun isGemini3XLive_gemini3LiveModelAsResourcePath_isTrue() {
    assertThat(
        isGemini3XLive("projects/p/locations/global/publishers/google/models/gemini-3.0-flash-live")
      )
      .isTrue()
  }

  @Test
  fun isGemini3XLive_gemini3NonLiveModel_isFalse() {
    assertThat(isGemini3XLive("gemini-3.0-flash")).isFalse()
  }

  @Test
  fun isGemini3XLive_gemini2LiveModel_isFalse() {
    assertThat(isGemini3XLive("gemini-2.5-flash-live")).isFalse()
  }

  @Test
  fun isGemini3XLive_liveTranslateModel_isFalse() {
    // 3.5, and live, but it behaves like the earlier models, so it must not take the 3.x branches.
    assertThat(isGemini3XLive("gemini-3.5-live-translate")).isFalse()
    assertThat(isGemini3XLive("gemini-3.5-live-translate-preview")).isFalse()
  }

  @Test
  fun isGemini3XLive_versionWithoutADot_isFalse() {
    assertThat(isGemini3XLive("gemini-30-flash-live")).isFalse()
  }

  @Test
  fun isGemini3XLive_nullOrEmpty_isFalse() {
    assertThat(isGemini3XLive(null)).isFalse()
    assertThat(isGemini3XLive("")).isFalse()
  }

  @Test
  fun isGemini35LiveTranslate_translateModel_isTrue() {
    assertThat(isGemini35LiveTranslate("gemini-3.5-live-translate")).isTrue()
  }

  @Test
  fun isGemini35LiveTranslate_otherLiveModel_isFalse() {
    assertThat(isGemini35LiveTranslate("gemini-3.0-flash-live")).isFalse()
  }
}
