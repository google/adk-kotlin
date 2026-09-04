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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidMcpSchemaConverterTest {
  private val json = Json

  @Test
  fun expandsDefsReferencesAndPreservesNestedConstraints() {
    val schema =
      ToolSchema(
        properties = jsonObject("""{"place":{"${'$'}ref":"#/${'$'}defs/Place"},"ignored":"not-a-schema"}"""),
        required = listOf("place", "missing"),
        defs =
          jsonObject(
            """{"Place":{"type":"object","properties":{"city":{"type":"string","pattern":"[A-Z]+","minLength":2},"limit":{"type":"integer","minimum":1,"maximum":10}},"required":["city","absent"]}}"""
          ),
      )

    val converted = schema.toAdkSchema()
    val place = requireNotNull(converted.properties?.get("place"))
    assertEquals(Type.OBJECT, converted.type)
    assertEquals(listOf("place"), converted.required)
    assertEquals(listOf("city"), place.required)
    assertEquals("[A-Z]+", place.properties?.get("city")?.pattern)
    assertEquals(2, place.properties?.get("city")?.minLength)
    assertEquals(1.0, place.properties?.get("limit")?.minimum)
    assertEquals(10.0, place.properties?.get("limit")?.maximum)
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
    val optional = requireNotNull(converted.properties?.get("optional"))
    val choice = requireNotNull(converted.properties?.get("choice"))
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
    assertEquals(Type.STRING, converted.properties?.get("tags")?.items?.type)
    assertEquals(1, converted.properties?.get("tags")?.minItems)
    assertEquals(3, converted.properties?.get("tags")?.maxItems)
    assertNull(converted.properties?.get("when")?.format)
    assertEquals("date-time", converted.properties?.get("at")?.format)
  }

  @Test
  fun circularReferencesAreBoundedAndResponseAnyOfIsDropped() {
    val schema =
      ToolSchema(
        properties = jsonObject("""{"node":{"${'$'}ref":"#/${'$'}defs/Node"},"result":{"anyOf":[{"type":"string"},{"type":"integer"}]}}"""),
        defs =
          jsonObject(
            """{"Node":{"type":"object","properties":{"child":{"${'$'}ref":"#/${'$'}defs/Node"}}}}"""
          ),
      )

    val converted = schema.toAdkSchema()
    assertEquals(Type.OBJECT, converted.properties?.get("node")?.properties?.get("child")?.type)
    assertFalse(converted.properties?.get("node")?.properties?.get("child")?.description.isNullOrEmpty())
    assertNull(schema.toAdkResponseSchema())
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

    val converted = requireNotNull(schema.toAdkSchema().properties?.get("value"))

    assertEquals(Type.STRING, converted.type)
    assertNull(converted.description)
    assertNull(converted.pattern)
    assertNull(converted.minLength)
    assertEquals(mapOf("nested" to "value"), converted.default)
  }

  private fun jsonObject(source: String) = json.parseToJsonElement(source).jsonObject
}
