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

package com.google.adk.kt.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KotlinxJson

class SerializeToJsonElementTest {

  @Serializable private data class Point(val x: Int, val y: Int, val label: String? = null)

  @Test
  fun serializableType_emitsJsonObject() {
    // `@Serializable` -> structured JSON object via kotlinx.serialization (JVM and Android).
    assertEquals(
      KotlinxJson.parseToJsonElement("""{"x":1,"y":2}"""),
      Json.serializeToJsonElement(Point(1, 2)),
    )
  }

  @Test
  fun serializableType_omitsNullProperties() {
    // `explicitNulls = false`: a null property (`label`) is dropped from the encoded object.
    assertEquals(
      KotlinxJson.parseToJsonElement("""{"x":1,"y":2}"""),
      Json.serializeToJsonElement(Point(1, 2, label = null)),
    )
  }
}
