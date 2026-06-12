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

import com.google.adk.kt.serialization.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json as KotlinxJson

/** JVM behavior of the non-`@Serializable` fallback in `Json.serializeToJsonElement` (Gson). */
class JsonSerializationFallbackJvmTest {

  // Not `@Serializable`, so the platform fallback (Gson on JVM) is used.
  private data class Unannotated(val v: Int)

  @Test
  fun nonSerializableType_reflectsToJsonObjectViaGson() {
    // On JVM the Gson-backed fallback reflects the data class into a structured JSON object.
    assertEquals(
      KotlinxJson.parseToJsonElement("""{"v":7}"""),
      Json.serializeToJsonElement(Unannotated(7)),
    )
  }
}
