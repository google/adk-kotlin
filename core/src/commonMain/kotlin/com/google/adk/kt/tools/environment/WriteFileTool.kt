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

/** Create or overwrite a file in the environment. */
@ExperimentalEnvironmentApi
internal class WriteFileTool(private val environment: Environment) :
  BaseTool(
    name = TOOL_WRITE_FILE,
    description =
      "Create or overwrite a file in the environment. Use for new files or full rewrites. For " +
        "small changes to existing files, prefer $TOOL_EDIT_FILE.",
  ) {

  private val logger = LoggerFactory.getLogger(WriteFileTool::class)

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
                  description = "Path to the file within the environment.",
                ),
              PARAM_CONTENT to
                GenaiSchema(type = Type.STRING, description = "The full file content to write."),
            ),
          required = listOf(PARAM_PATH, PARAM_CONTENT),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any> {
    val path = args[PARAM_PATH] as? String
    if (path.isNullOrEmpty()) return errorResult("`path` is required.")
    val content = args[PARAM_CONTENT] as? String ?: ""
    environment.writeFile(context, path, content.encodeToByteArray()).getOrElse { e ->
      return e.toEnvironmentErrorResponse(logger)
    }
    return mapOf(KEY_STATUS to STATUS_OK, KEY_MESSAGE to "Wrote $path")
  }
}
