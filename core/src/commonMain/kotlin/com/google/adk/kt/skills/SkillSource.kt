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

package com.google.adk.kt.skills

import kotlin.jvm.JvmOverloads

/**
 * The exception type returned by [SkillSource] methods.
 *
 * A [SkillSource] wraps this exception in [Result.failure] for any skill related failures, for
 * example: the skill does not exist, the requested resource path is malformed, the resource file is
 * missing, or the source itself is misconfigured. The [message] describes the specific cause and is
 * intended to be forwarded to the LLM, so it MUST be precise and self-contained, and MUST NOT leak
 * sensitive internal detail.
 */
class SkillSourceException @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * Core interface for accessing skill components.
 *
 * Implementations are expected to be safe for concurrent use by multiple coroutines.
 *
 * All methods return a [Result] whose failure case is a [SkillSourceException] with a detailed
 * error message intended to be surfaced to the LLM. Implementations should only wrap
 * [SkillSourceException] in [Result.failure].
 */
interface SkillSource {
  companion object {
    const val DIR_REFERENCES = "references"
    const val DIR_ASSETS = "assets"
    const val DIR_SCRIPTS = "scripts"
    val VALID_RESOURCE_DIRS = listOf(DIR_REFERENCES, DIR_ASSETS, DIR_SCRIPTS)
  }

  /**
   * Returns the frontmatter for all available skills.
   *
   * The returned [Result] wraps a [SkillSourceException] failure if the source is misconfigured or
   * if there are duplicate skill names. The exception's message identifies the specific cause.
   */
  suspend fun listFrontmatters(): Result<List<Frontmatter>>

  /**
   * Returns a list of resource paths within a specific directory for a given skill.
   *
   * @param skillName The name of the skill.
   * @param resourceDirectoryPath Relative path from the skill root (e.g., "references", "assets").
   * @return A [Result] wrapping the list of relative paths to resources from the skill root, or a
   *   [SkillSourceException] failure whose message identifies the specific cause (e.g. the skill
   *   does not exist, or [resourceDirectoryPath] is invalid or not found).
   */
  suspend fun listResources(skillName: String, resourceDirectoryPath: String): Result<List<String>>

  /**
   * Loads the frontmatter for a single skill by name.
   *
   * The returned [Result] wraps a [SkillSourceException] failure if the skill does not exist or is
   * malformed. The exception's message identifies the specific cause.
   */
  suspend fun loadFrontmatter(skillName: String): Result<Frontmatter>

  /**
   * Loads the instruction body for a single skill by name.
   *
   * The returned [Result] wraps a [SkillSourceException] failure if the skill does not exist or is
   * malformed. The exception's message identifies the specific cause.
   */
  suspend fun loadInstructions(skillName: String): Result<String>

  /**
   * Loads a specific resource file for a given skill.
   *
   * @param skillName The name of the skill.
   * @param resourcePath Relative path to the resource from the skill root.
   * @return A [Result] wrapping the resource content as a [ByteArray], or a [SkillSourceException]
   *   failure whose message identifies the specific cause (e.g. the skill does not exist, the path
   *   is malformed or invalid, or the resource does not exist within the skill).
   */
  suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray>
}
