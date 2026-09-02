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

package com.google.adk.kt.cli.builtinagents

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes the files the model names, and only those.
 *
 * The file belongs to the user, so a deletion the user has not agreed to removes nothing: the model
 * has to say that the user confirmed it, and a call that does not is refused with the reason.
 */
internal class DeleteFilesTool :
  BaseTool(
    name = "delete_files",
    description =
      "Deletes the files at the given paths, which are relative to the project the user is " +
        "working in. Only call this once the user has agreed to the deletion, and pass " +
        "confirm_deletion accordingly.",
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "file_paths" to
                Schema(
                  type = Type.ARRAY,
                  items = Schema(type = Type.STRING),
                  description = "Paths to delete, relative to the project directory.",
                ),
              "confirm_deletion" to
                Schema(
                  type = Type.BOOLEAN,
                  description = "Whether the user agreed to this deletion. Defaults to true.",
                ),
            ),
          required = listOf("file_paths"),
        ),
    )

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(Dispatchers.IO) {
      val requested = args.stringList("file_paths")
      // Every path is resolved before anything is deleted, so a batch naming one file outside the
      // project deletes none of them rather than some of them.
      val targets =
        runCatching { requested.map { resolveInProject(it, context.context.state) } }
          .getOrElse {
            return@withContext refusal(requested.size, "Delete operation failed: ${it.message}")
          }

      if (!args.flag("confirm_deletion", default = true)) {
        return@withContext refusal(requested.size, "Deletion not confirmed by user")
      }

      var deletions = 0
      var success = true
      val files = LinkedHashMap<String, Any?>()
      for (target in targets) {
        val entry = mutableMapOf<String, Any?>("existed" to false, "file_size" to 0L)
        when {
          !target.exists() -> {
            // Nothing to remove and nothing lost, so this counts as a deletion, as it does in the
            // Python ADK. It is still reported, because a path the model expected to exist and did
            // not is usually a path it got wrong.
            entry["error"] = "File does not exist: $target"
            deletions++
          }
          target.isDirectory -> {
            entry["error"] = "Not a file: $target"
            success = false
          }
          else -> {
            entry["existed"] = true
            entry["file_size"] = target.length()
            if (target.delete()) {
              deletions++
            } else {
              entry["error"] = "Deletion failed: $target"
              success = false
            }
          }
        }
        files[target.path] = entry
      }

      mapOf(
        "success" to success,
        "files" to files,
        "successful_deletions" to deletions,
        "total_files" to requested.size,
        "errors" to emptyList<String>(),
      )
    }

  private fun refusal(requested: Int, reason: String): Map<String, Any?> =
    mapOf(
      "success" to false,
      "files" to emptyMap<String, Any?>(),
      "successful_deletions" to 0,
      "total_files" to requested,
      "errors" to listOf(reason),
    )
}
