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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.genai.kotlin.types.Schema as GenAiSchema
import com.google.genai.kotlin.types.Type as GenAiType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(FrameworkInternalApi::class)
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

  @Test
  fun toGenAiSchema_constraintFields_roundTrip() {
    val ktSchema =
      Schema(
        type = Type.STRING,
        description = "A constrained value",
        enum = listOf("EAST", "WEST"),
        format = "date-time",
        nullable = true,
        default = "EAST",
        anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)),
        title = "Direction",
        pattern = "^[A-Z]+$",
        minimum = 1.0,
        maximum = 10.0,
        minLength = 2,
        maxLength = 8,
        minItems = 1,
        maxItems = 5,
        minProperties = 3,
        maxProperties = 9,
      )

    val roundTripped = ktSchema.toGenAiSchema().toKtSchema()

    assertEquals(ktSchema, roundTripped)
  }

  @Test
  fun toGenAiSchema_constraintFields_reachTheGenAiSchema() {
    val ktSchema =
      Schema(
        type = Type.STRING,
        format = "date-time",
        nullable = true,
        title = "Direction",
        pattern = "^[A-Z]+$",
        minimum = 1.0,
        maximum = 10.0,
        minLength = 2,
        maxLength = 8,
        minItems = 1,
        maxItems = 5,
        minProperties = 3,
        maxProperties = 9,
        anyOf = listOf(Schema(type = Type.INTEGER)),
      )

    val genAiSchema = ktSchema.toGenAiSchema()

    assertEquals("date-time", genAiSchema.format)
    assertEquals(true, genAiSchema.nullable)
    assertEquals("Direction", genAiSchema.title)
    assertEquals("^[A-Z]+$", genAiSchema.pattern)
    assertEquals(1.0, genAiSchema.minimum)
    assertEquals(10.0, genAiSchema.maximum)
    assertEquals(2L, genAiSchema.minLength)
    assertEquals(8L, genAiSchema.maxLength)
    assertEquals(1L, genAiSchema.minItems)
    assertEquals(5L, genAiSchema.maxItems)
    assertEquals(3L, genAiSchema.minProperties)
    assertEquals(9L, genAiSchema.maxProperties)
    assertEquals(GenAiType.INTEGER, genAiSchema.anyOf?.single()?.type)
  }

  @Test
  fun toGenAiSchema_defaultValue_survivesBothDirections() {
    val ktSchema = Schema(type = Type.INTEGER, default = 7)

    val genAiSchema = ktSchema.toGenAiSchema()

    assertNotNull(genAiSchema.default)
    // Compared as a number rather than by identity: the value crosses a JSON boundary, where the
    // integer widths Kotlin distinguishes collapse into one number type.
    assertEquals(7L, (genAiSchema.toKtSchema().default as Number).toLong())
  }

  @Test
  fun toGenAiSchema_longDefault_keepsItsValue() {
    val ktSchema = Schema(type = Type.INTEGER, default = 7L)

    val roundTripped = ktSchema.toGenAiSchema().toKtSchema()

    assertEquals(7L, (roundTripped.default as Number).toLong())
  }

  @Test
  fun toGenAiSchema_stringDefault_roundTrips() {
    val ktSchema = Schema(type = Type.STRING, default = "EAST")

    val roundTripped = ktSchema.toGenAiSchema().toKtSchema()

    assertEquals("EAST", roundTripped.default)
  }

  @Test
  fun adkJson_schemaWithDefault_serializes() {
    // `default` is the one field typed `Any`, so it needs the contextual serializer that `adkJson`
    // registers. Schemas are persisted as part of the cached-content metadata.
    val schema = Schema(type = Type.INTEGER, default = 7, title = "Count")

    val decoded =
      adkJson.decodeFromString(
        Schema.serializer(),
        adkJson.encodeToString(Schema.serializer(), schema),
      )

    assertEquals(Type.INTEGER, decoded.type)
    assertEquals("Count", decoded.title)
    assertEquals(7L, (decoded.default as Number).toLong())
  }
}
