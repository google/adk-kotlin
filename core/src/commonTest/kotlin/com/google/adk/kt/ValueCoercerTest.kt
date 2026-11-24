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

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.auth.AuthCredential
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
import kotlin.test.assertSame
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
    ValueCoercer.coerceValue(value, schema, fillDefaults = false).getOrThrow()

  private fun coerceFilling(value: Any?, schema: Schema): Any? =
    ValueCoercer.coerceValue(value, schema, fillDefaults = true).getOrThrow()

  private fun fails(value: Any?, schema: Schema): Boolean =
    ValueCoercer.coerceValue(value, schema, fillDefaults = false).isFailure

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
    val error =
      ValueCoercer.coerceValue(value, schema, "input of node 'n'", fillDefaults = false)
        .exceptionOrNull()
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
        fillDefaults = false,
      )

    // Assert
    assertTrue(result.isSuccess)
  }

  /** An object whose `limit` defaults to 10. */
  private val page =
    Schema(
      type = Type.OBJECT,
      properties = mapOf("limit" to Schema(type = Type.INTEGER, default = 10)),
    )

  @Test
  fun coerceValue_missingKeyWithADefault_takesTheDefaultWhenFilling() {
    // Act
    val read = coerceFilling(emptyMap<String, Any>(), page)
    val items =
      coerceFilling(listOf(emptyMap<String, Any>()), Schema(type = Type.ARRAY, items = page))
    val extras =
      coerceFilling(mapOf("a" to emptyMap<String, Any>()), Schema(additionalProperties = page))

    // Assert
    assertEquals(mapOf("limit" to 10), read)
    assertEquals(listOf(mapOf("limit" to 10)), items)
    assertEquals(mapOf("a" to mapOf("limit" to 10)), extras)
  }

  @Test
  fun coerceValue_missingKeyWithADefault_staysMissingWithoutFilling() {
    // Act
    val read = coerce(emptyMap<String, Any>(), page)

    // Assert
    assertEquals(emptyMap<String, Any>(), read)
  }

  @Test
  fun coerceValue_presentKeyWithADefault_keepsItsValue() {
    // Arrange
    val nullable =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("limit" to Schema(type = Type.INTEGER, nullable = true, default = 10)),
      )

    // Act
    val given = coerceFilling(mapOf("limit" to 3), page)
    val explicitNull = coerceFilling(mapOf("limit" to null), nullable)

    // Assert
    assertEquals(mapOf("limit" to 3), given)
    assertEquals(mapOf("limit" to null), explicitNull)
  }

  @Test
  fun coerceValue_missingRequiredKeyWithADefault_failsEvenWhenFilling() {
    // Act
    val result =
      ValueCoercer.coerceValue(
        emptyMap<String, Any>(),
        page.copy(required = listOf("limit")),
        fillDefaults = true,
      )

    // Assert
    assertContains(result.exceptionOrNull()?.message.orEmpty(), "lacks required 'limit'")
  }

  @Test
  fun coerceValue_unionWhoseFirstBranchConverts_prefersALaterBranchThatFitsAsIs() {
    // Arrange: the first branch would turn "42" into 42 besides filling `limit`.
    val counted = page.copy(properties = page.properties!! + ("n" to Schema(type = Type.INTEGER)))
    val objects = Schema(anyOf = listOf(counted, Schema(type = Type.OBJECT)))
    val lists =
      Schema(anyOf = listOf(Schema(type = Type.ARRAY, items = counted), Schema(type = Type.ARRAY)))

    // Act
    val map = coerceFilling(mapOf("n" to "42"), objects)
    val list = coerceFilling(listOf(mapOf("n" to "42")), lists)

    // Assert
    assertEquals(mapOf("n" to "42"), map)
    assertEquals(listOf(mapOf("n" to "42")), list)
  }

  @Test
  fun coerceValue_unionWithALaterOpenObject_stillFillsTheFirstBranchDefaults() {
    // Arrange: both branches take the map, and filling a default is no conversion. Pydantic also
    // picks the first branch here, since the value sets one of its fields.
    val counted = page.copy(properties = page.properties!! + ("n" to Schema(type = Type.INTEGER)))
    val union = Schema(anyOf = listOf(counted, Schema(type = Type.OBJECT)))

    // Act
    val read = coerceFilling(mapOf("n" to 1), union)

    // Assert
    assertEquals(mapOf("n" to 1, "limit" to 10), read)
  }

  @Test
  fun coerceValue_enumContent_readsInheritedAndUntypedEnumsAndDeduplicatesSharedBranches() {
    // Arrange
    val inheritedStringEnum =
      Schema(type = Type.STRING, anyOf = listOf(Schema(enum = listOf("LOW", "HIGH"))))
    val untypedParentEnum =
      Schema(enum = listOf("HIGH"), anyOf = listOf(Schema(type = Type.STRING)))
    val intersectedEnum =
      Schema(
        enum = listOf("HIGH"),
        anyOf = listOf(Schema(type = Type.STRING, enum = listOf("LOW", "HIGH"))),
      )
    var sharedDag = Schema(type = Type.STRING, enum = listOf("LOW", "HIGH"))
    repeat(40) { sharedDag = Schema(anyOf = listOf(sharedDag, sharedDag)) }

    // Act
    val fromInherited = coerce(text("\"HIGH\""), inheritedStringEnum)
    val fromUntypedParent = coerce(text("\"HIGH\""), untypedParentEnum)
    val fromIntersected = ValueCoercer.enumValueOrText("\"HIGH\"", intersectedEnum)
    val excludedByParent = ValueCoercer.enumValueOrText("\"LOW\"", intersectedEnum)
    val fromSharedDag = coerce(text("\"HIGH\""), sharedDag)

    // Assert
    assertEquals("HIGH", fromInherited)
    assertEquals("HIGH", fromUntypedParent)
    assertEquals("HIGH", fromIntersected)
    assertEquals("\"LOW\"", excludedByParent)
    assertEquals("HIGH", fromSharedDag)
    assertTrue(fails(text("\"FOO\""), inheritedStringEnum))
    assertTrue(fails(text("\"LOW\""), untypedParentEnum))
  }

  @Test
  fun coerceValue_anyOfEnumWithUnconstrainedString_keepsRawText() {
    // Arrange
    val enumOrString =
      Schema(
        anyOf = listOf(Schema(type = Type.STRING, enum = listOf("A")), Schema(type = Type.STRING))
      )
    val enumOrInt =
      Schema(
        anyOf = listOf(Schema(type = Type.STRING, enum = listOf("A")), Schema(type = Type.INTEGER))
      )

    // Act
    val openQuotedMember = coerce(text("\"A\""), enumOrString)
    val openQuotedOther = coerce(text("\"B\""), enumOrString)
    val openNumberText = coerce(text("42"), enumOrString)
    val closedQuotedMember = coerce(text("\"A\""), enumOrInt)
    val closedNumberText = coerce(text("42"), enumOrInt)

    // Assert
    assertEquals("\"A\"", openQuotedMember)
    assertEquals("\"B\"", openQuotedOther)
    assertEquals("42", openNumberText)
    assertEquals("A", closedQuotedMember)
    assertEquals(42L, closedNumberText)
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun coerceValue_typedCredential_checksItsJsonFormAndStaysTyped() {
    // Arrange
    val credential = AuthCredential.ApiKey.of("key")
    val credentialSchema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("authType" to Schema(type = Type.STRING)),
        required = listOf("authType"),
      )

    // Act
    val read = coerce(credential, credentialSchema)

    // Assert
    assertSame(credential, read)
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun coerceValue_typedCredentialNotMatchingItsFields_fails() {
    // Arrange
    val credential = AuthCredential.ApiKey.of("key")
    val schemas =
      listOf(
        Schema(type = Type.OBJECT, required = listOf("oauth2")),
        Schema(type = Type.OBJECT, properties = mapOf("authType" to Schema(type = Type.INTEGER))),
      )

    // Act
    val results = schemas.map { fails(credential, it) }

    // Assert
    assertTrue(results.all { it })
  }

  @Test
  @OptIn(ExperimentalAuthApi::class)
  fun coerceValue_typedCredentialsUnderObjectAndArraySchemas_stayTyped() {
    // Arrange
    val credential = AuthCredential.ApiKey.of("key")
    val credentialSchema =
      Schema(type = Type.OBJECT, properties = mapOf("authType" to Schema(type = Type.STRING)))
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "declared" to credentialSchema,
            "listed" to Schema(type = Type.ARRAY, items = credentialSchema),
          ),
        additionalProperties = credentialSchema,
      )
    val value =
      mapOf("declared" to credential, "listed" to listOf(credential), "extra" to credential)

    // Act
    val read = coerce(value, schema) as Map<*, *>

    // Assert
    assertSame(credential, read["declared"])
    assertSame(credential, (read["listed"] as List<*>).single())
    assertSame(credential, read["extra"])
  }
}
