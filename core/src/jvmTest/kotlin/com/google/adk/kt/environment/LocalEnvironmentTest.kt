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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

/** Unit tests for [LocalEnvironment]. Uses `runBlocking` because it spawns real subprocesses. */
@OptIn(ExperimentalEnvironmentApi::class)
class LocalEnvironmentTest {

  private lateinit var root: Path
  private lateinit var env: LocalEnvironment

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-local-env-test")
    env = LocalEnvironment(root.toString())
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun execute_capturesStdoutAndZeroExit(): Unit = runBlocking {
    val result = env.execute("echo hello").getOrThrow()

    assertEquals(0, result.exitCode)
    assertEquals("hello\n", result.stdout)
    assertTrue(result.isSuccess)
    assertFalse(result.timedOut)
  }

  @Test
  fun execute_capturesStderrAndNonZeroExit(): Unit = runBlocking {
    val result = env.execute("echo error 1>&2; exit 3").getOrThrow()

    assertEquals(3, result.exitCode)
    assertTrue(result.stderr.contains("error"))
    assertFalse(result.isSuccess)
  }

  @Test
  fun execute_chainsCommandsWithAmpersand(): Unit = runBlocking {
    val result = env.execute("echo one && echo two").getOrThrow()

    assertEquals(0, result.exitCode)
    assertEquals("one\ntwo\n", result.stdout)
  }

  @Test
  fun execute_runsInWorkingDir(): Unit = runBlocking {
    val result = env.execute("pwd").getOrThrow()

    assertEquals(root.toRealPath().toString(), result.stdout.trim())
  }

  @Test
  fun execute_mergesEnvVars(): Unit = runBlocking {
    val withVar = LocalEnvironment(root.toString(), envVars = mapOf("ADK_TEST_VAR" to "xyz"))

    val result = withVar.execute("printf %s \"\$ADK_TEST_VAR\"").getOrThrow()

    assertEquals("xyz", result.stdout)
  }

  @Test
  fun execute_timeout_isReported(): Unit = runBlocking {
    val result = env.execute("sleep 30", timeout = 300.milliseconds).getOrThrow()

    assertTrue(result.timedOut)
    assertFalse(result.isSuccess)
  }

  @Test
  fun execute_beforeInitialize_throwsIllegalState(): Unit = runBlocking {
    val uninitialized = LocalEnvironment()

    assertFailsWith<IllegalStateException> { uninitialized.execute("echo hi") }
  }

  @Test
  fun writeFile_thenReadFile_roundTrips(): Unit = runBlocking {
    env.writeFile("notes/todo.txt", "buy milk".encodeToByteArray()).getOrThrow()

    assertEquals("buy milk", env.readFile("notes/todo.txt").getOrThrow().decodeToString())
  }

  @Test
  fun writeFile_isVisibleToExecute(): Unit = runBlocking {
    env.writeFile("greeting.txt", "hi there".encodeToByteArray()).getOrThrow()

    assertEquals("hi there", env.execute("cat greeting.txt").getOrThrow().stdout)
  }

  @Test
  fun writeFile_createsParentDirs(): Unit = runBlocking {
    env.writeFile("sub/dir/file.txt", "nested".encodeToByteArray()).getOrThrow()

    assertEquals("nested", env.readFile("sub/dir/file.txt").getOrThrow().decodeToString())
  }

  @Test
  fun absolutePath_insideWorkingDir_isAccepted(): Unit = runBlocking {
    val absolute = "${env.workingDir}/absolute.txt"
    env.writeFile(absolute, "absolute".encodeToByteArray()).getOrThrow()

    assertEquals("absolute", env.readFile(absolute).getOrThrow().decodeToString())
  }

  @Test
  fun readFile_missing_returnsFailure(): Unit = runBlocking {
    val result = env.readFile("does-not-exist.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun readFile_relativePathEscape_returnsFailure(): Unit = runBlocking {
    val result = env.readFile("../escape.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun writeFile_relativePathEscape_returnsFailure(): Unit = runBlocking {
    val result = env.writeFile("../write-outside.txt", "hello".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
    assertFalse(Files.exists(root.resolveSibling("write-outside.txt")))
  }

  @Test
  fun readFile_absolutePathOutsideWorkingDir_returnsFailure(): Unit = runBlocking {
    val outside = root.resolveSibling("outside-absolute.txt")
    Files.write(outside, "secret".encodeToByteArray())
    try {
      val result = env.readFile(outside.toString())

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is EnvironmentException)
    } finally {
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun emptyWorkingDir_treatedAsUnspecified_createsTempDir(): Unit = runBlocking {
    val empty = LocalEnvironment("")
    empty.initialize()

    val dir = empty.workingDir
    assertTrue(dir.isNotEmpty())
    assertTrue(Files.isDirectory(Path.of(dir)))

    empty.close()
    assertFalse(Files.exists(Path.of(dir)))
  }

  @Test
  fun initialize_existingDir_doesNotThrow(): Unit = runBlocking {
    // `root` already exists (created in setUp); initializing over an existing dir must succeed,
    // matching Python's os.makedirs(..., exist_ok=True).
    val env = LocalEnvironment(root.toString())
    env.initialize()

    assertEquals(root.toString(), env.workingDir)
  }

  @Test
  fun initialize_repeatedSequentialCall_reusesSameDir(): Unit = runBlocking {
    val env = LocalEnvironment()
    env.initialize()
    val first = env.workingDir

    env.initialize() // second call reuses the existing dir, not a new temp dir

    assertEquals(first, env.workingDir)
  }

  @Test
  fun autoTempDir_createdOnInitialize_removedOnClose(): Unit = runBlocking {
    val auto = LocalEnvironment()
    auto.initialize()

    val dir = auto.workingDir
    assertTrue(Files.isDirectory(Path.of(dir)))
    auto.writeFile("f.txt", "x".encodeToByteArray()).getOrThrow()

    auto.close()
    assertFalse(Files.exists(Path.of(dir)))
  }
}
