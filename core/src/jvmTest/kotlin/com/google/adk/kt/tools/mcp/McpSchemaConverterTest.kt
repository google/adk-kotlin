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

import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkFunctionDeclaration
import com.google.adk.kt.tools.mcp.McpSchemaConverter.toAdkSchema
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpSchemaConverterTest {

  @Test
  fun parseTypeString_knownTypeName_returnsMatchingType() {
    assertEquals(Type.STRING, McpSchemaConverter.parseTypeString("string"))
    assertEquals(Type.INTEGER, McpSchemaConverter.parseTypeString("integer"))
    assertEquals(Type.NUMBER, McpSchemaConverter.parseTypeString("number"))
    assertEquals(Type.BOOLEAN, McpSchemaConverter.parseTypeString("boolean"))
    assertEquals(Type.ARRAY, McpSchemaConverter.parseTypeString("array"))
    assertEquals(Type.OBJECT, McpSchemaConverter.parseTypeString("object"))
  }

  @Test
  fun parseTypeString_nullTypeName_returnsNullType() {
    assertEquals(Type.NULL, McpSchemaConverter.parseTypeString("null"))
  }

  @Test
  fun parseTypeString_absentType_returnsTypeUnspecified() {
    assertEquals(Type.TYPE_UNSPECIFIED, McpSchemaConverter.parseTypeString(null))
  }

  @Test
  fun parseTypeString_unknownTypeName_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> { McpSchemaConverter.parseTypeString("unknown") }
  }

  // Type unions.

  @Test
  fun parsePropertyMap_typeUnionStartingWithNull_usesTheNonNullType() {
    val property = mapOf<String, Any>("type" to listOf("null", "string"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.type)
  }

  @Test
  fun parsePropertyMap_typeUnionEndingWithNull_usesTheNonNullType() {
    val property = mapOf<String, Any>("type" to listOf("integer", "null"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.INTEGER, converted.type)
  }

  @Test
  fun parsePropertyMap_splitUnionBranch_carriesTheKeywordsThatDescribeIt() {
    // Each branch has to take the keywords for its own type; without that the split would hand the
    // model two bare types and lose everything the server said about them.
    val property =
      mapOf<String, Any>(
        "type" to listOf("string", "object"),
        "description" to "an id",
        "enum" to listOf("EAST", "WEST"),
        "properties" to mapOf("inner" to mapOf("type" to "string")),
        "required" to listOf("inner"),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    val branches = requireNotNull(converted.anyOf)
    val stringBranch = branches.single { it.type == Type.STRING }
    assertEquals("an id", stringBranch.description)
    assertEquals(listOf("EAST", "WEST"), stringBranch.enum)
    val objectBranch = branches.single { it.type == Type.OBJECT }
    assertEquals(Type.STRING, objectBranch.properties?.get("inner")?.type)
    assertEquals(listOf("inner"), objectBranch.required)
    // `enum` describes a string, not an object, so it must not ride along onto the object branch.
    assertNull(objectBranch.enum)
  }

  @Test
  fun parsePropertyMap_booleanFalseSubSchema_becomesAnOpenObject() {
    // Python maps `false` to an object too; dropping it would take the argument off the contract.
    val property = mapOf<String, Any>("type" to "object", "properties" to mapOf("never" to false))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.OBJECT, converted.properties?.get("never")?.type)
  }

  @Test
  fun parsePropertyMap_booleanItems_isNotTreatedAsAnArrayOfStrings() {
    // A boolean sub-schema is legal wherever a schema is, not only under `properties`.
    val property = mapOf<String, Any>("type" to "array", "items" to true)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.OBJECT, converted.items?.type)
  }

  @Test
  fun parsePropertyMap_splitUnionArrayBranch_keepsDeclaredItems() {
    // The array branch has to take the declared `items`, not fall back to the string default.
    val property =
      mapOf<String, Any>("type" to listOf("string", "array"), "items" to mapOf("type" to "integer"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    val arrayBranch = requireNotNull(converted.anyOf).single { it.type == Type.ARRAY }
    assertEquals(Type.INTEGER, arrayBranch.items?.type)
  }

  @Test
  fun parsePropertyMap_booleanTrueSubSchema_becomesAnOpenObject() {
    // `true` is legal JSON Schema meaning "anything goes"; the argument must stay on the contract.
    val property =
      mapOf<String, Any>(
        "type" to "object",
        "properties" to mapOf("free" to true),
        "required" to listOf("free"),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.OBJECT, converted.properties?.get("free")?.type)
    assertEquals(listOf("free"), converted.required)
  }

  @Test
  fun parsePropertyMap_typeUnionContainingArray_splitsIntoBranches() {
    val property = mapOf<String, Any>("type" to listOf("string", "array"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    // Every member keeps a branch, so the array no longer has to be preferred over the rest.
    assertEquals(listOf(Type.STRING, Type.ARRAY), converted.anyOf?.map { it.type })
    assertNull(converted.type)
  }

  @Test
  fun parsePropertyMap_typeUnionOfScalars_splitsIntoBranches() {
    val property = mapOf<String, Any>("type" to listOf("string", "integer"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf(Type.STRING, Type.INTEGER), converted.anyOf?.map { it.type })
  }

  @Test
  fun parsePropertyMap_nullableUnionOfSeveralTypes_splitsAndStaysNullable() {
    val property = mapOf<String, Any>("type" to listOf("null", "string", "integer"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf(Type.STRING, Type.INTEGER), converted.anyOf?.map { it.type })
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_unionWithNullableKeyword_splitsAndStaysNullable() {
    // Nullability spelled as the keyword rather than as a `"null"` member of the union.
    val property = mapOf<String, Any>("type" to listOf("string", "integer"), "nullable" to true)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf(Type.STRING, Type.INTEGER), converted.anyOf?.map { it.type })
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_typeUnionOfOnlyNull_returnsNullType() {
    val property = mapOf<String, Any>("type" to listOf("null"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.NULL, converted.type)
  }

  @Test
  fun parsePropertyMap_typeUnionWithAnUnknownMember_keepsTheMemberItKnows() {
    // Splitting the union must not make a schema fail that used to convert: picking the first
    // member gave `string` here, and ADK Python still does exactly that.
    val property = mapOf<String, Any>("type" to listOf("string", "temperature"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.type)
    assertNull(converted.anyOf)
  }

  @Test
  fun parsePropertyMap_typeUnionWithAnUnknownMemberFirst_keepsTheMemberItKnows() {
    // The surviving member does not depend on where the unknown one sits, unlike picking the first.
    val property = mapOf<String, Any>("type" to listOf("temperature", "string"))

    assertEquals(Type.STRING, McpSchemaConverter.parsePropertyMap(property).type)
  }

  @Test
  fun parsePropertyMap_typeUnionWithAnUnknownMemberAmongSeveral_splitsTheRest() {
    val property = mapOf<String, Any>("type" to listOf("string", "temperature", "integer"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf(Type.STRING, Type.INTEGER), converted.anyOf?.map { it.type })
  }

  @Test
  fun parsePropertyMap_nullableTypeUnionWithAnUnknownMember_staysNullable() {
    val property = mapOf<String, Any>("type" to listOf("null", "string", "temperature"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.type)
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_typeUnionOfOnlyUnknownMembers_throwsIllegalArgument() {
    // Nothing is left to describe the value, so this fails the way a single unknown type does
    // rather than quietly becoming an unconstrained argument.
    val property = mapOf<String, Any>("type" to listOf("temperature", "pressure"))

    assertFailsWith<IllegalArgumentException> { McpSchemaConverter.parsePropertyMap(property) }
  }

  @Test
  fun parsePropertyMap_singleUnknownType_throwsIllegalArgument() {
    // Deliberate, and what ADK Python does: a parameter contract nothing could convert would leave
    // the model calling the tool against a description that was never validated.
    val property = mapOf<String, Any>("type" to "temperature")

    assertFailsWith<IllegalArgumentException> { McpSchemaConverter.parsePropertyMap(property) }
  }

  @Test
  fun parsePropertyMap_emptyTypeUnion_leavesTypeUnset() {
    val property = mapOf<String, Any>("type" to emptyList<String>())

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertNull(converted.type)
  }

  @Test
  fun parsePropertyMap_absentType_leavesTypeUnset() {
    // Sent as an absent field rather than as the name `TYPE_UNSPECIFIED`, which would put a
    // literal `"type"` on the wire that says nothing.
    val converted = McpSchemaConverter.parsePropertyMap(mapOf("description" to "no type here"))

    assertNull(converted.type)
  }

  @Test
  fun parsePropertyMap_scalarType_usesThatType() {
    val converted = McpSchemaConverter.parsePropertyMap(mapOf("type" to "boolean"))

    assertEquals(Type.BOOLEAN, converted.type)
  }

  @Test
  fun parsePropertyMap_singleElementTypeUnion_usesThatType() {
    val converted = McpSchemaConverter.parsePropertyMap(mapOf("type" to listOf("integer")))

    assertEquals(Type.INTEGER, converted.type)
  }

  // enum.

  @Test
  fun parsePropertyMap_stringEnum_preservesTheAllowedValues() {
    val property = mapOf<String, Any>("type" to "string", "enum" to listOf("EAST", "WEST"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf("EAST", "WEST"), converted.enum)
  }

  @Test
  fun parsePropertyMap_numericEnum_rendersTheValuesAsStrings() {
    val property = mapOf<String, Any>("type" to "integer", "enum" to listOf(101, 201))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf("101", "201"), converted.enum)
  }

  @Test
  fun parsePropertyMap_enumContainingNull_dropsTheNullMember() {
    val property = mapOf<String, Any>("type" to "string", "enum" to listOf("a", null, "b"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf("a", "b"), converted.enum)
  }

  @Test
  fun parsePropertyMap_emptyEnum_leavesEnumUnset() {
    val property = mapOf<String, Any>("type" to "string", "enum" to emptyList<String>())

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertNull(converted.enum)
  }

  @Test
  fun parsePropertyMap_absentEnum_leavesEnumUnset() {
    val converted = McpSchemaConverter.parsePropertyMap(mapOf("type" to "string"))

    assertNull(converted.enum)
  }

  // Structure.

  @Test
  fun parsePropertyMap_nestedObject_convertsTheNestedProperties() {
    val property =
      mapOf<String, Any>(
        "type" to "object",
        "properties" to mapOf("inner" to mapOf("type" to "boolean")),
        "required" to listOf("inner"),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.BOOLEAN, converted.properties?.get("inner")?.type)
    assertEquals(listOf("inner"), converted.required)
  }

  @Test
  fun parsePropertyMap_arraySchema_convertsTheItemSchema() {
    val property = mapOf<String, Any>("type" to "array", "items" to mapOf("type" to "string"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.items?.type)
  }

  @Test
  fun parsePropertyMap_arrayWithoutItems_defaultsItemsToString() {
    val property = mapOf<String, Any>("type" to "array")

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.items?.type)
  }

  @Test
  fun parsePropertyMap_typeUnionWithArrayBranchWithoutItems_defaultsItemsToString() {
    val property = mapOf<String, Any>("type" to listOf("string", "array"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    // The array branch is a schema like any other, so it gets the same `items` default.
    val arrayBranch = converted.anyOf?.single { it.type == Type.ARRAY }
    assertEquals(Type.STRING, arrayBranch?.items?.type)
  }

  @Test
  fun parsePropertyMap_nullableArrayWithoutItems_defaultsItemsToString() {
    val property = mapOf<String, Any>("type" to listOf("null", "array"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.ARRAY, converted.type)
    assertEquals(Type.STRING, converted.items?.type)
  }

  @Test
  fun parsePropertyMap_nonArrayWithoutItems_leavesItemsUnset() {
    val property = mapOf<String, Any>("type" to "string")

    assertNull(McpSchemaConverter.parsePropertyMap(property).items)
  }

  @Test
  fun parsePropertyMap_descriptionPresent_preservesTheDescription() {
    val property = mapOf<String, Any>("type" to "string", "description" to "the message")

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals("the message", converted.description)
  }

  @Test
  fun parsePropertyMap_requiredNamesADroppedProperty_removesItFromRequired() {
    // A property whose sub-schema is not a schema at all is dropped, and the backend rejects a
    // `required` entry naming a property the schema does not define.
    val property =
      mapOf<String, Any>(
        "type" to "object",
        "properties" to mapOf("kept" to mapOf("type" to "string"), "dropped" to "not-a-schema"),
        "required" to listOf("kept", "dropped"),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf("kept"), converted.required)
  }

  @Test
  fun parsePropertyMap_requiredWithoutAnyProperties_clearsRequired() {
    val property = mapOf<String, Any>("type" to "object", "required" to listOf("missing"))

    // Unset rather than empty: an empty `required` says nothing, so there is no reason to send it.
    assertNull(McpSchemaConverter.parsePropertyMap(property).required)
  }

  @Test
  fun parsePropertyMap_propertyThatIsNotAnObject_dropsThatProperty() {
    val property =
      mapOf<String, Any>("type" to "object", "properties" to mapOf("bogus" to "not a schema"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(emptyMap(), converted.properties)
  }

  // Recursion guard.

  @Test
  fun parsePropertyMap_schemaWithinMaxDepth_keepsEveryLevel() {
    val schema = nestedObjectSchema(levels = 10)

    val converted = McpSchemaConverter.parsePropertyMap(schema)

    assertEquals(10, schemaChainLength(converted))
  }

  @Test
  fun parsePropertyMap_schemaDeeperThanMaxDepth_truncatesInsteadOfRecursing() {
    val schema = nestedObjectSchema(levels = 500)

    val converted = McpSchemaConverter.parsePropertyMap(schema)

    // Level n is parsed at depth n-1, and the guard truncates at depth MAX_SCHEMA_DEPTH (32), so
    // the chain stops at level 33. Asserted exactly so that lowering the limit fails the test.
    assertEquals(33, schemaChainLength(converted))
  }

  // JsonSchema (a tool's top-level inputSchema).

  @Test
  fun toAdkSchema_objectSchema_convertsTypePropertiesAndRequired() {
    val inputSchema =
      jsonSchema(properties = mapOf("a" to mapOf("type" to "integer")), required = listOf("a"))

    val converted = inputSchema.toAdkSchema()

    assertEquals(Type.OBJECT, converted.type)
    assertEquals(Type.INTEGER, converted.properties?.get("a")?.type)
    assertEquals(listOf("a"), converted.required)
  }

  @Test
  fun toAdkSchema_propertyWithNullTypeUnion_doesNotThrow() {
    val inputSchema =
      jsonSchema(properties = mapOf("maybe" to mapOf("type" to listOf("null", "string"))))

    val converted = inputSchema.toAdkSchema()

    assertEquals(Type.STRING, converted.properties?.get("maybe")?.type)
  }

  @Test
  fun toAdkSchema_requiredNamesAnUndeclaredProperty_removesItFromRequired() {
    val inputSchema =
      jsonSchema(
        properties = mapOf("a" to mapOf("type" to "integer")),
        required = listOf("a", "missing"),
      )

    assertEquals(listOf("a"), inputSchema.toAdkSchema().required)
  }

  @Test
  fun toAdkSchema_arrayTypedInputSchema_defaultsItemsToString() {
    // The JsonSchema record has no `items` component, so an array can only take the default.
    val inputSchema =
      McpSchema.JsonSchema(
        /* type= */ "array",
        /* properties= */ null,
        /* required= */ null,
        /* additionalProperties= */ null,
        /* defs= */ null,
        /* definitions= */ null,
      )

    assertEquals(Type.STRING, inputSchema.toAdkSchema().items?.type)
  }

  // FunctionDeclaration.

  @Test
  fun toAdkFunctionDeclaration_toolWithInputSchema_setsNameDescriptionAndParameters() {
    val tool =
      McpSchema.Tool.builder()
        .name("add")
        .description("Adds two numbers.")
        .inputSchema(jsonSchema(properties = mapOf("a" to mapOf("type" to "integer"))))
        .build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertEquals("add", declaration.name)
    assertEquals("Adds two numbers.", declaration.description)
    assertEquals(Type.INTEGER, declaration.parameters?.properties?.get("a")?.type)
  }

  @Test
  fun toAdkFunctionDeclaration_toolWithoutDescription_usesAnEmptyDescription() {
    val tool = McpSchema.Tool.builder().name("bare").build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertEquals("", declaration.description)
  }

  @Test
  fun toAdkFunctionDeclaration_toolWithoutInputSchema_leavesParametersUnset() {
    val tool = McpSchema.Tool.builder().name("bare").build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertNull(declaration.parameters)
  }

  @Test
  fun toAdkFunctionDeclaration_toolWithNullTypedProperty_doesNotThrow() {
    val tool =
      McpSchema.Tool.builder()
        .name("nullable")
        .inputSchema(jsonSchema(properties = mapOf("maybe" to mapOf("type" to "null"))))
        .build()

    val declaration = tool.toAdkFunctionDeclaration()

    val parameters = assertNotNull(declaration.parameters)
    assertEquals(Type.NULL, parameters.properties?.get("maybe")?.type)
  }

  @Test
  fun parsePropertyMap_stringConstraints_preservesPatternAndLengthBounds() {
    val property =
      mapOf<String, Any>(
        "type" to "string",
        "pattern" to "^[a-z]+$",
        "minLength" to 2,
        "maxLength" to 8,
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals("^[a-z]+$", converted.pattern)
    assertEquals(2L, converted.minLength)
    assertEquals(8L, converted.maxLength)
  }

  @Test
  fun parsePropertyMap_numericConstraints_preservesMinimumAndMaximum() {
    val property = mapOf<String, Any>("type" to "integer", "minimum" to 1, "maximum" to 10)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(1.0, converted.minimum)
    assertEquals(10.0, converted.maximum)
  }

  @Test
  fun parsePropertyMap_arrayConstraints_preservesItemCountBounds() {
    val property = mapOf<String, Any>("type" to "array", "minItems" to 1, "maxItems" to 5)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(1L, converted.minItems)
    assertEquals(5L, converted.maxItems)
  }

  @Test
  fun parsePropertyMap_objectConstraints_preservesPropertyCountBounds() {
    val property =
      mapOf<String, Any>("type" to "object", "minProperties" to 1, "maxProperties" to 5)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(1L, converted.minProperties)
    assertEquals(5L, converted.maxProperties)
  }

  @Test
  fun parsePropertyMap_nonNumericConstraintValue_leavesTheConstraintUnset() {
    val property = mapOf<String, Any>("type" to "integer", "minimum" to "not-a-number")

    assertNull(McpSchemaConverter.parsePropertyMap(property).minimum)
  }

  @Test
  fun parsePropertyMap_titleAndDefault_preservesBoth() {
    val property = mapOf<String, Any>("type" to "string", "title" to "City", "default" to "Zurich")

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals("City", converted.title)
    assertEquals("Zurich", converted.default)
  }

  @Test
  fun parsePropertyMap_explicitNullableFlag_preservesIt() {
    val property = mapOf<String, Any>("type" to "string", "nullable" to true)

    assertEquals(true, McpSchemaConverter.parsePropertyMap(property).nullable)
  }

  @Test
  fun parsePropertyMap_typeUnionWithNull_marksTheSchemaNullable() {
    val property = mapOf<String, Any>("type" to listOf("string", "null"))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.type)
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_typeUnionWithoutNull_leavesNullableUnset() {
    val property = mapOf<String, Any>("type" to listOf("string", "integer"))

    assertNull(McpSchemaConverter.parsePropertyMap(property).nullable)
  }

  @Test
  fun parsePropertyMap_integerFormatSupportedByGemini_preservesIt() {
    val property = mapOf<String, Any>("type" to "integer", "format" to "int64")

    assertEquals("int64", McpSchemaConverter.parsePropertyMap(property).format)
  }

  @Test
  fun parsePropertyMap_stringFormatSupportedByGemini_preservesIt() {
    val property = mapOf<String, Any>("type" to "string", "format" to "date-time")

    assertEquals("date-time", McpSchemaConverter.parsePropertyMap(property).format)
  }

  @Test
  fun parsePropertyMap_stringFormatGeminiRejects_dropsIt() {
    val property = mapOf<String, Any>("type" to "string", "format" to "uri")

    assertNull(McpSchemaConverter.parsePropertyMap(property).format)
  }

  @Test
  fun parsePropertyMap_integerFormatGeminiRejectsForNumbers_dropsIt() {
    val property = mapOf<String, Any>("type" to "integer", "format" to "date-time")

    assertNull(McpSchemaConverter.parsePropertyMap(property).format)
  }

  @Test
  fun parsePropertyMap_anyOfMembers_convertsEachSubschema() {
    val property =
      mapOf<String, Any>("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer")))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(2, converted.anyOf?.size)
    assertEquals(Type.STRING, converted.anyOf?.get(0)?.type)
    assertEquals(Type.INTEGER, converted.anyOf?.get(1)?.type)
  }

  @Test
  fun parsePropertyMap_anyOfMemberThatIsNotAnObject_dropsThatMember() {
    val property = mapOf<String, Any>("anyOf" to listOf(mapOf("type" to "string"), "bogus"))

    assertEquals(1, McpSchemaConverter.parsePropertyMap(property).anyOf?.size)
  }

  @Test
  fun parsePropertyMap_absentAnyOf_leavesAnyOfUnset() {
    assertNull(McpSchemaConverter.parsePropertyMap(mapOf<String, Any>("type" to "string")).anyOf)
  }

  @Test
  fun parsePropertyMap_typeUnionCarryingPerTypeConstraints_putsEachOnItsOwnBranch() {
    val property =
      mapOf<String, Any>("type" to listOf("string", "integer"), "maxLength" to 5, "maximum" to 10)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    // Nothing is discarded: the string bound lands on the string branch and the numeric bound on
    // the integer one, which is what Python's `related_field_names_by_type` split produces.
    val stringBranch = converted.anyOf?.single { it.type == Type.STRING }
    val integerBranch = converted.anyOf?.single { it.type == Type.INTEGER }
    assertEquals(5L, stringBranch?.maxLength)
    assertNull(stringBranch?.maximum)
    assertEquals(10.0, integerBranch?.maximum)
    assertNull(integerBranch?.maxLength)
  }

  @Test
  fun parsePropertyMap_constraintForAnotherType_isDropped() {
    val property = mapOf<String, Any>("type" to "string", "minItems" to 3, "minimum" to 1)

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertNull(converted.minItems)
    assertNull(converted.minimum)
  }

  @Test
  fun parsePropertyMap_patternOnANonStringType_isDropped() {
    // `pattern` only describes a string, so hanging it off an integer would declare a constraint
    // the backend cannot apply to that type.
    val property = mapOf<String, Any>("type" to "integer", "pattern" to "^[0-9]+$")

    assertNull(McpSchemaConverter.parsePropertyMap(property).pattern)
  }

  @Test
  fun parsePropertyMap_anyOfOfOnlyARefAndNull_dropsTheUnionButStaysNullable() {
    // How Pydantic spells Optional[Foo]. `$defs` are not resolved, so the ref member carries
    // nothing a model can act on; keeping the lone "null" member would say null is the only legal
    // value, so the whole union is dropped. The nullability still survives -- only the referenced
    // type is lost.
    val property =
      mapOf<String, Any>(
        "anyOf" to listOf(mapOf("\$ref" to "#/\$defs/Foo"), mapOf("type" to "null"))
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertNull(converted.anyOf)
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_anyOfOfRealTypeAndNull_foldsIntoANullableSchema() {
    // How Pydantic spells Optional[str].
    val property =
      mapOf<String, Any>("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null")))

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.type)
    assertEquals(true, converted.nullable)
    assertNull(converted.anyOf)
  }

  @Test
  fun parsePropertyMap_anyOfOfSeveralRealTypesAndNull_keepsTheUnionAndMarksItNullable() {
    val property =
      mapOf<String, Any>(
        "anyOf" to
          listOf(mapOf("type" to "string"), mapOf("type" to "integer"), mapOf("type" to "null"))
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(listOf(Type.STRING, Type.INTEGER), converted.anyOf?.map { it.type })
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_unionSpelledEitherWay_leavesTypeUnset() {
    // A union describes the value through its members, so neither spelling has a type of its own.
    val viaType = McpSchemaConverter.parsePropertyMap(mapOf("type" to listOf("string", "integer")))
    val viaAnyOf =
      McpSchemaConverter.parsePropertyMap(
        mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer")))
      )

    assertNull(viaType.type)
    assertNull(viaAnyOf.type)
    assertEquals(listOf(Type.STRING, Type.INTEGER), viaAnyOf.anyOf?.map { it.type })
  }

  @Test
  fun parsePropertyMap_anyOfFoldedIntoSchema_keepsTheOuterDescription() {
    val property =
      mapOf<String, Any>(
        "description" to "the note",
        "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null")),
      )

    assertEquals("the note", McpSchemaConverter.parsePropertyMap(property).description)
  }

  @Test
  fun parsePropertyMap_anyOfFoldClashesOnDescription_theMemberWins() {
    // A description inside the arm describes that type; one outside describes the union. Folding
    // the union into its arm makes the arm's own description the more specific of the two.
    val property =
      mapOf<String, Any>(
        "description" to "the union",
        "anyOf" to
          listOf(mapOf("type" to "string", "description" to "the arm"), mapOf("type" to "null")),
      )

    assertEquals("the arm", McpSchemaConverter.parsePropertyMap(property).description)
  }

  @Test
  fun parsePropertyMap_typeDeclaredBesideAnyOfAndNull_doesNotFold() {
    // The fold replaces the schema with its sole arm, so it is only safe when the schema has no
    // type of its own. Here it has one, and folding would throw that type away.
    val property =
      mapOf<String, Any>(
        "type" to "object",
        "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null")),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.OBJECT, converted.type)
    assertEquals(listOf(Type.STRING), converted.anyOf?.map { it.type })
    assertEquals(true, converted.nullable)
  }

  @Test
  fun parsePropertyMap_anyOfOfOnlyRefs_leavesAnyOfUnset() {
    val property = mapOf<String, Any>("anyOf" to listOf(mapOf("\$ref" to "#/\$defs/Foo")))

    assertNull(McpSchemaConverter.parsePropertyMap(property).anyOf)
  }

  @Test
  fun parsePropertyMap_anyOfMemberDescribedByProperties_isKept() {
    val property =
      mapOf<String, Any>(
        "anyOf" to listOf(mapOf("properties" to mapOf("a" to mapOf("type" to "string"))))
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.anyOf?.single()?.properties?.get("a")?.type)
  }

  @Test
  fun toAdkFunctionDeclaration_toolWithOutputSchema_convertsItToResponse() {
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(
          mapOf(
            "type" to "object",
            "properties" to mapOf("tempC" to mapOf("type" to "number", "minimum" to -90)),
            "required" to listOf("tempC"),
          )
        )
        .build()

    val response = tool.toAdkFunctionDeclaration().response

    assertEquals(Type.OBJECT, response?.type)
    assertEquals(listOf("tempC"), response?.required)
    assertEquals(Type.NUMBER, response?.properties?.get("tempC")?.type)
    assertEquals(-90.0, response?.properties?.get("tempC")?.minimum)
  }

  @Test
  fun toAdkFunctionDeclaration_toolWithoutOutputSchema_leavesResponseUnset() {
    val tool = McpSchema.Tool.builder().name("weather").inputSchema(jsonSchema()).build()

    assertNull(tool.toAdkFunctionDeclaration().response)
  }

  @Test
  fun toAdkFunctionDeclaration_outputSchemaWithUnknownType_keepsTheDeclaration() {
    // Only the output schema is lost; the tool stays callable.
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(mapOf("type" to "temperature"))
        .build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertEquals("weather", declaration.name)
    assertNull(declaration.response)
    assertEquals(Type.OBJECT, declaration.parameters?.type)
  }

  @Test
  fun toAdkFunctionDeclaration_outputSchemaWithUnknownNestedType_keepsTheDeclaration() {
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(
          mapOf(
            "type" to "object",
            "properties" to mapOf("tempC" to mapOf("type" to "temperature")),
          )
        )
        .build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertEquals("weather", declaration.name)
    assertNull(declaration.response)
  }

  @Test
  fun parsePropertyMap_anyOfMemberWithUnknownType_dropsThatMember() {
    val property =
      mapOf<String, Any>(
        "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "temperature"))
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.STRING, converted.anyOf?.single()?.type)
  }

  @Test
  fun parsePropertyMap_anyOfFoldedIntoSchema_keepsTheOuterDefault() {
    // `Optional[int] = 5` puts the default outside the union.
    val property =
      mapOf<String, Any>(
        "default" to 5,
        "anyOf" to listOf(mapOf("type" to "integer"), mapOf("type" to "null")),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertEquals(Type.INTEGER, converted.type)
    assertEquals(true, converted.nullable)
    assertEquals(5, converted.default)
  }

  @Test
  fun parsePropertyMap_anyOfFoldClashesOnDefault_theMemberWins() {
    // Same rule as the description: a default written inside the arm belongs to that type, so it
    // outranks the one written beside the union.
    val property =
      mapOf<String, Any>(
        "default" to 5,
        "anyOf" to listOf(mapOf("type" to "integer", "default" to 1), mapOf("type" to "null")),
      )

    assertEquals(1, McpSchemaConverter.parsePropertyMap(property).default)
  }

  @Test
  fun toAdkFunctionDeclaration_outputSchemaContainingAnyOf_isDropped() {
    // Vertex rejects a response schema carrying anyOf, which fails every call the agent makes.
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(
          mapOf(
            "type" to "object",
            "properties" to mapOf("temp" to mapOf("type" to listOf("string", "integer"))),
          )
        )
        .build()

    val declaration = tool.toAdkFunctionDeclaration()

    assertNull(declaration.response)
    assertEquals("weather", declaration.name)
  }

  @Test
  fun toAdkFunctionDeclaration_emptyOutputSchema_leavesResponseUnset() {
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(mapOf())
        .build()

    assertNull(tool.toAdkFunctionDeclaration().response)
  }

  @Test
  fun toAdkFunctionDeclaration_outputSchemaWithItsOwnDefs_resolvesTheRef() {
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(jsonSchema())
        .outputSchema(
          mapOf(
            "type" to "object",
            "properties" to mapOf("city" to mapOf("\$ref" to "#/\$defs/City")),
            "\$defs" to mapOf("City" to mapOf("type" to "string", "maxLength" to 20)),
          )
        )
        .build()

    val response = tool.toAdkFunctionDeclaration().response

    assertEquals(Type.STRING, response?.properties?.get("city")?.type)
    assertEquals(20L, response?.properties?.get("city")?.maxLength)
  }

  @Test
  fun toAdkSchema_defsAndDefinitionsClash_prefersDefs() {
    // `$defs` is the current spelling, so it wins a name collision -- as it does in Python. The
    // record carries both blocks, so the converter decides the precedence rather than the caller.
    val inputSchema =
      McpSchema.JsonSchema(
        /* type= */ "object",
        /* properties= */ mapOf("where" to mapOf("\$ref" to "#/\$defs/Foo")),
        /* required= */ null,
        /* additionalProperties= */ null,
        /* defs= */ mapOf("Foo" to mapOf("type" to "string")),
        /* definitions= */ mapOf("Foo" to mapOf("type" to "integer")),
      )

    val converted = inputSchema.toAdkSchema()

    assertEquals(Type.STRING, converted.properties?.get("where")?.type)
  }

  @Test
  fun toResponseSchema_defsAndDefinitionsClash_prefersDefs() {
    // An output schema arrives as a raw map, where both blocks are ordinary keys rather than record
    // components, so the precedence is decided on a second code path.
    val tool =
      McpSchema.Tool.builder()
        .name("t")
        .description("d")
        .inputSchema(jsonSchema())
        .outputSchema(
          mapOf(
            "type" to "object",
            "properties" to mapOf("where" to mapOf("\$ref" to "#/\$defs/Foo")),
            "definitions" to mapOf("Foo" to mapOf("type" to "integer")),
            "\$defs" to mapOf("Foo" to mapOf("type" to "string")),
          )
        )
        .build()

    val response = tool.toAdkFunctionDeclaration().response

    assertEquals(Type.STRING, response?.properties?.get("where")?.type)
  }

  @Test
  fun parsePropertyMap_refIntoDefs_resolvesToTheReferencedSchema() {
    val definitions = mapOf<String, Any>("Foo" to mapOf("type" to "string", "maxLength" to 4))
    val property = mapOf<String, Any>("\$ref" to "#/\$defs/Foo")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.STRING, converted.type)
    assertEquals(4L, converted.maxLength)
  }

  @Test
  fun parsePropertyMap_refIntoLegacyDefinitions_resolves() {
    val definitions = mapOf<String, Any>("Foo" to mapOf("type" to "integer"))
    val property = mapOf<String, Any>("\$ref" to "#/definitions/Foo")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.INTEGER, converted.type)
  }

  @Test
  fun parsePropertyMap_keywordsBesideARef_refineTheReferencedSchema() {
    val definitions = mapOf<String, Any>("Foo" to mapOf("type" to "string"))
    val property = mapOf<String, Any>("\$ref" to "#/\$defs/Foo", "description" to "the foo")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.STRING, converted.type)
    assertEquals("the foo", converted.description)
  }

  @Test
  fun parsePropertyMap_keywordBesideARefClashesWithIt_theSiblingWins() {
    // Both sides spell the same keyword. The sibling is what the server wrote at this use site, so
    // it refines the shared definition rather than the other way round.
    val definitions =
      mapOf<String, Any>("Foo" to mapOf("type" to "string", "description" to "the shared one"))
    val property =
      mapOf<String, Any>("\$ref" to "#/\$defs/Foo", "description" to "the one written here")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals("the one written here", converted.description)
  }

  @Test
  fun parsePropertyMap_unresolvableRef_leavesTheSchemaUntyped() {
    val property = mapOf<String, Any>("\$ref" to "#/\$defs/Missing")

    val converted = McpSchemaConverter.parsePropertyMap(property)

    assertNull(converted.type)
  }

  @Test
  fun parsePropertyMap_selfReferentialRef_isCutWhereItCloses() {
    val definitions = mapOf<String, Any>("Node" to mapOf("\$ref" to "#/\$defs/Node"))
    val property = mapOf<String, Any>("\$ref" to "#/\$defs/Node")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.OBJECT, converted.type)
    assertEquals("Circular ref to Node", converted.description)
  }

  @Test
  fun parsePropertyMap_recursiveModel_doesNotExpandUntilTheDepthLimit() {
    // A tree node holding more of itself. Without cycle detection this expands once per depth
    // level, sending the model dozens of nested copies of the same type.
    val definitions =
      mapOf<String, Any>(
        "Node" to
          mapOf(
            "type" to "object",
            "properties" to
              mapOf(
                "value" to mapOf("type" to "string"),
                "child" to mapOf("\$ref" to "#/\$defs/Node"),
              ),
          )
      )
    val property = mapOf<String, Any>("\$ref" to "#/\$defs/Node")

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.OBJECT, converted.type)
    assertEquals(Type.STRING, converted.properties?.get("value")?.type)
    val child = requireNotNull(converted.properties?.get("child"))
    assertEquals("Circular ref to Node", child.description)
    // Cut at the first revisit: the child is a leaf, not another expanded Node.
    assertNull(child.properties)
  }

  @Test
  fun parsePropertyMap_wideAcyclicRefGraph_staysBounded() {
    // A chain of DISTINCT definitions, each referencing the next from four properties. No `$ref`
    // ever repeats on a path, so the cycle cut never fires and the depth cap is never reached --
    // without a work budget this expands 4^13 and exhausts the heap.
    val levels = 13
    val definitions = buildMap {
      for (i in 0 until levels) {
        put(
          "D$i",
          mapOf(
            "type" to "object",
            "properties" to
              (0 until 4).associate { branch ->
                "p$branch" to mapOf("\$ref" to "#/\$defs/D${i + 1}")
              },
          ),
        )
      }
      put("D$levels", mapOf("type" to "string"))
    }

    val converted =
      McpSchemaConverter.parsePropertyMap(
        mapOf("\$ref" to "#/\$defs/D0"),
        definitions = definitions,
      )

    // The positive signal is simply that this returns: the budget degrades the tail to untyped
    // objects the same way the depth cap does.
    assertEquals(Type.OBJECT, converted.type)
  }

  @Test
  fun parsePropertyMap_sameRefTwiceAsSiblings_resolvesBoth() {
    // Cycle detection is per path, not global: two siblings may both reference the same type.
    val definitions = mapOf<String, Any>("Foo" to mapOf("type" to "string"))
    val property =
      mapOf<String, Any>(
        "type" to "object",
        "properties" to
          mapOf("a" to mapOf("\$ref" to "#/\$defs/Foo"), "b" to mapOf("\$ref" to "#/\$defs/Foo")),
      )

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.STRING, converted.properties?.get("a")?.type)
    assertEquals(Type.STRING, converted.properties?.get("b")?.type)
  }

  @Test
  fun parsePropertyMap_anyOfOfARefAndNull_keepsTheReferencedTypeAndIsNullable() {
    // Pydantic's `Optional[Foo]`. Before the definitions were resolved this lost its type entirely.
    val definitions = mapOf<String, Any>("Foo" to mapOf("type" to "string"))
    val property =
      mapOf<String, Any>(
        "anyOf" to listOf(mapOf("\$ref" to "#/\$defs/Foo"), mapOf("type" to "null"))
      )

    val converted = McpSchemaConverter.parsePropertyMap(property, definitions = definitions)

    assertEquals(Type.STRING, converted.type)
    assertEquals(true, converted.nullable)
    assertNull(converted.anyOf)
  }

  @Test
  fun toAdkFunctionDeclaration_inputSchemaWithDefs_resolvesRefsInProperties() {
    val tool =
      McpSchema.Tool.builder()
        .name("weather")
        .inputSchema(
          McpSchema.JsonSchema(
            "object",
            mapOf("where" to mapOf("\$ref" to "#/\$defs/City")),
            listOf("where"),
            null,
            mapOf("City" to mapOf("type" to "string", "maxLength" to 20)),
            null,
          )
        )
        .build()

    val parameters = tool.toAdkFunctionDeclaration().parameters

    assertEquals(Type.STRING, parameters?.properties?.get("where")?.type)
    assertEquals(20L, parameters?.properties?.get("where")?.maxLength)
  }

  private companion object {
    /** Property name linking one level of a [nestedObjectSchema] chain to the next. */
    const val NESTED_KEY = "child"

    /**
     * Builds an `object` schema nested [levels] deep, each level holding the next under
     * [NESTED_KEY].
     */
    fun nestedObjectSchema(levels: Int): Map<String, Any> {
      var schema = mapOf<String, Any>("type" to "object")
      repeat(levels - 1) {
        schema = mapOf("type" to "object", "properties" to mapOf(NESTED_KEY to schema))
      }
      return schema
    }

    /** Counts the levels of a converted [nestedObjectSchema] chain. */
    fun schemaChainLength(schema: Schema): Int {
      var levels = 1
      var current = schema
      while (true) {
        current = current.properties?.get(NESTED_KEY) ?: return levels
        levels++
      }
    }

    /**
     * Builds an `{"type": "object", ...}` [McpSchema.JsonSchema]; the record is a Java record, so
     * its components have to be passed positionally.
     */
    fun jsonSchema(
      properties: Map<String, Any> = emptyMap(),
      required: List<String> = emptyList(),
    ): McpSchema.JsonSchema =
      McpSchema.JsonSchema(
        /* type= */ "object",
        /* properties= */ properties,
        /* required= */ required,
        /* additionalProperties= */ null,
        /* defs= */ null,
        /* definitions= */ null,
      )
  }
}
