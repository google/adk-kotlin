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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VertexAiUtilsTest {

  @Test
  fun getExpressModeApiKey_notEnterpriseMode_isNullEvenWithAKey() {
    // Express Mode is a way of reaching Vertex, so a process pointed at the Gemini API has nothing
    // for the key to address.
    assertNull(
      VertexAiUtils.getExpressModeApiKey(
        project = null,
        location = null,
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = false,
        apiKeyFromEnvironment = "environment-key",
      )
    )
  }

  @Test
  fun getExpressModeApiKey_enterpriseModeAndCallerKey_prefersTheCallerKey() {
    assertEquals(
      "caller-key",
      VertexAiUtils.getExpressModeApiKey(
        project = null,
        location = null,
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = "environment-key",
      ),
    )
  }

  @Test
  fun getExpressModeApiKey_enterpriseModeAndNoCallerKey_fallsBackToTheEnvironment() {
    assertEquals(
      "environment-key",
      VertexAiUtils.getExpressModeApiKey(
        project = null,
        location = null,
        expressModeApiKey = null,
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = "environment-key",
      ),
    )
  }

  @Test
  fun getExpressModeApiKey_enterpriseModeAndNoKeyAnywhere_isNull() {
    assertNull(
      VertexAiUtils.getExpressModeApiKey(
        project = null,
        location = null,
        expressModeApiKey = null,
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = null,
      )
    )
  }

  @Test
  fun getExpressModeApiKey_projectAndLocationWithNoKey_stillReadsTheEnvironment() {
    // The fallback deliberately does not look at project or location, which is what the Python ADK
    // answers for the same inputs.
    assertEquals(
      "environment-key",
      VertexAiUtils.getExpressModeApiKey(
        project = "a-project",
        location = "a-location",
        expressModeApiKey = null,
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = "environment-key",
      ),
    )
  }

  @Test
  fun getExpressModeApiKey_projectAlongsideAKey_isRejected() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiUtils.getExpressModeApiKey(
        project = "a-project",
        location = null,
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = null,
      )
    }
  }

  @Test
  fun getExpressModeApiKey_locationAlongsideAKey_isRejected() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiUtils.getExpressModeApiKey(
        project = null,
        location = "a-location",
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = null,
      )
    }
  }

  @Test
  fun getExpressModeApiKey_blankProjectAlongsideAKey_isRejected() {
    // A value counts as given whenever it is non-null; Python instead tests for truthiness here,
    // and would read an empty project as no project at all.
    assertFailsWith<IllegalArgumentException> {
      VertexAiUtils.getExpressModeApiKey(
        project = "",
        location = null,
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = true,
        apiKeyFromEnvironment = null,
      )
    }
  }

  @Test
  fun getExpressModeApiKey_bothFormsOfAddressing_isRejectedOutsideEnterpriseModeToo() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiUtils.getExpressModeApiKey(
        project = "a-project",
        location = "a-location",
        expressModeApiKey = "caller-key",
        enterpriseModeEnabled = false,
        apiKeyFromEnvironment = null,
      )
    }
  }
}
