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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.environment.EnvironmentException
import com.google.adk.kt.environment.ExecutionResult
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.skills.NewFileSystemSource
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import com.google.adk.kt.testing.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Tests for running skill scripts through a [LocalEnvironment]. Uses `runBlocking` because scripts
 * are executed as real subprocesses.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class SkillToolsetEnvironmentTest {

  private lateinit var skillsDir: Path
  private lateinit var source: NewFileSystemSource
  private val toolsets = mutableListOf<SkillToolset>()

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
    toolsets.forEach { it.close() }
    skillsDir.toFile().deleteRecursively()
  }

  /** Creates a toolset that [tearDown] closes, which removes any workspace it created. */
  private fun newToolset(
    environment: Environment = LocalEnvironment(),
    scriptTimeout: Duration = SkillToolset.DEFAULT_SCRIPT_TIMEOUT,
    skillSource: SkillSource = source,
  ): SkillToolset = SkillToolset(skillSource, environment, scriptTimeout).also { toolsets += it }

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

  /** Runs `run_skill_script`, adding the command a model would write when [args] has none. */
  private suspend fun runScriptTool(toolset: SkillToolset, args: Map<String, Any>): Map<*, *> {
    val script = (args["file_path"] as? String)?.substringAfterLast('/')
    val defaultCommand = "cd skills/${args["skill_name"]} && bash scripts/$script"
    return toolset
      .getTools(null)
      .first { it.name == SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT }
      .run(testToolContext(), mapOf("command" to defaultCommand) + args) as Map<*, *>
  }

  @Test
  fun getTools_withoutEnvironment_omitsRunSkillScript() = runBlocking {
    val names = SkillToolset(source).getTools(null).map { it.name }
    assertFalse(names.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun getTools_withEnvironment_includesRunSkillScript() = runBlocking {
    val names = newToolset().getTools(null).map { it.name }
    assertTrue(names.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun runSkillScript_executesScriptAndReturnsStdout() = runBlocking {
    val toolset = newToolset()

    val result =
      runScriptTool(
        toolset,
        mapOf(
          "skill_name" to SKILL_NAME,
          "file_path" to "scripts/hello.sh",
          "command" to "cd skills/$SKILL_NAME && bash scripts/hello.sh x",
        ),
      )

    assertEquals("ok", result["status"])
    assertEquals(SKILL_NAME, result["skill_name"])
    assertEquals("hello x\n", result["stdout"])
    assertEquals(0, result["exit_code"])
  }

  @Test
  fun runSkillScript_runsTheCommandUnchanged() = runBlocking {
    val toolset = newToolset()

    val result =
      runScriptTool(
        toolset,
        mapOf(
          "skill_name" to SKILL_NAME,
          "file_path" to "scripts/hello.sh",
          "command" to "cat skills/$SKILL_NAME/references/note.txt && echo && echo done",
        ),
      )

    // The command is the model's: it runs from the workspace root, with no `cd` or quoting added.
    assertEquals("ok", result["status"])
    assertEquals("reference contents\ndone\n", result["stdout"])
  }

  @Test
  fun runSkillScript_copiesAllResources_soScriptsCanReadSiblings() = runBlocking {
    val toolset = newToolset()

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
    val toolset = newToolset()

    val result =
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/fail.sh"))

    assertEquals("error", result["status"])
    assertEquals(3, result["exit_code"])
    assertEquals("boom\n", result["stderr"])
  }

  @Test
  fun runSkillScript_isRepeatable_whenRunTwice() = runBlocking {
    val toolset = newToolset()
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh")

    assertEquals("ok", runScriptTool(toolset, args)["status"])
    val second = runScriptTool(toolset, args)

    assertEquals("ok", second["status"])
    assertEquals("hello \n", second["stdout"])
  }

  @Test
  fun runSkillScript_picksUpAnEditedScript() = runBlocking {
    val toolset = newToolset()
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
    val toolset = newToolset()
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
    val toolset = newToolset()
    val args = mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh")
    assertEquals("ok", runScriptTool(toolset, args)["status"])

    Files.delete(skillsDir.resolve(SKILL_NAME).resolve("scripts").resolve("hello.sh"))

    // The script is looked up in the source first, so the stale copy is never run.
    val result = runScriptTool(toolset, args)
    assertTrue(result["error"] is String, "expected an error, got keys ${result.keys}")
    assertNull(result[SkillToolset.KEY_STATUS])
  }

  @Test
  fun runSkillScript_acceptsEquivalentSpellingsOfAScriptPath() = runBlocking {
    val toolset = newToolset()

    val paths =
      listOf(
        "hello.sh",
        "scripts/hello.sh",
        "./hello.sh",
        "scripts/./hello.sh",
        "scripts//hello.sh",
      )
    for (path in paths) {
      val result = runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to path))

      assertEquals("ok", result[SkillToolset.KEY_STATUS], "unexpected failure for $path")
      assertEquals("scripts/hello.sh", result["file_path"])
    }
  }

  @Test
  fun runSkillScript_rejectsPathsOutsideScriptsDirectory() = runBlocking {
    val toolset = newToolset()

    val paths =
      listOf(
        // Traversal to a non-script resource of the same skill, which must not become executable.
        "scripts/../references/note.txt",
        // Traversal out of the skill directory, in both spellings.
        "../../../bin/echo",
        "../scripts/hello.sh",
        // Absolute paths, including one that names a real script.
        "/bin/echo",
        "/scripts/hello.sh",
        // The scripts directory itself rather than a file in it.
        "scripts",
      )

    for (path in paths) {
      val result = runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to path))

      // Rejected by the path check itself, not by the later lookup in the source.
      assertTrue(
        (result["error"] as? String)?.startsWith("Invalid script path") == true,
        "expected $path to be rejected as an invalid script path",
      )
      assertNull(result[SkillToolset.KEY_STATUS])
    }
  }

  @Test
  fun runSkillScript_missingScript_returnsError() = runBlocking {
    val toolset = newToolset()

    val result =
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/nope.sh"))

    assertTrue((result["error"] as String).contains("nope.sh"))
  }

  @Test
  fun runSkillScript_missingSkill_returnsError() = runBlocking {
    val toolset = newToolset()

    val result =
      runScriptTool(toolset, mapOf("skill_name" to "ghost", "file_path" to "scripts/hello.sh"))

    assertTrue((result["error"] as String).contains("ghost"))
  }

  @Test
  fun runSkillScript_emptyArguments_returnError() = runBlocking {
    val toolset = newToolset()

    assertEquals(
      "Skill name is required.",
      runScriptTool(toolset, mapOf("skill_name" to "", "file_path" to "scripts/hello.sh"))["error"],
    )
    assertEquals(
      "Script path is required.",
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to ""))["error"],
    )
    assertEquals(
      "`command` is required.",
      runScriptTool(
        toolset,
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh", "command" to " "),
      )["error"],
    )
  }

  @Test
  fun runSkillScript_longOutput_isTruncated() = runBlocking {
    val lines = 5_000
    writeSkill(
      name = CHATTY_SKILL_NAME,
      scripts =
        mapOf(
          "chatty.sh" to
            "#!/bin/bash\nfor i in $(seq 1 $lines); do echo \"${'$'}{i}0123456789\"; echo \"${'$'}{i}0123456789\" >&2; done\n"
        ),
    )
    val toolset = newToolset()

    val result =
      runScriptTool(
        toolset,
        mapOf("skill_name" to CHATTY_SKILL_NAME, "file_path" to "scripts/chatty.sh"),
      )

    for (key in listOf("stdout", "stderr")) {
      val output = result[key] as String
      assertTrue(output.length < 31_000, "$key was not truncated: ${output.length} chars")
      assertTrue(output.contains("truncated"), "no truncation notice in $key")
    }
  }

  @Test
  fun runSkillScript_timeout_keepsPartialOutput() = runBlocking {
    writeSkill(
      name = SLOW_SKILL_NAME,
      scripts = mapOf("slow.sh" to "#!/bin/bash\necho \"partial\"\nsleep 30\n"),
    )
    val toolset = newToolset(scriptTimeout = 2.seconds)

    val result =
      runScriptTool(
        toolset,
        mapOf("skill_name" to SLOW_SKILL_NAME, "file_path" to "scripts/slow.sh"),
      )

    // The environment keeps what the script printed before it was killed.
    assertEquals("error", result["status"])
    assertEquals("Script timed out after 2s.", result["error"])
    assertEquals("partial\n", result["stdout"], "partial output was discarded")
  }

  @Test
  fun runSkillScript_skillNameWithPathSegments_returnsError() = runBlocking {
    val toolset = newToolset()

    // The name becomes the copy target skills/<skill_name>, so it must be one directory.
    for (name in
      listOf("../$SKILL_NAME", "..", ".", "a/b", "a\\b", "$SKILL_NAME/", "/$SKILL_NAME")) {
      val result = runScriptTool(toolset, mapOf("skill_name" to name, "file_path" to "hello.sh"))
      assertTrue(
        (result["error"] as? String)?.contains("skill_name") == true,
        "expected `$name` to be rejected as an invalid skill name",
      )
    }
  }

  @Test
  fun runSkillScript_skillNameEscapingSkillsDir_doesNotWriteOutsideIt() = runBlocking {
    // ../sibling-skill passes the source's check but would be copied outside skills/.
    val sibling = skillsDir.resolveSibling("sibling-skill")
    Files.createDirectories(sibling.resolve("scripts"))
    Files.writeString(
      sibling.resolve("SKILL.md"),
      "---\nname: sibling-skill\ndescription: A sibling skill.\n---\n\nInstructions.\n",
    )
    Files.writeString(sibling.resolve("scripts").resolve("hello.sh"), "#!/bin/bash\necho hi\n")

    val workspace = Files.createTempDirectory("adk-skill-escape-test")
    try {
      val toolset = newToolset(LocalEnvironment(workspace.toString()))

      val result =
        runScriptTool(toolset, mapOf("skill_name" to "../sibling-skill", "file_path" to "hello.sh"))

      assertTrue(result["error"] is String, "expected the skill name to be rejected")
      assertFalse(
        Files.exists(workspace.resolve("sibling-skill")),
        "the skill was copied outside skills/",
      )
    } finally {
      workspace.toFile().deleteRecursively()
      sibling.toFile().deleteRecursively()
    }
  }

  @Test
  fun catalogInstruction_mentionsRunSkillScript_onlyWithEnvironment() = runBlocking {
    val withoutEnv = SkillToolset(source).getSkillCatalogInstruction()
    val withEnv = newToolset().getSkillCatalogInstruction()

    assertFalse(withoutEnv!!.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
    assertTrue(withEnv!!.contains(SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT))
  }

  @Test
  fun catalogInstruction_withoutEnvironment_keepsScriptsBulletAndSpacing() = runBlocking {
    val withoutEnv = assertNotNull(SkillToolset(source).getSkillCatalogInstruction())

    assertTrue(
      withoutEnv.contains("Executable scripts that can be run via bash."),
      "the scripts bullet changed",
    )
    assertTrue(
      withoutEnv.contains("Do NOT use other tools to access these files.\n\n<available_skills>"),
      "the spacing before the skill catalog changed",
    )
  }

  @Test
  fun catalogInstruction_tellsModelWhereTheSkillIsCopied() = runBlocking {
    val withEnv = newToolset().getSkillCatalogInstruction()

    // The model writes the command, so the instruction is what tells it to run from the skill.
    assertTrue(
      withEnv!!.contains("cd skills/<skill_name> &&"),
      "no `cd` guidance in the instruction",
    )
  }

  @Test
  fun close_removesAutoCreatedWorkspace() = runBlocking {
    val environment = LocalEnvironment()
    val toolset = newToolset(environment)
    assertEquals(
      "ok",
      runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"))[
        "status"],
    )
    val workspace = environment.requireWorkingDir()
    assertTrue(Files.exists(workspace))

    toolset.close()

    assertFalse(Files.exists(workspace))
  }

  /**
   * Environment that records every operation and yields inside each one.
   *
   * The yield hands control to another waiting coroutine at every operation boundary, so on
   * `runBlocking`'s single thread, unserialized callers interleave deterministically rather than
   * occasionally.
   */
  private class RecordingEnvironment : Environment {
    val operations = mutableListOf<String>()

    override suspend fun execute(
      context: Context,
      command: String,
      timeout: Duration?,
    ): Result<ExecutionResult> {
      operations.add(command)
      yield()
      return Result.success(ExecutionResult())
    }

    override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
      Result.success(ByteArray(0))

    override suspend fun writeFile(
      context: Context,
      path: String,
      content: ByteArray,
    ): Result<Unit> {
      operations.add(path)
      yield()
      return Result.success(Unit)
    }
  }

  /**
   * Whether every operation mentioning [name] forms one unbroken run, which is what serializing on
   * that name produces.
   */
  private fun List<String>.areContiguousFor(name: String): Boolean {
    val positions = withIndex().filter { it.value.contains(name) }.map { it.index }
    return positions.isEmpty() || positions.last() - positions.first() == positions.size - 1
  }

  @Test
  fun catalogInstruction_tellsModelNotToRunOneSkillTwiceInAResponse() = runBlocking {
    val withEnv = newToolset().getSkillCatalogInstruction()

    // Nothing else stops two runs of one skill from overwriting each other's files.
    assertTrue(
      withEnv!!.contains("twice for the same skill in one response"),
      "the instruction must tell the model not to run one skill twice in a response",
    )
  }

  @Test
  fun runSkillScript_concurrentRunsOfDifferentSkills_overlap() = runBlocking {
    writeSkill(name = OTHER_SKILL_NAME, scripts = mapOf("hello.sh" to "#!/bin/bash\necho hi\n"))
    val environment = RecordingEnvironment()
    val toolset = newToolset(environment)

    val unused =
      listOf(SKILL_NAME, OTHER_SKILL_NAME)
        .map { skill ->
          async {
            runScriptTool(toolset, mapOf("skill_name" to skill, "file_path" to "scripts/hello.sh"))
          }
        }
        .awaitAll()

    // Guards against a global lock, which would let one slow skill block every other one.
    assertFalse(
      environment.operations.areContiguousFor(OTHER_SKILL_NAME),
      "runs of different skills were serialized",
    )
  }

  @Test
  fun runSkillScript_sourceListingOnlyResourceDirectories_copiesEveryResource() = runBlocking {
    // Like the in-memory sources in the examples, this one lists nothing for the skill root.
    val directoriesOnly =
      object : SkillSource by source {
        override suspend fun listResources(
          skillName: String,
          resourceDirectoryPath: String,
        ): Result<List<String>> =
          if (resourceDirectoryPath.isEmpty()) {
            Result.success(emptyList())
          } else {
            source.listResources(skillName, resourceDirectoryPath)
          }
      }

    val result =
      runScriptTool(
        newToolset(skillSource = directoriesOnly),
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/read_reference.sh"),
      )

    assertEquals("reference contents", result["stdout"])
  }

  @Test
  fun runSkillScript_resourceReadFailureDuringCopy_returnsSourceError() = runBlocking {
    val unreadableReferences =
      object : SkillSource by source {
        override suspend fun loadResource(
          skillName: String,
          resourcePath: String,
        ): Result<ByteArray> =
          if (resourcePath.startsWith("references/")) {
            Result.failure(SkillSourceException("reference unreadable"))
          } else {
            source.loadResource(skillName, resourcePath)
          }
      }

    val result =
      runScriptTool(
        newToolset(skillSource = unreadableReferences),
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
      )

    assertEquals("reference unreadable", result["error"])
  }

  @Test
  fun runSkillScript_sourceListingFailureOfAnotherType_isRethrown() = runBlocking {
    val brokenListing =
      object : SkillSource by source {
        override suspend fun listResources(
          skillName: String,
          resourceDirectoryPath: String,
        ): Result<List<String>> = Result.failure(IllegalStateException("boom"))
      }
    val toolset = newToolset(skillSource = brokenListing)

    val thrown =
      assertFailsWith<IllegalStateException> {
        runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"))
      }
    assertEquals("boom", thrown.message)
  }

  @Test
  fun runSkillScript_writeFailure_returnsEnvironmentErrorWithoutRunning() = runBlocking {
    val environment = FailingEnvironment(writeFailure = EnvironmentException("write failed"))

    val result =
      runScriptTool(
        newToolset(environment),
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
      )

    assertEquals("write failed", result["error"])
    assertFalse(environment.executed)
  }

  @Test
  fun runSkillScript_executeFailure_returnsEnvironmentError() = runBlocking {
    val environment = FailingEnvironment(executeFailure = EnvironmentException("execute failed"))

    val result =
      runScriptTool(
        newToolset(environment),
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
      )

    assertEquals("execute failed", result["error"])
  }

  @Test
  fun runSkillScript_timeoutWithZeroExitCode_reportsError() = runBlocking {
    val environment = FailingEnvironment(executeResult = ExecutionResult(timedOut = true))

    val result =
      runScriptTool(
        newToolset(environment),
        mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"),
      )

    assertEquals("error", result["status"])
  }

  @Test
  fun runSkillScript_environmentFailureOfAnotherType_isRethrown() = runBlocking {
    val toolset = newToolset(FailingEnvironment(writeFailure = IllegalStateException("boom")))

    val thrown =
      assertFailsWith<IllegalStateException> {
        runScriptTool(toolset, mapOf("skill_name" to SKILL_NAME, "file_path" to "scripts/hello.sh"))
      }
    assertEquals("boom", thrown.message)
  }

  @Test
  @OptIn(AdkJavaInteropApi::class)
  fun builder_setsEnvironmentAndScriptTimeout() = runBlocking {
    writeSkill(name = SLOW_SKILL_NAME, scripts = mapOf("slow.sh" to "#!/bin/bash\nsleep 30\n"))
    val toolset =
      SkillToolset.builder()
        .source(source)
        .environment(LocalEnvironment())
        .scriptTimeoutMillis(500)
        .build()
        .also { toolsets += it }

    val result =
      runScriptTool(
        toolset,
        mapOf("skill_name" to SLOW_SKILL_NAME, "file_path" to "scripts/slow.sh"),
      )

    assertEquals("Script timed out after 500ms.", result["error"])
  }

  /** Environment whose writes or commands fail with the given throwables, else return defaults. */
  private class FailingEnvironment(
    private val writeFailure: Throwable? = null,
    private val executeFailure: Throwable? = null,
    private val executeResult: ExecutionResult = ExecutionResult(),
  ) : Environment {
    var executed = false

    override suspend fun execute(
      context: Context,
      command: String,
      timeout: Duration?,
    ): Result<ExecutionResult> {
      executed = true
      return executeFailure?.let { Result.failure(it) } ?: Result.success(executeResult)
    }

    override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
      Result.success(ByteArray(0))

    override suspend fun writeFile(
      context: Context,
      path: String,
      content: ByteArray,
    ): Result<Unit> = writeFailure?.let { Result.failure(it) } ?: Result.success(Unit)
  }

  private companion object {
    const val SKILL_NAME = "test-skill"
    const val SLOW_SKILL_NAME = "slow-skill"
    const val CHATTY_SKILL_NAME = "chatty-skill"
    const val OTHER_SKILL_NAME = "other-skill"
  }
}
