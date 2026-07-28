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

package com.google.adk.kt.tools.environment

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type

/** Tool that reads a file, or a range of its lines, with line numbers. */
@ExperimentalEnvironmentApi
internal class ReadFileTool(private val environment: Environment, private val maxOutputChars: Int) :
  BaseTool(
    name = TOOL_READ_FILE,
    description =
      "Read the contents of a file in the environment. Returns the file content with line numbers.",
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        GenaiSchema(
          type = Type.OBJECT,
          properties =
            mapOf(
              PARAM_PATH to
                GenaiSchema(
                  type = Type.STRING,
                  description = "Path of the file to read within the environment.",
                ),
              PARAM_START_LINE to
                GenaiSchema(
                  type = Type.INTEGER,
                  description = "First line to return (1-based, inclusive). Defaults to 1.",
                ),
              PARAM_END_LINE to
                GenaiSchema(
                  type = Type.INTEGER,
                  description = "Last line to return (1-based, inclusive). Defaults to end of file.",
                ),
            ),
          required = listOf(PARAM_PATH),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any> {
    val path = args[PARAM_PATH] as? String
    if (path.isNullOrEmpty()) return errorResult("`$PARAM_PATH` is required.")
    val startLine =
      args[PARAM_START_LINE]?.let {
        toLineNumber(it)
          ?: return errorResult("`$PARAM_START_LINE` must be an integer if provided.")
      }
    val endLine =
      args[PARAM_END_LINE]?.let {
        toLineNumber(it) ?: return errorResult("`$PARAM_END_LINE` must be an integer if provided.")
      }

    val bytes =
      environment.readFile(context, path).getOrElse { e ->
        return e.toEnvironmentErrorResponse()
      }
    val lines = splitLinesNormalized(bytes.decodeToString())
    val total = lines.size
    val from = maxOf(1, startLine ?: 1)
    val to = minOf(total, endLine?.takeIf { it != 0 } ?: total)
    if (from > total) {
      return mapOf(
        KEY_STATUS to STATUS_ERROR,
        KEY_ERROR to "`$PARAM_START_LINE` $from exceeds file length ($total lines).",
        KEY_TOTAL_LINES to total,
      )
    }
    if (from > to) {
      return mapOf(
        KEY_STATUS to STATUS_ERROR,
        KEY_ERROR to "`$PARAM_START_LINE` ($from) is after `$PARAM_END_LINE` ($to).",
        KEY_TOTAL_LINES to total,
      )
    }
    val numbered = buildString {
      for (n in from..to) {
        append(n.toString().padStart(LINE_NUMBER_WIDTH)).append('\t').append(lines[n - 1])
      }
    }
    return buildMap {
      put(KEY_STATUS, STATUS_OK)
      put(KEY_CONTENT, truncateOutput(numbered, maxOutputChars))
      if (from > 1 || to < total) put(KEY_TOTAL_LINES, total)
    }
  }

  private companion object {
    const val LINE_NUMBER_WIDTH = 6

    /** Returns [value] clamped to the `Int` range if it is an `Int` or `Long`, else `null`. */
    fun toLineNumber(value: Any): Int? =
      when (value) {
        is Int -> value
        is Long -> value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        else -> null
      }

    /** Splits [text] into lines, each line break as `\n`; a last line without one gets none. */
    fun splitLinesNormalized(text: String): List<String> {
      val lines = text.lines()
      val withBreaks = lines.mapIndexed { i, line -> if (i < lines.lastIndex) "$line\n" else line }
      // Text that ends in a line break leaves an empty last element.
      return if (withBreaks.last().isEmpty()) withBreaks.dropLast(1) else withBreaks
    }
  }
}
