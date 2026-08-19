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

package com.google.adk.kt.tools.environment

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.environment.ExecutionResult
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/**
 * Tests for [EnvironmentToolset], including configurable output truncation. Uses `runBlocking`
 * because the [LocalEnvironment]-backed cases spawn real subprocesses.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class EnvironmentToolsetTest {

  /** Fake environment returning canned execution/file output, mirroring the Python test double. */
  private class FakeEnvironment(private val stdout: String, private val fileContent: ByteArray) :
    Environment {
    override val workingDir: String = "/workspace"

    override suspend fun execute(command: String, timeout: Duration?): Result<ExecutionResult> =
      Result.success(ExecutionResult(exitCode = 0, stdout = stdout, stderr = "", timedOut = false))

    override suspend fun readFile(path: String): Result<ByteArray> = Result.success(fileContent)

    override suspend fun writeFile(path: String, content: ByteArray): Result<Unit> =
      Result.success(Unit)
  }

  /** Environment double that records lifecycle calls, to verify [EnvironmentToolset.close]. */
  private class RecordingEnvironment : Environment {
    var initializeCount = 0
    var closeCount = 0
    override val workingDir: String = "/workspace"

    override suspend fun initialize() {
      initializeCount++
    }

    override fun close() {
      closeCount++
    }

    override suspend fun execute(command: String, timeout: Duration?): Result<ExecutionResult> =
      Result.success(ExecutionResult())

    override suspend fun readFile(path: String): Result<ByteArray> = Result.success(ByteArray(0))

    override suspend fun writeFile(path: String, content: ByteArray): Result<Unit> =
      Result.success(Unit)
  }

  private lateinit var root: Path
  private lateinit var toolset: EnvironmentToolset

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-env-toolset-test")
    toolset = EnvironmentToolset(LocalEnvironment(root.toString()))
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  private suspend fun tool(name: String): BaseTool =
    toolset.getTools(null).first { it.name == name }

  @Test
  fun getTools_returnsFourTools() = runBlocking {
    val names = toolset.getTools(null).map { it.name }.toSet()
    assertEquals(setOf("Execute", "ReadFile", "EditFile", "WriteFile"), names)
  }

  @Test
  fun processLlmRequest_injectsWorkspaceInstruction() = runBlocking {
    val request = toolset.processLlmRequest(testToolContext(), LlmRequest())

    val instruction =
      request.config.systemInstruction?.parts?.joinToString("") { it.text ?: "" } ?: ""
    assertTrue(instruction.contains(root.toString()))
    // Pin the substantive tool-selection rules (mirroring the reference instruction).
    assertTrue(instruction.contains("Execute"))
    assertTrue(instruction.contains("ReadFile"))
    assertTrue(instruction.contains("run in parallel"))
    assertTrue(instruction.contains("cat"))
  }

  @Test
  fun processLlmRequest_initializesEnvironmentOnce() = runBlocking {
    val env = RecordingEnvironment()
    val ts = EnvironmentToolset(env)

    val unused = ts.processLlmRequest(testToolContext(), LlmRequest())
    val unusedSecond = ts.processLlmRequest(testToolContext(), LlmRequest())

    assertEquals(1, env.initializeCount)
  }

  @Test
  fun close_closesInitializedEnvironmentOnce() {
    val env = RecordingEnvironment()
    val ts = EnvironmentToolset(env)
    runBlocking {
      val unused = ts.getTools(null)
    } // initializes the environment
    ts.close()
    ts.close() // idempotent: the environment is closed at most once
    assertEquals(1, env.closeCount)
  }

  @Test
  fun close_beforeInitialize_doesNotCloseEnvironment() {
    val env = RecordingEnvironment()
    EnvironmentToolset(env).close()
    assertEquals(0, env.closeCount)
  }

  @Test
  fun close_removesAutoCreatedWorkspace() {
    val env = LocalEnvironment()
    val toolset = EnvironmentToolset(env)
    runBlocking {
      val unused = toolset.getTools(null)
    } // initializes the environment, creating the temp workspace
    val workspace = Path.of(env.workingDir)
    assertTrue(Files.exists(workspace))

    toolset.close() // bridges to the suspend LocalEnvironment.close(), which deletes the workspace

    assertFalse(Files.exists(workspace))
  }

  @Test
  fun execute_returnsStdoutAndOkStatus() = runBlocking {
    val result = tool("Execute").run(testToolContext(), mapOf("command" to "echo hi")) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals("hi\n", result["stdout"])
  }

  @Test
  fun execute_nonZeroExit_reportsError() = runBlocking {
    val result = tool("Execute").run(testToolContext(), mapOf("command" to "exit 3")) as Map<*, *>

    assertEquals("error", result["status"])
    assertEquals(3, result["exit_code"])
  }

  @Test
  fun execute_emptyCommand_reportsError() = runBlocking {
    val result = tool("Execute").run(testToolContext(), mapOf("command" to "")) as Map<*, *>

    assertEquals("error", result["status"])
    assertEquals("`command` is required.", result["error"])
  }

  @Test
  fun execute_missingCommand_reportsError() = runBlocking {
    val result = tool("Execute").run(testToolContext(), emptyMap<String, Any>()) as Map<*, *>

    assertEquals("error", result["status"])
    assertEquals("`command` is required.", result["error"])
  }

  @Test
  fun writeFile_createsFileReadableByEnvironment() = runBlocking {
    val result =
      tool("WriteFile").run(testToolContext(), mapOf("path" to "a.txt", "content" to "hello"))
        as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals("hello", Files.readString(root.resolve("a.txt")))
  }

  @Test
  fun writeFile_missingContent_writesEmptyFile() = runBlocking {
    val result = tool("WriteFile").run(testToolContext(), mapOf("path" to "empty.txt")) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals("", Files.readString(root.resolve("empty.txt")))
  }

  @Test
  fun writeFile_emptyPath_returnsError() = runBlocking {
    val result =
      tool("WriteFile").run(testToolContext(), mapOf("path" to "", "content" to "x")) as Map<*, *>

    assertEquals("error", result["status"])
    assertEquals("`path` is required.", result["error"])
  }

  @Test
  fun defaultTruncationLimit_truncatesExecuteAndReadFileTo30k() = runBlocking {
    val longText = "a".repeat(40_000)
    val tools =
      EnvironmentToolset(FakeEnvironment(longText, longText.encodeToByteArray())).getTools(null)

    val exec =
      tools.first { it.name == "Execute" }.run(testToolContext(), mapOf("command" to "dummy"))
        as Map<*, *>
    assertEquals("ok", exec["status"])
    assertEquals(30_000 + TRUNCATION_SUFFIX.length, (exec["stdout"] as String).length)
    assertTrue((exec["stdout"] as String).endsWith(TRUNCATION_SUFFIX))

    val read =
      tools.first { it.name == "ReadFile" }.run(testToolContext(), mapOf("path" to "dummy.txt"))
        as Map<*, *>
    assertEquals("ok", read["status"])
    assertEquals(30_000 + TRUNCATION_SUFFIX.length, (read["content"] as String).length)
  }

  @Test
  fun customTruncationLimit_isHonored() = runBlocking {
    val longText = "a".repeat(40_000)
    val tools =
      EnvironmentToolset(
          FakeEnvironment(longText, longText.encodeToByteArray()),
          maxOutputChars = 10_000,
        )
        .getTools(null)

    val exec =
      tools.first { it.name == "Execute" }.run(testToolContext(), mapOf("command" to "dummy"))
        as Map<*, *>
    assertEquals(10_000 + TRUNCATION_SUFFIX.length, (exec["stdout"] as String).length)

    val read =
      tools.first { it.name == "ReadFile" }.run(testToolContext(), mapOf("path" to "dummy.txt"))
        as Map<*, *>
    assertEquals(10_000 + TRUNCATION_SUFFIX.length, (read["content"] as String).length)
  }

  @Test
  fun noTruncationUnderLimit() = runBlocking {
    val shortText = "a".repeat(100)
    val tools =
      EnvironmentToolset(
          FakeEnvironment(shortText, shortText.encodeToByteArray()),
          maxOutputChars = 10_000,
        )
        .getTools(null)

    val exec =
      tools.first { it.name == "Execute" }.run(testToolContext(), mapOf("command" to "dummy"))
        as Map<*, *>
    assertEquals("ok", exec["status"])
    assertEquals(shortText, exec["stdout"])
  }

  private companion object {
    /** The truncation notice for a 40,000-char input; its length matches the 40,007-char case. */
    const val TRUNCATION_SUFFIX = "\n... (truncated, 40000 total chars)"
  }
}
