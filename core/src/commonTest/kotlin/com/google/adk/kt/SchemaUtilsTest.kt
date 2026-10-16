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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmInline
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private data class UserProfileDomain(val userId: String, val tier: String)

@Serializable
private data class ShippingAddress(val street: String, @SerialName("zip_code") val zipCode: String)

@Serializable
private data class CustomerOrder(
  val id: Long,
  val note: String? = null,
  val express: Boolean = false,
  val coupon: String?,
  val address: ShippingAddress,
)

@Serializable
private enum class OrderStatus {
  @SerialName("open") OPEN,
  @SerialName("shipped") SHIPPED,
}

@Serializable private data class Box<T>(val item: T)

@Serializable
private sealed class Shape {
  @Serializable data class Circle(val radius: Double) : Shape()
}

@Serializable private data class TreeNode(val label: String, val children: List<TreeNode>)

@Serializable private data class LinkedItem(val value: Int, val next: LinkedItem?)

@Serializable private data class Payload(val id: String, val extra: JsonElement)

@Serializable private data class Reading(val value: JsonPrimitive)

@JvmInline @Serializable private value class OrderId(val value: String)

class SchemaUtilsTest {

  private val countSchema =
    Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))

  private fun text(value: String) = Content(parts = listOf(Part(text = value)))

  /** A value no failure message may quote. */
  private val secret = "SENTINEL-4711"

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
  fun validateMapOnSchema_nullValueForNullableProperty_returnsSuccess() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("note" to Schema(type = Type.STRING, nullable = true)),
        required = listOf("note"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("note" to null), schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_nullValueForNonNullableProperty_returnsFailure() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("note" to Schema(type = Type.STRING)),
        required = listOf("note"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("note" to null), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_wrongTypeForNullableProperty_returnsFailure() {
    // `nullable` widens the property to accept null, not to accept anything.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("note" to Schema(type = Type.STRING, nullable = true)),
        required = listOf("note"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("note" to 42), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_valueMatchingAnyOfMember_returnsSuccess() {
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "id" to Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))
          ),
        required = listOf("id"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("id" to 7), schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_valueMatchingNoAnyOfMember_returnsFailure() {
    // A schema carrying only `anyOf` has no type, which must not be read as "no constraint".
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "id" to Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))
          ),
        required = listOf("id"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("id" to true), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_topLevelAnyOf_matchesOneAlternative() {
    val schema =
      Schema(
        anyOf =
          listOf(
            Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(type = Type.STRING))),
            Schema(type = Type.OBJECT, properties = mapOf("b" to Schema(type = Type.INTEGER))),
          )
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("b" to 1), schema, "Input")

    assertTrue(result.isSuccess)
  }

  @Test
  fun validateMapOnSchema_topLevelAnyOf_matchingNoAlternative_returnsFailure() {
    val schema =
      Schema(
        anyOf =
          listOf(Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(type = Type.STRING))))
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("zzz" to 1), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_anyOfBesideProperties_stillChecksTheUnion() {
    // A schema does not stop meaning what its `anyOf` says because it also declares properties.
    // The same schema reached as a nested property has its union enforced, so the root must agree.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING)),
        anyOf =
          listOf(Schema(type = Type.OBJECT, properties = mapOf("b" to Schema(type = Type.INTEGER)))),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("a" to "x"), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_unionsThatMultiplyOut_giveUpInsteadOfHanging() {
    // Each level reuses the same child on both branches, so 24 objects describe 16 million
    // combinations. Trying them all takes minutes, so validation stops and says why.
    var nested = Schema(type = Type.STRING)
    repeat(24) { nested = Schema(anyOf = listOf(nested, nested)) }
    val schema = Schema(type = Type.OBJECT, properties = mapOf("x" to nested))

    // A value that matches nothing is what forces every branch to be tried.
    val result = SchemaUtils.validateMapOnSchema(mapOf("x" to 42), schema, "Input")

    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message?.contains("too complex to validate") == true,
      "expected the budget message, got: ${result.exceptionOrNull()?.message}",
    )
  }

  @Test
  fun validateMapOnSchema_deeplyNestedUnionsThatMatch_stillValidate() {
    // The same depth, but one branch matches at every level, so the search stops early and the
    // budget is never close to spent. Depth alone must not be what fails a schema.
    var nested = Schema(type = Type.STRING)
    repeat(24) { nested = Schema(anyOf = listOf(nested, Schema(type = Type.BOOLEAN))) }
    val schema = Schema(type = Type.OBJECT, properties = mapOf("x" to nested))

    assertTrue(SchemaUtils.validateMapOnSchema(mapOf("x" to "hello"), schema, "Input").isSuccess)
  }

  @Test
  fun validateMapOnSchema_anyOfOfRequiredOnlyMembers_acceptsEitherKey() {
    // The ordinary way to write "at least one of these". Each alternative names a required key and
    // nothing else, so it adds a rule to the schema around it rather than replacing its key list.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING), "b" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(required = listOf("b"))),
      )

    assertTrue(SchemaUtils.validateMapOnSchema(mapOf("a" to "x"), schema, "Input").isSuccess)
    assertTrue(SchemaUtils.validateMapOnSchema(mapOf("b" to "y"), schema, "Input").isSuccess)
    // Neither alternative is satisfied, so the map is still rejected.
    assertTrue(SchemaUtils.validateMapOnSchema(emptyMap(), schema, "Input").isFailure)
  }

  @Test
  fun validateMapOnSchema_topLevelAnyOf_stillChecksRequired() {
    // The union branch used to return before the required check, so a `required` declared beside
    // an `anyOf` went unenforced.
    val schema =
      Schema(
        anyOf =
          listOf(Schema(type = Type.OBJECT, properties = mapOf("a" to Schema(type = Type.STRING)))),
        required = listOf("b"),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("a" to "x"), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_objectWithoutDeclaredProperties_acceptsAnyKeys() {
    // `{"type": "object"}` with no properties is how a free-form argument is spelled. The Firebase
    // converter advertises it as an argument the model may fill, so rejecting whatever comes back
    // would contradict the declaration the model was given.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("meta" to Schema(type = Type.OBJECT)))

    val result = SchemaUtils.validateMapOnSchema(mapOf("meta" to mapOf("a" to 1)), schema, "Input")

    assertTrue(result.isSuccess, "got: ${result.exceptionOrNull()?.message}")
  }

  @Test
  fun validateMapOnSchema_objectWithEmptyProperties_stillRejectsUnknownKeys() {
    // An empty property map is a different statement from an absent one: this schema does say that
    // no key is allowed, and that is still enforced.
    val schema = Schema(type = Type.OBJECT, properties = emptyMap())

    assertTrue(SchemaUtils.validateMapOnSchema(mapOf("a" to 1), schema, "Input").isFailure)
  }

  @Test
  fun validateMapOnSchema_nestedObjectWithUnion_stillEnforcesTheUnion() {
    // `matchType` no longer reads a union when the schema is an object, because the walk it defers
    // to reads the same one. This is the test that the walk it defers to actually happens.
    val inner =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("b"))),
      )
    val schema = Schema(type = Type.OBJECT, properties = mapOf("x" to inner))

    // The only alternative requires "b", which is absent.
    val result = SchemaUtils.validateMapOnSchema(mapOf("x" to mapOf("a" to "v")), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_nestedObjectUnion_isNotWalkedTwice() {
    // An object hands its whole schema on to `validateMapOnSchema`, which reads the union again.
    // Reading it in `matchType` as well spent the budget twice over, so a union comfortably inside
    // the limit came back as "too complex". Only the last alternative matches, so both walks run to
    // the end.
    val members =
      (1..600).map {
        Schema(type = Type.OBJECT, properties = mapOf("k$it" to Schema(type = Type.STRING)))
      }
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("x" to Schema(type = Type.OBJECT, anyOf = members)),
      )

    val result =
      SchemaUtils.validateMapOnSchema(mapOf("x" to mapOf("k600" to "v")), schema, "Input")

    assertTrue(result.isSuccess, "got: ${result.exceptionOrNull()?.message}")
  }

  @Test
  fun validateMapOnSchema_unionMemberOfAnotherType_doesNotMatchAMap() {
    // `matchType` hands an object's whole union to `validateMapOnSchema`, which reads `properties`
    // and `required` but never `type`. A member declaring `string` describes no map, so it must
    // not be the alternative that satisfies one.
    val inner = Schema(type = Type.OBJECT, anyOf = listOf(Schema(type = Type.STRING)))
    val schema = Schema(type = Type.OBJECT, properties = mapOf("x" to inner))

    val result = SchemaUtils.validateMapOnSchema(mapOf("x" to mapOf("a" to 1)), schema, "Input")

    assertTrue(result.isFailure)
  }

  @Test
  fun validateMapOnSchema_rootSchemaOfAnotherType_isRejected() {
    // Nothing past the top of the function reads `type`, and the key check only runs for a schema
    // that names properties, so without an explicit check a primitive or array schema accepted any
    // object at all. `validateOutputSchema` documents the opposite and `LlmAgent.outputSchema`
    // relies on it.
    val args = mapOf("a" to 1)

    assertTrue(SchemaUtils.validateMapOnSchema(args, Schema(type = Type.STRING), "Input").isFailure)
    assertTrue(
      SchemaUtils.validateMapOnSchema(
          args,
          Schema(type = Type.ARRAY, items = Schema(type = Type.STRING)),
          "Input",
        )
        .isFailure
    )
  }

  @Test
  fun validateMapOnSchema_longListOfUnionItems_isNotCalledTooComplex() {
    // The budget bounds how far a schema multiplies out. A list is data, not schema, so its length
    // must not spend it: 501 plain strings against a two-member item union used to exhaust the
    // 1000 alternatives and report a trivial schema as too complex.
    val itemSchema = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("xs" to Schema(type = Type.ARRAY, items = itemSchema)),
      )

    val result = SchemaUtils.validateMapOnSchema(mapOf("xs" to List(501) { "s" }), schema, "Input")

    assertTrue(result.isSuccess, "got: ${result.exceptionOrNull()?.message}")
  }

  @Test
  fun validateMapOnSchema_budgetSpentInsideTheLastAlternative_saysWhyItGaveUp() {
    // Nothing spends the budget after the final alternative, so if that is where it runs out the
    // reason has to be carried out of the loop. Otherwise this comes back as "matches nothing",
    // which is a different answer and a wrong one.
    var nested = Schema(type = Type.STRING)
    repeat(24) { nested = Schema(anyOf = listOf(nested, nested)) }
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("x" to Schema(anyOf = listOf(nested))))

    val result = SchemaUtils.validateMapOnSchema(mapOf("x" to 42), schema, "Input")

    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message?.contains("too complex to validate") == true,
      "expected the budget message, got: ${result.exceptionOrNull()?.message}",
    )
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
    // The schema is named by the shape that explains the rejection, not by the whole data class:
    // eighteen fields, nearly all of them unset, would bury the one detail that matters.
    assertEquals(
      "Input arg: extra doesn't exist in input schema: Schema(type=OBJECT, properties=[name])",
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
  fun validateMapOnSchema_typeUnspecified_acceptsTheValue() {
    // An untyped property says nothing about its value, so validating against it must not fail.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("anything" to Schema(type = Type.TYPE_UNSPECIFIED)),
      )
    val args = mapOf<String, Any?>("anything" to "value")

    val result = SchemaUtils.validateMapOnSchema(args, schema, "Input")

    assertTrue(result.isSuccess)
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

  private val integer = Schema(type = Type.INTEGER)

  private val string = Schema(type = Type.STRING)

  private val intOrString = Schema(anyOf = listOf(integer, string))

  private val color = Schema(type = Type.STRING, enum = listOf("red"))

  private val colors = Schema(type = Type.STRING, enum = listOf("red", "blue"))

  private val idOnly =
    Schema(type = Type.OBJECT, properties = mapOf("id" to string), required = listOf("id"))

  private val idAndNote =
    Schema(
      type = Type.OBJECT,
      properties = mapOf("id" to string, "note" to string),
      required = listOf("id"),
    )

  private val objectWithA =
    Schema(type = Type.OBJECT, properties = mapOf("a" to string), required = listOf("a"))

  private val idWithAnyOfA = idOnly.copy(anyOf = listOf(objectWithA))

  // -- fitsInto --

  @Test
  fun fitsInto_everySourceShapeIntoEveryTargetShape_matchesTheAssignabilityTable() {
    // Arrange
    val shapes =
      mapOf(
        "integer" to integer,
        "number" to Schema(type = Type.NUMBER),
        "untyped" to Schema(),
        "integer or string" to intOrString,
        "red" to color,
        "nullable integer" to integer.copy(nullable = true),
        "open object" to idOnly,
        "closed object" to idOnly.copy(additionalProperties = false),
        "integer list" to Schema(type = Type.ARRAY, items = integer),
      )
    // Each source maps to the targets it fits.
    val expected =
      mapOf(
        "integer" to setOf("integer", "number", "untyped", "integer or string", "nullable integer"),
        "number" to setOf("number", "untyped"),
        "untyped" to shapes.keys,
        "integer or string" to setOf("untyped", "integer or string"),
        "red" to setOf("untyped", "integer or string", "red"),
        "nullable integer" to setOf("untyped", "nullable integer"),
        "open object" to setOf("untyped", "open object", "closed object"),
        "closed object" to setOf("untyped", "open object", "closed object"),
        "integer list" to setOf("untyped", "integer list"),
      )

    // Act
    val actual = shapes.mapValues { (_, source) ->
      shapes.filterValues { source.fitsInto(it) }.keys
    }

    // Assert
    assertEquals(expected, actual)
  }

  @Test
  fun fitsInto_numbers_integerWidensToNumberOnly() {
    assertTrue(integer.fitsInto(Schema(type = Type.NUMBER)))
    assertFalse(Schema(type = Type.NUMBER).fitsInto(integer))
  }

  @Test
  fun fitsInto_nullable_fitsOnlyATargetThatTakesNull() {
    // Arrange
    val nullableInteger = integer.copy(nullable = true)
    val integerOrNull = Schema(anyOf = listOf(integer, Schema(type = Type.NULL)))
    val nullableIntOrString = intOrString.copy(nullable = true)

    // Act / Assert
    assertFalse(nullableInteger.fitsInto(integer))
    assertTrue(nullableInteger.fitsInto(integerOrNull))
    assertFalse(nullableInteger.fitsInto(intOrString))
    assertTrue(nullableInteger.fitsInto(nullableIntOrString))
    assertFalse(nullableIntOrString.fitsInto(intOrString))
    assertTrue(nullableIntOrString.fitsInto(Schema()))
    assertTrue(
      nullableIntOrString.fitsInto(
        Schema(anyOf = listOf(integer, string, Schema(type = Type.NULL)))
      )
    )
    assertTrue(Schema(type = Type.NULL).fitsInto(nullableIntOrString))
    assertFalse(Schema(type = Type.NULL).fitsInto(intOrString))
    assertTrue(
      Schema(anyOf = listOf(integer, Schema(type = Type.INTEGER, nullable = true)))
        .fitsInto(nullableInteger)
    )
  }

  @Test
  fun fitsInto_typedParentThatIsNotNullable_rejectsNullWhateverItsAlternativesSay() {
    // Arrange
    val nullableThroughAnyOf =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("n" to Schema(type = Type.INTEGER, anyOf = listOf(integer.copy(nullable = true)))),
      )

    // Act / Assert
    assertTrue(
      nullableThroughAnyOf.fitsInto(Schema(type = Type.OBJECT, properties = mapOf("n" to integer)))
    )
    assertFalse(
      integer
        .copy(nullable = true)
        .fitsInto(Schema(type = Type.INTEGER, anyOf = listOf(integer, Schema(type = Type.NULL))))
    )
  }

  @Test
  fun fitsInto_typedSource_staysWithinTheTargetEnum() {
    assertTrue(color.fitsInto(colors))
    assertFalse(colors.fitsInto(color))
    assertFalse(string.fitsInto(color))
    assertFalse(string.fitsInto(Schema(enum = listOf("red"))))
    assertTrue(color.fitsInto(Schema(enum = listOf("red", "blue"))))
    assertFalse(colors.fitsInto(Schema(enum = listOf("red"))))
    // The target's enum holds beside its `anyOf`.
    assertFalse(string.fitsInto(Schema(anyOf = listOf(string), enum = listOf("red"))))
    assertTrue(color.fitsInto(Schema(anyOf = listOf(string), enum = listOf("red"))))
  }

  @Test
  fun fitsInto_untypedSource_fitsAnyTargetButAnEmptyUnion() {
    assertTrue(Schema().fitsInto(integer))
    assertTrue(Schema().fitsInto(Schema(enum = listOf("red"))))
    assertFalse(Schema().fitsInto(Schema(anyOf = emptyList())))
  }

  @Test
  fun fitsInto_untypedEnum_fitsWhenEachValueAndItsLiteralDo() {
    assertFalse(Schema(enum = listOf("red")).fitsInto(integer))
    assertTrue(Schema(enum = listOf("red")).fitsInto(string))
    // "1" also spells the number 1, and "true" the boolean, which a string schema rejects.
    assertFalse(Schema(enum = listOf("1")).fitsInto(string))
    assertFalse(Schema(enum = listOf("true")).fitsInto(string))
    assertTrue(Schema(enum = listOf("a", "1")).fitsInto(intOrString))
    assertFalse(Schema(enum = listOf("green")).fitsInto(color))
    assertTrue(Schema(enum = listOf("green")).fitsInto(Schema(enum = listOf("green", "red"))))
  }

  @Test
  fun fitsInto_objects_checkRequiredDeclaredAndExtraKeys() {
    assertTrue(idAndNote.fitsInto(idOnly))
    assertFalse(idOnly.copy(properties = mapOf("id" to integer)).fitsInto(idOnly))
    assertFalse(idOnly.fitsInto(idAndNote.copy(required = listOf("id", "note"))))
    assertFalse(idAndNote.fitsInto(idOnly.copy(additionalProperties = false)))
    assertFalse(idAndNote.fitsInto(idOnly.copy(additionalProperties = integer)))
    assertTrue(idAndNote.fitsInto(idOnly.copy(additionalProperties = string)))
    assertTrue(idAndNote.fitsInto(idOnly.copy(additionalProperties = mapOf("type" to "STRING"))))
    assertFalse(idAndNote.fitsInto(idOnly.copy(additionalProperties = mapOf("type" to "INTEGER"))))
    assertFalse(
      Schema(type = Type.OBJECT, additionalProperties = string)
        .fitsInto(Schema(type = Type.OBJECT, additionalProperties = integer))
    )
    assertTrue(
      Schema(type = Type.OBJECT, additionalProperties = integer)
        .fitsInto(Schema(type = Type.OBJECT, additionalProperties = Schema(type = Type.NUMBER)))
    )
    // An open map declares no keys, so it is checked at run time.
    assertTrue(Schema(type = Type.OBJECT).fitsInto(idOnly.copy(additionalProperties = false)))
    assertTrue(
      Schema(type = Type.OBJECT, additionalProperties = true)
        .fitsInto(idOnly.copy(additionalProperties = false))
    )
  }

  /** A closed target rejects a key the source requires and keys an untyped union declares. */
  @Test
  fun fitsInto_closedTarget_rejectsRequiredUndeclaredKeysAndUntypedUnionKeys() {
    // Arrange
    val string = Schema(type = Type.STRING)
    val idOnly =
      Schema(type = Type.OBJECT, properties = mapOf("id" to string), required = listOf("id"))
    val closedIdOnly = idOnly.copy(additionalProperties = false)
    val requiresId = Schema(type = Type.OBJECT, required = listOf("id"))
    val closedEmpty = Schema(type = Type.OBJECT, additionalProperties = false)
    val closedNote =
      Schema(type = Type.OBJECT, properties = mapOf("note" to string), additionalProperties = false)
    val untypedUnion = Schema(properties = mapOf("a" to string), anyOf = listOf(idOnly))

    // Act / Assert
    assertFalse(requiresId.fitsInto(closedEmpty))
    assertFalse(requiresId.fitsInto(closedNote))
    assertTrue(requiresId.fitsInto(closedIdOnly.copy(required = null)))
    assertFalse(untypedUnion.fitsInto(closedIdOnly))
    assertFalse(untypedUnion.copy(type = Type.OBJECT).fitsInto(closedIdOnly))
    assertTrue(Schema(anyOf = listOf(idOnly)).fitsInto(closedIdOnly))
  }

  /** A source that declares keys without properties is checked by those keys. */
  @Test
  fun fitsInto_sourceWithKeysButNoProperties_checksItsKeys() {
    // Arrange
    val closedEmpty = Schema(type = Type.OBJECT, additionalProperties = false)
    val requiresId = Schema(type = Type.OBJECT, required = listOf("id"))
    val requiresNote = Schema(type = Type.OBJECT, required = listOf("note"))

    // Act / Assert
    assertFalse(closedEmpty.fitsInto(idOnly))
    assertFalse(requiresId.fitsInto(requiresNote))
    assertTrue(Schema(type = Type.OBJECT).fitsInto(idOnly))
    assertTrue(closedEmpty.fitsInto(Schema(type = Type.OBJECT)))
    // A key the target declares and requires must be declared too; one it only requires need not.
    assertFalse(requiresId.fitsInto(idOnly))
    assertTrue(requiresId.copy(properties = emptyMap()).fitsInto(requiresId))
  }

  @Test
  fun fitsInto_untypedTarget_stillChecksTheKeysOrItemsItDeclares() {
    assertTrue(idOnly.fitsInto(Schema(properties = mapOf("id" to string))))
    assertTrue(idOnly.copy(nullable = true).fitsInto(Schema(properties = mapOf("id" to string))))
    assertFalse(idOnly.fitsInto(Schema(required = listOf("note"))))
    assertFalse(Schema(type = Type.ARRAY, items = string).fitsInto(Schema(items = integer)))
  }

  @Test
  fun fitsInto_arrays_checkItemsWhenBothDeclareThem() {
    assertFalse(
      Schema(type = Type.ARRAY, items = string).fitsInto(Schema(type = Type.ARRAY, items = integer))
    )
    assertTrue(
      Schema(type = Type.ARRAY, items = idAndNote)
        .fitsInto(Schema(type = Type.ARRAY, items = idOnly))
    )
    assertTrue(Schema(type = Type.ARRAY).fitsInto(Schema(type = Type.ARRAY, items = integer)))
  }

  @Test
  fun fitsInto_unionSource_needsItsOwnPartAndEveryBranchToFit() {
    // Arrange
    val kindString = Schema(type = Type.OBJECT, properties = mapOf("kind" to string))
    val kindEnumOnly =
      kindString.copy(
        anyOf =
          listOf(
            Schema(type = Type.OBJECT, properties = mapOf("kind" to Schema(enum = listOf("a"))))
          )
      )
    val closedIdOnly = idOnly.copy(additionalProperties = false)
    val optionalA = idWithAnyOfA.copy(nullable = true)

    // Act / Assert
    assertFalse(intOrString.fitsInto(integer))
    assertTrue(kindEnumOnly.fitsInto(kindString))
    assertFalse(Schema(type = Type.OBJECT, anyOf = listOf(objectWithA)).fitsInto(closedIdOnly))
    assertFalse(idWithAnyOfA.fitsInto(closedIdOnly))
    assertFalse(
      Schema(type = Type.OBJECT, properties = mapOf("a" to string), anyOf = listOf(idOnly))
        .fitsInto(closedIdOnly)
    )
    assertFalse(
      Schema(type = Type.ARRAY, anyOf = listOf(Schema(type = Type.ARRAY, items = string)))
        .fitsInto(Schema(type = Type.ARRAY, items = integer))
    )
    assertFalse(Schema(required = listOf("id"), anyOf = listOf(integer)).fitsInto(string))
    // An untyped branch of an untyped union takes null.
    assertFalse(Schema(anyOf = listOf(integer, Schema())).fitsInto(integer))
    assertFalse(
      Schema(type = Type.INTEGER, anyOf = listOf(Schema(type = Type.NUMBER, enum = listOf("2.0"))))
        .fitsInto(Schema(type = Type.INTEGER, enum = listOf("2.0")))
    )
    assertFalse(
      Schema(
          type = Type.OBJECT,
          properties = mapOf("n" to Schema(anyOf = listOf(integer))),
          anyOf = listOf(Schema(type = Type.OBJECT, properties = mapOf("n" to string))),
        )
        .fitsInto(Schema(type = Type.OBJECT, properties = mapOf("n" to string)))
    )
    // A branch that is the target itself fits it, nullable and all.
    assertTrue(Schema(anyOf = listOf(optionalA, Schema(type = Type.NULL))).fitsInto(optionalA))
  }

  @Test
  fun fitsInto_untypedBranch_takesItsUnionsType() {
    assertTrue(Schema(type = Type.INTEGER, anyOf = listOf(Schema())).fitsInto(integer))
    assertFalse(
      Schema(type = Type.OBJECT, anyOf = listOf(Schema(required = listOf("id"))))
        .fitsInto(Schema(type = Type.OBJECT, required = listOf("note")))
    )
    assertFalse(
      Schema(type = Type.STRING, anyOf = listOf(Schema())).fitsInto(idOnly.copy(nullable = true))
    )
  }

  /** An untyped union with no keys or items of its own is checked by its branches alone. */
  @Test
  fun fitsInto_untypedUnionOfEnums_fitsAWiderEnumOnly() {
    // Arrange
    val colors = Schema(type = Type.STRING, enum = listOf("red", "blue"))
    val red = Schema(type = Type.STRING, enum = listOf("red"))
    val green = Schema(type = Type.STRING, enum = listOf("green"))

    // Act / Assert
    assertTrue(Schema(anyOf = listOf(red)).fitsInto(colors))
    assertTrue(Schema(anyOf = listOf(red, colors)).fitsInto(colors))
    assertFalse(Schema(anyOf = listOf(red, green)).fitsInto(colors))
  }

  @Test
  fun fitsInto_unionTarget_needsOneBranchAndItsOwnPart() {
    assertTrue(integer.fitsInto(intOrString))
    assertTrue(integer.fitsInto(Schema(type = Type.INTEGER, anyOf = listOf(integer, string))))
    assertFalse(integer.fitsInto(Schema(type = Type.STRING, anyOf = listOf(integer))))
    assertTrue(
      idOnly.fitsInto(Schema(type = Type.OBJECT, anyOf = listOf(Schema(required = listOf("id")))))
    )
    assertFalse(
      idOnly.fitsInto(Schema(type = Type.OBJECT, anyOf = listOf(Schema(required = listOf("note")))))
    )
    assertFalse(
      idOnly.fitsInto(
        Schema(
          type = Type.OBJECT,
          required = listOf("note"),
          anyOf = listOf(Schema(type = Type.OBJECT)),
        )
      )
    )
    assertFalse(
      Schema(type = Type.ARRAY, items = string)
        .fitsInto(
          Schema(type = Type.ARRAY, items = integer, anyOf = listOf(Schema(type = Type.ARRAY)))
        )
    )
  }

  /** Only identity lets a union fit itself: its own part need not fit one of its alternatives. */
  @Test
  fun fitsInto_identicalUnion_fitsItself() {
    // Arrange
    val kindAOrB =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("kind" to Schema(type = Type.STRING, enum = listOf("a", "b"))),
        anyOf = listOf(Schema(type = Type.OBJECT, properties = mapOf("kind" to string))),
      )
    val integerAnyOfNumber = Schema(type = Type.INTEGER, anyOf = listOf(Schema(type = Type.NUMBER)))
    val numberAnyOfInteger = Schema(type = Type.NUMBER, anyOf = listOf(integer))
    val bothEnums =
      Schema(
        type = Type.STRING,
        enum = listOf("a", "b"),
        anyOf = listOf(Schema(type = Type.STRING, enum = listOf("b", "c"))),
      )

    // Act / Assert
    assertTrue(idWithAnyOfA.fitsInto(idWithAnyOfA))
    assertTrue(kindAOrB.fitsInto(kindAOrB))
    assertTrue(integerAnyOfNumber.fitsInto(integerAnyOfNumber))
    assertTrue(numberAnyOfInteger.fitsInto(numberAnyOfInteger))
    assertTrue(bothEnums.fitsInto(bothEnums))
  }

  @Test
  fun readJson_readsAnyJsonAndRejectsTextThatIsNotJsonWithoutQuotingIt() {
    assertEquals(
      mapOf("a" to listOf(1L, true)),
      SchemaUtils.readJson("""{"a": [1, true]}""").getOrThrow(),
    )
    assertEquals("x", SchemaUtils.readJson("\"x\"").getOrThrow())
    assertEquals(null, SchemaUtils.readJson("null").getOrThrow())
    // The JSON parser alone takes an unquoted word as a literal.
    for (notJson in
      listOf(
        "hello world",
        "hello",
        """{"a": hello}""",
        "NaN",
        "TRUE",
        "007",
        "+5",
        ".5",
        "1.",
        "1d",
        "0x1p3",
        """{"a": 007}""",
      )) {
      assertEquals(
        "validation error: value is not valid JSON.",
        SchemaUtils.readJson(notJson).exceptionOrNull()?.message,
      )
    }
  }

  @Test
  fun acceptsString_seesAStringTypeDirectlyOrInAnAlternative() {
    assertTrue(SchemaUtils.acceptsString(Schema(type = Type.STRING)))
    assertTrue(SchemaUtils.acceptsString(Schema(anyOf = listOf(Schema(type = Type.STRING)))))
    assertFalse(SchemaUtils.acceptsString(Schema(type = Type.INTEGER)))
  }

  // -- inferSchema --

  @Test
  fun inferSchema_scalarTypes_mapsToPrimitiveSchemas() {
    // Arrange
    val types =
      listOf(
        typeOf<String>(),
        typeOf<Int?>(),
        typeOf<Short>(),
        typeOf<Double>(),
        typeOf<Boolean>(),
        typeOf<Number>(),
        typeOf<CharSequence>(),
        typeOf<Char>(),
        typeOf<ByteArray?>(),
      )

    // Act
    val schemas = types.map { SchemaUtils.inferSchema(it) }

    // Assert
    assertEquals(
      listOf(
        Schema(type = Type.STRING),
        Schema(type = Type.INTEGER, nullable = true),
        Schema(type = Type.INTEGER),
        Schema(type = Type.NUMBER),
        Schema(type = Type.BOOLEAN),
        Schema(type = Type.NUMBER),
        Schema(type = Type.STRING),
        Schema(type = Type.STRING),
        Schema(type = Type.STRING, nullable = true),
      ),
      schemas,
    )
  }

  @Test
  fun inferSchema_collectionTypes_mapToArray() {
    // Arrange
    val types =
      listOf(
        typeOf<List<String>>(),
        typeOf<MutableList<String>>(),
        typeOf<ArrayList<String>>(),
        typeOf<Array<String>>(),
        typeOf<Collection<String>>(),
        typeOf<Set<String>>(),
      )

    // Act
    val schemas = types.map { SchemaUtils.inferSchema(it) }
    val anyItems = SchemaUtils.inferSchema(typeOf<List<Any?>>())

    // Assert
    val stringArray = Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))
    assertEquals(List(types.size) { stringArray }, schemas)
    assertEquals(Schema(type = Type.ARRAY), anyItems)
  }

  @Test
  fun inferSchema_mapTypes_mapToObjectWithoutProperties() {
    // Act
    val anyValues = SchemaUtils.inferSchema(typeOf<Map<String, Any?>>())
    val intValues = SchemaUtils.inferSchema(typeOf<Map<String, Int>>())

    // Assert
    assertEquals(Schema(type = Type.OBJECT), anyValues)
    assertEquals(Schema(type = Type.OBJECT), intValues)
  }

  @Test
  fun inferSchema_serializableClass_infersPropertiesAndRequiresThoseWithoutDefaults() {
    // Act
    val schema = SchemaUtils.inferSchema(typeOf<CustomerOrder?>())

    // Assert
    val address =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("street" to Schema(type = Type.STRING), "zip_code" to Schema(type = Type.STRING)),
        required = listOf("street", "zip_code"),
        additionalProperties = false,
      )
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "id" to Schema(type = Type.INTEGER),
            "note" to Schema(type = Type.STRING, nullable = true),
            "express" to Schema(type = Type.BOOLEAN),
            "coupon" to Schema(type = Type.STRING, nullable = true),
            "address" to address,
          ),
        required = listOf("id", "coupon", "address"),
        additionalProperties = false,
        nullable = true,
      ),
      schema,
    )
  }

  @Test
  fun inferSchema_forOutput_requiresEveryFieldAtEveryLevel() {
    // Act
    val output = SchemaUtils.inferSchema(typeOf<List<Box<CustomerOrder>>>(), forOutput = true)
    val input = SchemaUtils.inferSchema(typeOf<List<Box<CustomerOrder>>>())

    // Assert
    val outputOrder = checkNotNull(output?.items?.properties?.get("item"))
    val inputOrder = checkNotNull(input?.items?.properties?.get("item"))
    assertEquals(listOf("id", "note", "express", "coupon", "address"), outputOrder.required)
    assertEquals(listOf("id", "coupon", "address"), inputOrder.required)
  }

  @Test
  fun inferSchema_enumGenericAndValueClass_infersFromTheirDescriptors() {
    // Act
    val enumSchema = SchemaUtils.inferSchema(typeOf<OrderStatus>())
    val boxSchema = SchemaUtils.inferSchema(typeOf<Box<Int>>())
    val valueClassSchema = SchemaUtils.inferSchema(typeOf<OrderId>())

    // Assert
    assertEquals(Schema(type = Type.STRING, enum = listOf("open", "shipped")), enumSchema)
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties = mapOf("item" to Schema(type = Type.INTEGER)),
        required = listOf("item"),
        additionalProperties = false,
      ),
      boxSchema,
    )
    assertEquals(Schema(type = Type.STRING), valueClassSchema)
  }

  @Test
  fun inferSchema_nestedGenericOfTheSameClass_isNotACycle() {
    // Act
    val nestedBox = SchemaUtils.inferSchema(typeOf<Box<Box<Int>>>())
    val nestedPair = SchemaUtils.inferSchema(typeOf<Pair<Pair<Int, Int>, String>>())

    // Assert
    val intBox =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("item" to Schema(type = Type.INTEGER)),
        required = listOf("item"),
        additionalProperties = false,
      )
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties = mapOf("item" to intBox),
        required = listOf("item"),
        additionalProperties = false,
      ),
      nestedBox,
    )
    val intPair =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("first" to Schema(type = Type.INTEGER), "second" to Schema(type = Type.INTEGER)),
        required = listOf("first", "second"),
        additionalProperties = false,
      )
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties = mapOf("first" to intPair, "second" to Schema(type = Type.STRING)),
        required = listOf("first", "second"),
        additionalProperties = false,
      ),
      nestedPair,
    )
  }

  @Test
  fun inferSchema_fieldWithoutDescribableShape_isUnconstrained() {
    // Act
    val schema = SchemaUtils.inferSchema(typeOf<Payload>())

    // Assert
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties = mapOf("id" to Schema(type = Type.STRING), "extra" to Schema()),
        required = listOf("id", "extra"),
        additionalProperties = false,
      ),
      schema,
    )
  }

  @Test
  fun inferSchema_jsonPrimitiveAndJsonNull_areUnconstrained() {
    // Act
    val topLevel =
      listOf(typeOf<JsonPrimitive>(), typeOf<JsonPrimitive?>(), typeOf<JsonNull>()).map {
        SchemaUtils.inferSchema(it)
      }
    val field = SchemaUtils.inferSchema(typeOf<Reading>())

    // Assert
    assertEquals(listOf(null, null, null), topLevel)
    assertEquals(
      Schema(
        type = Type.OBJECT,
        properties = mapOf("value" to Schema()),
        required = listOf("value"),
        additionalProperties = false,
      ),
      field,
    )
  }

  @Test
  fun inferSchema_typesWithoutDescribableShape_returnNull() {
    // Arrange
    val types =
      listOf(
        typeOf<Any>(),
        typeOf<Any?>(),
        typeOf<Unit>(),
        typeOf<Nothing?>(),
        typeOf<Shape>(),
        typeOf<List<*>>(),
        typeOf<Map<String, *>>(),
        typeOf<TreeNode>(),
        typeOf<LinkedItem>(),
        typeOf<UserProfileDomain>(),
      )

    // Act
    val schemas = types.map { SchemaUtils.inferSchema(it) }

    // Assert
    assertEquals(List(types.size) { null }, schemas)
  }

  @Test
  fun inferSchema_inferredSchemas_acceptJsonFormsOfTheirValues() {
    // Arrange
    val order =
      CustomerOrder(id = 7, coupon = "SAVE10", address = ShippingAddress("Main St", "00-001"))
    val samples =
      listOf(
        typeOf<String>() to "a",
        typeOf<Long>() to 1L,
        typeOf<Number>() to 1.5,
        typeOf<Set<Int>>() to setOf(1, 2),
        typeOf<OrderStatus>() to "shipped",
        typeOf<CustomerOrder>() to anyJson(order),
      )

    // Act
    val results = samples.map { (type, value) ->
      ValueCoercer.coerceValue(value, checkNotNull(SchemaUtils.inferSchema(type)), "value")
    }

    // Assert
    assertTrue(results.all { it.isSuccess })
  }

  private fun anyJson(value: CustomerOrder): Any? =
    jsonElementToAny(adkJson.encodeToJsonElement(CustomerOrder.serializer(), value))

  @Test
  fun validateValue_mapIsCheckedAndReturnedUnchanged() {
    assertEquals(
      mapOf("count" to 1),
      SchemaUtils.validateValue(mapOf("count" to 1), countSchema).getOrThrow(),
    )
    assertTrue(SchemaUtils.validateValue(mapOf("count" to "1"), countSchema).isFailure)
    assertTrue(SchemaUtils.validateValue(mapOf("count" to 1, "extra" to 2), countSchema).isFailure)
    assertTrue(
      SchemaUtils.validateValue(mapOf("count" to 1), Schema(type = Type.INTEGER)).isFailure
    )
  }

  @Test
  fun validateValue_valueThatIsNotAMap_isCheckedWithTheSameRules() {
    val integer = Schema(type = Type.INTEGER)
    val integers = Schema(type = Type.ARRAY, items = integer)
    val color = Schema(type = Type.STRING, enum = listOf("red"))

    assertEquals(42, SchemaUtils.validateValue(42, integer).getOrThrow())
    assertEquals(listOf(1), SchemaUtils.validateValue(listOf(1), integers).getOrThrow())
    assertEquals("blue", SchemaUtils.validateValue("blue", color).getOrThrow())
    assertTrue(SchemaUtils.validateValue("abc", integer).isFailure)
    assertTrue(SchemaUtils.validateValue(42.0, integer).isFailure)
    assertTrue(SchemaUtils.validateValue(listOf("a"), integers).isFailure)
    assertTrue(SchemaUtils.validateValue("""{"count": 1}""", countSchema).isFailure)
  }

  @Test
  fun validateValue_nullPasses() {
    assertEquals(null, SchemaUtils.validateValue(null, Schema(type = Type.INTEGER)).getOrThrow())
  }

  @Test
  fun validateValue_readsAContentAsJsonUnlessTheSchemaTakesAString() {
    val json = """{"count": 42}"""
    val eitherIntOrString =
      Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))

    assertEquals(
      mapOf("count" to 42L),
      SchemaUtils.validateValue(text(json), countSchema).getOrThrow(),
    )
    assertEquals(
      json,
      SchemaUtils.validateValue(text(json), Schema(type = Type.STRING)).getOrThrow(),
    )
    assertEquals("42", SchemaUtils.validateValue(text("42"), eitherIntOrString).getOrThrow())
    assertEquals("hello", SchemaUtils.validateValue(text("hello"), Schema()).getOrThrow())
    assertTrue(SchemaUtils.validateValue(text("""{"count": "42"}"""), countSchema).isFailure)
    assertTrue(SchemaUtils.validateValue(text("hello"), countSchema).isFailure)
  }

  /** Returns the failure message for [value], checking that it never quotes [secret]. */
  private fun validateValueFailureMessage(value: Any?, schema: Schema): String {
    val error = SchemaUtils.validateValue(value, schema, "input of node 'n'").exceptionOrNull()
    assertIs<IllegalArgumentException>(error)
    assertEquals(null, error.cause)
    val message = error.message.orEmpty()
    assertFalse(secret in message)
    return message
  }

  @Test
  fun validateValue_mapFailure_namesTheDeclaredKeyOrTheSchemaButNeverTheValue() {
    val person =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("name" to Schema(type = Type.STRING)),
        required = listOf("name"),
      )
    val prefix = "validation error: input of node 'n' does not match its schema: "

    assertEquals(
      prefix +
        "Value arg: count type does not match value schema: Schema(type=OBJECT, properties=[count])",
      validateValueFailureMessage(mapOf("count" to secret), countSchema),
    )
    assertEquals(
      prefix + "Value args does not contain required name",
      validateValueFailureMessage(mapOf<String, Any?>(), person),
    )
    assertEquals(
      prefix + "Value schema does not describe an object: Schema(type=INTEGER)",
      validateValueFailureMessage(mapOf("count" to secret), Schema(type = Type.INTEGER)),
    )
  }

  @Test
  fun validateValue_undeclaredKey_isReportedWithoutItsName() {
    assertEquals(
      "validation error: input of node 'n' does not match its schema: " +
        "it has a key the schema does not declare",
      validateValueFailureMessage(mapOf("count" to 1, secret to 2), countSchema),
    )
  }

  @Test
  fun validateValue_scalarFailure_isGenericUnlessTheSchemaIsTooComplex() {
    // Each level reuses its child on both branches, so 12 levels describe 4096 combinations.
    var nested = Schema(type = Type.STRING)
    repeat(12) { nested = Schema(anyOf = listOf(nested, nested)) }

    assertEquals(
      "validation error: input of node 'n' does not match its schema.",
      validateValueFailureMessage(secret, Schema(type = Type.INTEGER)),
    )
    assertContains(
      validateValueFailureMessage(listOf(secret), nested),
      "schema is too complex to validate",
    )
  }

  @Test
  fun validateValue_setForArraySchema_succeeds() {
    // Act
    val result =
      SchemaUtils.validateValue(
        setOf(1, 2),
        Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER)),
        "ids",
      )

    // Assert
    assertTrue(result.isSuccess)
  }
}
