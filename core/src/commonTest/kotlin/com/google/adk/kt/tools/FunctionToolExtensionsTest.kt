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

package com.google.adk.kt.tools

import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FunctionToolExtensionsTest {

  class DummyFunctionTool(name: String, description: String, private val schema: Schema?) :
    FunctionTool(name, description) {
    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
      return mapOf("result" to "Success")
    }

    override fun declaration(): FunctionDeclaration {
      return FunctionDeclaration(name, description, schema)
    }
  }

  @Test
  fun toPromptDescription_xmlFormat_generatesCorrectXml() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "get_weather",
          description = "Gets the weather for a location",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "location" to
                    Schema(
                      type = Type.STRING,
                      description = "The city and state, e.g. San Francisco, CA",
                    )
                ),
              required = listOf("location"),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)
    assertTrue(result.contains("<tools>"))
    assertTrue(result.contains("<name>get_weather</name>"))
    assertTrue(result.contains("<description>Gets the weather for a location</description>"))
    assertTrue(result.contains("<name>location</name>"))
    assertTrue(result.contains("<type>string</type>"))
  }

  @Test
  fun toPromptDescription_jsonFormat_generatesCorrectJson() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "get_weather",
          description = "Gets the weather for a location",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "location" to
                    Schema(
                      type = Type.STRING,
                      description = "The city and state, e.g. San Francisco, CA",
                    )
                ),
              required = listOf("location"),
            ),
        )
      )

    // The whole document is pinned, not a few substrings: this renderer's contract is the exact
    // bytes it produces, so key order and the set of keys have to be part of the assertion.
    assertEquals(
      """[{"name":"get_weather","description":"Gets the weather for a location",""" +
        """"parameters":{"type":"object","properties":{"location":{"type":"string",""" +
        """"description":"The city and state, e.g. San Francisco, CA"}},"required":["location"]}}]""",
      tools.toPromptDescription(PromptFormat.JSON),
    )
  }

  @Test
  fun toPromptDescription_jsonFormat_arrayPropertyCarriesItsItems() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "add_labels",
          description = "Adds labels",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("labels" to Schema(type = Type.ARRAY, items = Schema(type = Type.STRING))),
            ),
        )
      )

    assertEquals(
      """[{"name":"add_labels","description":"Adds labels","parameters":{"type":"object",""" +
        """"properties":{"labels":{"type":"array","items":{"type":"string"}}}}}]""",
      tools.toPromptDescription(PromptFormat.JSON),
    )
  }

  @Test
  fun toPromptDescription_jsonFormat_arrayWithoutItemsAndObjectWithoutProperties_writeOnlyTheType() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "describe",
          description = "Describes a thing",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("tags" to Schema(type = Type.ARRAY), "extras" to Schema(type = Type.OBJECT)),
            ),
        )
      )

    // Neither sub-schema says what it contains, so neither gets an `items` or a `properties` key.
    // The drop is silent, so it is pinned here rather than left to be noticed in a prompt.
    assertEquals(
      """[{"name":"describe","description":"Describes a thing","parameters":{"type":"object",""" +
        """"properties":{"tags":{"type":"array"},"extras":{"type":"object"}}}}]""",
      tools.toPromptDescription(PromptFormat.JSON),
    )
  }

  @Test
  fun toPromptDescription_jsonFormat_lineSeparatorIsWrittenLiterally() {
    val tools =
      listOf(DummyFunctionTool(name = "a\u2028b", description = "line\u2029break", schema = null))

    // The one place the output is not what gson produced. gson escaped U+2028 and U+2029 so that
    // its output could be handed to JavaScript's `eval`; kotlinx writes them as themselves. Both
    // are valid JSON, and this string goes into a prompt rather than into a script.
    assertEquals(
      "[{\"name\":\"a\u2028b\",\"description\":\"line\u2029break\"}]",
      tools.toPromptDescription(PromptFormat.JSON),
    )
  }

  @Test
  fun toPromptDescription_jsonFormat_carriesConstraintFields() {
    // This description is the whole contract on a prompt-driven path, so the constraints have to
    // reach it too.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "priority" to
                    Schema(
                      type = Type.INTEGER,
                      format = "int32",
                      title = "Priority",
                      nullable = true,
                      minimum = 1.0,
                      maximum = 9.0,
                    ),
                  "tag" to
                    Schema(type = Type.STRING, pattern = "^[a-z]+$", minLength = 2, maxLength = 8),
                  "labels" to Schema(type = Type.ARRAY, minItems = 1, maxItems = 5),
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"format\":\"int32\""), result)
    assertTrue(result.contains("\"title\":\"Priority\""), result)
    assertTrue(result.contains("\"nullable\":true"), result)
    assertTrue(result.contains("\"minimum\":1.0"), result)
    assertTrue(result.contains("\"maximum\":9.0"), result)
    assertTrue(result.contains("\"pattern\":\"^[a-z]+$\""), result)
    assertTrue(result.contains("\"minLength\":2"), result)
    assertTrue(result.contains("\"maxLength\":8"), result)
    assertTrue(result.contains("\"minItems\":1"), result)
    assertTrue(result.contains("\"maxItems\":5"), result)
  }

  @Test
  fun toPromptDescription_jsonFormat_defaultKeepsItsJsonType() {
    // JSON Schema wants `default` to have the property's own type: 5, not "5".
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "count" to Schema(type = Type.INTEGER, default = 5),
                  "flag" to Schema(type = Type.BOOLEAN, default = true),
                  "name" to Schema(type = Type.STRING, default = "x"),
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"default\":5"), result)
    assertTrue(result.contains("\"default\":true"), result)
    assertTrue(result.contains("\"default\":\"x\""), result)
  }

  @Test
  fun toPromptDescription_xmlFormat_carriesConstraints() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "priority" to
                    Schema(
                      type = Type.INTEGER,
                      title = "Priority",
                      format = "int32",
                      nullable = true,
                      default = 3,
                      minimum = 1.0,
                      maximum = 9.0,
                    ),
                  "tag" to
                    Schema(
                      type = Type.STRING,
                      pattern = "^[a-z]+$",
                      minLength = 2,
                      maxLength = 8,
                      enum = listOf("east", "west"),
                    ),
                  "labels" to Schema(type = Type.ARRAY, minItems = 1, maxItems = 5),
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    assertTrue(result.contains("<title>Priority</title>"), result)
    assertTrue(result.contains("<format>int32</format>"), result)
    assertTrue(result.contains("<nullable>true</nullable>"), result)
    assertTrue(result.contains("<default>3</default>"), result)
    assertTrue(result.contains("<minimum>1.0</minimum>"), result)
    assertTrue(result.contains("<maximum>9.0</maximum>"), result)
    assertTrue(result.contains("<pattern>^[a-z]+$</pattern>"), result)
    assertTrue(result.contains("<minLength>2</minLength>"), result)
    assertTrue(result.contains("<maxLength>8</maxLength>"), result)
    assertTrue(result.contains("<enum>east</enum>"), result)
    assertTrue(result.contains("<enum>west</enum>"), result)
    assertTrue(result.contains("<minItems>1</minItems>"), result)
    assertTrue(result.contains("<maxItems>5</maxItems>"), result)
  }

  @Test
  fun toPromptDescription_objectPropertyBounds_areEmitted() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("meta" to Schema(type = Type.OBJECT, minProperties = 1, maxProperties = 4)),
            ),
        )
      )

    val json = tools.toPromptDescription(PromptFormat.JSON)
    assertTrue(json.contains("\"minProperties\":1"), json)
    assertTrue(json.contains("\"maxProperties\":4"), json)

    val xml = tools.toPromptDescription(PromptFormat.XML)
    assertTrue(xml.contains("<minProperties>1</minProperties>"), xml)
    assertTrue(xml.contains("<maxProperties>4</maxProperties>"), xml)
  }

  @Test
  fun toPromptDescription_xmlFormat_unspecifiedTypeIsDescribedAsAString() {
    // With no type and no alternatives to speak for it, the property still needs to tell the model
    // something, so it keeps the same `string` fallback an absent type gets.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties = mapOf("note" to Schema(type = Type.TYPE_UNSPECIFIED)),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    // The schema declares one property, so the only `<type>` in the output is that property's.
    assertTrue(result.contains("<name>note</name>"), result)
    assertTrue(result.contains("<type>string</type>"), result)
  }

  @Test
  fun toPromptDescription_collectionDefault_isWrittenAsJson() {
    // `Schema.default` accepts a list or a map, and Kotlin's `toString` renders one as `[east,
    // west]` -- text the model cannot read back as the value it is meant to assume.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("labels" to Schema(type = Type.ARRAY, default = listOf("east", "west"))),
            ),
        )
      )

    val json = tools.toPromptDescription(PromptFormat.JSON)
    val xml = tools.toPromptDescription(PromptFormat.XML)

    assertTrue(json.contains("\"default\":[\"east\",\"west\"]"), json)
    assertTrue(xml.contains("<default>[\"east\",\"west\"]</default>"), xml)
  }

  @Test
  fun toPromptDescription_jsonFormat_carriesEnumValues() {
    // The allowed values are the whole point of an enum, so a description that omits them lets the
    // model invent one.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf("direction" to Schema(type = Type.STRING, enum = listOf("EAST", "WEST"))),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"enum\":[\"EAST\",\"WEST\"]"), result)
  }

  @Test
  fun toPromptDescription_jsonFormat_unspecifiedTypeIsDescribedAsAString() {
    // A schema with no usable type and no alternatives still has to say something the model can
    // act on, and `string` is the fallback an absent type already gets.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties = mapOf("note" to Schema(type = Type.TYPE_UNSPECIFIED)),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"note\":{\"type\":\"string\"}"), result)
  }

  @Test
  fun toPromptDescription_xmlFormat_untypedUnionListsItsAlternatives() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "id" to
                    Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    // The model has to be told what it may send, so the alternatives are written out and the
    // property is never labelled with a type called "anyOf".
    val flattened = result.replace(Regex("\\s+"), "")
    assertTrue(flattened.contains("<anyOf><type>string</type></anyOf>"), result)
    assertTrue(flattened.contains("<anyOf><type>integer</type></anyOf>"), result)
    assertFalse(flattened.contains("<type>anyOf</type>"), result)
  }

  @Test
  fun toPromptDescription_xmlFormat_arrayItemsKeepTheirConstraints() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "tags" to
                    Schema(
                      type = Type.ARRAY,
                      items = Schema(type = Type.STRING, pattern = "^[a-z]+$", maxLength = 8),
                    )
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    assertTrue(result.contains("<pattern>^[a-z]+$</pattern>"), result)
    assertTrue(result.contains("<maxLength>8</maxLength>"), result)
  }

  @Test
  fun toPromptDescription_jsonFormat_untypedUnionIsNotCalledAString() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties =
                mapOf(
                  "id" to
                    Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER)))
                ),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"anyOf\""), result)
    assertTrue(result.contains("\"id\":{\"anyOf\""), result)
  }

  @Test
  fun toPromptDescription_jsonFormat_openObjectParameters_saysTheToolTakesAnObject() {
    // `{"type": "object"}` with no properties is how a server declares a free-form argument. It
    // used to be dropped, telling the model the tool takes nothing at all.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "store",
          description = "Stores a blob",
          schema = Schema(type = Type.OBJECT),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"parameters\":{\"type\":\"object\"}"), result)
  }

  @Test
  fun toPromptDescription_jsonFormat_unionParameters_listsTheAlternatives() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "store",
          description = "Stores a blob",
          schema = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER))),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"parameters\":{\"anyOf\""), result)
  }

  @Test
  fun toPromptDescription_xmlFormat_openObjectParameters_saysTheToolTakesAnObject() {
    val tools =
      listOf(
        DummyFunctionTool(
          name = "store",
          description = "Stores a blob",
          schema = Schema(type = Type.OBJECT),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    assertTrue(result.contains("<parameters>"), result)
    assertTrue(result.contains("<type>object</type>"), result)
  }

  @Test
  fun toPromptDescription_xmlFormat_unionParameters_listsTheAlternatives() {
    // `schemaToXml` only walks named properties, so without writing the schema out here this would
    // be an empty `<parameters>` element -- worse than leaving it out.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "store",
          description = "Stores a blob",
          schema = Schema(anyOf = listOf(Schema(type = Type.STRING), Schema(type = Type.INTEGER))),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.XML)

    assertTrue(result.contains("<anyOf>"), result)
    assertTrue(result.contains("<type>string</type>"), result)
    assertTrue(result.contains("<type>integer</type>"), result)
  }

  @Test
  fun toPromptDescription_toolTakingNoArguments_omitsTheParametersBlock() {
    // An empty `properties` map is the one shape that really does mean "no arguments", and it is
    // how the in-tree zero-argument tools are declared. It must stay omitted.
    val tools =
      listOf(
        DummyFunctionTool(
          name = "exit_loop",
          description = "Exits the loop",
          schema = Schema(type = Type.OBJECT, properties = emptyMap()),
        )
      )

    assertFalse(tools.toPromptDescription(PromptFormat.XML).contains("<parameters>"))
    assertFalse(tools.toPromptDescription(PromptFormat.JSON).contains("\"parameters\""))
  }

  @Test
  fun toPromptDescription_defaultWithNoJsonShape_fallsBackToItsText() {
    // `default` is declared `Any?`, so a caller can put a value in it that has no JSON shape. It is
    // written as its own text rather than failing the whole tool description.
    val opaque =
      object {
        override fun toString(): String = "opaque-value"
      }
    val tools =
      listOf(
        DummyFunctionTool(
          name = "annotate",
          description = "Records a note",
          schema =
            Schema(
              type = Type.OBJECT,
              properties = mapOf("note" to Schema(type = Type.STRING, default = opaque)),
            ),
        )
      )

    val result = tools.toPromptDescription(PromptFormat.JSON)

    assertTrue(result.contains("\"default\":\"opaque-value\""), result)
  }
}
