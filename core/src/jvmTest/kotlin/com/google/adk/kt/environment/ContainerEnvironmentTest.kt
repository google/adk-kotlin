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

package com.google.adk.kt.environment

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.testing.testToolContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [ContainerEnvironment].
 *
 * These use a fake [CommandRunner] instead of a real Docker daemon, so they verify that each
 * [Environment] operation builds the correct `docker` argv and maps results and errors correctly,
 * without needing Docker to be installed or running. Uses `runBlocking` per the project style.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class ContainerEnvironmentTest {

  // ContainerEnvironment ignores the per-call context; these satisfy the interface's parameters.
  private val toolContext = testToolContext()
  private val readonlyContext = toolContext.context

  /** Records every invocation the environment makes so tests can assert on the exact argv. */
  private class RecordedCall(val command: List<String>, val timeout: Duration?) {
    /** The `docker` subcommand, e.g. `pull`, `run`, `exec`, `rm`. */
    val subcommand: String?
      get() = command.getOrNull(1)
  }

  /**
   * A fake `docker` CLI: records calls, replies via [responder], and can simulate launch errors.
   */
  private class FakeDocker {
    private val lock = Any()
    private val recorded = mutableListOf<RecordedCall>()

    /** Maps a `docker` argv to its canned outcome. Defaults to success (with an id for `run`). */
    var responder: (List<String>) -> CommandResult = { command ->
      when (command.getOrNull(1)) {
        "run" -> CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
        else -> CommandResult(0, EMPTY, EMPTY)
      }
    }

    /** Returns an [IOException] to throw for a given argv (a launch failure), or `null`. */
    var throwOn: (List<String>) -> IOException? = { null }

    val runner = CommandRunner { command, timeout ->
      synchronized(lock) { recorded.add(RecordedCall(command, timeout)) }
      throwOn(command)?.let { throw it }
      responder(command)
    }

    val calls: List<RecordedCall>
      get() = synchronized(lock) { recorded.toList() }

    fun callsTo(subcommand: String): List<RecordedCall> = calls.filter {
      it.subcommand == subcommand
    }

    fun onlyCallTo(subcommand: String): RecordedCall {
      val matching = callsTo(subcommand)
      assertEquals(1, matching.size, "expected exactly one `docker $subcommand` call")
      return matching.single()
    }
  }

  private fun newEnv(
    fake: FakeDocker,
    image: String = IMAGE,
    workingDir: String = WORKING_DIR,
    pullImage: Boolean = true,
    envVars: Map<String, String> = emptyMap(),
  ): ContainerEnvironment =
    ContainerEnvironment(image, workingDir, pullImage, envVars, DOCKER, fake.runner)

  private suspend fun initializedEnv(
    fake: FakeDocker,
    envVars: Map<String, String> = emptyMap(),
    pullImage: Boolean = true,
  ): ContainerEnvironment =
    newEnv(fake, envVars = envVars, pullImage = pullImage).also { it.initialize(readonlyContext) }

  // ---- initialize -------------------------------------------------------------------------------

  @Test
  fun initialize_pullsImageThenRunsDetachedContainer(): Unit = runBlocking {
    val fake = FakeDocker()

    val env = initializedEnv(fake)

    assertEquals(listOf(DOCKER, "pull", IMAGE), fake.onlyCallTo("pull").command)
    assertEquals(
      listOf(DOCKER, "run", "-d", "-w", WORKING_DIR, IMAGE, "sleep", "infinity"),
      fake.onlyCallTo("run").command,
    )
    // The container id is parsed from `docker run` stdout with the trailing newline stripped.
    assertEquals(CONTAINER_ID, env.containerId)
  }

  @Test
  fun initialize_pullImageFalse_skipsPull(): Unit = runBlocking {
    val fake = FakeDocker()

    val unused = initializedEnv(fake, pullImage = false)

    assertTrue(fake.callsTo("pull").isEmpty(), "should not pull when pullImage is false")
    assertEquals(1, fake.callsTo("run").size)
  }

  @Test
  fun initialize_isIdempotentAcrossRepeatedCalls(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = newEnv(fake, pullImage = false)

    env.initialize(readonlyContext)
    env.initialize(readonlyContext)

    // The second call reuses the existing container rather than starting another.
    assertEquals(1, fake.callsTo("run").size)
  }

  @Test
  fun initialize_concurrentCalls_createASingleContainer(): Unit = runBlocking {
    // Tool calls in one turn run in parallel, so initialize() can be entered many times at once.
    val fake = FakeDocker()
    val env = newEnv(fake, pullImage = false)

    (1..8).map { async(Dispatchers.Default) { env.initialize(readonlyContext) } }.awaitAll()

    assertEquals(
      1,
      fake.callsTo("run").size,
      "concurrent initialize must start exactly one container",
    )
  }

  @Test
  fun initialize_pullFails_throwsEnvironmentException(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "pull")
        CommandResult(1, EMPTY, "no such image".encodeToByteArray())
      else CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
    }
    val env = newEnv(fake)

    assertFailsWith<EnvironmentException> { env.initialize(readonlyContext) }
    assertTrue(fake.callsTo("run").isEmpty(), "must not start a container when the pull failed")
  }

  @Test
  fun initialize_runFails_throwsEnvironmentException(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "run")
        CommandResult(125, EMPTY, "daemon down".encodeToByteArray())
      else CommandResult(0, EMPTY, EMPTY)
    }
    val env = newEnv(fake, pullImage = false)

    assertFailsWith<EnvironmentException> { env.initialize(readonlyContext) }
  }

  @Test
  fun initialize_launchFailure_throwsEnvironmentException(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.throwOn = { IOException("docker not found") }
    val env = newEnv(fake, pullImage = false)

    assertFailsWith<EnvironmentException> { env.initialize(readonlyContext) }
  }

  // ---- execute ----------------------------------------------------------------------------------

  @Test
  fun execute_buildsDockerExecArgv(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)

    env.execute(toolContext, "echo hello").getOrThrow()

    assertEquals(
      listOf(DOCKER, "exec", "-w", WORKING_DIR, CONTAINER_ID, "/bin/sh", "-c", "echo hello"),
      fake.onlyCallTo("exec").command,
    )
  }

  @Test
  fun execute_includesEnvVarsInOrder(): Unit = runBlocking {
    val fake = FakeDocker()
    val env =
      initializedEnv(fake, pullImage = false, envVars = linkedMapOf("FOO" to "bar", "BAZ" to "qux"))

    env.execute(toolContext, "true").getOrThrow()

    assertEquals(
      listOf(
        DOCKER,
        "exec",
        "-w",
        WORKING_DIR,
        "-e",
        "FOO=bar",
        "-e",
        "BAZ=qux",
        CONTAINER_ID,
        "/bin/sh",
        "-c",
        "true",
      ),
      fake.onlyCallTo("exec").command,
    )
  }

  @Test
  fun execute_mapsExitCodeStdoutStderr(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "exec")
        CommandResult(3, "out\n".encodeToByteArray(), "err\n".encodeToByteArray())
      else CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
    }
    val env = initializedEnv(fake, pullImage = false)

    val result = env.execute(toolContext, "whatever").getOrThrow()

    assertEquals(3, result.exitCode)
    assertEquals("out\n", result.stdout)
    assertEquals("err\n", result.stderr)
    assertFalse(result.timedOut)
  }

  @Test
  fun execute_timeout_isReportedAndPassedToRunner(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "exec") CommandResult(137, EMPTY, EMPTY, timedOut = true)
      else CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
    }
    val env = initializedEnv(fake, pullImage = false)

    val result = env.execute(toolContext, "sleep 30", timeout = 5.seconds).getOrThrow()

    assertTrue(result.timedOut)
    assertEquals(5.seconds, fake.onlyCallTo("exec").timeout)
  }

  @Test
  fun execute_launchFailure_returnsFailure(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)
    fake.throwOn = { command -> if (command.getOrNull(1) == "exec") IOException("boom") else null }

    val result = env.execute(toolContext, "echo hi")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun execute_beforeInitialize_throwsIllegalState(): Unit = runBlocking {
    val env = newEnv(FakeDocker())

    assertFailsWith<IllegalStateException> { env.execute(toolContext, "echo hi") }
  }

  // ---- readFile ---------------------------------------------------------------------------------

  @Test
  fun readFile_buildsCatArgvAndReturnsRawBytes(): Unit = runBlocking {
    // Includes a NUL and a high byte to prove the read path is binary-safe.
    val bytes = byteArrayOf(0, 1, 2, 127, -1, 65)
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "exec") CommandResult(0, bytes, EMPTY)
      else CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
    }
    val env = initializedEnv(fake, pullImage = false)

    val result = env.readFile(toolContext, "data/blob.bin").getOrThrow()

    assertContentEquals(bytes, result)
    assertEquals(
      listOf(
        DOCKER,
        "exec",
        "-w",
        WORKING_DIR,
        CONTAINER_ID,
        "/bin/sh",
        "-c",
        "cat -- 'data/blob.bin'",
      ),
      fake.onlyCallTo("exec").command,
    )
  }

  @Test
  fun readFile_missing_returnsFailure(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      if (command.getOrNull(1) == "exec")
        CommandResult(1, EMPTY, "No such file".encodeToByteArray())
      else CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
    }
    val env = initializedEnv(fake, pullImage = false)

    val result = env.readFile(toolContext, "does-not-exist.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun readFile_launchFailure_returnsFailure(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)
    fake.throwOn = { command -> if (command.getOrNull(1) == "exec") IOException("boom") else null }

    val result = env.readFile(toolContext, "f.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun readFile_beforeInitialize_throwsIllegalState(): Unit = runBlocking {
    val env = newEnv(FakeDocker())

    assertFailsWith<IllegalStateException> { env.readFile(toolContext, "f.txt") }
  }

  // ---- writeFile --------------------------------------------------------------------------------

  @Test
  fun writeFile_createsParentDirsThenCopiesStagedFileIn(): Unit = runBlocking {
    // A NUL and a high byte prove the write path is binary-safe: bytes go to a host temp file, then
    // in via `docker cp`, never through argv.
    val bytes = byteArrayOf(0, 10, -2, 66)
    val fake = FakeDocker()
    // The `cp` responder reads the staged host file before it is deleted, capturing what would be
    // copied into the container so the test can assert the bytes survived staging verbatim.
    var copiedIn: ByteArray? = null
    fake.responder = { command ->
      when (command.getOrNull(1)) {
        "cp" -> {
          copiedIn = Files.readAllBytes(Path.of(command[2]))
          CommandResult(0, EMPTY, EMPTY)
        }
        "run" -> CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
        else -> CommandResult(0, EMPTY, EMPTY)
      }
    }
    val env = initializedEnv(fake, pullImage = false)

    env.writeFile(toolContext, "notes/todo.bin", bytes).getOrThrow()

    // Parent dirs are created first, keyed off the resolved absolute container path.
    assertEquals(
      listOf(
        DOCKER,
        "exec",
        "-w",
        WORKING_DIR,
        CONTAINER_ID,
        "/bin/sh",
        "-c",
        "mkdir -p \"\$(dirname -- '$WORKING_DIR/notes/todo.bin')\"",
      ),
      fake.onlyCallTo("exec").command,
    )
    // The staged host file is copied to the resolved container path.
    val cp = fake.onlyCallTo("cp").command
    assertEquals(4, cp.size)
    assertEquals(DOCKER, cp[0])
    assertEquals("cp", cp[1])
    assertTrue(cp[2].isNotEmpty(), "expected a host staging path as the `docker cp` source")
    assertEquals("$CONTAINER_ID:$WORKING_DIR/notes/todo.bin", cp[3])
    // The bytes reached the staging file verbatim.
    assertContentEquals(bytes, copiedIn)
  }

  @Test
  fun writeFile_shellQuotesPathWithSingleQuote(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)

    env.writeFile(toolContext, "o'brien.txt", "x".encodeToByteArray()).getOrThrow()

    // The path is interpolated into the mkdir `sh -c` script, so its single quote is escaped as
    // '\''.
    val script = fake.onlyCallTo("exec").command.last()
    assertTrue(script.contains("o'\\''brien.txt"), "path was not safely single-quoted: $script")
  }

  @Test
  fun writeFile_copyFails_returnsFailure(): Unit = runBlocking {
    val fake = FakeDocker()
    fake.responder = { command ->
      when (command.getOrNull(1)) {
        "cp" -> CommandResult(1, EMPTY, "read-only fs".encodeToByteArray())
        "run" -> CommandResult(0, "$CONTAINER_ID\n".encodeToByteArray(), EMPTY)
        else -> CommandResult(0, EMPTY, EMPTY)
      }
    }
    val env = initializedEnv(fake, pullImage = false)

    val result = env.writeFile(toolContext, "f.txt", "x".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun writeFile_launchFailure_returnsFailure(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)
    fake.throwOn = { command -> if (command.getOrNull(1) == "exec") IOException("boom") else null }

    val result = env.writeFile(toolContext, "f.txt", "x".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun writeFile_beforeInitialize_throwsIllegalState(): Unit = runBlocking {
    val env = newEnv(FakeDocker())

    assertFailsWith<IllegalStateException> {
      env.writeFile(toolContext, "f.txt", "x".encodeToByteArray())
    }
  }

  // ---- close ------------------------------------------------------------------------------------

  @Test
  fun close_forceRemovesContainer(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)

    env.close()

    assertEquals(listOf(DOCKER, "rm", "-f", CONTAINER_ID), fake.onlyCallTo("rm").command)
  }

  @Test
  fun close_isIdempotent(): Unit = runBlocking {
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)

    env.close()
    env.close()

    assertEquals(1, fake.callsTo("rm").size, "second close must not issue another rm")
  }

  @Test
  fun close_beforeInitialize_isNoOp() {
    val fake = FakeDocker()
    val env = newEnv(fake)

    env.close()

    assertTrue(fake.calls.isEmpty(), "closing an uninitialized environment must not touch docker")
  }

  @Test
  fun close_removeFailure_isSwallowed(): Unit = runBlocking {
    // close() is best-effort teardown, so a failed `docker rm` must not throw.
    val fake = FakeDocker()
    val env = initializedEnv(fake, pullImage = false)
    fake.throwOn = { command -> if (command.getOrNull(1) == "rm") IOException("boom") else null }

    env.close() // must not throw

    // The rm was attempted (the runner records before throwing) and the failure was swallowed.
    assertEquals(1, fake.callsTo("rm").size)
  }

  // ---- ProcessCommandRunner (the default runner, exercised with real /bin/sh subprocesses)
  // -------
  // These need no Docker: they run local shell commands to cover the timeout, kill, stdin, and
  // capture logic that the fake CommandRunner in the tests above deliberately bypasses.

  @Test
  fun processRunner_capturesStdoutAndZeroExit() {
    val result = ProcessCommandRunner().run(listOf("/bin/sh", "-c", "echo hello"), timeout = null)

    assertEquals(0, result.exitCode)
    assertEquals("hello\n", result.stdout.decodeToString())
    assertFalse(result.timedOut)
  }

  @Test
  fun processRunner_capturesStderrAndNonZeroExit() {
    val result =
      ProcessCommandRunner().run(listOf("/bin/sh", "-c", "echo boom 1>&2; exit 7"), timeout = null)

    assertEquals(7, result.exitCode)
    assertTrue(result.stderr.decodeToString().contains("boom"))
  }

  @Test
  fun processRunner_capturesLargeStdoutWithoutDeadlock() {
    // Output larger than a pipe buffer would deadlock if it were drained on the waitFor thread;
    // redirecting to a temp file avoids that. /dev/zero gives 1 MiB of NUL bytes, which also proves
    // the capture is binary-safe.
    val result =
      ProcessCommandRunner()
        .run(listOf("/bin/sh", "-c", "head -c 1048576 /dev/zero"), timeout = null)

    assertEquals(0, result.exitCode)
    assertEquals(1 shl 20, result.stdout.size)
    assertContentEquals(ByteArray(1 shl 20), result.stdout)
  }

  @Test
  fun processRunner_timeout_isReported() {
    val result =
      ProcessCommandRunner().run(listOf("/bin/sh", "-c", "sleep 30"), timeout = 300.milliseconds)

    assertTrue(result.timedOut)
  }

  @Test
  fun processRunner_launchFailure_throwsIOException() {
    assertFailsWith<IOException> {
      ProcessCommandRunner().run(listOf("/no/such/binary/adk-does-not-exist"), timeout = null)
    }
  }

  private companion object {
    const val IMAGE = "python:3.11-slim"
    const val WORKING_DIR = "/workspace"
    const val DOCKER = "docker"
    const val CONTAINER_ID = "container-123"
    val EMPTY = ByteArray(0)
  }
}
