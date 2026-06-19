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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric tests for [AssetSkillSource] focused on the behavior unique to the asset-manager
 * backend: path resolution, the two `AssetManager.list` dialects (real-Android direct children vs
 * Robolectric descendant file paths), the allow-listed resource subdirectories, traversal
 * protection, and `AssetManager` I/O error mapping.
 *
 * The `SKILL.md` parsing pipeline itself is covered by [SkillMdParsingTest]; the fixture SKILL.md
 * files here are intentionally minimal.
 */
@RunWith(AndroidJUnit4::class)
class AssetSkillSourceTest {

  private lateinit var assets: AssetManager
  private lateinit var source: AssetSkillSource

  @Before
  fun setUp() {
    assets = ApplicationProvider.getApplicationContext<Context>().assets
    source = AssetSkillSource(assets, skillsBaseDir = "skills")
  }

  // ---------------------------------------------------------------------------
  // Construction / base-dir handling
  // ---------------------------------------------------------------------------

  @Test
  fun loadFrontmatter_emptyBaseDir_treatsAssetsRootAsBase() = runTest {
    val rootSource = AssetSkillSource(assets, skillsBaseDir = "")

    val frontmatterResult = rootSource.loadFrontmatter("root-only-skill")
    val instructionsResult = rootSource.loadInstructions("root-only-skill")

    assertThat(frontmatterResult.isSuccess).isTrue()
    assertThat(frontmatterResult.getOrThrow().name).isEqualTo("root-only-skill")
    assertThat(instructionsResult.isSuccess).isTrue()
    assertThat(instructionsResult.getOrThrow()).isEqualTo("Top-level instructions")
  }

  @Test
  fun loadFrontmatter_nonexistentBaseDir_reportsBaseMissingNotSkillMissing() = runTest {
    val brokenSource = AssetSkillSource(assets, skillsBaseDir = "does-not-exist")

    val result = brokenSource.loadFrontmatter("any-skill")

    assertThat(result.isFailure).isTrue()
    val message = result.exceptionOrNull()!!.message
    assertThat(message).contains("Configured skills base directory does not exist")
    // Must not blame the skill: the source itself is misconfigured.
    assertThat(message).doesNotContain("any-skill")
  }

  @Test
  fun loadFrontmatter_baseDirIsFile_reportsNotADirectory() = runTest {
    val brokenSource = AssetSkillSource(assets, skillsBaseDir = "not-a-directory.txt")

    val result = brokenSource.loadFrontmatter("any-skill")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message)
      .contains("Configured skills base path is not a directory")
  }

  // ---------------------------------------------------------------------------
  // Path joining and skill discovery
  // ---------------------------------------------------------------------------

  @Test
  fun loadFrontmatter_baseDirAndSkillNameAreJoinedWithSlash() = runTest {
    val result = source.loadFrontmatter("valid-skill")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().name).isEqualTo("valid-skill")
  }

  @Test
  fun loadFrontmatter_missingSkillDirectory_reportsSkillNotFound() = runTest {
    val result = source.loadFrontmatter("ghost")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("Skill ghost not found")
  }

  @Test
  fun loadFrontmatter_skillDirectoryWithoutSkillMd_reportsMissingSkillMd() = runTest {
    val result = source.loadFrontmatter("skill-missing-md")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("missing SKILL.md")
  }

  // ---------------------------------------------------------------------------
  // loadInstructions
  // ---------------------------------------------------------------------------

  @Test
  fun loadInstructions_returnsTrimmedBody() = runTest {
    val result = source.loadInstructions("valid-skill")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow()).isEqualTo("Instructions for valid-skill")
  }

  // ---------------------------------------------------------------------------
  // listFrontmatters
  // ---------------------------------------------------------------------------

  @Test
  fun listFrontmatters_returnsAllDirectChildSkills() = runTest {
    val listingSource = AssetSkillSource(assets, skillsBaseDir = "skills-for-listing")

    val frontmatters = listingSource.listFrontmatters().getOrThrow()

    assertThat(frontmatters.map { it.name }).containsExactly("skill1", "skill2")
  }

  @Test
  fun listFrontmatters_missingSkillMd_returnsFailure() = runTest {
    val brokenSource = AssetSkillSource(assets, skillsBaseDir = "skills-with-missing")

    val result = brokenSource.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val message = result.exceptionOrNull()!!.message
    assertThat(message).contains("One of the skills is invalid")
    assertThat(message).contains("missing SKILL.md")
  }

  @Test
  fun listFrontmatters_mismatchedName_returnsFailure() = runTest {
    val brokenSource = AssetSkillSource(assets, skillsBaseDir = "skills-with-mismatch")

    val result = brokenSource.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val message = result.exceptionOrNull()!!.message
    assertThat(message).contains("One of the skills is invalid")
    assertThat(message).contains("does not match directory name")
  }

  // ---------------------------------------------------------------------------
  // loadResource: allow-list, traversal protection, byte payload, error mapping
  // ---------------------------------------------------------------------------

  @Test
  fun loadResource_referencesDir_returnsExactBytes() = runTest {
    val result = source.loadResource("valid-skill", "references/file1.txt")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().decodeToString()).isEqualTo("content1")
  }

  @Test
  fun loadResource_assetsDir_returnsExactBytes() = runTest {
    val result = source.loadResource("valid-skill", "assets/root_file.txt")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().decodeToString()).isEqualTo("hello world")
  }

  @Test
  fun loadResource_scriptsDir_returnsExactBytes() = runTest {
    val result = source.loadResource("valid-skill", "scripts/run.sh")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().decodeToString().trim()).isEqualTo("echo hello")
  }

  @Test
  fun loadResource_unauthorizedDirectory_isRejected() = runTest {
    val result = source.loadResource("skill-unauthorized", "unauthorized/file.txt")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message)
      .contains("must be within 'references/', 'assets/', or 'scripts/'")
  }

  @Test
  fun loadResource_parentTraversal_isRejected() = runTest {
    val result = source.loadResource("valid-skill", "references/../../escape.txt")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("Invalid resource path")
  }

  @Test
  fun loadResource_absolutePath_isRejected() = runTest {
    val result = source.loadResource("valid-skill", "/etc/passwd")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message).contains("Invalid resource path")
  }

  @Test
  fun loadResource_missingResource_returnsSkillTaggedFailure() = runTest {
    val result = source.loadResource("valid-skill", "references/ghost.txt")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message)
      .contains("Resource references/ghost.txt not found in skill valid-skill")
  }

  // ---------------------------------------------------------------------------
  // listResources: per-target recursion, root listing, allow-list
  // ---------------------------------------------------------------------------

  @Test
  fun listResources_singleAllowedDirectory_returnsPathsRelativeToSkillRoot() = runTest {
    val result = source.listResources("valid-skill", "references")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow())
      .containsExactly("references/file1.txt", "references/file2.txt", "references/sub/nested.txt")
  }

  @Test
  fun listResources_rootPath_walksAllAllowedTopLevelDirectories() = runTest {
    val result = source.listResources("valid-skill", ".")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow())
      .containsExactly(
        "references/file1.txt",
        "references/file2.txt",
        "references/sub/nested.txt",
        "assets/root_file.txt",
        "scripts/run.sh",
      )
  }

  @Test
  fun listResources_unauthorizedDirectory_isRejected() = runTest {
    val result = source.listResources("skill-unauthorized", "unauthorized")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message)
      .contains("must be empty, root (.), or within 'references/', 'assets/', or 'scripts/'")
  }

  @Test
  fun listResources_missingResourceDirectory_reportsResourceNotFound() = runTest {
    val result = source.listResources("valid-skill", "references/missing")

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()!!.message)
      .contains("Resource not found: references/missing")
  }
}
