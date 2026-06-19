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

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** Filename of the skill manifest inside a skill directory. */
internal const val SKILL_FILE_NAME = "SKILL.md"

/** YAML frontmatter delimiter used in [SKILL_FILE_NAME] documents. */
internal const val FRONTMATTER_SEPARATOR = "---"

/**
 * Runs [block] and wraps its outcome in a [Result].
 *
 * A successful return is wrapped in [Result.success]; a [SkillSourceException] thrown by [block] is
 * wrapped in [Result.failure] with the message preserved verbatim. Any other exception is
 * intentionally NOT caught and propagates to the caller.
 */
internal inline fun <R> sourceRunCatching(block: () -> R): Result<R> =
  try {
    Result.success(block())
  } catch (e: SkillSourceException) {
    Result.failure(e)
  }

/**
 * Parses a `SKILL.md` document into its YAML frontmatter map and the instruction body.
 *
 * @throws IllegalArgumentException if the document is malformed in any of the following ways: the
 *   leading `---` separator is missing; the trailing `---` separator is missing; the frontmatter
 *   body is empty or whitespace-only; the frontmatter is not valid YAML; the frontmatter root is
 *   not a YAML mapping (e.g. it is a list, scalar, or null).
 */
internal fun parseSkillMdContent(content: String): Pair<Map<String, Any>, String> {
  if (!content.startsWith(FRONTMATTER_SEPARATOR)) {
    throw IllegalArgumentException(
      "$SKILL_FILE_NAME must start with YAML frontmatter ($FRONTMATTER_SEPARATOR)"
    )
  }
  val parts = content.split(FRONTMATTER_SEPARATOR, limit = 3)
  if (parts.size < 3) {
    throw IllegalArgumentException(
      "$SKILL_FILE_NAME frontmatter not properly closed with $FRONTMATTER_SEPARATOR"
    )
  }

  val frontmatterStr = parts[1]
  val instructions = parts[2].trim()

  if (frontmatterStr.isBlank()) {
    throw IllegalArgumentException("Frontmatter must not be empty.")
  }

  try {
    val yaml = Yaml(SafeConstructor(LoaderOptions()))
    val parsed: Map<String, Any> = yaml.load(frontmatterStr)
    return parsed to instructions
  } catch (e: YAMLException) {
    throw IllegalArgumentException("Invalid YAML in frontmatter: ${e.message}", e)
  } catch (e: ClassCastException) {
    throw IllegalArgumentException("Frontmatter must be a YAML mapping.", e)
  }
}

/**
 * Builds a [Frontmatter] from a parsed YAML mapping and validates that the declared `name` matches
 * [skillName].
 *
 * @throws SkillSourceException if `name` or `description` are missing, if the declared `name` does
 *   not match [skillName], or if [Frontmatter] construction fails its own validation.
 */
internal fun buildValidatedFrontmatter(
  skillName: String,
  frontmatterMap: Map<String, Any>,
): Frontmatter {
  val name =
    frontmatterMap["name"] as? String
      ?: throw SkillSourceException(
        "Skill $skillName is malformed: 'name' field missing from frontmatter."
      )
  val description =
    frontmatterMap["description"] as? String
      ?: throw SkillSourceException(
        "Skill $skillName is malformed: 'description' field missing from frontmatter."
      )
  if (name != skillName) {
    throw SkillSourceException(
      "Skill $skillName is malformed: name '$name' in $SKILL_FILE_NAME does not match directory name '$skillName'"
    )
  }

  return try {
    Frontmatter(
      name = name,
      description = description,
      license = frontmatterMap["license"] as? String,
      compatibility = frontmatterMap["compatibility"] as? String,
      allowedTools =
        frontmatterMap["allowed-tools"] as? String ?: frontmatterMap["allowed_tools"] as? String,
      metadata =
        (frontmatterMap["metadata"] as? Map<*, *>)
          ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
          ?.toMap() ?: emptyMap(),
    )
  } catch (e: IllegalArgumentException) {
    throw SkillSourceException("Skill $skillName is malformed: ${e.message}", e)
  }
}

/**
 * Parses [content] (the raw contents of a [SKILL_FILE_NAME] file) belonging to a skill named
 * [skillName] into a validated [Frontmatter] and the instruction body, wrapping any parsing or
 * validation error in a [SkillSourceException] tagged with [skillName].
 */
internal fun parseSkillMd(skillName: String, content: String): Pair<Frontmatter, String> {
  val (frontmatterMap, instructions) =
    try {
      parseSkillMdContent(content)
    } catch (e: IllegalArgumentException) {
      throw SkillSourceException(
        "Skill $skillName is malformed: invalid frontmatter: ${e.message}",
        e,
      )
    }
  return buildValidatedFrontmatter(skillName, frontmatterMap) to instructions
}
