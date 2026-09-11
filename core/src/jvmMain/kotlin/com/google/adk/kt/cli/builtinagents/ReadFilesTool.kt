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

/** Hands the model the contents of the files it names, keyed by the path each one was read from. */
internal class ReadFilesTool :
  BaseTool(
    name = "read_files",
    description =
      "Reads the files at the given paths and returns their contents. Paths are relative to the " +
        "project the user is working in.",
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
                  description = "Paths to read, relative to the project directory.",
                )
            ),
          required = listOf("file_paths"),
        ),
    )

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(Dispatchers.IO) {
      val requested = args.stringList("file_paths")
      // Every path is resolved before anything is read, so a batch naming one file outside the
      // project reads none of them rather than half of them.
      val targets =
        runCatching { requested.map { resolveInProject(it, context.context.state) } }
          .getOrElse {
            return@withContext mapOf(
              "success" to false,
              "files" to emptyMap<String, Any?>(),
              "successful_reads" to 0,
              "total_files" to requested.size,
              "errors" to listOf("Read operation failed: ${it.message}"),
            )
          }

      var reads = 0
      var success = true
      val files = LinkedHashMap<String, Any?>()
      for (target in targets) {
        val entry =
          mutableMapOf<String, Any?>("content" to "", "file_size" to 0L, "exists" to false)
        if (!target.exists()) {
          entry["error"] = "File does not exist: $target"
        } else {
          runCatching { target.readText() }
            .onSuccess { content ->
              entry["exists"] = true
              entry["content"] = content
              entry["file_size"] = target.length()
              reads++
            }
            .onFailure {
              entry["error"] = "Failed to read $target: ${it.message}"
              success = false
            }
        }
        files[target.path] = entry
      }

      mapOf(
        "success" to success,
        "files" to files,
        "successful_reads" to reads,
        "total_files" to requested.size,
        "errors" to emptyList<String>(),
      )
    }
}
