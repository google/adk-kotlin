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

import com.google.genai.kotlin.types.BlockedReason as SdkBlockedReason
import com.google.genai.kotlin.types.FinishReason as SdkFinishReason
import com.google.genai.kotlin.types.ModelRoutingPreference as SdkModelRoutingPreference
import com.google.genai.kotlin.types.ThinkingLevel as SdkThinkingLevel
import com.google.genai.kotlin.types.Type as SdkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round-trips every ADK enum through the GenAI SDK and checks the unknown-value fallbacks. */
class EnumConvertersTest {

  @Test
  fun blockedReason_roundTripsThroughSdk() {
    for (value in BlockedReason.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun finishReason_roundTripsThroughSdk() {
    for (value in FinishReason.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun thinkingLevel_roundTripsThroughSdk() {
    for (value in ThinkingLevel.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun type_roundTripsThroughSdk() {
    for (value in Type.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun modelRoutingPreference_roundTripsThroughSdk() {
    for (value in ModelRoutingPreference.entries) {
      assertEquals(value, value.toGenaiSdk().toKt())
    }
  }

  @Test
  fun toKt_unknownSdkValue_fallsBackToDefault() {
    // An SDK value with no matching ADK constant degrades to the catch-all (or null for `Type`)
    // rather than throwing.
    assertEquals(BlockedReason.OTHER, SdkBlockedReason("UNRECOGNIZED").toKt())
    assertEquals(FinishReason.OTHER, SdkFinishReason("UNRECOGNIZED").toKt())
    assertEquals(ThinkingLevel.THINKING_LEVEL_UNSPECIFIED, SdkThinkingLevel("UNRECOGNIZED").toKt())
    assertEquals(ModelRoutingPreference.UNKNOWN, SdkModelRoutingPreference("UNRECOGNIZED").toKt())
    assertNull(SdkType("UNRECOGNIZED").toKt())
  }

  @Test
  fun blockedReason_toFinishReason_mapsKnownReasons() {
    assertEquals(FinishReason.SAFETY, BlockedReason.SAFETY.toFinishReason())
    assertEquals(FinishReason.BLOCKLIST, BlockedReason.BLOCKLIST.toFinishReason())
    assertEquals(FinishReason.PROHIBITED_CONTENT, BlockedReason.PROHIBITED_CONTENT.toFinishReason())
    assertEquals(FinishReason.IMAGE_SAFETY, BlockedReason.IMAGE_SAFETY.toFinishReason())
    // A BlockedReason with no FinishReason equivalent falls back to OTHER.
    assertEquals(FinishReason.OTHER, BlockedReason.MODEL_ARMOR.toFinishReason())
  }
}
