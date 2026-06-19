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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

class NewFileSystemSource(private val skillsBaseDir: String) : SkillSource {

  override suspend fun listFrontmatters(): Result<List<Frontmatter>> = sourceRunCatching {
    val baseDirPath = Paths.get(skillsBaseDir)
    val baseDirStream =
      try {
        Files.list(baseDirPath)
      } catch (e: java.nio.file.NoSuchFileException) {
        throw SkillSourceException("Configured skills base directory does not exist.", e)
      } catch (e: java.nio.file.NotDirectoryException) {
        throw SkillSourceException("Configured skills base path is not a directory.", e)
      }
    baseDirStream.use { stream ->
      Sequence { stream.iterator() }
        .filter { Files.isDirectory(it) }
        .map { skillDir ->
          try {
            parseSkillFromDir(skillDir).first
          } catch (e: SkillSourceException) {
            throw SkillSourceException("One of the skills is invalid: ${e.message}", e)
          }
        }
        .toList()
    }
  }

  override suspend fun listResources(
    skillName: String,
    resourceDirectoryPath: String,
  ): Result<List<String>> = sourceRunCatching {
    validateBaseDir()
    val skillDirPath = Paths.get(skillsBaseDir).resolve(skillName)
    // Validates the skill.
    val unused = parseSkillFromDir(skillDirPath)

    val normalizedPath = Paths.get(resourceDirectoryPath).normalize()
    if (normalizedPath.isAbsolute || normalizedPath.startsWith(Paths.get(".."))) {
      throw SkillSourceException("Invalid resource path: $resourceDirectoryPath")
    }

    val isRoot = normalizedPath.toString().isEmpty()
    if (!isRoot && normalizedPath.getName(0).toString() !in SkillSource.VALID_RESOURCE_DIRS) {
      throw SkillSourceException(
        "$resourceDirectoryPath must be empty, root (.), or within 'references/', 'assets/', or 'scripts/'"
      )
    }

    val fullResourceDirPath = if (isRoot) skillDirPath else skillDirPath.resolve(normalizedPath)
    if (!Files.exists(fullResourceDirPath)) {
      throw SkillSourceException("Resource not found: $resourceDirectoryPath")
    }

    val targets = if (isRoot) SkillSource.VALID_RESOURCE_DIRS else listOf(normalizedPath.toString())
    buildList {
      for (target in targets) {
        val targetPath = skillDirPath.resolve(target)
        if (Files.exists(targetPath)) {
          Files.walk(targetPath).use { stream ->
            stream
              .asSequence()
              .filter { Files.isRegularFile(it) }
              .map { skillDirPath.relativize(it).toString().replace(java.io.File.separator, "/") }
              .forEach { add(it) }
          }
        }
      }
    }
  }

  override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> = sourceRunCatching {
    validateBaseDir()
    parseSkillFromDir(Paths.get(skillsBaseDir).resolve(skillName)).first
  }

  override suspend fun loadInstructions(skillName: String): Result<String> = sourceRunCatching {
    validateBaseDir()
    parseSkillFromDir(Paths.get(skillsBaseDir).resolve(skillName)).second
  }

  override suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray> =
    sourceRunCatching {
      validateBaseDir()
      val skillDirPath = Paths.get(skillsBaseDir).resolve(skillName)
      // Validates the skill.
      val unused = parseSkillFromDir(skillDirPath)

      val normalizedPath = Paths.get(resourcePath).normalize()
      if (normalizedPath.startsWith(Paths.get("..")) || normalizedPath.isAbsolute) {
        throw SkillSourceException("Invalid resource path: $resourcePath")
      }
      if (
        normalizedPath.nameCount > 0 &&
          normalizedPath.getName(0).toString() !in SkillSource.VALID_RESOURCE_DIRS
      ) {
        throw SkillSourceException(
          "Invalid resource path: $resourcePath must be within 'references/', 'assets/', or 'scripts/'"
        )
      }

      val fullResourcePath = skillDirPath.resolve(normalizedPath)
      if (!Files.exists(fullResourcePath) || !Files.isRegularFile(fullResourcePath)) {
        throw SkillSourceException("Resource $resourcePath not found in skill $skillName")
      }
      try {
        Files.readAllBytes(fullResourcePath)
      } catch (e: java.io.IOException) {
        throw SkillSourceException(
          "Failed to read resource $resourcePath in skill $skillName: ${e.message}",
          e,
        )
      }
    }

  /**
   * Verifies that [skillsBaseDir] points to an existing directory, throwing [SkillSourceException]
   * otherwise.
   */
  private fun validateBaseDir() {
    val baseDirPath = Paths.get(skillsBaseDir)
    if (!baseDirPath.exists()) {
      throw SkillSourceException("Configured skills base directory does not exist.")
    }
    if (!baseDirPath.isDirectory()) {
      throw SkillSourceException("Configured skills base path is not a directory.")
    }
  }
}

private fun parseSkillFromDir(skillDirPath: Path): Pair<Frontmatter, String> {
  val skillName = skillDirPath.name
  if (!skillDirPath.exists() || !skillDirPath.isDirectory()) {
    throw SkillSourceException("Skill $skillName not found.")
  }

  val skillMdPath = skillDirPath.resolve(SKILL_FILE_NAME)
  if (!skillMdPath.exists()) {
    throw SkillSourceException("Skill $skillName is malformed: missing $SKILL_FILE_NAME.")
  }

  val content =
    try {
      skillMdPath.readText()
    } catch (e: java.io.IOException) {
      throw SkillSourceException(
        "Failed to read $SKILL_FILE_NAME for skill $skillName: ${e.message}",
        e,
      )
    }
  return parseSkillMd(skillName, content)
}
