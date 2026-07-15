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

package com.google.adk.kt.telemetry

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryConfigTest {

  @AfterTest
  fun tearDown() {
    TelemetryConfig.captureMessageContent = false
  }

  @Test
  fun captureMessageContent_atStartup_defaultsToFalse() {
    assertFalse(TelemetryConfig.captureMessageContent)
  }

  @Test
  fun captureMessageContent_whenSetToTrue_updatesValue() {
    TelemetryConfig.captureMessageContent = true
    assertTrue(TelemetryConfig.captureMessageContent)
  }

  @Test
  fun parseCaptureMessageContent_unset_isFalse() {
    assertFalse(parseCaptureMessageContent(null))
  }

  @Test
  fun parseCaptureMessageContent_true_isTrue() {
    assertTrue(parseCaptureMessageContent("true"))
  }

  @Test
  fun parseCaptureMessageContent_one_isTrue() {
    assertTrue(parseCaptureMessageContent("1"))
  }

  @Test
  fun parseCaptureMessageContent_mixedCaseAndSurroundingWhitespace_isTrue() {
    assertTrue(parseCaptureMessageContent("  TrUe "))
  }

  @Test
  fun parseCaptureMessageContent_false_isFalse() {
    assertFalse(parseCaptureMessageContent("false"))
  }

  @Test
  fun parseCaptureMessageContent_zero_isFalse() {
    assertFalse(parseCaptureMessageContent("0"))
  }

  @Test
  fun parseCaptureMessageContent_blank_isFalse() {
    assertFalse(parseCaptureMessageContent("   "))
  }

  @Test
  fun parseCaptureMessageContent_unrecognizedValue_isFalse() {
    assertFalse(parseCaptureMessageContent("yes"))
  }

  @Test
  fun capturedJson_captureDisabled_returnsEmptyJsonWithoutEvaluatingPayload() {
    TelemetryConfig.captureMessageContent = false
    assertEquals(EMPTY_JSON, capturedJson { throw AssertionError("payload must not be evaluated") })
  }

  @Test
  fun capturedJson_payloadThrows_returnsErrorPlaceholder() {
    TelemetryConfig.captureMessageContent = true
    assertEquals(SERIALIZATION_ERROR_JSON, capturedJson { throw RuntimeException("boom") })
  }
}
