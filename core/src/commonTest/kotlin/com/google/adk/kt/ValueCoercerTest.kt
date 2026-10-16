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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValueCoercerTest {

  private val countSchema =
    Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))

  private val integer = Schema(type = Type.INTEGER)

  private val string = Schema(type = Type.STRING)

  private val color = Schema(type = Type.STRING, enum = listOf("red"))

  private fun text(value: String) = Content(parts = listOf(Part(text = value)))

  /** A value no failure message may quote. */
  private val secret = "SENTINEL-4711"

  private fun coerce(value: Any?, schema: Schema): Any? =
    ValueCoercer.coerceValue(value, schema).getOrThrow()

  private fun fails(value: Any?, schema: Schema): Boolean =
    ValueCoercer.coerceValue(value, schema).isFailure

  @Test
  fun coerceValue_object_isNormalizedAndKeepsUndeclaredKeysUnlessClosed() {
    // Arrange
    val closed = countSchema.copy(additionalProperties = false)
    val extrasAreIntegers = countSchema.copy(additionalProperties = Schema(type = Type.INTEGER))

    // Act
    val normalized = coerce(mapOf("count" to "1"), countSchema)
    val withExtra = coerce(mapOf("count" to 1, "extra" to "x"), countSchema)
    val checkedExtra = coerce(mapOf<String, Any>("count" to 1, "extra" to 2.0), extrasAreIntegers)

    // Assert
    assertEquals(mapOf("count" to 1L), normalized)
    assertEquals(mapOf("count" to 1, "extra" to "x"), withExtra)
    assertEquals(mapOf<String, Any>("count" to 1, "extra" to 2L), checkedExtra)
    assertTrue(fails(mapOf("count" to 1, "extra" to 2), closed))
    assertTrue(fails(mapOf("count" to 1, "extra" to "x"), extrasAreIntegers))
    assertTrue(fails(mapOf("count" to 1), Schema(type = Type.INTEGER)))
  }

  @Test
  fun coerceValue_scalars_acceptTheirJsonTypeAQuotedLiteralOrAWholeNumberOnly() {
    // Arrange
    val number = Schema(type = Type.NUMBER)
    val boolean = Schema(type = Type.BOOLEAN)

    // Act
    val integers = listOf(42, 42L, 42.0, "42", "1e2", "4.0").map { coerce(it, integer) }
    val numbers = listOf(1.5, "1.5", "3").map { coerce(it, number) }
    val booleans = listOf(true, "false").map { coerce(it, boolean) }

    // Assert
    assertEquals(listOf<Any>(42, 42L, 42L, 42L, 100L, 4L), integers)
    assertEquals(listOf<Any>(1.5, 1.5, 3L), numbers)
    assertEquals(listOf(true, false), booleans)
    for (value in
      listOf(
        3.5,
        "3.5",
        true,
        "abc",
        "003",
        "+3",
        "007",
        " 3",
        "42d",
        "0x1p4",
        "5.",
        "1_000",
        "9223372036854775808",
        "-9223372036854775809",
        "-9223372036854775809.0",
        "-9.223372036854775809e18",
        1e19,
        "1e19",
        -1e19,
      )) {
      assertTrue(fails(value, integer), "integer from $value")
    }
    for (value in
      listOf("nan", "Infinity", Double.NaN, false, "1".repeat(310), "+1.5", "42d", ".5", "1.5f")) {
      assertTrue(fails(value, number))
    }
    for (value in listOf("TRUE", "yes", 1, "1")) assertTrue(fails(value, boolean))
    for (value in listOf(3, 1.5, true)) assertTrue(fails(value, string))
  }

  @Test
  fun coerceValue_enumAndListItems_areEnforced() {
    // Arrange
    val integers = Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER))

    // Act
    val red = coerce("red", color)
    val list = coerce(setOf("1", 2.0), integers)

    // Assert
    assertEquals("red", red)
    assertEquals(listOf(1L, 2L), list)
    assertTrue(fails("blue", color))
    assertTrue(fails(listOf("a"), integers))
    assertTrue(fails("""{"count": 1}""", countSchema))
  }

  @Test
  fun coerceValue_untypedSchemaWithKeysOrItems_stillChecksThem() {
    // Arrange
    val untypedCount =
      Schema(properties = mapOf("count" to Schema(type = Type.INTEGER)), required = listOf("count"))
    val untypedIntegers = Schema(items = Schema(type = Type.INTEGER))

    // Act
    val count = coerce(mapOf("count" to "3"), untypedCount)
    val integers = coerce(listOf("1", 2.0), untypedIntegers)

    // Assert
    assertEquals(mapOf("count" to 3L), count)
    assertEquals(listOf(1L, 2L), integers)
    assertTrue(fails(emptyMap<String, Any>(), untypedCount))
    assertTrue(fails(mapOf("count" to "abc"), untypedCount))
    assertTrue(fails(listOf("x"), untypedIntegers))
    assertTrue(fails(mapOf("x" to 1), Schema(additionalProperties = false)))
    assertEquals("free text", coerce("free text", untypedCount))
  }

  @Test
  fun coerceValue_null_passesOnlyANullableOrUntypedSchema() {
    // Arrange
    val nullableInt = Schema(type = Type.INTEGER, nullable = true)

    // Act
    val intoNullable = coerce(null, nullableInt)
    val intoUntyped = coerce(null, Schema())

    // Assert
    assertEquals(null, intoNullable)
    assertEquals(null, intoUntyped)
    assertTrue(fails(null, Schema(type = Type.INTEGER)))
    assertTrue(fails(mapOf("count" to null), countSchema))
  }

  @Test
  fun coerceValue_content_isReadAsJsonUnlessTheSchemaTakesAString() {
    // Arrange
    val json = """{"count": "42"}"""
    val eitherIntOrString =
      Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))

    // Act
    val asObject = coerce(text(json), countSchema)
    val asString = coerce(text(json), Schema(type = Type.STRING))
    val asUnion = coerce(text("42"), eitherIntOrString)
    val untyped = coerce(text("hello"), Schema())

    // Assert
    assertEquals(mapOf("count" to 42L), asObject)
    assertEquals(json, asString)
    assertEquals("42", asUnion)
    assertEquals("hello", untyped)
    assertTrue(fails(text("hello"), countSchema))
  }

  @Test
  fun coerceValue_longListUnderAUnion_givesEachItemItsOwnBudget() {
    // Arrange
    val items = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.NULL)))
    val list = List(2000) { if (it % 2 == 0) null else "x" }

    // Act
    val result = coerce(list, Schema(type = Type.ARRAY, items = items))

    // Assert
    assertEquals(list, result)
  }

  @Test
  fun coerceValue_additionalPropertiesSchema_survivesASerializationRoundTrip() {
    // Arrange
    val schema = countSchema.copy(additionalProperties = Schema(type = Type.INTEGER))
    val reloaded =
      adkJson.decodeFromString(
        Schema.serializer(),
        adkJson.encodeToString(Schema.serializer(), schema),
      )

    // Act
    val checked = coerce(mapOf<String, Any>("count" to 1, "extra" to "2"), reloaded)

    // Assert
    assertIs<Map<*, *>>(reloaded.additionalProperties)
    assertEquals(mapOf<String, Any>("count" to 1, "extra" to 2L), checked)
    assertTrue(fails(mapOf("count" to 1, "extra" to "x"), reloaded))
  }

  /** Returns the failure message for [value], checking that it never quotes [secret]. */
  private fun failureMessage(value: Any?, schema: Schema): String {
    val error = ValueCoercer.coerceValue(value, schema, "input of node 'n'").exceptionOrNull()
    assertIs<IllegalArgumentException>(error)
    assertEquals(null, error.cause)
    val message = error.message.orEmpty()
    assertFalse(secret in message)
    return message
  }

  @Test
  fun coerceValue_failure_namesWhereButNeverTheValueOrAnUndeclaredKey() {
    // Arrange
    val person =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val prefix = "validation error: input of node 'n' does not match its schema: "
    // Each level reuses its child on both branches, so 12 levels describe 4096 combinations.
    var nested = Schema(type = Type.STRING)
    repeat(12) { nested = Schema(anyOf = listOf(nested, nested)) }

    // Act
    val wrongField = failureMessage(mapOf("count" to secret), countSchema)
    val missing = failureMessage(mapOf<String, Any?>(), person)
    val notAnObject = failureMessage(mapOf("count" to secret), Schema(type = Type.INTEGER))
    val undeclared =
      failureMessage(
        mapOf("count" to 1, secret to 2),
        countSchema.copy(additionalProperties = false),
      )
    val tooComplex = failureMessage(listOf(secret), nested)

    // Assert
    assertEquals(prefix + "'count' is not an integer", wrongField)
    assertEquals(prefix + "the value lacks required 'name'", missing)
    assertEquals(prefix + "the value is not an integer", notAnObject)
    assertEquals(prefix + "the value has a key the schema does not declare", undeclared)
    assertContains(tooComplex, "union alternatives")
  }

  /** Null passes only a nullable schema, a NULL type, or an untyped schema without an enum. */
  @Test
  fun coerceValue_nullIntoUntypedEnumOrTypedParentWithAnyOf_failsAndFitsIntoAgrees() {
    // Arrange
    val untypedEnum = Schema(enum = listOf("red"))
    val objectWithUntypedBranch =
      Schema(type = Type.OBJECT, anyOf = listOf(Schema(required = listOf("id"))))
    val stringWithNullableBranch =
      Schema(type = Type.STRING, anyOf = listOf(Schema(type = Type.STRING, nullable = true)))
    val untypedWithNullBranch =
      Schema(anyOf = listOf(Schema(type = Type.NULL), Schema(type = Type.STRING)))

    // Act
    val intoUntypedUnion = coerce(null, untypedWithNullBranch)

    // Assert
    assertTrue(fails(null, untypedEnum))
    assertTrue(fails(null, objectWithUntypedBranch))
    assertTrue(fails(null, stringWithNullableBranch))
    assertEquals(null, intoUntypedUnion)
    assertFalse(Schema(type = Type.NULL).fitsInto(untypedEnum))
    assertTrue(untypedEnum.fitsInto(Schema(type = Type.STRING, enum = listOf("red"))))
    assertTrue(stringWithNullableBranch.fitsInto(Schema(type = Type.STRING)))
    assertFalse(untypedWithNullBranch.fitsInto(Schema(type = Type.STRING)))
    assertTrue(fails(null, Schema(type = Type.STRING, anyOf = listOf(untypedEnum))))
    assertFalse(
      Schema(type = Type.STRING, enum = listOf("red"), nullable = true).fitsInto(untypedEnum)
    )
  }

  @Test
  fun coerceValue_setForArraySchema_succeeds() {
    // Act
    val result =
      ValueCoercer.coerceValue(
        setOf(1, 2),
        Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER)),
        "ids",
      )

    // Assert
    assertTrue(result.isSuccess)
  }
}
