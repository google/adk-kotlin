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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.common.annotations.VisibleForTesting
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * An [Environment] that runs commands in local `/bin/sh` subprocesses and reads and writes files in
 * one workspace directory shared by every caller, so it ignores the per-call [Context].
 *
 * Without a [workingDir], [initialize] creates a temporary directory that [close] deletes; a given
 * directory is created if missing and never deleted. There is no sandboxing: commands run with the
 * host's privileges and environment variables (filtered by [inheritedEnvVarAllowlist], with
 * [envVars] on top), and on timeout or cancellation they get SIGTERM, then SIGKILL.
 *
 * @param workingDir Absolute path to the workspace directory. If `null` or empty, a temporary
 *   directory is created during [initialize].
 * @param envVars Extra environment variables for every command, applied after the allowlist.
 * @param inheritedEnvVarAllowlist Names of the host's environment variables commands may inherit;
 *   `null` inherits all of them. `PATH` is always inherited, so commands find the host's programs.
 */
@ExperimentalEnvironmentApi
class LocalEnvironment
@JvmOverloads
constructor(
  private val workingDir: String? = null,
  private val envVars: Map<String, String> = emptyMap(),
  private val inheritedEnvVarAllowlist: Set<String>? = null,
) : Environment {

  /** Guards [resolvedWorkingDir], which [initialize] and [close] both write. */
  private val lifecycleLock = Any()

  /** The workspace, set by [initialize] and cleared by [close]; any other use is rejected. */
  private var resolvedWorkingDir: Path? = null
  private val logger = LoggerFactory.getLogger(LocalEnvironment::class)

  /** Returns the workspace; throws [IllegalStateException] before [initialize] or after [close]. */
  @VisibleForTesting
  internal fun requireWorkingDir(): Path =
    synchronized(lifecycleLock) {
      checkNotNull(resolvedWorkingDir) { "`workingDir` is not set. Call initialize() first." }
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun initialize() {
    withContext(Dispatchers.IO) {
      synchronized(lifecycleLock) {
        if (resolvedWorkingDir != null) return@synchronized
        try {
          if (workingDir.isNullOrEmpty()) {
            resolvedWorkingDir = Files.createTempDirectory(WORKSPACE_PREFIX)
            logger.debug { "Created temporary workspace: $resolvedWorkingDir" }
          } else {
            resolvedWorkingDir = Files.createDirectories(Paths.get(workingDir))
          }
        } catch (e: IOException) {
          throw EnvironmentException("Failed to initialize working directory.", e)
        } catch (e: InvalidPathException) {
          throw EnvironmentException("Failed to initialize working directory.", e)
        }
      }
    }
  }

  override fun close() {
    synchronized(lifecycleLock) {
      val dir = resolvedWorkingDir ?: return
      if (workingDir.isNullOrEmpty()) deleteWorkspace(dir)
      resolvedWorkingDir = null
    }
  }

  /**
   * Deletes [dir] without following symbolic links, so a link a command left behind is unlinked and
   * its target kept. Best-effort, since [close] runs during teardown: entries that cannot be
   * deleted are counted and logged, not thrown.
   */
  private fun deleteWorkspace(dir: Path) {
    var failures = 0
    val deleter =
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes) = delete(file)

        override fun visitFileFailed(file: Path, exc: IOException) = delete(file)

        override fun postVisitDirectory(directory: Path, exc: IOException?) = delete(directory)

        private fun delete(path: Path): FileVisitResult {
          try {
            Files.deleteIfExists(path)
          } catch (_: IOException) {
            failures++
          }
          return FileVisitResult.CONTINUE
        }
      }
    try {
      Files.walkFileTree(dir, deleter)
    } catch (_: IOException) {
      failures++
    }
    if (failures == 0) {
      logger.debug { "Removed temporary workspace: $dir" }
    } else {
      logger.warn { "Could not remove $failures entries of temporary workspace $dir." }
    }
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun execute(
    context: Context,
    command: String,
    timeout: Duration?,
  ): Result<ExecutionResult> {
    val dir = requireWorkingDir()
    return runCatchingEnv { runInterruptible(Dispatchers.IO) { runCommand(command, dir, timeout) } }
  }

  /** Runs [command] in [dir], capturing its output in temporary files that are always deleted. */
  private fun runCommand(command: String, dir: Path, timeout: Duration?): ExecutionResult {
    val stdoutFile = createOutputFile("adk-exec-out")
    try {
      val stderrFile = createOutputFile("adk-exec-err")
      try {
        return runProcess(command, dir, timeout, stdoutFile, stderrFile)
      } finally {
        deleteQuietly(stderrFile)
      }
    } finally {
      deleteQuietly(stdoutFile)
    }
  }

  private fun runProcess(
    command: String,
    dir: Path,
    timeout: Duration?,
    stdoutFile: Path,
    stderrFile: Path,
  ): ExecutionResult {
    val builder =
      ProcessBuilder("/bin/sh", "-c", command)
        .directory(dir.toFile())
        .redirectOutput(stdoutFile.toFile())
        .redirectError(stderrFile.toFile())
    val environment = builder.environment()
    if (inheritedEnvVarAllowlist != null) {
      environment.keys.retainAll(inheritedEnvVarAllowlist + "PATH")
    }
    environment.putAll(envVars)

    val process =
      try {
        builder.start()
      } catch (e: IOException) {
        throw EnvironmentException("Failed to start command.", e)
      }
    try {
      // Close stdin so commands that read from it do not hang.
      try {
        process.outputStream.close()
      } catch (_: IOException) {}

      val finished =
        if (timeout != null) {
          process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } else {
          val unused = process.waitFor()
          true
        }
      if (!finished) {
        killProcessTree(process)
        val unused = process.waitFor()
      }
      return ExecutionResult(
        exitCode = process.exitValue(),
        stdout = readOutput(stdoutFile),
        stderr = readOutput(stderrFile),
        timedOut = !finished,
      )
    } finally {
      // Cancellation interrupts waitFor(); stop the command instead of orphaning it.
      if (process.isAlive) killProcessTree(process)
    }
  }

  /**
   * Sends SIGTERM to [process] and its descendants, then SIGKILL to those still alive after
   * [TERMINATE_GRACE].
   *
   * Best-effort: the descendants are collected again before SIGKILL, but a child started after
   * that, or one that left the tree with `setsid`, can survive; Python ADK closes that gap by
   * signaling a process group, which the JVM cannot do.
   */
  private fun killProcessTree(process: Process) {
    val tree = process.descendants().toList() + process.toHandle()
    tree.forEach { it.destroy() }
    // The shell exits on SIGTERM at once; the command it runs may still be cleaning up.
    val deadline = TimeSource.Monotonic.markNow() + TERMINATE_GRACE
    try {
      while (tree.any { it.isAlive } && deadline.hasNotPassedNow()) {
        Thread.sleep(EXIT_POLL.inWholeMilliseconds)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt() // Escalate now and keep the interrupt visible.
    }
    // Children started during the grace period are not in the first snapshot.
    (tree + tree.flatMap { it.descendants().toList() })
      .distinct()
      .filter { it.isAlive }
      .forEach { it.destroyForcibly() }
  }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun readFile(context: Context, path: String): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatchingEnv {
        val target = resolve(path)
        if (!Files.exists(target)) throw EnvironmentException("File not found.")
        if (!Files.isRegularFile(target)) throw EnvironmentException("Not a regular file.")
        try {
          Files.readAllBytes(target)
        } catch (e: IOException) {
          throw EnvironmentException("Failed to read file.", e)
        }
      }
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun writeFile(context: Context, path: String, content: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatchingEnv {
        val target = resolve(path)
        try {
          target.parent?.let { Files.createDirectories(it) }
          Files.write(target, content)
          Unit
        } catch (e: IOException) {
          throw EnvironmentException("Failed to write file.", e)
        }
      }
    }

  /**
   * Resolves [path] against the workspace and rejects paths that escape it, following symbolic
   * links on the part of the path that exists (a [writeFile] target may not exist yet).
   *
   * This keeps file operations from leaving the workspace by accident; it is not a security
   * boundary, since [execute] runs an unsandboxed shell.
   */
  private fun resolve(path: String): Path {
    val root =
      try {
        requireWorkingDir().toRealPath()
      } catch (e: IOException) {
        throw EnvironmentException("Failed to resolve the working directory.", e)
      }
    val candidate =
      try {
        Paths.get(path)
      } catch (e: InvalidPathException) {
        throw EnvironmentException("Invalid path.", e)
      }
    // `.` and `..` collapsed, symbolic links not yet followed.
    val normalizedPath =
      (if (candidate.isAbsolute) candidate else root.resolve(candidate)).normalize()
    // The filesystem root always exists, so this always finds an ancestor.
    val existing =
      generateSequence(normalizedPath) { it.parent }
        .first { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    val resolved =
      try {
        existing.toRealPath().resolve(existing.relativize(normalizedPath))
      } catch (e: IOException) {
        throw EnvironmentException("Failed to resolve path.", e)
      }
    if (!resolved.startsWith(root)) throw EnvironmentException("Path escapes working directory.")
    return resolved
  }

  private fun createOutputFile(prefix: String): Path =
    try {
      Files.createTempFile(prefix, ".log")
    } catch (e: IOException) {
      throw EnvironmentException("Failed to prepare command execution.", e)
    }

  private fun readOutput(file: Path): String =
    try {
      Files.readAllBytes(file).decodeToString()
    } catch (e: IOException) {
      throw EnvironmentException("Failed to read command output.", e)
    }

  private fun deleteQuietly(file: Path) {
    try {
      Files.deleteIfExists(file)
    } catch (_: IOException) {}
  }

  /** Runs [block], returning an [EnvironmentException] it throws as a [Result.failure]. */
  private inline fun <T> runCatchingEnv(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: EnvironmentException) {
      Result.failure(e)
    }

  /**
   * Fluent builder for [LocalEnvironment], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var workingDir: String? = null
    private var envVars: Map<String, String> = emptyMap()
    private var inheritedEnvVarAllowlist: Set<String>? = null

    fun workingDir(workingDir: String?): Builder = apply { this.workingDir = workingDir }

    fun envVars(envVars: Map<String, String>): Builder = apply { this.envVars = envVars }

    fun inheritedEnvVarAllowlist(inheritedEnvVarAllowlist: Set<String>?): Builder = apply {
      this.inheritedEnvVarAllowlist = inheritedEnvVarAllowlist
    }

    fun build(): LocalEnvironment =
      LocalEnvironment(
        workingDir = workingDir,
        envVars = envVars,
        inheritedEnvVarAllowlist = inheritedEnvVarAllowlist,
      )
  }

  companion object {
    /** Name prefix of the temporary workspace [initialize] creates without a [workingDir]. */
    private const val WORKSPACE_PREFIX = "adk_workspace_"

    /** How long a stopped command and its descendants get to exit after SIGTERM. */
    private val TERMINATE_GRACE = 5.seconds

    /** How often [killProcessTree] checks whether the stopped processes have exited. */
    private val EXIT_POLL = 50.milliseconds

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
