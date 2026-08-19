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
import com.google.adk.kt.logging.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
 * Commands run via `/bin/sh -c` with the process environment optionally augmented by [envVars]; on
 * timeout the process and its descendants are force-killed.
 *
 * This backend provides **no sandboxing** — commands run with the host's privileges. Use a
 * sandboxed or remote environment for untrusted workloads.
 *
 * Unsynchronized, mirroring the reference implementation: [execute] runs as a subprocess and
 * [readFile]/[writeFile] run on a background thread, so operations may run in parallel but the
 * environment provides no isolation between them. Overlapping calls that touch the same files (a
 * [writeFile] during an [execute], or two concurrent writes) race, so ordering is the caller's
 * responsibility. Call [initialize] once before first use and [close] once after last use.
 *
 * @param workingDir Absolute path to the workspace directory. If `null`, a temporary directory is
 *   created during [initialize].
 * @param envVars Extra environment variables merged into the subprocess environment.
 */
@ExperimentalEnvironmentApi
class LocalEnvironment(
  workingDir: String? = null,
  private val envVars: Map<String, String>? = null,
) : BaseEnvironment {

  private var resolvedWorkingDir: String? = workingDir
  private var autoCreated: Boolean = false
  private val logger = LoggerFactory.getLogger(LocalEnvironment::class)

  override val workingDir: String
    get() = resolvedWorkingDir ?: error("`workingDir` is not set. Call initialize() first.")

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun initialize() {
    withContext(Dispatchers.IO) {
      try {
        val current = resolvedWorkingDir
        if (current.isNullOrEmpty()) {
          resolvedWorkingDir = Files.createTempDirectory("adk_workspace_").toString()
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

  @Suppress("GlobalCoroutineDispatchers") // Blocking java.io must run off the caller's thread.
  override suspend fun close() {
    val dir = resolvedWorkingDir
    if (autoCreated && dir != null) {
      withContext(Dispatchers.IO) { Paths.get(dir).toFile().deleteRecursively() }
      logger.debug { "Removed temporary workspace: $dir" }
      resolvedWorkingDir = null
      autoCreated = false
    }
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun execute(command: String, timeout: Duration?): Result<ExecutionResult> {
    val dir = Paths.get(workingDir)
    val stdoutFile: Path
    val stderrFile: Path
    try {
      stdoutFile = Files.createTempFile("adk-exec-out", ".log")
      stderrFile = Files.createTempFile("adk-exec-err", ".log")
    } catch (e: IOException) {
      return Result.failure(EnvironmentException("Failed to prepare command execution.", e))
    }

    return try {
      val result =
        runInterruptible(Dispatchers.IO) {
          val builder = ProcessBuilder("/bin/sh", "-c", command).directory(dir.toFile())
          builder.redirectOutput(stdoutFile.toFile())
          builder.redirectError(stderrFile.toFile())
          envVars?.let { builder.environment().putAll(it) }

          val process =
            try {
              builder.start()
            } catch (e: IOException) {
              throw EnvironmentException("Failed to start command.", e)
            }

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
            // Kill the whole process tree so background children are not orphaned.
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            val unused = process.waitFor()
          }

          ExecutionResult(
            exitCode = process.exitValue(),
            stdout = Files.readAllBytes(stdoutFile).decodeToString(),
            stderr = Files.readAllBytes(stderrFile).decodeToString(),
            timedOut = !finished,
          )
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
  override suspend fun readFile(path: String): Result<ByteArray> =
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
  override suspend fun writeFile(path: String, content: ByteArray): Result<Unit> =
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

  /** Resolves [path] against [workingDir] and rejects paths that escape it. */
  private fun resolve(path: String): Path {
    val root = Paths.get(workingDir).toAbsolutePath().normalize()
    val candidate = Paths.get(path)
    val resolved =
      (if (candidate.isAbsolute) candidate else root.resolve(candidate))
        .toAbsolutePath()
        .normalize()
    if (resolved != root && !resolved.startsWith(root)) {
      throw EnvironmentException("Path escapes working directory: $path")
    }
    return resolved
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
}
