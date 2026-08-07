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

import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NewFileSystemSourceTest {

  private lateinit var tempDir: Path
  private lateinit var source: NewFileSystemSource

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("new_file_system_source_test")
    source = NewFileSystemSource(tempDir.toString())
  }

  @After
  fun tearDown() {
    tempDir.toFile().deleteRecursively()
  }

  @CanIgnoreReturnValue
  private fun createSkill(
    parent: Path = tempDir,
    dirName: String,
    name: String = dirName,
    description: String = "d",
    instructions: String = "",
  ): Path {
    val skillDir = parent.resolve(dirName)
    Files.createDirectory(skillDir)
    val body = buildString {
      append("---\n")
      append("name: ").append(name).append('\n')
      append("description: ").append(description).append('\n')
      append("---\n")
      if (instructions.isNotEmpty()) append(instructions).append('\n')
    }
    skillDir.resolve(SKILL_MD).writeText(body)
    return skillDir
  }

  @CanIgnoreReturnValue
  private fun writeRawSkillMd(parent: Path = tempDir, dirName: String, content: String): Path {
    val skillDir = parent.resolve(dirName)
    Files.createDirectory(skillDir)
    skillDir.resolve(SKILL_MD).writeText(content)
    return skillDir
  }

  @CanIgnoreReturnValue
  private fun Path.mkSubdir(name: String): Path = resolve(name).also { Files.createDirectory(it) }

  @CanIgnoreReturnValue
  private fun Path.writeFile(name: String, content: String): Path =
    resolve(name).also { it.writeText(content) }

  @Test
  fun listFrontmatters_emptyDirectory_returnsEmptyList() = runTest {
    val result = source.listFrontmatters()

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow()).isEmpty()
  }

  @Test
  fun listFrontmatters_nonexistentBaseDir_returnsFailure() = runTest {
    val missing = tempDir.resolve("does-not-exist")
    val brokenSource = NewFileSystemSource(missing.toString())

    val result = brokenSource.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Configured skills base directory does not exist")
    // The absolute filesystem path must not leak into the user-facing message.
    assertThat(exception.message).doesNotContain(missing.toString())
  }

  @Test
  fun listFrontmatters_baseDirIsFile_returnsFailure() = runTest {
    val filePath = tempDir.resolve("not-a-directory")
    filePath.writeText("some content")
    val brokenSource = NewFileSystemSource(filePath.toString())

    val result = brokenSource.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Configured skills base path is not a directory")
    // The absolute filesystem path must not leak into the user-facing message.
    assertThat(exception.message).doesNotContain(filePath.toString())
  }

  @Test
  fun listFrontmatters_validSkills_returnsFrontmatters() = runTest {
    createSkill(dirName = "skill1", description = "Description 1", instructions = "Instructions 1")
    createSkill(dirName = "skill2", description = "Description 2", instructions = "Instructions 2")

    val result = source.listFrontmatters()

    assertThat(result.isSuccess).isTrue()
    val frontmatters = result.getOrThrow()
    assertThat(frontmatters).hasSize(2)
    assertThat(frontmatters.map { it.name }).containsExactly("skill1", "skill2")
  }

  @Test
  fun listFrontmatters_missingSkillMd_returnsFailure() = runTest {
    createSkill(dirName = "skill1", description = "Description 1", instructions = "Instructions 1")
    // Invalid: missing SKILL.md
    Files.createDirectory(tempDir.resolve("invalid1"))

    val result = source.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("One of the skills is invalid")
    assertThat(exception.message).contains("missing SKILL.md")
  }

  @Test
  fun listFrontmatters_mismatchedName_returnsFailure() = runTest {
    createSkill(dirName = "invalid2", name = "mismatched", description = "Description")

    val result = source.listFrontmatters()

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("One of the skills is invalid")
    assertThat(exception.message).contains("does not match directory name")
  }

  @Test
  fun loadFrontmatter_invalidFrontmatterName_returnsFailure() = runTest {
    createSkill(dirName = "invalid_name", description = "Description")

    val result = source.loadFrontmatter("invalid_name")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message)
      .contains("name may only contain lowercase alphanumeric characters")
  }

  @Test
  fun listResources_returnsPathsRelativeToSkillRoot() = runTest {
    val skillDir = createSkill(dirName = "skill1")
    val referencesDir = skillDir.mkSubdir("references")
    referencesDir.writeFile("file1.txt", "content1")
    referencesDir.writeFile("file2.txt", "content2")
    val assetsDir = skillDir.mkSubdir("assets")
    assetsDir.writeFile("root_file.txt", "content_root")
    val scriptsDir = skillDir.mkSubdir("scripts")
    scriptsDir.writeFile("run.sh", "echo hello")

    assertThat(source.listResources("skill1", "references").getOrThrow())
      .containsExactly("references/file1.txt", "references/file2.txt")
    assertThat(source.listResources("skill1", "assets").getOrThrow())
      .containsExactly("assets/root_file.txt")
    assertThat(source.listResources("skill1", "scripts").getOrThrow())
      .containsExactly("scripts/run.sh")
  }

  @Test
  fun loadFrontmatter_validSkill_returnsFrontmatter() = runTest {
    createSkill(dirName = "skill1", description = "Description 1")

    val result = source.loadFrontmatter("skill1")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().name).isEqualTo("skill1")
  }

  @Test
  fun loadFrontmatter_adkAdditionalToolsMetadata_returnsFrontmatterWithMetadata() = runTest {
    writeRawSkillMd(
      dirName = "test-skill",
      content =
        """
        ---
        name: test-skill
        description: Description
        metadata:
          adk_additional_tools:
            - extra_tool_a
            - extra_tool_b
          owner: adk
        ---
        Instructions.
        """
          .trimIndent(),
    )

    val result = source.loadFrontmatter("test-skill")

    assertThat(result.isSuccess).isTrue()
    val frontmatter = result.getOrThrow()
    assertThat(frontmatter.metadata["adk_additional_tools"])
      .isEqualTo(listOf("extra_tool_a", "extra_tool_b"))
    assertThat(frontmatter.metadata["owner"]).isEqualTo("adk")
  }

  @Test
  fun loadFrontmatter_nonexistentSkill_returnsFailure() = runTest {
    val result = source.loadFrontmatter("nonexistent")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Skill nonexistent not found")
  }

  @Test
  fun loadFrontmatter_misconfiguredBaseDir_attributesErrorToBaseDirNotSkill() = runTest {
    val missing = tempDir.resolve("does-not-exist")
    val brokenSource = NewFileSystemSource(missing.toString())

    val result = brokenSource.loadFrontmatter("any-skill")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    val message = exception!!.message
    assertThat(message).contains("Configured skills base directory does not exist")
    // Must not blame the skill: the source itself is misconfigured.
    assertThat(message).doesNotContain("any-skill")
    // Must not leak the absolute path.
    assertThat(message).doesNotContain(missing.toString())
  }

  @Test
  fun loadInstructions_validSkill_returnsInstructions() = runTest {
    createSkill(
      dirName = "skill1",
      description = "Description 1",
      instructions = "Instructions for skill1",
    )

    val result = source.loadInstructions("skill1")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow()).isEqualTo("Instructions for skill1")
  }

  @Test
  fun loadInstructions_nonexistentSkill_returnsFailure() = runTest {
    val result = source.loadInstructions("nonexistent")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Skill nonexistent not found")
  }

  @Test
  fun loadResource_validResource_returnsBytes() = runTest {
    val skillDir = createSkill(dirName = "skill1")
    skillDir.mkSubdir("assets").writeFile("resource.txt", "hello world")

    val result = source.loadResource("skill1", "assets/resource.txt")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow().decodeToString()).isEqualTo("hello world")
  }

  @Test
  fun loadResource_nonexistentResource_returnsFailure() = runTest {
    createSkill(dirName = "skill1")

    val result = source.loadResource("skill1", "assets/nonexistent.txt")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message)
      .contains("Resource assets/nonexistent.txt not found in skill skill1")
  }

  @Test
  fun loadResource_unauthorizedDirectory_returnsFailure() = runTest {
    createSkill(dirName = "skill1")

    val result = source.loadResource("skill1", "unauthorized/file.txt")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message)
      .contains("must be within 'references/', 'assets/', or 'scripts/'")
  }

  @Test
  fun loadResource_nonexistentSkill_returnsFailure() = runTest {
    val result = source.loadResource("nonexistent", "assets/resource.txt")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Skill nonexistent not found")
  }

  @Test
  fun listResources_emptySearchPath_treatsAsRoot() = runTest {
    val skillDir = createSkill(dirName = "skill1")
    skillDir.mkSubdir("references").writeFile("file1.txt", "content1")

    val result = source.listResources("skill1", "")

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow()).containsExactly("references/file1.txt")
  }

  @Test
  fun listResources_skillNotFound_returnsFailure() = runTest {
    val result = source.listResources("nonexistent", ".")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Skill nonexistent not found")
  }

  @Test
  fun listResources_skillNameMismatch_returnsFailure() = runTest {
    createSkill(dirName = "test-skill", name = "wrong-skill")

    val result = source.listResources("test-skill", ".")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("does not match directory name")
  }

  @Test
  fun listResources_invalidFrontmatter_returnsFailure() = runTest {
    writeRawSkillMd(dirName = "test-skill", content = "---\ninvalid: [yaml\n---\n")

    val result = source.listResources("test-skill", ".")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("invalid frontmatter")
  }

  @Test
  fun loadFrontmatter_emptyFrontmatter_returnsFailure() = runTest {
    writeRawSkillMd(dirName = "test-skill", content = "---\n---\n")

    val result = source.loadFrontmatter("test-skill")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Frontmatter must not be empty")
  }

  @Test
  fun loadFrontmatter_nonMapFrontmatterRoot_returnsFailure() = runTest {
    writeRawSkillMd(dirName = "test-skill", content = "---\n- a\n- b\n---\n")

    val result = source.loadFrontmatter("test-skill")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Frontmatter must be a YAML mapping")
  }

  @Test
  fun listResources_unauthorizedDirectory_returnsFailure() = runTest {
    val skillDir = createSkill(dirName = "skill1")
    skillDir.mkSubdir("unauthorized")

    val result = source.listResources("skill1", "unauthorized")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message)
      .contains("must be empty, root (.), or within 'references/', 'assets/', or 'scripts/'")
  }

  @Test
  fun listResources_directoryNotFound_returnsFailure() = runTest {
    createSkill(dirName = "skill1")

    val result = source.listResources("skill1", "references/missing")

    assertThat(result.isFailure).isTrue()
    val exception = result.exceptionOrNull()
    assertThat(exception).isInstanceOf(SkillSourceException::class.java)
    assertThat(exception!!.message).contains("Resource not found")
  }

  private companion object {
    const val SKILL_MD = "SKILL.md"
  }
}
