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

import com.google.gson.Gson

/** JVM implementation of JSON serialization utility using Gson. */
private object JvmJson : Json {
  private val gson = Gson()

  override fun toJsonString(obj: Any?): String {
    return gson.toJson(obj)
  }

  override fun fromJsonToMap(json: String): Map<String, Any?> {
    return gson.fromJson(
      json,
      object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type,
    )
  }
}

internal actual fun getJson(): Json = JvmJson
