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

import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class SetModelResponseToolTest {

  private val outputSchema =
    Schema(
      type = Type.OBJECT,
      properties =
        mapOf("name" to Schema(type = Type.STRING), "city" to Schema(type = Type.STRING)),
      required = listOf("name"),
    )

  @Test
  fun declaration_exposesOutputSchemaAsParameters() {
    val declaration = SetModelResponseTool(outputSchema).declaration()

    assertEquals(SetModelResponseTool.NAME, declaration.name)
    assertEquals(outputSchema, declaration.parameters)
  }

  @Test
  fun run_validArgs_returnsArgsUnchanged() = runBlocking {
    val tool = SetModelResponseTool(outputSchema)
    val args = mapOf<String, Any>("name" to "John", "city" to "NYC")

    val result = tool.run(testToolContext(), args)

    assertEquals(args, result)
  }

  @Test
  fun run_argsMissingRequiredField_throws() =
    runBlocking<Unit> {
      val tool = SetModelResponseTool(outputSchema)

      assertFailsWith<IllegalArgumentException> {
        tool.run(testToolContext(), mapOf("city" to "NYC"))
      }
    }

  @Test
  fun run_argsWithUnknownField_throws() =
    runBlocking<Unit> {
      val tool = SetModelResponseTool(outputSchema)

      assertFailsWith<IllegalArgumentException> {
        tool.run(testToolContext(), mapOf("name" to "John", "unknown" to "x"))
      }
    }

  @Test
  fun run_wholeNumberDoubleForInteger_recordsItAsLong() = runBlocking {
    // Arrange: Gson-based decoders read the JSON number 1 as 1.0.
    val optionalInt = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.NULL)))
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf(
            "n" to Schema(type = Type.INTEGER),
            "items" to Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER)),
            "maybe" to optionalInt,
          ),
      )
    val tool = SetModelResponseTool(schema)

    // Act
    val result =
      tool.run(
        testToolContext(functionCallId = "call_1"),
        mapOf("n" to 1.0, "items" to listOf(2.0), "maybe" to 3.0),
      )

    // Assert
    assertEquals(mapOf("n" to 1L, "items" to listOf(2L), "maybe" to 3L), result)
  }

  @Test
  fun run_wholeNumberDoubleUnderIntegerAdditionalProperties_recordsItAsLong() = runBlocking {
    // Arrange
    val counts = Schema(type = Type.OBJECT, additionalProperties = Schema(type = Type.INTEGER))
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("counts" to counts)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("counts" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("counts" to mapOf("a" to 1L)), result)
    assertEquals("""{"counts":{"a":1}}""", tool.validatedResponseJson("call_1"))
  }

  @Test
  fun run_wholeNumberDoubleInOneOfTheseFieldsSchema_recordsItAsLong() = runBlocking {
    // Arrange: the object's own properties type `a`; its `anyOf` only says which field to send.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.INTEGER), "b" to Schema(type = Type.STRING)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(required = listOf("b"))),
      )
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("outcome" to outcome)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("outcome" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("outcome" to mapOf("a" to 1L)), result)
  }

  @Test
  fun run_wholeNumberDoubleWithOwnPropertiesAndANullBranch_recordsItAsLong() = runBlocking {
    // Arrange: the one non-NULL branch declares no properties, so the object's own ones apply.
    val outcome =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("a" to Schema(type = Type.INTEGER)),
        anyOf = listOf(Schema(required = listOf("a")), Schema(type = Type.NULL)),
      )
    val tool =
      SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("outcome" to outcome)))

    // Act
    val result =
      tool.run(testToolContext(functionCallId = "call_1"), mapOf("outcome" to mapOf("a" to 1.0)))

    // Assert
    assertEquals(mapOf("outcome" to mapOf("a" to 1L)), result)
  }

  @Test
  fun run_wholeNumberDoubleInIntegerOrStringUnion_recordsItAsLong() = runBlocking {
    // Arrange: no NUMBER branch, so the whole double can only have matched INTEGER.
    val id = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    val tool = SetModelResponseTool(Schema(type = Type.OBJECT, properties = mapOf("id" to id)))

    // Act
    val result = tool.run(testToolContext(functionCallId = "call_1"), mapOf("id" to 7.0))

    // Assert
    assertEquals(mapOf("id" to 7L), result)
    assertEquals("""{"id":7}""", tool.validatedResponseJson("call_1"))
  }
}
