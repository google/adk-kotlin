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

import android.content.Context
import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android implementation of [SkillSource] backed by an [AssetManager].
 *
 * Use this source when skills are packaged inside an APK's `assets/` directory rather than unpacked
 * to the filesystem. Prefer building instances via [fromContext]; the primary constructor is
 * `internal`.
 *
 * The implementation tolerates the differing semantics of [AssetManager.list] between real Android
 * (returns immediate children, mixing files and subdirectories) and Robolectric (returns all
 * descendant file paths relative to the queried directory). Helpers below derive direct children
 * and recursive file listings in a way that works under both.
 *
 * @param assets the [AssetManager] to load skill files from.
 * @param skillsBaseDir the asset path that contains skill directories. May be `""` to indicate that
 *   skills live directly under the assets root.
 */
class AssetSkillSource
internal constructor(private val assets: AssetManager, private val skillsBaseDir: String) :
  SkillSource {

  override suspend fun listFrontmatters(): Result<List<Frontmatter>> = sourceRunCatching {
    val skillNames = listImmediateChildren(skillsBaseDir, requireExists = true)
    skillNames
      .filter { isAssetDirectory(joinAssetPath(skillsBaseDir, it)) }
      .map { skillDirName ->
        try {
          parseSkillFromAssets(skillDirName).first
        } catch (e: SkillSourceException) {
          throw SkillSourceException("One of the skills is invalid: ${e.message}", e)
        }
      }
  }

  override suspend fun listResources(
    skillName: String,
    resourceDirectoryPath: String,
  ): Result<List<String>> = sourceRunCatching {
    validateBaseDir()
    // Validates the skill.
    val unused = parseSkillFromAssets(skillName)

    val normalizedPath = normalizeRelativePath(resourceDirectoryPath)
    if (normalizedPath == null) {
      throw SkillSourceException("Invalid resource path: $resourceDirectoryPath")
    }

    val isRoot = normalizedPath.isEmpty()
    if (!isRoot && firstSegment(normalizedPath) !in SkillSource.VALID_RESOURCE_DIRS) {
      throw SkillSourceException(
        "$resourceDirectoryPath must be empty, root (.), or within 'references/', 'assets/', or 'scripts/'"
      )
    }

    val skillDirAssetPath = joinAssetPath(skillsBaseDir, skillName)
    val fullResourceDirPath =
      if (isRoot) skillDirAssetPath else joinAssetPath(skillDirAssetPath, normalizedPath)
    if (!isAssetDirectory(fullResourceDirPath)) {
      throw SkillSourceException("Resource not found: $resourceDirectoryPath")
    }

    val targets = if (isRoot) SkillSource.VALID_RESOURCE_DIRS else listOf(normalizedPath)
    buildList {
      for (target in targets) {
        val targetAssetPath = joinAssetPath(skillDirAssetPath, target)
        if (isAssetDirectory(targetAssetPath)) {
          for (file in listAllFilesUnder(targetAssetPath)) {
            add(joinAssetPath(target, file))
          }
        }
      }
    }
  }

  override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> = sourceRunCatching {
    validateBaseDir()
    parseSkillFromAssets(skillName).first
  }

  override suspend fun loadInstructions(skillName: String): Result<String> = sourceRunCatching {
    validateBaseDir()
    parseSkillFromAssets(skillName).second
  }

  override suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray> =
    sourceRunCatching {
      validateBaseDir()
      // Validates the skill.
      val unused = parseSkillFromAssets(skillName)

      val normalizedPath = normalizeRelativePath(resourcePath)
      if (normalizedPath == null) {
        throw SkillSourceException("Invalid resource path: $resourcePath")
      }
      if (
        normalizedPath.isEmpty() || firstSegment(normalizedPath) !in SkillSource.VALID_RESOURCE_DIRS
      ) {
        throw SkillSourceException(
          "Invalid resource path: $resourcePath must be within 'references/', 'assets/', or 'scripts/'"
        )
      }

      val skillDirAssetPath = joinAssetPath(skillsBaseDir, skillName)
      val fullResourcePath = joinAssetPath(skillDirAssetPath, normalizedPath)
      if (!isAssetFile(fullResourcePath)) {
        throw SkillSourceException("Resource $resourcePath not found in skill $skillName")
      }
      try {
        assets.open(fullResourcePath).use { it.readBytes() }
      } catch (e: IOException) {
        throw SkillSourceException(
          "Failed to read resource $resourcePath in skill $skillName: ${e.message}",
          e,
        )
      }
    }

  /**
   * Verifies that [skillsBaseDir] points to an existing asset directory, throwing
   * [SkillSourceException] otherwise.
   */
  private fun validateBaseDir() {
    if (skillsBaseDir.isEmpty()) {
      // The assets root always exists; nothing to validate.
      return
    }
    if (!assetPathExists(skillsBaseDir)) {
      throw SkillSourceException("Configured skills base directory does not exist.")
    }
    if (!isAssetDirectory(skillsBaseDir)) {
      throw SkillSourceException("Configured skills base path is not a directory.")
    }
  }

  private fun parseSkillFromAssets(skillName: String): Pair<Frontmatter, String> {
    val skillDirAssetPath = joinAssetPath(skillsBaseDir, skillName)
    if (!isAssetDirectory(skillDirAssetPath)) {
      throw SkillSourceException("Skill $skillName not found.")
    }

    val skillMdAssetPath = joinAssetPath(skillDirAssetPath, SKILL_FILE_NAME)
    if (!isAssetFile(skillMdAssetPath)) {
      throw SkillSourceException("Skill $skillName is malformed: missing $SKILL_FILE_NAME.")
    }

    val content =
      try {
        assets.open(skillMdAssetPath).use { it.readBytes().decodeToString() }
      } catch (e: IOException) {
        throw SkillSourceException(
          "Failed to read $SKILL_FILE_NAME for skill $skillName: ${e.message}",
          e,
        )
      }

    return parseSkillMd(skillName, content)
  }

  /**
   * Returns the immediate child names (file or subdirectory) of [dirPath]. Works whether the
   * underlying [AssetManager.list] returns direct children (real Android) or descendant paths
   * (Robolectric). When [requireExists] is `true`, throws [SkillSourceException] if [dirPath] does
   * not resolve to an asset directory.
   */
  private fun listImmediateChildren(dirPath: String, requireExists: Boolean): List<String> {
    val raw = safeList(dirPath)
    if (raw == null) {
      if (requireExists) {
        throw SkillSourceException("Configured skills base directory does not exist.")
      }
      return emptyList()
    }
    if (raw.isEmpty() && requireExists && dirPath.isNotEmpty()) {
      // An empty result means either the path does not exist or it is a regular file.
      if (!isAssetDirectory(dirPath)) {
        if (isAssetFile(dirPath)) {
          throw SkillSourceException("Configured skills base path is not a directory.")
        }
        throw SkillSourceException("Configured skills base directory does not exist.")
      }
    }
    return raw.map { it.substringBefore('/') }.distinct()
  }

  /**
   * Returns paths of all regular files under [dirPath] (recursively), with paths relative to
   * [dirPath] and using `/` separators.
   */
  private fun listAllFilesUnder(dirPath: String): List<String> {
    val entries = safeList(dirPath).orEmpty()
    return buildList {
      for (entry in entries) {
        val childPath = joinAssetPath(dirPath, entry)
        val childListing = safeList(childPath).orEmpty()
        if (childListing.isNotEmpty()) {
          // Subdirectory whose children Android (not Robolectric) is reporting; recurse.
          for (descendant in listAllFilesUnder(childPath)) {
            add(joinAssetPath(entry, descendant))
          }
        } else if (isAssetFile(childPath)) {
          add(entry)
        }
      }
    }
  }

  /**
   * Returns the descendants reported by [AssetManager.list] for [assetPath], or `null` on error.
   */
  private fun safeList(assetPath: String): List<String>? {
    return try {
      assets.list(assetPath)?.toList()
    } catch (e: IOException) {
      null
    }
  }

  /**
   * Returns whether [assetPath] resolves to a directory in the asset tree. The assets root (`""`)
   * is always considered a directory.
   */
  private fun isAssetDirectory(assetPath: String): Boolean {
    if (assetPath.isEmpty()) return true
    // A directory has at least one listed entry; a file or missing path lists as empty.
    val entries = safeList(assetPath) ?: return false
    return entries.isNotEmpty()
  }

  /** Returns whether [assetPath] resolves to a regular file in the asset tree. */
  private fun isAssetFile(assetPath: String): Boolean {
    if (assetPath.isEmpty()) return false
    return try {
      assets.open(assetPath).close()
      true
    } catch (e: FileNotFoundException) {
      false
    } catch (e: IOException) {
      // Opening a directory raises a non-FileNotFound IOException on some platforms.
      false
    }
  }

  /** Returns whether [assetPath] exists as either a file or a directory. */
  private fun assetPathExists(assetPath: String): Boolean =
    isAssetDirectory(assetPath) || isAssetFile(assetPath)

  companion object {
    /**
     * Builds an [AssetSkillSource] that reads skills from the APK's `assets/` directory using
     * `context.applicationContext.assets`. Using the application context avoids Activity leaks.
     *
     * @param context any [Context]; the application context is used internally.
     * @param skillsBaseDir the asset path that contains skill directories. May be `""` to indicate
     *   that skills live directly under the assets root.
     */
    fun fromContext(context: Context, skillsBaseDir: String): AssetSkillSource =
      AssetSkillSource(context.applicationContext.assets, skillsBaseDir)
  }
}

/**
 * Joins two asset path segments using `/`. Empty segments are skipped so that calling with an empty
 * base produces just the child.
 */
private fun joinAssetPath(base: String, child: String): String =
  when {
    base.isEmpty() -> child
    child.isEmpty() -> base
    else -> "$base/$child"
  }

/**
 * Normalizes a relative path supplied by callers (using `/` as the separator), rejecting paths that
 * escape the skill root or are absolute. Returns the normalized path, the empty string if the path
 * is empty or `.`, or `null` if the path is invalid.
 */
private fun normalizeRelativePath(path: String): String? {
  if (path.startsWith("/")) return null
  val segments = mutableListOf<String>()
  for (raw in path.split('/')) {
    when (raw) {
      "",
      "." -> {}
      ".." -> return null
      else -> segments.add(raw)
    }
  }
  return segments.joinToString("/")
}

private fun firstSegment(normalizedPath: String): String {
  val slash = normalizedPath.indexOf('/')
  return if (slash < 0) normalizedPath else normalizedPath.substring(0, slash)
}
