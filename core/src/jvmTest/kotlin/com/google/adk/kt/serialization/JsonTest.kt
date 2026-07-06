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

import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.Part
import com.google.gson.Gson
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class JsonTest {

  @Before fun setUp() {}

  /**
   * Plain Kotlin class used to verify Gson's default reflective serialization continues to cover
   * arbitrary user-defined types when ADK calls [Json.toJsonString].
   */
  class TestSerializable(
    val name: String,
    val number: Int,
    val props: Map<String, Any>,
    val items: List<String>,
  )

  @Test
  fun toJsonString_withPlainClass() {
    val obj =
      TestSerializable(
        "test",
        123,
        mapOf("a" to 1, "b" to "two", "c" to 3.0),
        listOf("a", "b", "c"),
      )
    val json = Json.toJsonString(obj)
    val abstractJson = Gson().fromJson(json, Map::class.java)
    assertThat(abstractJson["name"]).isEqualTo("test")
    assertThat(abstractJson["number"]).isEqualTo(123.0)
    assertThat(abstractJson["props"]).isEqualTo(mapOf("a" to 1.0, "b" to "two", "c" to 3.0))
    assertThat(abstractJson["items"]).isEqualTo(listOf("a", "b", "c"))
  }

  @Test
  fun toJsonString_withContent() {
    val content = Content(role = "USER", parts = listOf(Part(text = "hello")))
    val json = Json.toJsonString(content)
    val abstractJson = Gson().fromJson(json, Map::class.java)
    assertThat(abstractJson["role"]).isEqualTo("USER")
    assertThat(abstractJson["parts"]).isEqualTo(listOf(mapOf("text" to "hello")))
  }

  @Test
  fun toJsonString_withNullField() {
    data class Wrapper(val payload: Content?)
    val obj = Wrapper(null)
    val json = Json.toJsonString(obj)
    val abstractJson = Gson().fromJson(json, Map::class.java)
    assertThat(abstractJson["payload"]).isNull()
  }

  @Test
  fun fromJsonToMap_flatObject_returnsParsedMap() {
    val json = """{"name":"John","active":true}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result).containsExactly("name", "John", "active", true)
  }

  @Test
  fun fromJsonToMap_numericValues_returnsDoubles() {
    // Gson's default deserialization of Map<String, Any?> represents all JSON numbers as Doubles,
    // even integral ones. Documenting this so callers can pre-convert if needed.
    val json = """{"age":30,"price":1.5}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["age"]).isEqualTo(30.0)
    assertThat(result["price"]).isEqualTo(1.5)
  }

  @Test
  fun fromJsonToMap_nestedObject_returnsNestedMap() {
    val json = """{"address":{"city":"NYC","zip":"10001"}}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["address"]).isEqualTo(mapOf("city" to "NYC", "zip" to "10001"))
  }

  @Test
  fun fromJsonToMap_arrayValues_returnsList() {
    val json = """{"tags":["a","b","c"]}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["tags"]).isEqualTo(listOf("a", "b", "c"))
  }

  @Test
  fun fromJsonToMap_nullValue_returnsNullEntry() {
    val json = """{"missing":null}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result).containsKey("missing")
    assertThat(result["missing"]).isNull()
  }

  @Test
  fun fromJsonToMap_emptyObject_returnsEmptyMap() {
    val json = "{}"

    val result = Json.fromJsonToMap(json)

    assertThat(result).isEmpty()
  }
}
