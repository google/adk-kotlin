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

package com.google.adk.kt.types

import com.google.genai.kotlin.types.Schema as GenAiSchema
import com.google.genai.kotlin.types.Type as GenAiType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SchemaTest {

  @Test
  fun dataClass_storesValuesCorrectly() {
    val schema =
      Schema(
        type = Type.OBJECT,
        description = "A test schema",
        properties = mapOf("prop1" to Schema(type = Type.STRING)),
        required = listOf("prop1"),
      )

    assertEquals(Type.OBJECT, schema.type)
    assertEquals("A test schema", schema.description)
    assertEquals(1, schema.properties?.size)
    assertEquals(Type.STRING, schema.properties?.get("prop1")?.type)
    assertEquals(listOf("prop1"), schema.required)
  }

  @Test
  fun toGenAiSchema_convertsCorrectly() {
    val ktSchema =
      Schema(
        type = Type.OBJECT,
        description = "Test toGenAiSchema",
        properties = mapOf("val" to Schema(type = Type.NUMBER)),
        required = listOf("val"),
      )

    val genAiSchema = ktSchema.toGenAiSchema()

    assertEquals(GenAiType.OBJECT, genAiSchema.type)
    assertEquals("Test toGenAiSchema", genAiSchema.description)
    assertEquals(1, genAiSchema.properties?.size)
    assertEquals(GenAiType.NUMBER, genAiSchema.properties?.get("val")?.type)
    assertEquals(listOf("val"), genAiSchema.required)
  }

  @Test
  fun toKtSchema_convertsCorrectly() {
    val genAiSchema =
      GenAiSchema(
        type = GenAiType.ARRAY,
        description = "Test toKtSchema",
        items = GenAiSchema(type = GenAiType.INTEGER),
      )

    val ktSchema = genAiSchema.toKtSchema()

    assertEquals(Type.ARRAY, ktSchema.type)
    assertEquals("Test toKtSchema", ktSchema.description)
    val items = ktSchema.items
    assertNotNull(items)
    assertEquals(Type.INTEGER, items.type)
  }
}
