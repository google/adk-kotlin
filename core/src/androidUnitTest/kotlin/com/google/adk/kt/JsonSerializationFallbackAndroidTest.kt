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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.serialization.Json
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android behavior of the non-`@Serializable` fallback in `Json.serializeToJsonElement` (org.json).
 */
@RunWith(AndroidJUnit4::class)
class JsonSerializationFallbackAndroidTest {

  // Not `@Serializable`, so the platform fallback (org.json on Android) is used.
  private data class Unannotated(val v: Int)

  @Test
  fun nonSerializableType_stringifiesViaOrgJson() {
    // org.json cannot reflect, so the value is stringified via `toString()`.
    assertEquals(JsonPrimitive("Unannotated(v=7)"), Json.serializeToJsonElement(Unannotated(7)))
  }
}
