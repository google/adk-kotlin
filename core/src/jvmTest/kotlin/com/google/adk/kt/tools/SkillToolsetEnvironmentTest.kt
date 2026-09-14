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

package com.google.adk.kt.tools

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.skills.NewFileSystemSource
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.environment.EnvironmentToolset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for running skill scripts through a [LocalEnvironment]. Uses `runBlocking` because scripts
 * are executed as real subprocesses.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class SkillToolsetEnvironmentTest {

  private lateinit var skillsDir: Path
  private lateinit var source: NewFileSystemSource

  @BeforeTest
  fun setUp() {
    skillsDir = Files.createTempDirectory("adk-skill-scripts-test")
    writeSkill(
      name = SKILL_NAME,
      scripts =
        mapOf(
          "hello.sh" to "#!/bin/bash\necho \"hello ${'$'}1\"\n",
          "read_reference.sh" to "#!/bin/bash\ncat references/note.txt\n",
          "fail.sh" to "#!/bin/bash\necho \"boom\" >&2\nexit 3\n",
        ),
      references = mapOf("note.txt" to "reference contents"),
    )
    source = NewFileSystemSource(skillsDir.toString())
  }

  @AfterTest
  fun tearDown() {
    skillsDir.toFile().deleteRecursively()
  }

  /** Writes a minimal on-disk skill: a SKILL.md plus the given scripts and references. */
  private fun writeSkill(
    name: String,
    scripts: Map<String, String> = emptyMap(),
    references: Map<String, String> = emptyMap(),
  ) {
    val skillDir = skillsDir.resolve(name)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("SKILL.md"),
      "---\nname: $name\ndescription: A test skill.\n---\n\nTest instructions.\n",
    )
    writeResources(skillDir.resolve("scripts"), scripts)
    writeResources(skillDir.resolve("references"), references)
  }

  /** Writes [files] into [directory], creating it when there is anything to write. */
  private fun writeResources(directory: Path, files: Map<String, String>) {
    if (files.isEmpty()) return
    Files.createDirectories(directory)
    for ((fileName, content) in files) {
      Files.writeString(directory.resolve(fileName), content)
    }
  }

  private suspend fun runScriptTool(toolset: SkillToolset, args: Map<String, Any>): Map<*, *> =
    toolset
      .getTools(null)
      .first { it.name == SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT }
      .run(testToolContext(), args) as Map<*, *>

  @Test
  fun getTools_withoutEnvironment_omitsRunSkillScript() = runBlocking {
    val names = SkillToolset(source).getTools(null).map { it.name }
    assertFalse(names.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun getTools_withEnvironment_includesRunSkillScript() = runBlocking {
    val names = SkillToolset(source, LocalEnvironment()).getTools(null).map { it.name }
    assertTrue(names.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun runSkillScript_executesScriptAndReturnsStdout() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val result =
      runScriptTool(
        toolset,
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh", "args" to listOf("x")),
      )

    assertEquals("ok", result["status"])
    assertEquals("hello x\n", result["stdout"])
    assertEquals(0, result["exit_code"])
  }

  @Test
  fun runSkillScript_acceptsBareScriptName() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val result =
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "hello.sh"))

    assertEquals("ok", result["status"])
    assertEquals("scripts/hello.sh", result["file_path"])
  }

  @Test
  fun runSkillScript_copiesAllResources_soScriptsCanReadSiblings() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    // The script reads references/note.txt, which is only present if the whole skill was copied.
    val result =
      runScriptTool(
        toolset,
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/read_reference.sh"),
      )

    assertEquals("ok", result["status"])
    assertEquals("reference contents", result["stdout"])
  }

  @Test
  fun runSkillScript_nonZeroExit_reportsError() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val result =
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/fail.sh"))

    assertEquals("error", result["status"])
    assertEquals(3, result["exit_code"])
    assertEquals("boom\n", result["stderr"])
  }

  @Test
  fun runSkillScript_isRepeatable_whenRunTwice() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh")

    assertEquals("ok", runScriptTool(toolset, args)["status"])
    val second = runScriptTool(toolset, args)

    assertEquals("ok", second["status"])
    assertEquals("hello \n", second["stdout"])
  }

  @Test
  fun runSkillScript_picksUpAnEditedScript() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh")
    assertEquals("hello \n", runScriptTool(toolset, args)["stdout"])

    Files.writeString(
      skillsDir.resolve(SKILL_NAME).resolve("scripts").resolve("hello.sh"),
      "#!/bin/bash\necho \"goodbye\"\n",
    )

    assertEquals("goodbye\n", runScriptTool(toolset, args)["stdout"])
  }

  @Test
  fun runSkillScript_picksUpAnEditedReference() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/read_reference.sh")
    assertEquals("reference contents", runScriptTool(toolset, args)["stdout"])

    Files.writeString(
      skillsDir.resolve(SKILL_NAME).resolve("references").resolve("note.txt"),
      "updated contents",
    )

    assertEquals("updated contents", runScriptTool(toolset, args)["stdout"])
  }

  @Test
  fun runSkillScript_deletedScript_returnsErrorEvenAfterAnEarlierRun() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh")
    assertEquals("ok", runScriptTool(toolset, args)["status"])

    Files.delete(skillsDir.resolve(SKILL_NAME).resolve("scripts").resolve("hello.sh"))

    // The script is resolved through the source, so a copy left in the environment is not run.
    val result = runScriptTool(toolset, args)
    assertTrue(result["error"] is String, "expected an error but got $result")
    assertEquals(null, result[SkillToolset.KEY_STATUS])
  }

  @Test
  fun runSkillScript_acceptsEquivalentSpellingsOfAScriptPath() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    for (path in listOf("hello.sh", "scripts/hello.sh", "./hello.sh", "scripts/./hello.sh")) {
      val result = runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to path))

      assertEquals("ok", result[SkillToolset.KEY_STATUS], "unexpected failure for $path: $result")
      assertEquals("scripts/hello.sh", result["file_path"])
    }
  }

  @Test
  fun runSkillScript_rejectsPathsOutsideScriptsDirectory() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val paths =
      listOf(
        // Traversal to a non-script resource of the same skill, which must not become executable.
        "scripts/../references/note.txt",
        // Traversal out of the skill directory, in both spellings.
        "../../../bin/echo",
        "../scripts/hello.sh",
        // Absolute path.
        "/bin/echo",
        // The scripts directory itself rather than a file in it.
        "scripts",
      )

    for (path in paths) {
      val result = runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to path))

      assertTrue(result["error"] is String, "expected an error for $path but got $result")
      assertEquals(null, result[SkillToolset.KEY_STATUS])
    }
  }

  @Test
  fun runSkillScript_missingScript_returnsError() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val result =
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/nope.sh"))

    assertTrue((result["error"] as String).contains("nope.sh"))
  }

  @Test
  fun runSkillScript_missingSkill_returnsError() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    val result =
      runScriptTool(toolset, mapOf("skill_name" to "ghost", "file_path" to "scripts/hello.sh"))

    assertTrue((result["error"] as String).contains("ghost"))
  }

  @Test
  fun runSkillScript_emptyArguments_returnError() = runBlocking {
    val toolset = SkillToolset(source, LocalEnvironment())

    assertEquals(
      "Skill name is required.",
      runScriptTool(toolset, mapOf("skill_name" to "", "file_path" to "scripts/hello.sh"))["error"],
    )
    assertEquals(
      "Script path is required.",
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to ""))["error"],
    )
  }

  @Test
  fun catalogInstruction_mentionsRunSkillScript_onlyWithEnvironment() = runBlocking {
    val withoutEnv = SkillToolset(source).getSkillCatalogInstruction()
    val withEnv = SkillToolset(source, LocalEnvironment()).getSkillCatalogInstruction()

    assertFalse(withoutEnv!!.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
    assertTrue(withEnv!!.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun sharedEnvironment_copiedSkillIsVisibleToEnvironmentToolset() = runBlocking {
    val environment = LocalEnvironment()
    val skillToolset = SkillToolset(source, environment)
    val environmentToolset = EnvironmentToolset(environment)

    assertEquals(
      "ok",
      runScriptTool(
        skillToolset,
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
      )["status"],
    )

    // The skill copied by the SkillToolset is readable through the shared environment.
    val readResult =
      environmentToolset
        .getTools(null)
        .first { it.name == "ReadFile" }
        .run(testToolContext(), mapOf("path" to "skills/$SKILL_NAME/references/note.txt"))
        as Map<*, *>

    assertEquals("ok", readResult["status"])
    assertTrue((readResult["content"] as String).contains("reference contents"))
  }

  @Test
  fun sharedEnvironment_withProvidedWorkingDir_isNotDeletedByClose() = runBlocking {
    val workingDir = Files.createTempDirectory("adk-shared-env-test")
    try {
      val environment = LocalEnvironment(workingDir.toString())
      val skillToolset = SkillToolset(source, environment)
      val environmentToolset = EnvironmentToolset(environment)
      assertEquals(
        "ok",
        runScriptTool(
          skillToolset,
          mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
        )["status"],
      )

      // Both toolsets close the shared environment; a caller-provided directory survives.
      skillToolset.close()
      environmentToolset.close()

      assertTrue(Files.exists(workingDir))
      assertTrue(Files.exists(workingDir.resolve("skills").resolve(SKILL_NAME)))
    } finally {
      workingDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun close_removesAutoCreatedWorkspace() = runBlocking {
    val environment = LocalEnvironment()
    val toolset = SkillToolset(source, environment)
    assertEquals(
      "ok",
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"))[
        "status"],
    )
    val workspace = Path.of(environment.workingDir)
    assertTrue(Files.exists(workspace))

    toolset.close()

    assertFalse(Files.exists(workspace))
  }

  private companion object {
    const val SKILL_NAME = "test-skill"
  }
}
