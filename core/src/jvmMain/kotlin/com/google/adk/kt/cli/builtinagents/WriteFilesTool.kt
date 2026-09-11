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
 * Writes the files the model supplies into the project.
 *
 * This is the tool the assistant exists for. Everything else it carries looks at a project or
 * tidies one; this is the one that ends the conversation with an agent the user did not have when
 * it started.
 */
internal class WriteFilesTool :
  BaseTool(
    name = "write_files",
    description =
      "Writes each of the given contents to its path, creating the file if it is not there and " +
        "replacing it if it is. Paths are relative to the project the user is working in.",
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
              "files" to
                Schema(
                  type = Type.OBJECT,
                  description =
                    "The content to write, keyed by the path to write it to, relative to the " +
                      "project directory.",
                ),
              "create_directories" to
                Schema(
                  type = Type.BOOLEAN,
                  description = "Create missing parent directories. Defaults to true.",
                ),
            ),
          required = listOf("files"),
        ),
    )

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(Dispatchers.IO) {
      val requested = args.contentByPath("files")
      val createDirectories = args.flag("create_directories", default = true)
      // Every path is resolved before anything is written, so a batch naming one file outside the
      // project writes none of them rather than some of them.
      val targets =
        runCatching {
            requested.map { (path, content) ->
              resolveInProject(path, context.context.state) to content
            }
          }
          .getOrElse {
            return@withContext mapOf(
              "success" to false,
              "files" to emptyMap<String, Any?>(),
              "successful_writes" to 0,
              "total_files" to requested.size,
              "errors" to listOf("Write operation failed: ${it.message}"),
            )
          }

      var writes = 0
      var success = true
      val files = LinkedHashMap<String, Any?>()
      for ((target, content) in targets) {
        val entry =
          mutableMapOf<String, Any?>("existed_before" to target.exists(), "file_size" to 0L)
        runCatching {
            if (createDirectories) {
              target.parentFile?.mkdirs()
            }
            target.writeText(content)
          }
          .onSuccess {
            entry["file_size"] = target.length()
            writes++
          }
          .onFailure {
            entry["error"] = "Write failed: ${it.message}"
            success = false
          }
        files[target.path] = entry
      }

      mapOf(
        "success" to success,
        "files" to files,
        "successful_writes" to writes,
        "total_files" to requested.size,
        "errors" to emptyList<String>(),
      )
    }

  /** The contents the model sent under [name], keyed by the path each is meant for. */
  private fun Map<String, Any?>.contentByPath(name: String): Map<String, String> {
    val sent = this[name] as? Map<*, *> ?: return emptyMap()
    return buildMap {
      for ((path, content) in sent) {
        if (path is String) put(path, content?.toString().orEmpty())
      }
    }
  }
}
