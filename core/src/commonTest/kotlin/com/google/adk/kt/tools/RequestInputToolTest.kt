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
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RequestInputToolTest {

  @Test
  fun isLongRunning_isTrue() {
    assertTrue(RequestInputTool().isLongRunning)
  }

  @Test
  fun name_isAdkRequestInput() {
    assertEquals("adk_request_input", RequestInputTool().name)
  }

  @Test
  fun description_describesAskingTheUser() {
    assertTrue(
      RequestInputTool().description.contains("Ask the user a question and wait for their response")
    )
  }

  @Test
  fun declaration_hasMessageAndResponseSchemaParameters() {
    val declaration = RequestInputTool().declaration()

    assertEquals("adk_request_input", declaration.name)
    assertTrue(declaration.description.contains("Ask the user a question"))
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    val properties = parameters.properties
    assertNotNull(properties)
    assertEquals(Type.STRING, properties["message"]?.type)
    assertEquals(Type.OBJECT, properties["response_schema"]?.type)
    assertEquals(listOf("message"), parameters.required)
  }

  @Test
  fun declaration_appendsLongRunningNote() {
    assertTrue(
      RequestInputTool()
        .declaration()
        .description
        .contains(FunctionTool.LONG_RUNNING_OPERATION_NOTE)
    )
  }

  @Test
  fun run_withMessage_deferValueIsUnit() = runTest {
    val result = RequestInputTool().run(testToolContext(), mapOf("message" to "What is your name?"))

    assertEquals(Unit, result)
  }

  @Test
  fun run_withMessageAndResponseSchema_deferValueIsUnit() = runTest {
    val result =
      RequestInputTool()
        .run(
          testToolContext(),
          mapOf("message" to "Enter your username:", "response_schema" to mapOf("type" to "string")),
        )

    assertEquals(Unit, result)
  }

  @Test
  fun run_withEmptyMessage_deferValueIsUnit() = runTest {
    // Parity with Python: a present `message` (even empty or not a string) passes the mandatory-arg
    // check and defers; only an absent `message` errors.
    val result = RequestInputTool().run(testToolContext(), mapOf("message" to ""))

    assertEquals(Unit, result)
  }

  @Test
  fun run_missingMessage_returnsMandatoryArgError() = runTest {
    val result =
      RequestInputTool()
        .run(testToolContext(), mapOf("response_schema" to mapOf("type" to "string")))

    val error = (result as Map<*, *>)["error"] as String
    assertTrue(error.contains("mandatory input parameters are not present"))
    assertTrue(error.contains("message"))
  }

  @Test
  fun run_doesNotSkipSummarization() = runTest {
    val context = testToolContext()

    val result = RequestInputTool().run(context, mapOf("message" to "hi"))

    assertEquals(Unit, result)
    assertFalse(context.actions.skipSummarization)
  }
}
