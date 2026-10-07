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

/** Tool that replaces one exact occurrence of a string in an existing file. */
@ExperimentalEnvironmentApi
internal class EditFileTool(private val environment: Environment) :
  BaseTool(
    name = TOOL_EDIT_FILE,
    description =
      "Replace an exact substring in an existing file with new text. The $PARAM_OLD_STRING must " +
        "appear exactly once in the file. To create new files, use the $TOOL_WRITE_FILE tool.",
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
                  description = "Path of the file to edit within the environment.",
                ),
              PARAM_OLD_STRING to
                GenaiSchema(
                  type = Type.STRING,
                  description = "The exact text to find and replace. Must not be empty.",
                ),
              PARAM_NEW_STRING to
                GenaiSchema(type = Type.STRING, description = "The replacement text."),
            ),
          required = listOf(PARAM_PATH, PARAM_OLD_STRING, PARAM_NEW_STRING),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any> {
    val path = args[PARAM_PATH] as? String ?: ""
    val oldString = args[PARAM_OLD_STRING] as? String ?: ""
    val newString = args[PARAM_NEW_STRING] as? String ?: ""
    if (path.isEmpty()) return errorResult("`$PARAM_PATH` is required.")
    if (oldString.isEmpty()) {
      return errorResult(
        "`$PARAM_OLD_STRING` cannot be empty. To create a new file, use the $TOOL_WRITE_FILE tool."
      )
    }

    val content =
      environment
        .readFile(context, path)
        .getOrElse { e ->
          return e.toEnvironmentErrorResponse()
        }
        .decodeToString()

    // Each line break in old_string matches any single line ending in the file.
    val regex = Regex(oldString.lines().joinToString(LINE_BREAK_PATTERN) { Regex.escape(it) })
    val matches = regex.findAll(content).toList()
    if (matches.isEmpty()) {
      return errorResult(
        "`$PARAM_OLD_STRING` not found in file. Read the file first to verify contents."
      )
    }
    if (matches.size > 1) {
      return errorResult(
        "`$PARAM_OLD_STRING` appears ${matches.size} times. " +
          "Provide more surrounding context to make it unique."
      )
    }

    environment
      .writeFile(
        context,
        path,
        content.replaceRange(matches.single().range, newString).encodeToByteArray(),
      )
      .getOrElse { e ->
        return e.toEnvironmentErrorResponse()
      }
    return mapOf(KEY_STATUS to STATUS_OK, KEY_MESSAGE to "Edited $path")
  }

  private companion object {
    /** Matches one line ending; a `\r` followed by `\n` never matches on its own. */
    const val LINE_BREAK_PATTERN = """(?:\r\n|\r(?!\n)|\n)"""
  }
}
