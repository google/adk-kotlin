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

package com.google.adk.kt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelNameUtilsTest {

  @Test
  fun extractModelName_bareName_isUnchanged() {
    assertEquals("gemini-2.5-flash", ModelNameUtils.extractModelName("gemini-2.5-flash"))
  }

  @Test
  fun extractModelName_vertexPublisherPath_isUnwrapped() {
    assertEquals(
      "gemini-2.5-pro",
      ModelNameUtils.extractModelName(
        "projects/my-project/locations/us-central1/publishers/google/models/gemini-2.5-pro"
      ),
    )
  }

  @Test
  fun extractModelName_apigeePath_isUnwrappedAtAnyDepth() {
    assertEquals("gemini-2.5-flash", ModelNameUtils.extractModelName("apigee/gemini-2.5-flash"))
    assertEquals(
      "gemini-2.5-flash",
      ModelNameUtils.extractModelName("apigee/prod/proxy/gemini-2.5-flash"),
    )
  }

  @Test
  fun extractModelName_modelsPrefix_isStripped() {
    assertEquals("gemini-2.5-pro", ModelNameUtils.extractModelName("models/gemini-2.5-pro"))
  }

  @Test
  fun extractModelName_projectsPathThatIsNotAPublisherModel_comesBackWhole() {
    // A tuned-model endpoint. Its last segment is not a model id, so returning it whole is what
    // stops the provider-prefix step below from reading one out of it.
    val tuned = "projects/my-project/locations/us-central1/models/1234567890"
    assertEquals(tuned, ModelNameUtils.extractModelName(tuned))
  }

  @Test
  fun extractModelName_providerPrefixedGeminiName_isUnwrapped() {
    assertEquals("gemini-2.5-flash", ModelNameUtils.extractModelName("gemini/gemini-2.5-flash"))
    assertEquals(
      "gemini-2.5-pro:online",
      ModelNameUtils.extractModelName("openrouter/google/gemini-2.5-pro:online"),
    )
  }

  @Test
  fun extractModelName_anotherProvidersName_comesBackWhole() {
    assertEquals("openai/gpt-4o", ModelNameUtils.extractModelName("openai/gpt-4o"))
  }

  @Test
  fun isGeminiModel_seesThroughEveryWrappedForm() {
    assertTrue(ModelNameUtils.isGeminiModel("gemini-2.5-flash"))
    assertTrue(ModelNameUtils.isGeminiModel("models/gemini-2.5-pro"))
    assertTrue(ModelNameUtils.isGeminiModel("gemini/gemini-2.5-flash"))
    assertTrue(
      ModelNameUtils.isGeminiModel(
        "projects/my-project/locations/us-central1/publishers/google/models/gemini-2.5-pro"
      )
    )
  }

  @Test
  fun isGeminiModel_anotherProvidersName_isFalse() {
    assertFalse(ModelNameUtils.isGeminiModel("openai/gpt-4o"))
    assertFalse(ModelNameUtils.isGeminiModel("claude-3-5-sonnet"))
  }

  @Test
  fun isGeminiModel_tunedModelEndpoint_isFalse() {
    assertFalse(
      ModelNameUtils.isGeminiModel("projects/my-project/locations/us-central1/models/1234567890")
    )
  }

  @Test
  fun isGeminiModel_nullOrEmpty_isFalse() {
    assertFalse(ModelNameUtils.isGeminiModel(null))
    assertFalse(ModelNameUtils.isGeminiModel(""))
  }
}
