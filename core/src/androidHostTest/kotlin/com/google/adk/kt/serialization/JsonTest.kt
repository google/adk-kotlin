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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonTest {

  @Test
  fun fromJsonToMap_flatObject_returnsParsedMap() {
    val json = """{"name":"John","active":true}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result).containsExactly("name", "John", "active", true)
  }

  @Test
  fun fromJsonToMap_integerValue_returnsInt() {
    // Android's org.json preserves integral values as Int (unlike Gson on JVM, which decodes
    // every JSON number as Double).
    val json = """{"age":30}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["age"]).isEqualTo(30)
  }

  @Test
  fun fromJsonToMap_doubleValue_returnsDouble() {
    val json = """{"price":1.5}"""

    val result = Json.fromJsonToMap(json)

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
  fun fromJsonToMap_arrayOfObjects_returnsListOfMaps() {
    val json = """{"items":[{"id":1},{"id":2}]}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["items"]).isEqualTo(listOf(mapOf("id" to 1), mapOf("id" to 2)))
  }

  @Test
  fun fromJsonToMap_nestedArray_returnsNestedList() {
    val json = """{"matrix":[[1,2],[3,4]]}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["matrix"]).isEqualTo(listOf(listOf(1, 2), listOf(3, 4)))
  }

  @Test
  fun fromJsonToMap_nullValue_returnsNullEntry() {
    val json = """{"missing":null}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result).containsKey("missing")
    assertThat(result["missing"]).isNull()
  }

  @Test
  fun fromJsonToMap_nullInArray_returnsListWithNull() {
    val json = """{"items":[1,null,2]}"""

    val result = Json.fromJsonToMap(json)

    assertThat(result["items"]).isEqualTo(listOf(1, null, 2))
  }

  @Test
  fun fromJsonToMap_emptyObject_returnsEmptyMap() {
    val json = "{}"

    val result = Json.fromJsonToMap(json)

    assertThat(result).isEmpty()
  }
}
