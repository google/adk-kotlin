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

import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializerOrNull

/** Platform-independent utility for JSON serialization. */
interface Json {
  /** Serializes an object to a JSON string. */
  fun toJsonString(obj: Any?): String

  /** Parses a JSON string to a map. */
  fun fromJsonToMap(json: String): Map<String, Any?>

  companion object : Json by getJson() {
    private val KMP_JSON = kotlinx.serialization.json.Json { explicitNulls = false }

    /**
     * Converts [value] to a [JsonElement]: kotlinx.serialization for `@Serializable` types,
     * otherwise the platform [Json] fallback (best-effort, not round-trippable).
     */
    @OptIn(InternalSerializationApi::class)
    internal fun serializeToJsonElement(value: Any): JsonElement {
      @Suppress("UNCHECKED_CAST") val serializer = (value::class as KClass<Any>).serializerOrNull()
      return if (serializer != null) {
        KMP_JSON.encodeToJsonElement(serializer, value)
      } else {
        KMP_JSON.parseToJsonElement(toJsonString(value))
      }
    }
  }
}

internal expect fun getJson(): Json
