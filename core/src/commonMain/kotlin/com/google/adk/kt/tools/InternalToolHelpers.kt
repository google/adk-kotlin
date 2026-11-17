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

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Shared helpers for built-in [BaseTool] implementations (e.g. [RequestInputTool],
 * [GetUserChoiceTool]) that don't go through the KSP [FunctionTool] machinery yet need the same
 * declaration and error conventions.
 */
internal object InternalToolHelpers {

  /**
   * Returns [description] with [FunctionTool.LONG_RUNNING_OPERATION_NOTE] appended, the declaration
   * description used by long-running tools.
   */
  fun withLongRunningNote(description: String): String =
    "$description\n\n${FunctionTool.LONG_RUNNING_OPERATION_NOTE}"

  /**
   * Builds the LLM-visible error a tool returns when the model omits one or more mandatory
   * parameters, matching Python ADK's `FunctionTool.run_async`. Listing every missing parameter
   * lets the model retry with all of them at once.
   */
  fun missingMandatoryParamsError(toolName: String, missingParams: List<String>): Map<String, Any> =
    mapOf(
      FunctionTool.ERROR_KEY to
        "Invoking `$toolName()` failed as the following mandatory input parameters are not " +
          "present:\n${missingParams.joinToString("\n")}\nYou could retry calling this tool, " +
          "but it is IMPORTANT for you to provide all the mandatory parameters."
    )

  /** The `error.type` of a failure a tool reports in its response. */
  const val TOOL_ERROR = "TOOL_ERROR"

  /** Returns [TOOL_ERROR] when [response] is a map with a truthy `error` entry, else null. */
  fun errorEntryFailure(response: Any?): String? =
    if (response is Map<*, *> && isTruthy(response[FunctionTool.ERROR_KEY])) TOOL_ERROR else null

  /** Whether [value] is truthy: not null, false, zero or empty. */
  fun isTruthy(value: Any?): Boolean =
    when (value) {
      null -> false
      is Boolean -> value
      is String -> value.isNotEmpty()
      is Number -> value.toDouble() != 0.0
      is Collection<*> -> value.isNotEmpty()
      is Map<*, *> -> value.isNotEmpty()
      is Array<*> -> value.isNotEmpty()
      is JsonNull -> false
      is JsonPrimitive ->
        if (value.isString) value.content.isNotEmpty()
        else value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 } ?: true
      else -> true
    }
}
