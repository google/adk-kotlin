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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.environment.EnvironmentException
import com.google.adk.kt.environment.ExecutionResult
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** Tests for [EnvironmentToolset]. */
@OptIn(ExperimentalEnvironmentApi::class)
class EnvironmentToolsetTest {

  /**
   * Fake environment returning canned results, mirroring the Python test double. A set [failure] is
   * returned by [execute] and [writeFile], while reads succeed so that EditFile reaches its write.
   */
  private class FakeEnvironment(
    private val stdout: String = "",
    private val fileContent: ByteArray = ByteArray(0),
    private val timedOut: Boolean = false,
    private val failure: Throwable? = null,
  ) : Environment {
    override suspend fun execute(
      context: Context,
      command: String,
      timeout: Duration?,
    ): Result<ExecutionResult> =
      if (failure != null) {
        Result.failure(failure)
      } else {
        Result.success(ExecutionResult(stdout = stdout, timedOut = timedOut))
      }

    override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
      Result.success(fileContent)

    override suspend fun writeFile(
      context: Context,
      path: String,
      content: ByteArray,
    ): Result<Unit> = if (failure != null) Result.failure(failure) else Result.success(Unit)
  }

  /** Environment double that records lifecycle calls and the sessions that reach it. */
  private class RecordingEnvironment : Environment {
    var initializeCount = 0
    var closeCount = 0
    val executeSessionIds = mutableListOf<String?>()

    override suspend fun initialize() {
      initializeCount++
    }

    override fun close() {
      closeCount++
    }

    override suspend fun execute(
      context: Context,
      command: String,
      timeout: Duration?,
    ): Result<ExecutionResult> {
      executeSessionIds.add(context.session.key.id)
      return Result.success(ExecutionResult())
    }

    override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
      Result.success(ByteArray(0))

    override suspend fun writeFile(
      context: Context,
      path: String,
      content: ByteArray,
    ): Result<Unit> = Result.success(Unit)
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

  private suspend fun tool(name: String, from: EnvironmentToolset = toolset): BaseTool =
    from.getTools(null).first { it.name == name }

  @Test
  fun constructor_negativeMaxOutputChars_throws() {
    assertFailsWith<IllegalArgumentException> {
      EnvironmentToolset(RecordingEnvironment(), maxOutputChars = -1)
    }
  }

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
    // The instruction names no path: a multi-tenant environment has no single working directory.
    assertTrue(instruction.contains("workspace"))
    // Pin the tool-selection rules taken from the reference instruction.
    assertTrue(instruction.contains("Execute"))
    assertTrue(instruction.contains("ReadFile"))
    assertTrue(instruction.contains("run in parallel"))
    assertTrue(instruction.contains("cat"))
    // The only guard against parallel calls racing on one file.
    assertTrue(instruction.contains("Use the same file in two calls of one response"))
  }

  @Test
  fun getTools_initializesEnvironmentOnEveryCall() = runBlocking {
    val env = RecordingEnvironment()
    val ts = EnvironmentToolset(env)

    val unused = ts.getTools(null)
    val unusedSecond = ts.getTools(null)

    // The toolset does not cache: only the environment can know what is already prepared.
    assertEquals(2, env.initializeCount)
  }

  @Test
  fun execute_passesTheCallingSessionToTheEnvironment() = runBlocking {
    val env = RecordingEnvironment()
    val execute = EnvironmentToolset(env).getTools(null).first { it.name == "Execute" }
    val first = testToolContext(testInvocationContext(session = testSession(id = "session-a")))
    val second = testToolContext(testInvocationContext(session = testSession(id = "session-b")))

    val unused = execute.run(first, mapOf("command" to "true"))
    val unusedSecond = execute.run(second, mapOf("command" to "true"))

    // A multi-tenant environment picks the workspace from the context of each call.
    assertEquals(listOf<String?>("session-a", "session-b"), env.executeSessionIds.toList())
  }

  @Test
  fun close_forwardsEveryCallToTheEnvironment() {
    val env = RecordingEnvironment()
    val ts = EnvironmentToolset(env)
    runBlocking {
      val unused = ts.getTools(null)
    } // initializes the environment

    ts.close()
    ts.close()

    // The toolset keeps no lifecycle state; [Environment.close] is required to be idempotent.
    assertEquals(2, env.closeCount)
  }

  @Test
  fun close_beforeInitialize_stillClosesEnvironment() {
    val env = RecordingEnvironment()

    EnvironmentToolset(env).close()

    // Closing an environment that was never used is the environment's problem, not the toolset's.
    assertEquals(1, env.closeCount)
  }

  @Test
  fun close_removesAutoCreatedWorkspace() {
    val env = LocalEnvironment()
    val toolset = EnvironmentToolset(env)
    runBlocking {
      val unused = toolset.getTools(null)
    } // initializes the environment, creating the temp workspace
    val workspace = env.requireWorkingDir()
    assertTrue(Files.exists(workspace))

    toolset.close() // Deletes the auto-created workspace.

    assertFalse(Files.exists(workspace))
  }

  @Test
  fun execute_returnsStdoutAndOkStatus() = runBlocking {
    val result = tool("Execute").run(testToolContext(), mapOf("command" to "echo hi")) as Map<*, *>

    assertEquals("ok", result["status"])
    assertEquals("hi\n", result["stdout"])
  }

  @Test
  fun execute_returnsStderr() = runBlocking {
    val result =
      tool("Execute").run(testToolContext(), mapOf("command" to "echo oops >&2")) as Map<*, *>

    assertEquals(mapOf("status" to "ok", "stderr" to "oops\n"), result)
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
  fun execute_timedOut_reportsError() = runBlocking {
    val execute = tool("Execute", from = EnvironmentToolset(FakeEnvironment(timedOut = true)))

    val result = execute.run(testToolContext(), mapOf("command" to "sleep 60")) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "Command timed out after 30s."), result)
  }

  @Test
  fun execute_environmentFailure_returnsError() = runBlocking {
    val env = FakeEnvironment(failure = EnvironmentException("Failed to start command."))
    val execute = tool("Execute", from = EnvironmentToolset(env))

    val result = execute.run(testToolContext(), mapOf("command" to "true")) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "Failed to start command."), result)
  }

  @Test
  fun execute_nonEnvironmentExceptionFailure_isRethrown() = runBlocking {
    val failure = IllegalStateException()
    val execute = tool("Execute", from = EnvironmentToolset(FakeEnvironment(failure = failure)))

    val thrown =
      assertFailsWith<IllegalStateException> {
        execute.run(testToolContext(), mapOf("command" to "true"))
      }

    // Only an EnvironmentException becomes a tool error; anything else breaks the contract.
    assertSame(failure, thrown)
  }

  @Test
  fun writeFile_writeFailure_returnsError() = runBlocking {
    val env = FakeEnvironment(failure = EnvironmentException("Failed to write file."))
    val writeFile = tool("WriteFile", from = EnvironmentToolset(env))

    val result =
      writeFile.run(testToolContext(), mapOf("path" to "a.txt", "content" to "x")) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "Failed to write file."), result)
  }

  @Test
  fun editFile_writeFailure_returnsError() = runBlocking {
    val env =
      FakeEnvironment(
        fileContent = "old".encodeToByteArray(),
        failure = EnvironmentException("Failed to write file."),
      )
    val editFile = tool("EditFile", from = EnvironmentToolset(env))

    val result =
      editFile.run(
        testToolContext(),
        mapOf("path" to "a.txt", "old_string" to "old", "new_string" to "new"),
      ) as Map<*, *>

    assertEquals(mapOf("status" to "error", "error" to "Failed to write file."), result)
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
    assertEquals(30_000 + READ_TRUNCATION_SUFFIX.length, (read["content"] as String).length)
    assertTrue((read["content"] as String).endsWith(READ_TRUNCATION_SUFFIX))
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
    assertEquals(10_000 + READ_TRUNCATION_SUFFIX.length, (read["content"] as String).length)
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
    /** Notice for Execute's 40,000-char output. */
    const val TRUNCATION_SUFFIX = "\n... (truncated, 40000 total chars)"

    /** Notice for ReadFile's output of the same text: a 7-char line-number prefix, no newline. */
    const val READ_TRUNCATION_SUFFIX = "\n... (truncated, 40007 total chars)"
  }
}
