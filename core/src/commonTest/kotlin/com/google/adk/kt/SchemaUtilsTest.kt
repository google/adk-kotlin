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

package com.google.adk.kt

import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaUtilsTest {

  @Test
  fun validateMapOnSchema_validInput_returnsSuccess() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "name" to Schema(type = Type.STRING),
            "age" to Schema(type = Type.INTEGER),
            "tags" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)),
          ),
        required = listOf("name", "age"),
      )
    val args = mapOf("name" to "John", "age" to 30L, "tags" to listOf("tag1", "tag2"))

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_missingRequired_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val args = emptyMap<String, Any?>()

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
    assertEquals("Input args does not contain required name", result.exceptionOrNull()?.message)
  }

  @Test
  fun validateMapOnSchema_wrongType_returnsFailure() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("age" to Schema(type = Type.INTEGER)))
    val args = mapOf("age" to "30")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_extraProperty_returnsFailure() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("name" to Schema(type = Type.STRING)))
    val args = mapOf("name" to "John", "extra" to "value")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
    assertEquals(
      "Input arg: extra doesn't exist in input schema: $schema",
      result.exceptionOrNull()?.message,
    )
  }

  @Test
  fun validateMapOnSchema_integerAsInt_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("age" to Schema(type = Type.INTEGER)))
    val args = mapOf("age" to 30)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_integerAsLong_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("age" to Schema(type = Type.INTEGER)))
    val args = mapOf("age" to 30L)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_integerAsDouble_returnsFailure() {
    // Documents that JSON-parsed numbers (which Gson decodes as Double) are NOT accepted for an
    // INTEGER schema. Callers must pre-convert to Int/Long before validating.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("age" to Schema(type = Type.INTEGER)))
    val args = mapOf("age" to 30.0)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_numberAsDouble_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("price" to Schema(type = Type.NUMBER)))
    val args = mapOf("price" to 1.5)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_numberAsInt_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("price" to Schema(type = Type.NUMBER)))
    val args = mapOf("price" to 1)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_booleanType_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("active" to Schema(type = Type.BOOLEAN)))
    val args = mapOf("active" to true)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_booleanWithStringValue_returnsFailure() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("active" to Schema(type = Type.BOOLEAN)))
    val args = mapOf("active" to "true")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_nullTypeWithNullValue_returnsSuccess() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("nothing" to Schema(type = Type.NULL)))
    val args = mapOf<String, Any?>("nothing" to null)

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_nullTypeWithNonNullValue_returnsFailure() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("nothing" to Schema(type = Type.NULL)))
    val args = mapOf<String, Any?>("nothing" to "something")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_typeUnspecified_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("anything" to Schema(type = Type.TYPE_UNSPECIFIED)),
      )
    val args = mapOf<String, Any?>("anything" to "value")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_propertyWithoutType_returnsSuccess() {
    val schema = Schema(type = Type.OBJECT, properties = mapOf("anything" to Schema(type = null)))
    val args = mapOf<String, Any?>("anything" to "anyValue")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_arrayWithNonListValue_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("tags" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))),
      )
    val args = mapOf("tags" to "not-a-list")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_arrayWithWrongItemType_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("tags" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))),
      )
    val args = mapOf("tags" to listOf("ok", 123))

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_arrayWithoutItemSchema_returnsSuccess() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("tags" to Schema(type = Type.ARRAY, items = null)),
      )
    val args = mapOf("tags" to listOf("a", 1, true))

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_nestedObject_returnsSuccess() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "address" to
              Schema(
                type = Type.OBJECT,
                properties =
                  mapOf("city" to Schema(type = Type.STRING), "zip" to Schema(type = Type.INTEGER)),
                required = listOf("city"),
              )
          ),
      )
    val args = mapOf("address" to mapOf("city" to "NYC", "zip" to 10001L))

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_nestedObjectWithInvalidChild_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "address" to
              Schema(type = Type.OBJECT, properties = mapOf("zip" to Schema(type = Type.INTEGER)))
          ),
      )
    val args = mapOf("address" to mapOf("zip" to "not-a-number"))

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_objectTypeWithNonMapValue_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "address" to
              Schema(type = Type.OBJECT, properties = mapOf("city" to Schema(type = Type.STRING)))
          ),
      )
    val args = mapOf("address" to "not-a-map")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }

  @Test
  fun validateMapOnSchema_argsNamePropagatedToMessage() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val args = emptyMap<String, Any?>()

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Output")

    assertTrue(result.isFailure)
    assertEquals("Output args does not contain required name", result.exceptionOrNull()?.message)
  }

  @Test
  fun validateOutputSchema_validJsonMatchingSchema_returnsParsedMap() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("name" to Schema(type = Type.STRING), "city" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )

    val result = SchemaUtils.validateOutputSchema("""{"name": "John", "city": "NYC"}""", schema)

    assertTrue(result.isSuccess)
    assertEquals(mapOf("name" to "John", "city" to "NYC"), result.getOrNull())
  }

  @Test
  fun validateOutputSchema_invalidJson_returnsFailure() {
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("name" to Schema(type = Type.STRING)))

    val result = SchemaUtils.validateOutputSchema("not json", schema)

    assertTrue(result.isFailure)
  }

  @Test
  fun validateOutputSchema_jsonNotMatchingSchema_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )

    val result = SchemaUtils.validateOutputSchema("""{"unexpected": "value"}""", schema)

    assertTrue(result.isFailure)
    assertIs<IllegalArgumentException>(result.exceptionOrNull())
  }
}
