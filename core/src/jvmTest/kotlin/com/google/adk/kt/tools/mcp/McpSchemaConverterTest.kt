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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpSchemaConverterTest {
  @Test
  fun mcpSchemaConverter_parseTypeString() {
    assertEquals(Type.STRING, McpSchemaConverter.parseTypeString("string"))
    assertEquals(Type.INTEGER, McpSchemaConverter.parseTypeString("integer"))
    assertEquals(Type.NUMBER, McpSchemaConverter.parseTypeString("number"))
    assertEquals(Type.BOOLEAN, McpSchemaConverter.parseTypeString("boolean"))
    assertEquals(Type.ARRAY, McpSchemaConverter.parseTypeString("array"))
    assertEquals(Type.OBJECT, McpSchemaConverter.parseTypeString("object"))
    assertFailsWith<IllegalArgumentException> { McpSchemaConverter.parseTypeString("unknown") }
    assertEquals(Type.TYPE_UNSPECIFIED, McpSchemaConverter.parseTypeString(null))
  }

  @Test
  fun parsePropertyMap_stringType_usesThatType() {
    val schema = McpSchemaConverter.parsePropertyMap(mapOf("type" to "boolean"))

    assertEquals(Type.BOOLEAN, schema.type)
  }

  @Test
  fun parsePropertyMap_singleElementTypeList_usesThatType() {
    val schema = McpSchemaConverter.parsePropertyMap(mapOf("type" to listOf("integer")))

    assertEquals(Type.INTEGER, schema.type)
  }

  @Test
  fun parsePropertyMap_unionType_narrowsToFirstType() {
    // A JSON-schema union type (e.g. ["string", "null"]) is unsupported by ADK schemas, so it is
    // narrowed to its first entry and a warning is logged.
    val schema = McpSchemaConverter.parsePropertyMap(mapOf("type" to listOf("string", "null")))

    assertEquals(Type.STRING, schema.type)
  }
}
