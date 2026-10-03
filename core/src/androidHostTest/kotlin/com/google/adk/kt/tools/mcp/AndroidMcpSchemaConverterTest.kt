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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.types.Type
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class AndroidMcpSchemaConverterTest {
  private val json = Json

  @Test
  fun preservesNestedConstraints() {
    val schema =
      ToolSchema(
        properties =
          jsonObject(
            """{"place":{"type":"object","properties":{"city":{"type":"string","pattern":"[A-Z]+","minLength":2},"limit":{"type":"integer","minimum":1,"maximum":10}},"required":["city","absent"]},"ignored":"not-a-schema"}"""
          ),
        required = listOf("place", "missing"),
      )

    val converted = schema.toAdkSchema()
    val properties = requireNotNull(converted.properties)
    val place = requireNotNull(properties["place"])
    val placeProperties = requireNotNull(place.properties)
    assertEquals(Type.OBJECT, converted.type)
    assertEquals(listOf("place"), converted.required)
    assertEquals(listOf("city"), place.required)
    assertEquals("[A-Z]+", placeProperties["city"]?.pattern)
    assertEquals(2, placeProperties["city"]?.minLength)
    assertEquals(1.0, placeProperties["limit"]?.minimum)
    assertEquals(10.0, placeProperties["limit"]?.maximum)
  }

  @Test
  fun convertsNullableAndUnionSchemas() {
    val schema =
      ToolSchema(
        properties =
          jsonObject(
            """{"optional":{"type":["integer","null"],"default":3},"choice":{"anyOf":[{"type":"string"},{"type":"integer"}]}}"""
          )
      )

    val converted = schema.toAdkSchema()
    val properties = requireNotNull(converted.properties)
    val optional = requireNotNull(properties["optional"])
    val choice = requireNotNull(properties["choice"])
    assertEquals(Type.INTEGER, optional.type)
    assertTrue(optional.nullable == true)
    assertEquals(3, optional.default)
    assertEquals(listOf(Type.STRING, Type.INTEGER), choice.anyOf?.map { it.type })
  }

  @Test
  fun suppliesArrayItemsAndSanitizesProviderSensitiveFields() {
    val schema =
      ToolSchema(
        properties =
          jsonObject(
            """{"tags":{"type":"array","minItems":1,"maxItems":3},"when":{"type":"string","format":"uri"},"at":{"type":"string","format":"date-time"}}"""
          )
      )

    val converted = schema.toAdkSchema()
    val properties = requireNotNull(converted.properties)
    val tags = requireNotNull(properties["tags"])
    assertEquals(Type.STRING, tags.items?.type)
    assertEquals(1, tags.minItems)
    assertEquals(3, tags.maxItems)
    assertNull(properties["when"]?.format)
    assertEquals("date-time", properties["at"]?.format)
  }

  @Test
  fun ignoresMalformedKeywordValuesInsteadOfRejectingTheToolSchema() {
    val schema =
      ToolSchema(
        properties =
          jsonObject(
            """{"value":{"type":"string","description":{"unexpected":true},"pattern":42,"minLength":{},"default":{"nested":"value"}}}"""
          )
      )

    val properties = requireNotNull(schema.toAdkSchema().properties)
    val converted = requireNotNull(properties["value"])

    assertEquals(Type.STRING, converted.type)
    assertNull(converted.description)
    assertNull(converted.pattern)
    assertNull(converted.minLength)
    assertEquals(mapOf("nested" to "value"), converted.default)
  }

  @Test
  fun mapsLocalDefsThroughToolSchemaDefs() {
    val schema =
      ToolSchema(
        properties = jsonObject("""{"place":{"${'$'}ref":"#/${'$'}defs/Place"}}"""),
        defs =
          jsonObject(
            """{"Place":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}"""
          ),
      )

    val properties = requireNotNull(schema.toAdkSchema().properties)
    val place = requireNotNull(properties["place"])
    val placeProperties = requireNotNull(place.properties)
    assertEquals(Type.OBJECT, place.type)
    assertEquals(Type.STRING, placeProperties["city"]?.type)
    assertEquals(listOf("city"), place.required)
  }

  private fun jsonObject(source: String) = json.parseToJsonElement(source).jsonObject
}
