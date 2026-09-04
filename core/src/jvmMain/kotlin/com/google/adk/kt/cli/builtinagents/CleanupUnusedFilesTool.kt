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
 * The source files under the project that the caller's list does not account for.
 *
 * A project that has been rebuilt a few times accumulates tool sources no agent references any
 * more, and the assistant is the one that knows which are still referenced. It names them and stops
 * there: removing a file the user never agreed to lose is [DeleteFilesTool]'s job.
 */
internal class CleanupUnusedFilesTool :
  BaseTool(
    name = "cleanup_unused_files",
    description =
      "Lists the source files under the project that are not in the given list of files in use. " +
        "It deletes nothing; call delete_files once the user has agreed to remove them.",
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
              "used_files" to
                Schema(
                  type = Type.ARRAY,
                  items = Schema(type = Type.STRING),
                  description = "Paths still in use, relative to the project directory.",
                ),
              "file_patterns" to
                Schema(
                  type = Type.ARRAY,
                  items = Schema(type = Type.STRING),
                  description = "File-name globs to consider. Defaults to source files.",
                ),
              "exclude_patterns" to
                Schema(
                  type = Type.ARRAY,
                  items = Schema(type = Type.STRING),
                  description = "File-name globs to leave alone. Defaults to markers and tests.",
                ),
            ),
          required = listOf("used_files"),
        ),
    )

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(Dispatchers.IO) {
      val state = context.context.state
      val considered = args.stringList("file_patterns").ifEmpty { SOURCE_FILES }.map(::globRegex)
      val skipped =
        args.stringList("exclude_patterns").ifEmpty { NOT_THE_USERS_TO_LOSE }.map(::globRegex)

      val root =
        runCatching { projectRoot(state) }
          .getOrElse {
            return@withContext failure("Cleanup scan failed: ${it.message}")
          }
      if (!root.isDirectory) {
        return@withContext failure("Root directory does not exist: $root")
      }
      val inUse =
        runCatching { args.stringList("used_files").map { resolveInProject(it, state) }.toSet() }
          .getOrElse {
            return@withContext failure("Cleanup scan failed: ${it.message}")
          }

      val unused =
        root
          .walkTopDown()
          .filter { it.isFile }
          .filter { file -> considered.any { it.matches(file.name) } }
          .filterNot { file -> skipped.any { it.matches(file.name) } }
          .filterNot { it.canonicalFile in inUse }
          .map { it.path }
          .toList()

      mapOf("success" to true, "unused_files" to unused, "errors" to emptyList<String>())
    }

  private fun failure(reason: String): Map<String, Any?> =
    mapOf("success" to false, "unused_files" to emptyList<String>(), "errors" to listOf(reason))

  private companion object {
    /**
     * What counts as a source file worth reporting: Kotlin because that is what this SDK's tools
     * are written in, and Python because a project carried over from adk-python still holds the
     * tools it came with.
     */
    val SOURCE_FILES = listOf("*.kt", "*.py")

    /** Files that are not something the user forgot about: package markers and tests. */
    val NOT_THE_USERS_TO_LOSE = listOf("__init__.py", "*Test.kt", "*_test.py", "test_*.py")
  }
}
