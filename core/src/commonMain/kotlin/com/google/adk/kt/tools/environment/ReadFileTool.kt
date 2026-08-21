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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type

/** Read a file from the environment, returning line-numbered content with pagination. */
@ExperimentalEnvironmentApi
internal class ReadFileTool(private val environment: Environment, private val maxOutputChars: Int) :
  BaseTool(
    name = TOOL_READ_FILE,
    description =
      "Read the contents of a file in the environment. Returns the file content with line numbers.",
  ) {

  private val logger = LoggerFactory.getLogger(ReadFileTool::class)

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

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any> {
    val path = args[PARAM_PATH] as? String
    if (path.isNullOrEmpty()) return errorResult("`path` is required.")

    val startRaw = args[PARAM_START_LINE]
    if (startRaw != null && !isValidLineNumber(startRaw)) {
      return errorResult("`$PARAM_START_LINE` must be an integer if provided.")
    }
    val endRaw = args[PARAM_END_LINE]
    if (endRaw != null && !isValidLineNumber(endRaw)) {
      return errorResult("`$PARAM_END_LINE` must be an integer if provided.")
    }
    val startLine = (startRaw as? Number)?.toInt()
    val endLine = (endRaw as? Number)?.toInt()

    val bytes =
      environment.readFile(context, path).getOrElse { e ->
        return e.toEnvironmentErrorResponse(logger)
      }
    val lines = splitLinesKeepEnds(bytes.decodeToString())
    val total = lines.size
    val from = maxOf(1, startLine?.takeIf { it != 0 } ?: 1)
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
    val builder = StringBuilder()
    for (n in from..to) {
      builder.append(n.toString().padStart(LINE_NUMBER_WIDTH)).append('\t').append(lines[n - 1])
    }
    return buildMap {
      put(KEY_STATUS, STATUS_OK)
      put(KEY_CONTENT, truncateOutput(builder.toString(), maxOutputChars))
      if (from > 1 || to < total) put(KEY_TOTAL_LINES, total)
    }
  }

  private companion object {
    const val LINE_NUMBER_WIDTH = 6

    /** A valid line number is a non-boolean integer. */
    fun isValidLineNumber(value: Any): Boolean = value is Int || value is Long
  }
}
