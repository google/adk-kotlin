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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.ToolContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Execute commands via local subprocesses.
 *
 * When [workingDir] is not specified, a temporary directory is created on [initialize] and removed
 * on [close]; otherwise the caller owns the directory (it is created if missing but never deleted).
 * Commands run via `/bin/sh -c`; the subprocess inherits the JVM environment (optionally restricted
 * to an allowlist by [inheritedEnvVarAllowlist]) with [envVars] layered on top; on timeout or
 * cancellation the process and its descendants are force-killed.
 *
 * This backend provides **no sandboxing** — commands run with the host's privileges. Use a
 * sandboxed or remote environment for untrusted workloads.
 *
 * This backend is single-tenant: it serves one workspace and ignores the per-call [ReadonlyContext]
 * and [ToolContext] that the [Environment] interface passes for multi-tenant backends.
 *
 * [initialize] and [close] may be called concurrently: they are serialized, so the workspace is
 * created once and removed once.
 *
 * The file operations are not synchronized with each other, mirroring the reference implementation:
 * [execute] runs as a subprocess and [readFile]/[writeFile] run on a background thread, so they may
 * run in parallel with no isolation between them. Overlapping calls that touch the same files (a
 * [writeFile] during an [execute], or two concurrent writes) race, so ordering is the caller's
 * responsibility.
 *
 * @param workingDir Absolute path to the workspace directory. If `null`, a temporary directory is
 *   created during [initialize].
 * @param envVars Extra environment variables; these always take effect regardless of
 *   [inheritedEnvVarAllowlist].
 * @param inheritedEnvVarAllowlist Names of the JVM's environment variables the subprocess may
 *   inherit. `null` (the default) applies no allowlist, so the full host environment is inherited.
 *   A non-null set restricts inheritance to those names (an empty set inherits nothing). Keep the
 *   variables ordinary tooling needs (at least `PATH`, usually also `HOME`, `TMPDIR`, and `LANG`)
 *   or commands will break.
 */
@ExperimentalEnvironmentApi
class LocalEnvironment(
  workingDir: String? = null,
  private val envVars: Map<String, String> = emptyMap(),
  private val inheritedEnvVarAllowlist: Set<String>? = null,
) : Environment {

  /**
   * Guards [resolvedWorkingDir] and [autoCreated], which [initialize] and [close] both write. Used
   * with a `synchronized` block to ensure only one thread at a time can modify the values.
   */
  private val lifecycleLock = Any()

  private var resolvedWorkingDir: String? = workingDir
  private var autoCreated: Boolean = false
  private val logger = LoggerFactory.getLogger(LocalEnvironment::class)

  /**
   * The workspace directory, resolved once [initialize] has run. This backend's own property, not
   * an [Environment] member: a working directory is a single-tenant concept the interface omits.
   */
  internal val workingDir: String
    get() =
      synchronized(lifecycleLock) {
        resolvedWorkingDir ?: error("`workingDir` is not set. Call initialize() first.")
      }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun initialize(context: ReadonlyContext?) {
    withContext(Dispatchers.IO) {
      synchronized(lifecycleLock) {
        try {
          val current = resolvedWorkingDir
          if (current.isNullOrEmpty()) {
            resolvedWorkingDir = Files.createTempDirectory(WORKSPACE_PREFIX).toString()
            autoCreated = true
            logger.debug { "Created temporary folder: $resolvedWorkingDir" }
          } else {
            Files.createDirectories(Paths.get(current))
          }
        } catch (e: IOException) {
          throw EnvironmentException("Failed to initialize working directory.", e)
        }
      }
    }
  }

  override fun close() {
    synchronized(lifecycleLock) {
      val dir = resolvedWorkingDir
      if (autoCreated && dir != null) {
        if (deleteWorkspace(Paths.get(dir))) {
          logger.debug { "Removed temporary workspace: $dir" }
        }
        resolvedWorkingDir = null
        autoCreated = false
      }
    }
  }

  /**
   * Deletes [dir] and everything below it, without following symbolic links.
   *
   * A command run in the workspace can leave a link to a directory elsewhere behind. Walking such a
   * link would delete the files it points at, so the walk treats a link as a file and unlinks it,
   * which leaves whatever it pointed at untouched.
   *
   * Deletion is best-effort: [close] is called while tearing things down, so a file that cannot be
   * removed is logged rather than raised.
   *
   * @return `true` if the tree was fully removed, `false` if deletion failed (the failure is logged
   *   rather than thrown).
   */
  private fun deleteWorkspace(dir: Path): Boolean {
    return try {
      Files.walkFileTree(
        dir,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            Files.deleteIfExists(directory)
            return FileVisitResult.CONTINUE
          }
        },
      )
      true
    } catch (e: IOException) {
      logger.warn(e) { "Failed to remove temporary workspace: $dir" }
      false
    }
  }

  /**
   * Prepares a shell running [command] in [dir], with its output going to [stdoutFile] and
   * [stderrFile].
   *
   * The command inherits the JVM's environment, restricted to [inheritedEnvVarAllowlist] when one
   * is set, with [envVars] layered on top.
   */
  private fun newShellProcess(
    command: String,
    dir: Path,
    stdoutFile: Path,
    stderrFile: Path,
  ): ProcessBuilder {
    val builder =
      ProcessBuilder("/bin/sh", "-c", command)
        .directory(dir.toFile())
        .redirectOutput(stdoutFile.toFile())
        .redirectError(stderrFile.toFile())
    // Start from the inherited JVM environment, restrict it to the allowlist when one is set, then
    // layer envVars on top so explicitly provided values always take effect.
    val environment = builder.environment()
    inheritedEnvVarAllowlist?.let { environment.keys.retainAll(it) }
    environment.putAll(envVars)
    return builder
  }

  /**
   * Force-kills [process] and its descendants.
   *
   * Best-effort: `descendants()` is a point-in-time snapshot and `destroyForcibly` is asynchronous,
   * so a child spawned right after the snapshot — or one that detached into its own session via
   * `setsid`/double-fork — can survive. Closing that gap needs a process-group kill, which this
   * unsandboxed backend does not attempt.
   */
  private fun killProcessTree(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun execute(
    context: ToolContext,
    command: String,
    timeout: Duration?,
  ): Result<ExecutionResult> {
    val dir = Paths.get(workingDir)
    val stdoutFile: Path
    val stderrFile: Path
    try {
      stdoutFile = Files.createTempFile(STDOUT_PREFIX, LOG_SUFFIX)
      stderrFile = Files.createTempFile(STDERR_PREFIX, LOG_SUFFIX)
    } catch (e: IOException) {
      return Result.failure(EnvironmentException("Failed to prepare command execution.", e))
    }

    return try {
      val result =
        runInterruptible(Dispatchers.IO) {
          val builder = newShellProcess(command, dir, stdoutFile, stderrFile)

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
              // Command exceeded its timeout.
              killProcessTree(process)
              val unused = process.waitFor()
            }

            ExecutionResult(
              exitCode = process.exitValue(),
              stdout = Files.readAllBytes(stdoutFile).decodeToString(),
              stderr = Files.readAllBytes(stderrFile).decodeToString(),
              timedOut = !finished,
            )
          } finally {
            // Coroutine cancellation interrupts waitFor(); kill the tree so the process is not
            // orphaned and its still-open output files are not unlinked from under it. No-op on
            // the normal and timeout paths, where the process has already exited.
            if (process.isAlive) {
              killProcessTree(process)
            }
          }
        }
      Result.success(result)
    } catch (e: EnvironmentException) {
      Result.failure(e)
    } finally {
      try {
        Files.deleteIfExists(stdoutFile)
        Files.deleteIfExists(stderrFile)
      } catch (_: IOException) {}
    }
  }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun readFile(context: ToolContext, path: String): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatchingEnv {
        val target = resolve(path)
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
          throw EnvironmentException("File not found: $path")
        }
        try {
          Files.readAllBytes(target)
        } catch (e: IOException) {
          throw EnvironmentException("Failed to read file: $path", e)
        }
      }
    }

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun writeFile(
    context: ToolContext,
    path: String,
    content: ByteArray,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatchingEnv {
        val target = resolve(path)
        try {
          target.parent?.let { Files.createDirectories(it) }
          Files.write(target, content)
          Unit
        } catch (e: IOException) {
          throw EnvironmentException("Failed to write file: $path", e)
        }
      }
    }

  /**
   * Resolves [path] against [workingDir] and rejects paths that escape it.
   *
   * Symbolic links are followed before the check, so a link inside the working directory cannot be
   * used to reach a file outside it. The link is followed on the part of the path that exists,
   * since [writeFile] is given the path of a file that is not there yet.
   *
   * This keeps a command from wandering out of its workspace by accident; it is not a security
   * boundary. [execute] runs an unsandboxed shell, which can reach the rest of the filesystem.
   */
  private fun resolve(path: String): Path {
    val root =
      try {
        Paths.get(workingDir).toRealPath()
      } catch (e: IOException) {
        throw EnvironmentException("Failed to resolve the working directory.", e)
      } catch (e: InvalidPathException) {
        throw EnvironmentException("Failed to resolve the working directory.", e)
      }
    // A model-supplied path with an illegal character (e.g. a NUL byte) makes Paths.get throw
    // InvalidPathException; wrap it so it surfaces as a tool error, not an uncaught invocation
    // abort.
    val candidate =
      try {
        Paths.get(path)
      } catch (e: InvalidPathException) {
        throw EnvironmentException("Invalid path: $path", e)
      }
    val lexical = (if (candidate.isAbsolute) candidate else root.resolve(candidate)).normalize()

    // toRealPath() requires the path to exist, but writeFile targets a file that does not yet. So
    // resolve links on the part that exists, then re-append the not-yet-created remainder.
    val ancestor = deepestExistingAncestor(lexical, path)
    val resolved =
      try {
        ancestor.toRealPath().resolve(ancestor.relativize(lexical))
      } catch (e: IOException) {
        throw EnvironmentException("Failed to resolve path: $path", e)
      }

    // `Path.startsWith` is reflexive, so this also allows the working directory root itself.
    if (!resolved.startsWith(root)) {
      throw EnvironmentException("Path escapes working directory: $path")
    }
    return resolved
  }

  /**
   * Returns the deepest ancestor of [lexical] (including itself) that exists on disk, without
   * following links. That is the longest prefix whose links [Path.toRealPath] can resolve; the
   * remainder does not exist yet (e.g. a [writeFile] to a new path).
   *
   * @throws EnvironmentException if no ancestor exists, which means [originalPath] climbed past the
   *   filesystem root and so escapes the working directory.
   */
  private fun deepestExistingAncestor(lexical: Path, originalPath: String): Path {
    var existing: Path = lexical
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing =
        existing.parent
          ?: throw EnvironmentException("Path escapes working directory: $originalPath")
    }
    return existing
  }

  /**
   * Runs [block], wrapping the sole sanctioned [EnvironmentException] failure in [Result.failure].
   */
  private inline fun <T> runCatchingEnv(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: EnvironmentException) {
      Result.failure(e)
    }

  private companion object {
    /** Prefix for the auto-created temporary workspace directory. */
    const val WORKSPACE_PREFIX = "adk_workspace_"

    /** Prefixes and suffix for the temporary files capturing a command's stdout and stderr. */
    const val STDOUT_PREFIX = "adk-exec-out"
    const val STDERR_PREFIX = "adk-exec-err"
    const val LOG_SUFFIX = ".log"
  }
}
