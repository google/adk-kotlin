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

import com.google.adk.kt.YamlUtils
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Directories a survey of a project has no reason to descend into. */
private val UNINTERESTING_DIRECTORIES = setOf("__pycache__", "node_modules", "build")

/** How far down the tree the survey reports before it says "truncated". */
private const val MAX_TREE_DEPTH = 3

/** The file extensions this SDK reads an agent document from. */
private val AGENT_DOCUMENT_EXTENSIONS = setOf("yaml", "yml")

/**
 * Reports what is in the project the conversation is about.
 *
 * This is the tool the assistant opens with. It takes no arguments, because the project is the one
 * named in session state and the model cannot point it somewhere else, and every path it reports is
 * relative to the project, because a model handed absolute paths starts writing them back.
 */
internal class ExploreProjectTool :
  BaseTool(
    name = "explore_project",
    description =
      "Reports what is in the project the user is working in: its name, whether it is empty, " +
        "which kinds of file it holds, and the agent documents at its root. Takes no arguments.",
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description, parameters = Schema(Type.OBJECT))

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    withContext(Dispatchers.IO) {
      val root =
        runCatching { projectRoot(context.context.state) }
          .getOrElse {
            return@withContext mapOf(
              "success" to false,
              "error" to "Error exploring project: ${it.message}",
            )
          }
      if (!root.exists()) {
        return@withContext mapOf(
          "success" to false,
          "error" to "Project directory does not exist: $root",
        )
      }
      if (!root.isDirectory) {
        return@withContext mapOf("success" to false, "error" to "Path is not a directory: $root")
      }

      mapOf(
        "success" to true,
        "project_info" to projectInfo(root),
        "existing_configs" to agentConfigsAtRoot(root),
        "directory_structure" to tree(root, root, depth = 0),
      )
    }

  private fun projectInfo(root: File): Map<String, Any?> {
    var files = 0
    var directories = 0
    var python = false
    var yaml = false
    var kotlin = false
    for (entry in root.walkTopDown().drop(1)) {
      if (entry.isDirectory) {
        directories++
        continue
      }
      files++
      when (entry.extension.lowercase()) {
        "py" -> python = true
        "kt",
        "kts" -> kotlin = true
        "yaml",
        "yml" -> yaml = true
      }
    }
    return mapOf(
      "name" to root.name,
      "absolute_path" to root.path,
      "is_empty" to (root.list()?.isEmpty() ?: true),
      "total_files" to files,
      "total_directories" to directories,
      "has_kotlin_files" to kotlin,
      "has_python_files" to python,
      "has_yaml_files" to yaml,
      "has_tools_directory" to File(root, "tools").isDirectory,
      "has_callbacks_directory" to File(root, "callbacks").isDirectory,
    )
  }

  /**
   * The agent documents of the project, which are the YAML files at its root.
   *
   * A YAML file further down is somebody's fixture or somebody's data. Only the root is where this
   * SDK looks for an agent, so only the root is reported as holding one.
   */
  private fun agentConfigsAtRoot(root: File): List<Map<String, Any?>> =
    (root.listFiles() ?: emptyArray())
      .filter { it.isFile && it.extension.lowercase() in AGENT_DOCUMENT_EXTENSIONS }
      .sortedBy { it.name }
      .map { configInfo(it, root) }

  private fun configInfo(config: File, root: File): Map<String, Any?> {
    val document = runCatching { YamlUtils.loadYamlFile(config.path) }.getOrNull() as? Map<*, *>
    return mapOf(
      "filename" to config.name,
      "relative_path" to config.toRelativeString(root),
      "size" to config.length(),
      "is_valid_yaml" to (document != null),
      "agent_name" to document?.get("name")?.toString(),
      "agent_class" to (document?.get("agent_class")?.toString() ?: document?.let { "LlmAgent" }),
      "has_sub_agents" to document?.get("sub_agents").isPresent(),
      "has_tools" to document?.get("tools").isPresent(),
    )
  }

  private fun tree(entry: File, root: File, depth: Int): Map<String, Any?> {
    if (depth > MAX_TREE_DEPTH) return mapOf("truncated" to true)

    val node =
      mutableMapOf<String, Any?>(
        "name" to entry.name,
        "type" to if (entry.isDirectory) "directory" else "file",
        "path" to entry.toRelativeString(root),
      )
    if (entry.isDirectory) {
      node["children"] =
        (entry.listFiles() ?: emptyArray())
          .filter { !it.name.startsWith(".") && it.name !in UNINTERESTING_DIRECTORIES }
          .sortedBy { it.name }
          .map { tree(it, root, depth + 1) }
    } else {
      node["size"] = entry.length()
    }
    return node
  }

  /** Whether a field of a YAML document says anything, the way Python's truthiness reads it. */
  private fun Any?.isPresent(): Boolean =
    when (this) {
      null -> false
      is Boolean -> this
      is Collection<*> -> isNotEmpty()
      is Map<*, *> -> isNotEmpty()
      is String -> isNotEmpty()
      else -> true
    }
}
