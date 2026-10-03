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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Unit tests for [LocalEnvironment]. Uses `runBlocking` because it spawns real subprocesses. */
@OptIn(ExperimentalEnvironmentApi::class)
class LocalEnvironmentTest {

  private lateinit var root: Path
  private lateinit var env: LocalEnvironment

  // LocalEnvironment ignores the per-call context; these satisfy the interface's parameters.
  private val toolContext = testToolContext()
  private val readonlyContext = toolContext.context

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("adk-local-env-test")
    env = LocalEnvironment(root.toString())
  }

  @AfterTest
  fun tearDown() {
    if (Files.exists(root)) {
      // Does not follow symbolic links, so a link left by a test only has the link removed.
      Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
          }
        },
      )
    }
  }

  @Test
  fun close_removesWorkspaceWithoutFollowingSymlinks(): Unit = runBlocking {
    // A command run in the workspace can leave a link to a directory elsewhere behind.
    val outside = Files.createTempDirectory("adk-local-env-outside")
    val outsideFile = outside.resolve("important.txt")
    Files.writeString(outsideFile, "must survive")
    val autoCreated = LocalEnvironment()
    autoCreated.initialize(readonlyContext)
    val workspace = Path.of(autoCreated.workingDir)
    Files.createSymbolicLink(workspace.resolve("link"), outside)
    assertTrue(Files.exists(workspace.resolve("link").resolve("important.txt")))

    autoCreated.close()

    assertFalse(Files.exists(workspace), "workspace should be removed")
    assertTrue(Files.exists(outsideFile), "file outside the workspace must not be deleted")
    Files.deleteIfExists(outsideFile)
    Files.deleteIfExists(outside)
  }

  @Test
  fun execute_capturesStdoutAndZeroExit(): Unit = runBlocking {
    val result = env.execute(toolContext, "echo hello").getOrThrow()

    assertEquals(0, result.exitCode)
    assertEquals("hello\n", result.stdout)
    assertFalse(result.timedOut)
  }

  @Test
  fun execute_capturesStderrAndNonZeroExit(): Unit = runBlocking {
    val result = env.execute(toolContext, "echo error 1>&2; exit 3").getOrThrow()

    assertEquals(3, result.exitCode)
    assertTrue(result.stderr.contains("error"))
  }

  @Test
  fun execute_chainsCommandsWithAmpersand(): Unit = runBlocking {
    val result = env.execute(toolContext, "echo one && echo two").getOrThrow()

    assertEquals(0, result.exitCode)
    assertEquals("one\ntwo\n", result.stdout)
  }

  @Test
  fun execute_runsInWorkingDir(): Unit = runBlocking {
    val result = env.execute(toolContext, "pwd").getOrThrow()

    assertEquals(root.toRealPath().toString(), result.stdout.trim())
  }

  @Test
  fun execute_mergesEnvVars(): Unit = runBlocking {
    val withVar = LocalEnvironment(root.toString(), envVars = mapOf("ADK_TEST_VAR" to "xyz"))

    val result = withVar.execute(toolContext, "printf %s \"\$ADK_TEST_VAR\"").getOrThrow()

    assertEquals("xyz", result.stdout)
  }

  @Test
  fun execute_inheritedEnvVarAllowlist_dropsUnlistedHostVars(): Unit = runBlocking {
    // Probe a host var the shell will not synthesize on its own, so dropping it is observable.
    // PATH is unsuitable: /bin/sh substitutes a built-in default PATH when none is inherited.
    val shellManaged =
      setOf("PATH", "PWD", "IFS", "PS1", "PS2", "PS4", "OPTIND", "PPID", "SHLVL", "_")
    val hostVar =
      System.getenv().entries.first { it.key !in shellManaged && it.value.isNotEmpty() }.key
    val restricted = LocalEnvironment(root.toString(), inheritedEnvVarAllowlist = emptySet())

    val result = restricted.execute(toolContext, "printf %s \"\$$hostVar\"").getOrThrow()

    assertEquals("", result.stdout)
  }

  @Test
  fun execute_inheritedEnvVarAllowlist_keepsListedHostVars(): Unit = runBlocking {
    val hostPath = System.getenv("PATH").orEmpty()
    val restricted = LocalEnvironment(root.toString(), inheritedEnvVarAllowlist = setOf("PATH"))

    val result = restricted.execute(toolContext, "printf %s \"\$PATH\"").getOrThrow()

    assertEquals(hostPath, result.stdout)
  }

  @Test
  fun execute_inheritedEnvVarAllowlist_doesNotBlockExplicitEnvVars(): Unit = runBlocking {
    // envVars are layered on top of the allowlist, so an explicit value survives even an empty one.
    val restricted =
      LocalEnvironment(
        root.toString(),
        envVars = mapOf("ADK_TEST_VAR" to "xyz"),
        inheritedEnvVarAllowlist = emptySet(),
      )

    val result = restricted.execute(toolContext, "printf %s \"\$ADK_TEST_VAR\"").getOrThrow()

    assertEquals("xyz", result.stdout)
  }

  @Test
  fun execute_timeout_isReported(): Unit = runBlocking {
    val result = env.execute(toolContext, "sleep 30", timeout = 300.milliseconds).getOrThrow()

    assertTrue(result.timedOut)
  }

  @Test
  fun execute_cancellation_killsProcessTree(): Unit = runBlocking {
    // A long-running command whose child keeps writing a heartbeat, so we can tell if it survives.
    val heartbeat = root.resolve("heartbeat.txt")
    val job =
      launch(Dispatchers.IO) {
        val unused =
          env.execute(toolContext, "while true; do echo tick >> heartbeat.txt; sleep 0.05; done")
      }
    while (!Files.exists(heartbeat) || Files.size(heartbeat) == 0L) {
      delay(20) // wait until the child is actually running and writing
    }

    job.cancelAndJoin()

    // Once the tree is killed the file stops growing; a surviving process would keep appending.
    delay(300)
    val sizeAfterCancel = Files.size(heartbeat)
    delay(300)
    assertEquals(sizeAfterCancel, Files.size(heartbeat), "process kept writing after cancellation")
  }

  @Test
  fun execute_beforeInitialize_throwsIllegalState(): Unit = runBlocking {
    val uninitialized = LocalEnvironment()

    assertFailsWith<IllegalStateException> { uninitialized.execute(toolContext, "echo hi") }
  }

  @Test
  fun writeFile_thenReadFile_roundTrips(): Unit = runBlocking {
    env.writeFile(toolContext, "notes/todo.txt", "buy milk".encodeToByteArray()).getOrThrow()

    assertEquals(
      "buy milk",
      env.readFile(toolContext, "notes/todo.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun writeFile_isVisibleToExecute(): Unit = runBlocking {
    env.writeFile(toolContext, "greeting.txt", "hi there".encodeToByteArray()).getOrThrow()

    assertEquals("hi there", env.execute(toolContext, "cat greeting.txt").getOrThrow().stdout)
  }

  @Test
  fun writeFile_createsParentDirs(): Unit = runBlocking {
    env.writeFile(toolContext, "sub/dir/file.txt", "nested".encodeToByteArray()).getOrThrow()

    assertEquals(
      "nested",
      env.readFile(toolContext, "sub/dir/file.txt").getOrThrow().decodeToString(),
    )
  }

  @Test
  fun absolutePath_insideWorkingDir_isAccepted(): Unit = runBlocking {
    val absolute = "${env.workingDir}/absolute.txt"
    env.writeFile(toolContext, absolute, "absolute".encodeToByteArray()).getOrThrow()

    assertEquals("absolute", env.readFile(toolContext, absolute).getOrThrow().decodeToString())
  }

  @Test
  fun readFile_missing_returnsFailure(): Unit = runBlocking {
    val result = env.readFile(toolContext, "does-not-exist.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun readFile_invalidPath_returnsFailure(): Unit = runBlocking {
    // A NUL byte makes Paths.get throw InvalidPathException; it must come back as a failed Result,
    // not escape as an uncaught exception that aborts the whole invocation.
    val result = env.readFile(toolContext, "a\u0000b")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun writeFile_invalidPath_returnsFailure(): Unit = runBlocking {
    val result = env.writeFile(toolContext, "a\u0000b", "x".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun readFile_relativePathEscape_returnsFailure(): Unit = runBlocking {
    val result = env.readFile(toolContext, "../escape.txt")

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
  }

  @Test
  fun writeFile_relativePathEscape_returnsFailure(): Unit = runBlocking {
    val result = env.writeFile(toolContext, "../write-outside.txt", "hello".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
    assertFalse(Files.exists(root.resolveSibling("write-outside.txt")))
  }

  @Test
  fun writeFile_dotDotEscapesThroughExistingDir_returnsFailure(): Unit = runBlocking {
    // `..` climbs back out through a real in-workspace dir. Normalization collapses it before the
    // path is split, so the escape is caught even though the leading `sub/` component is valid.
    Files.createDirectories(root.resolve("sub"))

    val result = env.writeFile(toolContext, "sub/../../pwned.txt", "hello".encodeToByteArray())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is EnvironmentException)
    assertFalse(Files.exists(root.resolveSibling("pwned.txt")), "wrote outside the working dir")
  }

  @Test
  fun writeFile_dotDotWithinWorkspace_isNormalizedAndContained(): Unit = runBlocking {
    // `..` that stays inside is normalized to the in-workspace location, not rejected.
    Files.createDirectories(root.resolve("sub"))

    env.writeFile(toolContext, "sub/../note.txt", "contained".encodeToByteArray()).getOrThrow()

    assertEquals("contained", Files.readString(root.resolve("note.txt")))
  }

  @Test
  fun readFile_throughSymlinkOutOfWorkingDir_returnsFailure(): Unit = runBlocking {
    // The path is inside the working directory as text, but the symlink leads out of it.
    val outside = Files.createTempDirectory("adk-local-env-outside")
    Files.writeString(outside.resolve("creds.txt"), "secret")
    try {
      Files.createSymbolicLink(root.resolve("link"), outside)

      val result = env.readFile(toolContext, "link/creds.txt")

      assertTrue(result.isFailure, "reading through a symlink should not leave the working dir")
      assertTrue(result.exceptionOrNull() is EnvironmentException)
    } finally {
      Files.deleteIfExists(outside.resolve("creds.txt"))
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun writeFile_throughSymlinkOutOfWorkingDir_returnsFailure(): Unit = runBlocking {
    val outside = Files.createTempDirectory("adk-local-env-outside")
    try {
      Files.createSymbolicLink(root.resolve("link"), outside)

      val result = env.writeFile(toolContext, "link/planted.txt", "hello".encodeToByteArray())

      assertTrue(result.isFailure, "writing through a symlink should not leave the working dir")
      assertFalse(Files.exists(outside.resolve("planted.txt")), "wrote outside the working dir")
    } finally {
      Files.deleteIfExists(outside.resolve("planted.txt"))
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun readFile_throughSymlinkWithinWorkingDir_succeeds(): Unit = runBlocking {
    // A link that stays inside the working directory is fine, and must not be rejected.
    Files.createDirectories(root.resolve("real"))
    Files.writeString(root.resolve("real").resolve("note.txt"), "inside")
    Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"))

    val result = env.readFile(toolContext, "alias/note.txt")

    assertEquals("inside", result.getOrThrow().decodeToString())
  }

  @Test
  fun readFile_finalComponentIsSymlinkToOutside_returnsFailure(): Unit = runBlocking {
    // The requested path is itself a symlink (the last component), pointing at a file outside.
    val outside = Files.createTempDirectory("adk-local-env-outside")
    Files.writeString(outside.resolve("creds.txt"), "secret")
    try {
      Files.createSymbolicLink(root.resolve("link"), outside.resolve("creds.txt"))

      val result = env.readFile(toolContext, "link")

      assertTrue(result.isFailure, "a final-component symlink out of the dir must be rejected")
      assertTrue(result.exceptionOrNull() is EnvironmentException)
    } finally {
      Files.deleteIfExists(outside.resolve("creds.txt"))
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun writeFile_throughNestedSymlinkDir_returnsFailure(): Unit = runBlocking {
    // A symlink several levels deep must be caught, not just one directly under the workspace.
    val outside = Files.createTempDirectory("adk-local-env-outside")
    try {
      Files.createDirectories(root.resolve("nested"))
      Files.createSymbolicLink(root.resolve("nested").resolve("out"), outside)

      val result = env.writeFile(toolContext, "nested/out/planted.txt", "hello".encodeToByteArray())

      assertTrue(result.isFailure, "writing through a nested symlink must not leave the dir")
      assertFalse(Files.exists(outside.resolve("planted.txt")), "wrote outside the working dir")
    } finally {
      Files.deleteIfExists(outside.resolve("planted.txt"))
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun readFile_dotDotAfterSymlink_staysInsideWorkingDir(): Unit = runBlocking {
    // `link/..` cancels lexically before any link is followed, so this reads the in-workspace file,
    // never the symlink target. This is the safe (containing) reading, even if it differs from a
    // shell's link-then-parent semantics.
    val outside = Files.createTempDirectory("adk-local-env-outside")
    Files.writeString(outside.resolve("inside.txt"), "leaked")
    Files.writeString(root.resolve("inside.txt"), "kept-inside")
    try {
      Files.createSymbolicLink(root.resolve("link"), outside)

      val result = env.readFile(toolContext, "link/../inside.txt")

      assertEquals("kept-inside", result.getOrThrow().decodeToString())
    } finally {
      Files.deleteIfExists(outside.resolve("inside.txt"))
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun writeFile_throughSymlinkedDirWithinWorkingDir_succeeds(): Unit = runBlocking {
    // A new file written under a symlink that stays inside the workspace resolves to the real dir.
    Files.createDirectories(root.resolve("real"))
    Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"))

    env.writeFile(toolContext, "alias/new.txt", "written".encodeToByteArray()).getOrThrow()

    assertEquals("written", Files.readString(root.resolve("real").resolve("new.txt")))
  }

  @Test
  fun readFile_symlinkChainWithinWorkingDir_succeeds(): Unit = runBlocking {
    // A chain of links that never leaves the workspace must resolve, not be rejected.
    Files.createDirectories(root.resolve("real"))
    Files.writeString(root.resolve("real").resolve("note.txt"), "chained")
    Files.createSymbolicLink(root.resolve("mid"), root.resolve("real"))
    Files.createSymbolicLink(root.resolve("head"), root.resolve("mid"))

    val result = env.readFile(toolContext, "head/note.txt")

    assertEquals("chained", result.getOrThrow().decodeToString())
  }

  @Test
  fun readFile_absolutePathOutsideWorkingDir_returnsFailure(): Unit = runBlocking {
    val outside = root.resolveSibling("outside-absolute.txt")
    Files.write(outside, "secret".encodeToByteArray())
    try {
      val result = env.readFile(toolContext, outside.toString())

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is EnvironmentException)
    } finally {
      Files.deleteIfExists(outside)
    }
  }

  @Test
  fun emptyWorkingDir_treatedAsUnspecified_createsTempDir(): Unit = runBlocking {
    val empty = LocalEnvironment("")
    empty.initialize(readonlyContext)

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
    env.initialize(readonlyContext)

    assertEquals(root.toString(), env.workingDir)
  }

  @Test
  fun initialize_concurrentCalls_createASingleWorkspace(): Unit = runBlocking {
    // Tool calls in one turn run in parallel, so initialize() can be entered twice at once.
    val concurrent = LocalEnvironment()
    try {
      val dirs =
        (1..8)
          .map {
            async(Dispatchers.Default) {
              concurrent.initialize(readonlyContext)
              concurrent.workingDir
            }
          }
          .awaitAll()

      // A losing initialize() would create a second workspace that nothing can reach or delete.
      assertEquals(1, dirs.toSet().size, "each caller should see the same workspace: $dirs")
    } finally {
      concurrent.close()
    }
  }

  @Test
  fun initialize_repeatedSequentialCall_reusesSameDir(): Unit = runBlocking {
    val env = LocalEnvironment()
    env.initialize(readonlyContext)
    val first = env.workingDir

    env.initialize(readonlyContext) // second call reuses the existing dir, not a new temp dir

    assertEquals(first, env.workingDir)
  }

  @Test
  fun autoTempDir_createdOnInitialize_removedOnClose(): Unit = runBlocking {
    val auto = LocalEnvironment()
    auto.initialize(readonlyContext)

    val dir = auto.workingDir
    assertTrue(Files.isDirectory(Path.of(dir)))
    auto.writeFile(toolContext, "f.txt", "x".encodeToByteArray()).getOrThrow()

    auto.close()
    assertFalse(Files.exists(Path.of(dir)))
  }

  @Test
  fun providedWorkingDir_isKeptOnClose(): Unit = runBlocking {
    val provided = Files.createTempDirectory("adk-local-env-provided")
    val env = LocalEnvironment(provided.toString())
    env.initialize(readonlyContext)
    env.writeFile(toolContext, "f.txt", "x".encodeToByteArray()).getOrThrow()

    env.close()

    assertTrue(Files.exists(provided), "a caller-owned workspace must not be removed")
    assertTrue(Files.exists(provided.resolve("f.txt")))
    Files.deleteIfExists(provided.resolve("f.txt"))
    Files.deleteIfExists(provided)
  }
}
