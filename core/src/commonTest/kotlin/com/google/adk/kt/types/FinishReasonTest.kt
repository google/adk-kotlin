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

package com.google.adk.kt.types

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.test.assertEquals
import org.junit.Test

/** Tests for [FinishReason] serialization, including unknown-value robustness. */
@OptIn(FrameworkInternalApi::class)
class FinishReasonTest {

  @Test
  fun serialize_usesEnumName() {
    assertEquals("\"STOP\"", adkJson.encodeToString(FinishReason.serializer(), FinishReason.STOP))
  }

  @Test
  fun deserialize_knownValue_roundTrips() {
    assertEquals(
      FinishReason.MALFORMED_FUNCTION_CALL,
      adkJson.decodeFromString(FinishReason.serializer(), "\"MALFORMED_FUNCTION_CALL\""),
    )
  }

  @Test
  fun deserialize_unknownValue_fallsBackToOther() {
    assertEquals(
      FinishReason.OTHER,
      adkJson.decodeFromString(FinishReason.serializer(), "\"SOME_FUTURE_REASON\""),
    )
  }
}
