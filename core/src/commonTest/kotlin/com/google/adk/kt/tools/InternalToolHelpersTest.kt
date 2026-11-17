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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class InternalToolHelpersTest {

  @Test
  fun isTruthy_emptyOrZeroValues_areFalsy() {
    val falsy =
      listOf<Any?>(
        null,
        false,
        "",
        0,
        0L,
        0.0,
        emptyList<Any>(),
        emptySet<Any>(),
        emptyMap<String, Any>(),
        emptyArray<Any>(),
        JsonNull,
        JsonPrimitive(""),
        JsonPrimitive(false),
        JsonPrimitive(0),
      )

    for (value in falsy) assertFalse(InternalToolHelpers.isTruthy(value), describe(value))
  }

  @Test
  fun isTruthy_nonEmptyOrNonZeroValues_areTruthy() {
    val truthy =
      listOf<Any?>(
        true,
        "x",
        1,
        -0.5,
        listOf(1),
        setOf("a"),
        mapOf("k" to "v"),
        arrayOf(1),
        JsonPrimitive("x"),
        JsonPrimitive(true),
        JsonPrimitive(2),
        // An unquoted token that is neither a number nor a boolean.
        lenientJson.parseToJsonElement("unquoted"),
        Any(),
      )

    for (value in truthy) assertTrue(InternalToolHelpers.isTruthy(value), describe(value))
  }

  @Test
  fun errorEntryFailure_truthyErrorEntry_reportsToolError() {
    assertEquals("TOOL_ERROR", InternalToolHelpers.errorEntryFailure(mapOf("error" to "boom")))
  }

  @Test
  fun errorEntryFailure_noTruthyErrorEntry_reportsNothing() {
    assertNull(InternalToolHelpers.errorEntryFailure(mapOf("error" to "")), "empty error")
    assertNull(InternalToolHelpers.errorEntryFailure(mapOf("result" to "ok")), "no error entry")
    assertNull(InternalToolHelpers.errorEntryFailure("error"), "not a map")
  }

  private fun describe(value: Any?): String = "${value?.let { it::class.simpleName }}: $value"

  private companion object {
    val lenientJson = Json { isLenient = true }
  }
}
