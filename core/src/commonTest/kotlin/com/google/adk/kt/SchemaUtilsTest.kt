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
  private fun failureMessage(value: Any?, schema: Schema): String {
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
      failureMessage(mapOf("count" to secret), countSchema),
    )
    assertEquals(
      prefix + "Value args does not contain required name",
      failureMessage(mapOf<String, Any?>(), person),
    )
    assertEquals(
      prefix + "Value schema does not describe an object: Schema(type=INTEGER)",
      failureMessage(mapOf("count" to secret), Schema(type = Type.INTEGER)),
    )
  }

  @Test
  fun validateValue_undeclaredKey_isReportedWithoutItsName() {
    assertEquals(
      "validation error: input of node 'n' does not match its schema: " +
        "it has a key the schema does not declare",
      failureMessage(mapOf("count" to 1, secret to 2), countSchema),
    )
  }

  @Test
  fun validateValue_scalarFailure_isGenericUnlessTheSchemaIsTooComplex() {
    // Each level reuses its child on both branches, so 12 levels describe 4096 combinations.
    var nested = Schema(type = Type.STRING)
    repeat(12) { nested = Schema(anyOf = listOf(nested, nested)) }

    assertEquals(
      "validation error: input of node 'n' does not match its schema.",
      failureMessage(secret, Schema(type = Type.INTEGER)),
    )
    assertContains(failureMessage(listOf(secret), nested), "schema is too complex to validate")
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
    for (notJson in listOf("hello world", "hello", """{"a": hello}""", "NaN")) {
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
}
