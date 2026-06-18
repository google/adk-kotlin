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
}
