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

import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The five file tools the agent builder assistant carries, run against a real directory. */
@RunWith(JUnit4::class)
class ProjectToolsTest {

  private lateinit var root: File
  private lateinit var context: ToolContext

  @Before
  fun setUp() {
    root = Files.createTempDirectory("project_tools_test").toFile().canonicalFile
    context = toolContextRootedAt(root.path)
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun writeFiles_thenReadFiles_roundTripsTheContent(): Unit = runBlocking {
    val files = mapOf("tools/my_tool.kt" to "fun main() {}")
    val written = WriteFilesTool().resultFor(context, mapOf("files" to files))
    assertThat(written["success"]).isEqualTo(true)
    assertThat(written["successful_writes"]).isEqualTo(1)

    val paths = listOf("tools/my_tool.kt")
    val read = ReadFilesTool().resultFor(context, mapOf("file_paths" to paths))

    assertThat(read["successful_reads"]).isEqualTo(1)
    val entry = (read["files"] as Map<*, *>).values.single() as Map<*, *>
    assertThat(entry["content"]).isEqualTo("fun main() {}")
    assertThat(entry["exists"]).isEqualTo(true)
  }

  @Test
  fun writeFiles_missingParentDirectory_isCreated(): Unit = runBlocking {
    val unusedWrite =
      WriteFilesTool().resultFor(context, mapOf("files" to mapOf("a/b/c.kt" to "x")))

    assertThat(File(root, "a/b/c.kt").isFile).isTrue()
  }

  @Test
  fun writeFiles_batchNamingOnePathOutsideTheProject_writesNoneOfThem(): Unit = runBlocking {
    val files = mapOf("good.kt" to "kept", "../escaped.kt" to "leaked")

    val result = WriteFilesTool().resultFor(context, mapOf("files" to files))

    assertThat(result["success"]).isEqualTo(false)
    assertThat(result["successful_writes"]).isEqualTo(0)
    assertThat(result["errors"] as List<*>).isNotEmpty()
    assertThat(File(root, "good.kt").exists()).isFalse()
    assertThat(File(root.parentFile, "escaped.kt").exists()).isFalse()
  }

  @Test
  fun readFiles_missingFile_isReportedWithoutFailingTheBatch(): Unit = runBlocking {
    val result = ReadFilesTool().resultFor(context, mapOf("file_paths" to listOf("absent.kt")))

    assertThat(result["success"]).isEqualTo(true)
    assertThat(result["successful_reads"]).isEqualTo(0)
    val entry = (result["files"] as Map<*, *>).values.single() as Map<*, *>
    assertThat(entry["exists"]).isEqualTo(false)
    assertThat(entry["error"] as String).contains("does not exist")
  }

  @Test
  fun readFiles_batchNamingOnePathOutsideTheProject_readsNoneOfThem(): Unit = runBlocking {
    File(root, "good.kt").writeText("kept")
    val paths = listOf("good.kt", "/etc/passwd")

    val result = ReadFilesTool().resultFor(context, mapOf("file_paths" to paths))

    assertThat(result["success"]).isEqualTo(false)
    assertThat(result["files"] as Map<*, *>).isEmpty()
  }

  @Test
  fun deleteFiles_withoutConfirmation_deletesNothing(): Unit = runBlocking {
    val doomed = File(root, "doomed.kt").apply { writeText("x") }
    val args = mapOf("file_paths" to listOf("doomed.kt"), "confirm_deletion" to false)

    val result = DeleteFilesTool().resultFor(context, args)

    assertThat(result["success"]).isEqualTo(false)
    assertThat(result["errors"] as List<*>).containsExactly("Deletion not confirmed by user")
    assertThat(doomed.exists()).isTrue()
  }

  @Test
  fun deleteFiles_confirmed_removesTheFile(): Unit = runBlocking {
    val doomed = File(root, "doomed.kt").apply { writeText("x") }

    val result = DeleteFilesTool().resultFor(context, mapOf("file_paths" to listOf("doomed.kt")))

    assertThat(result["success"]).isEqualTo(true)
    assertThat(result["successful_deletions"]).isEqualTo(1)
    assertThat(doomed.exists()).isFalse()
  }

  @Test
  fun deleteFiles_directory_isRefusedAndTheDirectorySurvives(): Unit = runBlocking {
    val directory = File(root, "tools").apply { mkdirs() }

    val result = DeleteFilesTool().resultFor(context, mapOf("file_paths" to listOf("tools")))

    assertThat(result["success"]).isEqualTo(false)
    val entry = (result["files"] as Map<*, *>).values.single() as Map<*, *>
    assertThat(entry["error"] as String).contains("Not a file")
    assertThat(directory.isDirectory).isTrue()
  }

  @Test
  fun cleanupUnusedFiles_namesOnlyTheSourcesNothingAccountsFor(): Unit = runBlocking {
    File(root, "used.kt").writeText("x")
    File(root, "unused.kt").writeText("x")
    File(root, "HelperTest.kt").writeText("x")
    File(root, "notes.md").writeText("x")

    val args = mapOf("used_files" to listOf("used.kt"))
    val result = CleanupUnusedFilesTool().resultFor(context, args)

    assertThat(result["success"]).isEqualTo(true)
    // `notes.md` is not a source file and `HelperTest.kt` is a test, so neither is the user's to
    // lose, and `used.kt` was accounted for.
    val named = (result["unused_files"] as List<*>).map { File(it as String).name }
    assertThat(named).containsExactly("unused.kt")
  }

  @Test
  fun cleanupUnusedFiles_deletesNothing(): Unit = runBlocking {
    val unused = File(root, "unused.kt").apply { writeText("x") }

    val unusedCleanup =
      CleanupUnusedFilesTool().resultFor(context, mapOf("used_files" to emptyList<String>()))

    assertThat(unused.exists()).isTrue()
  }

  @Test
  fun exploreProject_reportsTheAgentDocumentsAtTheRootAndNotBelowIt(): Unit = runBlocking {
    File(root, "root_agent.yaml").writeText("name: helper\ndescription: a helper\n")
    File(root, "fixtures").mkdirs()
    File(root, "fixtures/not_an_agent.yaml").writeText("name: fixture\n")

    val result = ExploreProjectTool().resultFor(context, emptyMap())

    assertThat(result["success"]).isEqualTo(true)
    val configs = result["existing_configs"] as List<*>
    assertThat(configs).hasSize(1)
    val config = configs.single() as Map<*, *>
    assertThat(config["filename"]).isEqualTo("root_agent.yaml")
    assertThat(config["is_valid_yaml"]).isEqualTo(true)
    assertThat(config["agent_name"]).isEqualTo("helper")
    // A document that does not say what builds it describes an LlmAgent.
    assertThat(config["agent_class"]).isEqualTo("LlmAgent")
    assertThat(config["has_tools"]).isEqualTo(false)
  }

  @Test
  fun exploreProject_countsWhatTheProjectHolds(): Unit = runBlocking {
    File(root, "tools").mkdirs()
    File(root, "tools/my_tool.kt").writeText("x")

    val info = ExploreProjectTool().resultFor(context, emptyMap())["project_info"] as Map<*, *>

    assertThat(info["has_kotlin_files"]).isEqualTo(true)
    assertThat(info["has_yaml_files"]).isEqualTo(false)
    assertThat(info["has_tools_directory"]).isEqualTo(true)
    assertThat(info["is_empty"]).isEqualTo(false)
    assertThat(info["total_files"]).isEqualTo(1)
  }

  @Test
  fun exploreProject_directoryThatIsNotThere_reportsTheFailure(): Unit = runBlocking {
    val absent = toolContextRootedAt(File(root, "gone").path)

    val result = ExploreProjectTool().resultFor(absent, emptyMap())

    assertThat(result["success"]).isEqualTo(false)
    assertThat(result["error"] as String).contains("does not exist")
  }

  private suspend fun BaseTool.resultFor(
    toolContext: ToolContext,
    args: Map<String, Any?>,
  ): Map<*, *> = run(toolContext, args) as Map<*, *>

  private fun toolContextRootedAt(path: String): ToolContext =
    testToolContext(
      invocationContext =
        testInvocationContext(
          session =
            Session(
              key = SessionKey("test_app_name", "test_user_id", "test_session_id"),
              state = State(mapOf(ROOT_DIRECTORY_KEY to path)),
            )
        )
    )
}
