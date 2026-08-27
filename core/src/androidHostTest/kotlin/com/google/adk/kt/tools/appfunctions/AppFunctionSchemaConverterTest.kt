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

package com.google.adk.kt.tools.appfunctions

import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBytesTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDeprecationMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionOneOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFunctionSchemaConverterTest {

  @Test
  fun toFunctionDeclaration_scalarParameters_mapsTypesAndFormats() {
    val declaration =
      convert(
        params =
          listOf(
            param("title", string()),
            param("count", AppFunctionIntTypeMetadata(isNullable = false)),
            param("size", AppFunctionLongTypeMetadata(isNullable = false)),
            param("ratio", doubleType()),
            param("done", boolean()),
          )
      )

    val properties = checkNotNull(declaration?.parameters?.properties)
    assertThat(properties["title"]?.type).isEqualTo(Type.STRING)
    assertThat(properties["count"]?.type).isEqualTo(Type.INTEGER)
    assertThat(properties["count"]?.format).isEqualTo("int32")
    assertThat(properties["size"]?.format).isEqualTo("int64")
    assertThat(properties["ratio"]?.type).isEqualTo(Type.NUMBER)
    // Gemini rejects a float/double format, so a real number must carry none.
    assertThat(properties["ratio"]?.format).isNull()
    assertThat(properties["done"]?.type).isEqualTo(Type.BOOLEAN)
  }

  @Test
  fun toFunctionDeclaration_stringEnum_carriesEnumFormat() {
    val declaration =
      convert(
        params =
          listOf(
            param(
              "mode",
              AppFunctionStringTypeMetadata(
                isNullable = false,
                enumValues = linkedSetOf("FAST", "SLOW"),
              ),
            )
          )
      )

    val mode = checkNotNull(declaration?.parameters?.properties?.get("mode"))
    assertThat(mode.enum).containsExactly("FAST", "SLOW").inOrder()
    assertThat(mode.format).isEqualTo("enum")
  }

  @Test
  fun toFunctionDeclaration_intEnum_keepsNumericFormat() {
    val declaration =
      convert(
        params =
          listOf(
            param("level", AppFunctionIntTypeMetadata(isNullable = false, enumValues = setOf(1, 2)))
          )
      )

    val level = checkNotNull(declaration?.parameters?.properties?.get("level"))
    assertThat(level.enum).containsExactly("1", "2")
    // Gemini accepts only int32/int64 as an integer format; "enum" there is rejected.
    assertThat(level.format).isEqualTo("int32")
  }

  @Test
  fun toFunctionDeclaration_requiredParameters_listsOnlyRequiredOnes() {
    val declaration =
      convert(
        params =
          listOf(param("a", string(), required = true), param("b", string(), required = false))
      )

    assertThat(declaration?.parameters?.required).containsExactly("a")
  }

  @Test
  fun toFunctionDeclaration_noRequiredParameters_omitsRequired() {
    val declaration = convert(params = listOf(param("a", string(), required = false)))

    assertThat(declaration?.parameters?.required).isNull()
  }

  @Test
  fun toFunctionDeclaration_optionalUnsupportedParameter_dropsParameterAndKeepsFunction() {
    val declaration =
      convert(
        params =
          listOf(
            param("title", string(), required = true),
            param("blob", AppFunctionBytesTypeMetadata(isNullable = false), required = false),
          )
      )

    assertThat(declaration).isNotNull()
    assertThat(declaration?.parameters?.properties?.keys).containsExactly("title")
    assertThat(declaration?.parameters?.required).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_requiredUnsupportedParameter_dropsFunction() {
    val declaration =
      convert(
        params =
          listOf(param("blob", AppFunctionBytesTypeMetadata(isNullable = false), required = true))
      )

    assertThat(declaration).isNull()
  }

  @Test
  fun toFunctionDeclaration_nestedObject_convertsPropertiesAndRequired() {
    val note =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string(), "body" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    val declaration = convert(params = listOf(param("note", note)))

    val schema = checkNotNull(declaration?.parameters?.properties?.get("note"))
    assertThat(schema.type).isEqualTo(Type.OBJECT)
    assertThat(schema.properties?.keys).containsExactly("title", "body")
    assertThat(schema.required).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_objectWithRequiredUnsupportedProperty_dropsFunction() {
    val note =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("blob" to AppFunctionBytesTypeMetadata(isNullable = false)),
        required = listOf("blob"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    assertThat(convert(params = listOf(param("note", note, required = true)))).isNull()
  }

  @Test
  fun toFunctionDeclaration_arrayOfObjects_convertsItemSchema() {
    val note =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    val declaration =
      convert(
        params =
          listOf(param("notes", AppFunctionArrayTypeMetadata(itemType = note, isNullable = false)))
      )

    val schema = checkNotNull(declaration?.parameters?.properties?.get("notes"))
    assertThat(schema.type).isEqualTo(Type.ARRAY)
    assertThat(schema.items?.type).isEqualTo(Type.OBJECT)
    assertThat(schema.items?.properties?.keys).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_arrayOfUnsupportedItems_dropsFunction() {
    val array =
      AppFunctionArrayTypeMetadata(
        itemType = AppFunctionBytesTypeMetadata(isNullable = false),
        isNullable = false,
      )

    assertThat(convert(params = listOf(param("blobs", array, required = true)))).isNull()
  }

  @Test
  fun toFunctionDeclaration_reference_expandsReferencedType() {
    val note =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    val declaration =
      convert(
        params = listOf(param("note", reference("Note"))),
        components = components("Note" to note),
      )

    val schema = checkNotNull(declaration?.parameters?.properties?.get("note"))
    assertThat(schema.type).isEqualTo(Type.OBJECT)
    assertThat(schema.properties?.keys).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_unresolvableReference_dropsFunction() {
    assertThat(convert(params = listOf(param("note", reference("Missing"), required = true))))
      .isNull()
  }

  @Test
  fun toFunctionDeclaration_selfReferentialType_dropsRecursiveProperty() {
    // A tree node holding more of itself has no finite expansion.
    val node =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("value" to string(), "child" to reference("Node")),
        required = listOf("value"),
        qualifiedName = "com.example.Node",
        isNullable = false,
      )

    val declaration =
      convert(
        params = listOf(param("node", reference("Node"))),
        components = components("Node" to node),
      )

    val schema = checkNotNull(declaration?.parameters?.properties?.get("node"))
    assertThat(schema.properties?.keys).containsExactly("value")
  }

  @Test
  fun toFunctionDeclaration_allOf_mergesMemberProperties() {
    val address =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("city" to string()),
        required = listOf("city"),
        qualifiedName = "com.example.Address",
        isNullable = false,
      )
    val person =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("name" to string()),
        required = listOf("name"),
        qualifiedName = "com.example.Person",
        isNullable = false,
      )
    val merged =
      AppFunctionAllOfTypeMetadata(
        matchAll = listOf(reference("Address"), person),
        qualifiedName = "com.example.PersonWithAddress",
        isNullable = false,
      )

    val declaration =
      convert(params = listOf(param("who", merged)), components = components("Address" to address))

    val schema = checkNotNull(declaration?.parameters?.properties?.get("who"))
    assertThat(schema.properties?.keys).containsExactly("city", "name")
    assertThat(schema.required).containsExactly("city", "name")
  }

  @Test
  fun toFunctionDeclaration_response_nestsValueUnderResultKey() {
    val declaration = convert(params = emptyList(), response = string())

    val response = checkNotNull(declaration?.response)
    assertThat(response.type).isEqualTo(Type.OBJECT)
    assertThat(response.properties?.get("result")?.type).isEqualTo(Type.STRING)
    // A failed call reports an error in place of the value, so nothing is required.
    assertThat(response.required).isNull()
  }

  @Test
  fun toFunctionDeclaration_unitResponse_declaresNoResponse() {
    val declaration =
      convert(params = emptyList(), response = AppFunctionUnitTypeMetadata(isNullable = false))

    assertThat(declaration?.response).isNull()
  }

  @Test
  fun toFunctionDeclaration_blankDescription_fallsBackToFunctionId() {
    val declaration = convert(params = emptyList(), description = "")

    assertThat(declaration?.description).isEqualTo(FUNCTION_ID)
  }

  @Test
  fun toFunctionDeclaration_parameterDescription_overridesTypeDescription() {
    val declaration =
      convert(
        params =
          listOf(
            param(
              "title",
              AppFunctionStringTypeMetadata(isNullable = false, description = "from the type"),
              description = "from the parameter",
            )
          )
      )

    assertThat(declaration?.parameters?.properties?.get("title")?.description)
      .isEqualTo("from the parameter")
  }

  @Test
  fun toFunctionDeclaration_nullableParameter_marksSchemaNullable() {
    val declaration =
      convert(params = listOf(param("title", AppFunctionStringTypeMetadata(isNullable = true))))

    assertThat(declaration?.parameters?.properties?.get("title")?.nullable).isTrue()
  }

  @Test
  fun toFunctionDeclaration_nonNullableParameter_omitsNullable() {
    val declaration = convert(params = listOf(param("title", string())))

    assertThat(declaration?.parameters?.properties?.get("title")?.nullable).isNull()
  }

  @Test
  fun toFunctionDeclaration_deeplyNestedType_dropsFunctionRatherThanRecursingForever() {
    // Deeper than the converter's nesting cap, and each level is required.
    var type: AppFunctionDataTypeMetadata = string()
    repeat(40) { type = AppFunctionArrayTypeMetadata(itemType = type, isNullable = false) }

    assertThat(convert(params = listOf(param("deep", type, required = true)))).isNull()
  }

  @Test
  fun toFunctionDeclaration_anyFunction_usesSuppliedName() {
    val declaration = convert(params = emptyList())

    assertThat(declaration?.name).isEqualTo(TOOL_NAME)
  }

  @Test
  fun toFunctionDeclaration_oneOfInTheResponse_dropsTheResponseNotTheTool() {
    val choice = oneOf(string(), AppFunctionIntTypeMetadata(isNullable = false))

    val declaration = convert(params = emptyList(), response = choice)

    assertThat(declaration).isNotNull()
    assertThat(declaration?.response).isNull()
  }

  @Test
  fun toFunctionDeclaration_referenceToAComposition_isOffered() {
    // The library builds an object for a reference to an all-of, so the model may supply one.
    val composed =
      AppFunctionAllOfTypeMetadata(
        matchAll = listOf(note()),
        qualifiedName = "com.example.Composed",
        isNullable = false,
      )

    val declaration =
      convert(
        params = listOf(param("note", reference("Composed"))),
        components = components("Composed" to composed),
      )

    assertThat(declaration?.parameters?.properties?.get("note")?.properties?.keys)
      .containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_referenceToAScalar_dropsTheFunction() {
    // The app supplies an object for a reference whatever it names, so a scalar can never be sent.
    val declaration =
      convert(
        params = listOf(param("name", reference("Name"))),
        components = components("Name" to string()),
      )

    assertThat(declaration).isNull()
  }

  @Test
  fun toFunctionDeclaration_nonNullReferenceToANullableDefinition_isNotNullable() {
    val nullableDefinition =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = true,
      )
    val useSite = AppFunctionReferenceTypeMetadata(referenceDataType = "Note", isNullable = false)

    val declaration =
      convert(
        params = listOf(param("note", useSite)),
        components = components("Note" to nullableDefinition),
      )

    assertThat(declaration?.parameters?.properties?.get("note")?.nullable).isNull()
  }

  @Test
  fun toFunctionDeclaration_referenceWithItsOwnDescription_keepsItOverTheDefinitions() {
    val definition =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
        description = "the shared definition",
      )
    val components = components("Note" to definition)
    val useSite =
      AppFunctionReferenceTypeMetadata(
        referenceDataType = "Note",
        isNullable = false,
        description = "the note to update",
      )

    val declaration = convert(params = listOf(param("note", useSite)), components = components)

    val note = checkNotNull(declaration?.parameters?.properties?.get("note"))
    assertThat(note.description).isEqualTo("the note to update")
    assertThat(note.nullable).isNull()
  }

  @Test
  fun toFunctionDeclaration_parameterDeclaredTwice_showsTheFirstDeclarationOnce() {
    // The library resolves a repeated name to its first declaration, so the model must be shown
    // that one, and a repeated required entry would be malformed.
    val declaration =
      convert(
        params = listOf(param("title", string()), param("title", AppFunctionIntTypeMetadata(false)))
      )

    assertThat(declaration?.parameters?.required).containsExactly("title")
    assertThat(declaration?.parameters?.properties?.get("title")?.type).isEqualTo(Type.STRING)
  }

  @Test
  fun toFunctionDeclaration_firstDeclarationOfARepeatedNameIsUnrepresentable_dropsTheParameter() {
    // The library reads the first declaration, so the second must not be offered in its place.
    val declaration =
      convert(
        params =
          listOf(
            param("x", AppFunctionBytesTypeMetadata(isNullable = false), required = false),
            param("x", string(), required = false),
          )
      )

    assertThat(declaration?.parameters?.properties).isEmpty()
  }

  @Test
  fun toFunctionDeclaration_repeatedNameOptionalThenRequired_followsTheFirstDeclaration() {
    val declaration =
      convert(
        params =
          listOf(
            param("title", string(), required = false),
            param("title", string(), required = true),
          )
      )

    assertThat(declaration?.parameters?.required).isNull()
  }

  @Test
  fun toFunctionDeclaration_nullableRequiredParameterIsUnrepresentable_keepsTheFunction() {
    // The library counts a nullable required parameter as optional, so it costs one parameter.
    val declaration =
      convert(
        params =
          listOf(
            param("blob", AppFunctionBytesTypeMetadata(isNullable = true), required = true),
            param("title", string()),
          )
      )

    assertThat(declaration?.parameters?.properties?.keys).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_nullableRequiredParameter_isStillListedAsRequired() {
    // Deliberately asymmetric: the library counts it optional when deciding whether losing it
    // costs the function, but the emitted list reports what the app declared.
    val declaration =
      convert(params = listOf(param("title", AppFunctionStringTypeMetadata(isNullable = true))))

    assertThat(declaration?.parameters?.required).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_nullableRequiredPropertyIsUnrepresentable_keepsTheObject() {
    val holder =
      AppFunctionObjectTypeMetadata(
        properties =
          mapOf("title" to string(), "blob" to AppFunctionBytesTypeMetadata(isNullable = true)),
        required = listOf("title", "blob"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    val declaration = convert(params = listOf(param("note", holder)))

    val note = declaration?.parameters?.properties?.get("note")
    assertThat(note?.properties?.keys).containsExactly("title")
    assertThat(note?.required).containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_deprecatedFunction_saysSoInTheDescription() {
    val declaration = convert(params = emptyList(), deprecation = "Use createNoteV2 instead")

    assertThat(declaration?.description).contains("Deprecated: Use createNoteV2 instead")
  }

  @Test
  fun toFunctionDeclaration_requiredOneOfParameter_dropsTheFunction() {
    // The data side cannot build a one-of back, so offering it would fail on every call.
    val choice = oneOf(string(), AppFunctionIntTypeMetadata(isNullable = false))

    assertThat(convert(params = listOf(param("value", choice)))).isNull()
  }

  @Test
  fun toFunctionDeclaration_optionalOneOfParameter_dropsOnlyTheParameter() {
    val choice = oneOf(string(), AppFunctionIntTypeMetadata(isNullable = false))

    val declaration =
      convert(params = listOf(param("value", choice, required = false), param("title", string())))

    assertThat(declaration?.parameters?.properties?.keys).containsExactly("title")
  }

  @Test(timeout = 30_000)
  fun toFunctionDeclaration_moreReferencesThanTheBudget_dropsTheFunction() {
    // The depth cap bounds how deep a type goes, not how often a definition is expanded, so a wide
    // object naming one definition from every property is what the expansion budget is for.
    val properties = (0 until 600).associate { "p$it" to reference("Note") }
    val wide =
      AppFunctionObjectTypeMetadata(
        properties = properties,
        required = properties.keys.toList(),
        qualifiedName = "Wide",
        isNullable = false,
      )

    val declaration =
      convert(params = listOf(param("wide", wide)), components = components("Note" to note()))

    assertThat(declaration).isNull()
  }

  @Test
  fun toFunctionDeclaration_screenReturn_declaresTheUiActionShape() {
    val declaration = convert(params = emptyList(), response = pendingIntent())

    val result = checkNotNull(declaration?.response?.properties?.get(BaseTool.RESULT_KEY))
    assertThat(result.properties?.keys).containsExactly(AppFunctionSchemaConverter.UI_ACTION_KEY)
  }

  @Test
  fun toFunctionDeclaration_screenReturnWithADescription_keepsIt() {
    // The screen never reaches the model, but what opening it does still should.
    val metadata =
      AppFunctionMetadata(
        id = FUNCTION_ID,
        packageName = "com.example.notes",
        isEnabled = true,
        schema = null,
        parameters = emptyList(),
        response =
          AppFunctionResponseMetadata(
            valueType = pendingIntent(),
            description = "Opens the note that was created",
          ),
        components = AppFunctionComponentsMetadata(),
        description = "Creates a note",
      )

    val declaration = AppFunctionSchemaConverter.toFunctionDeclaration(metadata, TOOL_NAME)

    assertThat(declaration?.response?.properties?.get(BaseTool.RESULT_KEY)?.description)
      .isEqualTo("Opens the note that was created")
  }

  @Test
  fun toFunctionDeclaration_requiredButNullableUnrepresentableProperty_keepsTheObject() {
    // The library counts a nullable required property as optional, so losing it costs one property
    // rather than the whole object -- dropping the object would hide a function the app can run.
    val note =
      AppFunctionObjectTypeMetadata(
        properties =
          mapOf("title" to string(), "blob" to AppFunctionBytesTypeMetadata(isNullable = true)),
        required = listOf("title", "blob"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    val declaration = convert(params = listOf(param("note", note)))

    assertThat(declaration?.parameters?.properties?.get("note")?.properties?.keys)
      .containsExactly("title")
  }

  @Test
  fun toFunctionDeclaration_requiredNonNullUnrepresentableProperty_dropsTheFunction() {
    val note =
      AppFunctionObjectTypeMetadata(
        properties =
          mapOf("title" to string(), "blob" to AppFunctionBytesTypeMetadata(isNullable = false)),
        required = listOf("title", "blob"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    assertThat(convert(params = listOf(param("note", note)))).isNull()
  }

  @Test
  fun toFunctionDeclaration_unrepresentableParcelableReturn_dropsTheResponseNotTheTool() {
    // This warning is the whole mitigation for keeping PENDING_INTENT_NAME as a literal: if a
    // parcelable under a different name ever stopped being logged, the rejection stops being safe.
    val declaration =
      convert(
        params = emptyList(),
        response =
          AppFunctionParcelableTypeMetadata(qualifiedName = "android.net.Uri", isNullable = false),
      )

    assertThat(declaration).isNotNull()
    assertThat(declaration?.response).isNull()
  }

  @Test
  fun toFunctionDeclaration_referenceChainThatDoesNotResolve_dropsTheFunction() {
    val components = components("Alias" to reference("Missing"))

    val declaration =
      convert(params = listOf(param("note", reference("Alias"))), components = components)

    assertThat(declaration).isNull()
  }

  private companion object {
    const val FUNCTION_ID = "com.example.notes.NotesFunctions#createNote"
    const val TOOL_NAME = "com.example.notes.NotesFunctions_createNote"

    fun string() = AppFunctionStringTypeMetadata(isNullable = false)

    fun pendingIntent() =
      AppFunctionParcelableTypeMetadata(
        qualifiedName = "android.app.PendingIntent",
        isNullable = false,
      )

    fun boolean() = AppFunctionBooleanTypeMetadata(isNullable = false)

    fun doubleType() = AppFunctionDoubleTypeMetadata(isNullable = false)

    fun reference(name: String) =
      AppFunctionReferenceTypeMetadata(referenceDataType = name, isNullable = false)

    fun oneOf(vararg alternatives: AppFunctionDataTypeMetadata) =
      AppFunctionOneOfTypeMetadata(
        matchOneOf = alternatives.toList(),
        qualifiedName = "com.example.Choice",
        isNullable = false,
      )

    fun note() =
      AppFunctionObjectTypeMetadata(
        properties = mapOf("title" to string()),
        required = listOf("title"),
        qualifiedName = "com.example.Note",
        isNullable = false,
      )

    fun components(vararg types: Pair<String, AppFunctionDataTypeMetadata>) =
      AppFunctionComponentsMetadata(dataTypes = types.toMap())

    fun param(
      name: String,
      type: AppFunctionDataTypeMetadata,
      required: Boolean = true,
      description: String = "",
    ) =
      AppFunctionParameterMetadata(
        name = name,
        isRequired = required,
        dataType = type,
        description = description,
      )

    fun convert(
      params: List<AppFunctionParameterMetadata>,
      response: AppFunctionDataTypeMetadata = AppFunctionUnitTypeMetadata(isNullable = false),
      components: AppFunctionComponentsMetadata = AppFunctionComponentsMetadata(),
      description: String = "Creates a note",
      deprecation: String? = null,
    ) =
      AppFunctionSchemaConverter.toFunctionDeclaration(
        AppFunctionMetadata(
          id = FUNCTION_ID,
          packageName = "com.example.notes",
          isEnabled = true,
          schema = null,
          parameters = params,
          response = AppFunctionResponseMetadata(valueType = response),
          components = components,
          description = description,
          deprecation = deprecation?.let { AppFunctionDeprecationMetadata(it) },
        ),
        TOOL_NAME,
      )
  }
}
