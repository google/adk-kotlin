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
import com.google.adk.kt.environment.BaseEnvironment
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type

/** Perform a surgical text replacement in an existing file. */
@ExperimentalEnvironmentApi
internal class EditFileTool(private val environment: BaseEnvironment) :
  BaseTool(
    name = TOOL_EDIT_FILE,
    description =
      "Replace an exact substring in an existing file with new text. The old_string must appear " +
        "exactly once in the file. To create new files, use the WriteFile tool.",
  ) {

  private val logger = LoggerFactory.getLogger(EditFileTool::class)

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

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any> {
    val path = args[PARAM_PATH] as? String ?: ""
    val oldString = args[PARAM_OLD_STRING] as? String ?: ""
    val newString = args[PARAM_NEW_STRING] as? String ?: ""
    if (path.isEmpty()) return errorResult("`path` is required.")
    if (oldString.isEmpty()) {
      return errorResult(
        "`old_string` cannot be empty. To create a new file, use the WriteFile tool."
      )
    }

    val content =
      environment
        .readFile(path)
        .getOrElse { e ->
          return e.toEnvironmentErrorResponse(logger)
        }
        .decodeToString()

    // Match line breaks flexibly: normalize old_string to `\n`, then let each newline match `\r?\n`
    // so a search string and file can differ in line endings. Non-newline text is matched
    // literally.
    val normalizedOld = oldString.replace("\r\n", "\n")
    val pattern = normalizedOld.split("\n").joinToString("""\r?\n""") { Regex.escape(it) }
    val regex = Regex(pattern)
    val count = regex.findAll(content).count()

    if (count == 0) {
      return errorResult("`old_string` not found in file. Read the file first to verify contents.")
    }
    if (count > 1) {
      return errorResult(
        "`old_string` appears $count times. Provide more surrounding context to make it unique."
      )
    }

    // count == 1, so replacing all matches replaces exactly the single occurrence.
    environment
      .writeFile(path, regex.replace(content) { newString }.encodeToByteArray())
      .getOrElse { e ->
        return e.toEnvironmentErrorResponse(logger)
      }
    return mapOf(KEY_STATUS to STATUS_OK, KEY_MESSAGE to "Edited $path")
  }
}
