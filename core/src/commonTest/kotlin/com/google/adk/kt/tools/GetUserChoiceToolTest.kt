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

class GetUserChoiceToolTest {

  @Test
  fun isLongRunning_isTrue() {
    assertTrue(GetUserChoiceTool().isLongRunning)
  }

  @Test
  fun name_isGetUserChoice() {
    assertEquals("get_user_choice", GetUserChoiceTool().name)
  }

  @Test
  fun declaration_hasOptionsArrayParameter() {
    val declaration = GetUserChoiceTool().declaration()

    assertEquals("get_user_choice", declaration.name)
    val parameters = declaration.parameters
    assertNotNull(parameters)
    assertEquals(Type.OBJECT, parameters.type)
    val options = parameters.properties?.get("options")
    assertNotNull(options)
    assertEquals(Type.ARRAY, options.type)
    assertEquals(Type.STRING, options.items?.type)
    assertEquals(listOf("options"), parameters.required)
  }

  @Test
  fun declaration_appendsLongRunningNote() {
    assertTrue(
      GetUserChoiceTool()
        .declaration()
        .description
        .contains(FunctionTool.LONG_RUNNING_OPERATION_NOTE)
    )
  }

  @Test
  fun run_withOptions_setsSkipSummarization() = runTest {
    val context = testToolContext()

    val result = GetUserChoiceTool().run(context, mapOf("options" to listOf("a", "b")))

    assertEquals(Unit, result)
    assertTrue(context.actions.skipSummarization)
  }

  @Test
  fun run_withOptions_deferValueIsUnit() = runTest {
    val result = GetUserChoiceTool().run(testToolContext(), mapOf("options" to listOf("a", "b")))

    assertEquals(Unit, result)
  }

  @Test
  fun run_withPresentNonListOptions_deferValueIsUnit() = runTest {
    // Parity with Python: a present `options` arg passes the mandatory-arg check (its type is not
    // validated) and defers; only an absent `options` errors.
    val context = testToolContext()

    val result = GetUserChoiceTool().run(context, mapOf("options" to "not-a-list"))

    assertEquals(Unit, result)
    assertTrue(context.actions.skipSummarization)
  }

  @Test
  fun run_missingOptions_returnsMandatoryArgError() = runTest {
    val context = testToolContext()

    val result = GetUserChoiceTool().run(context, emptyMap())

    val error = (result as Map<*, *>)["error"] as String
    assertTrue(error.contains("mandatory input parameters are not present"))
    assertTrue(error.contains("options"))
    // Summarization must not be skipped when the tool errors out without deferring.
    assertFalse(context.actions.skipSummarization)
  }
}
