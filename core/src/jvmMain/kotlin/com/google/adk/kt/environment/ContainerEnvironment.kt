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
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Execute commands inside a long-lived Docker container, driving the `docker` CLI as a subprocess.
 *
 * A single container is created on [initialize] (`docker run -d ... sleep infinity`) and reused for
 * every call, so the working directory and any files written to it persist across [execute],
 * [readFile], and [writeFile]. [close] force-removes it. Every operation shells out to the `docker`
 * binary rather than a Docker client library, mirroring [LocalEnvironment]'s `ProcessBuilder`
 * approach, so the only runtime dependency is a reachable `docker` CLI and daemon. [readFile]
 * streams bytes out with `cat`; [writeFile] stages the content in a host temp file and copies it in
 * with `docker cp`, which transfers by path rather than through the process's stdin.
 *
 * Commands run via `docker exec ... /bin/sh -c`, so the image only needs `/bin/sh`; any image
 * `ENTRYPOINT` is bypassed. The container is the isolation boundary: unlike [LocalEnvironment] this
 * backend does not guard against paths escaping [workingDir], because a path only ever reaches the
 * container's own filesystem, not the host's.
 *
 * This backend is single-tenant: it serves one container and ignores the per-call [ReadonlyContext]
 * and [ToolContext] that the [Environment] interface passes for multi-tenant backends.
 *
 * [initialize] and [close] may be called concurrently: they are serialized on [lifecycleLock], so
 * the container is created once and removed once. The file operations are not synchronized with
 * each other or with [execute]: overlapping calls that touch the same files race, so ordering is
 * the caller's responsibility.
 *
 * @param image The Docker image to run (e.g. `python:3.11-slim`).
 * @param workingDir The working directory inside the container; created by `docker run -w` and used
 *   as the `docker exec` working directory, so relative [readFile]/[writeFile] paths resolve there.
 * @param pullImage Whether [initialize] runs `docker pull` before creating the container. Leave
 *   `true` to fetch the image on demand; set `false` when the image is already present locally.
 * @param envVars Extra environment variables passed to each `docker exec` via `-e`.
 * @param dockerBinary The `docker` CLI binary to invoke; override to point at a wrapper or an
 *   absolute path.
 */
@ExperimentalEnvironmentApi
class ContainerEnvironment
internal constructor(
  private val image: String,
  private val workingDir: String,
  private val pullImage: Boolean,
  private val envVars: Map<String, String>,
  private val dockerBinary: String,
  private val runner: CommandRunner,
) : Environment {

  /**
   * Public constructor that drives the real `docker` CLI. The internal primary constructor exists
   * so tests can inject a fake [CommandRunner] and avoid needing a Docker daemon.
   */
  constructor(
    image: String,
    workingDir: String = DEFAULT_WORKING_DIR,
    pullImage: Boolean = true,
    envVars: Map<String, String> = emptyMap(),
    dockerBinary: String = DEFAULT_DOCKER_BINARY,
  ) : this(image, workingDir, pullImage, envVars, dockerBinary, ProcessCommandRunner())

  /** Guards [resolvedContainerId], which [initialize] and [close] both write. */
  private val lifecycleLock = Any()

  private var resolvedContainerId: String? = null
  private val logger = LoggerFactory.getLogger(ContainerEnvironment::class)

  /**
   * The id of the running container, resolved once [initialize] has run. This backend's own
   * property, not an [Environment] member: a single shared container is a single-tenant concept the
   * interface omits.
   */
  internal val containerId: String
    get() =
      synchronized(lifecycleLock) {
        resolvedContainerId ?: error("Container is not started. Call initialize() first.")
      }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun initialize(context: ReadonlyContext?) {
    withContext(Dispatchers.IO) {
      synchronized(lifecycleLock) {
        // Idempotent: a container already exists, so nothing to do. Serialized on lifecycleLock so
        // concurrent callers create exactly one container.
        if (resolvedContainerId != null) return@synchronized

        if (pullImage) {
          val unused = runStep(listOf(dockerBinary, "pull", image), "Failed to pull image.")
        }

        val run =
          runStep(
            listOf(dockerBinary, "run", "-d", "-w", workingDir, image, "sleep", "infinity"),
            "Failed to start container.",
          )
        val id = run.stdout.decodeToString().trim()
        if (id.isEmpty()) throw EnvironmentException("Failed to start container.")
        resolvedContainerId = id
        logger.debug { "Started container: $id" }
      }
    }
  }

  override fun close() {
    synchronized(lifecycleLock) {
      val id = resolvedContainerId ?: return
      // Best-effort teardown: close() runs while tearing things down, so any failure to remove the
      // container is logged rather than raised, and the id is always cleared (in `finally`) so
      // close() stays idempotent. A bounded timeout keeps a wedged daemon from hanging close().
      try {
        val result = runner.run(listOf(dockerBinary, "rm", "-f", id), timeout = CLOSE_TIMEOUT)
        if (result.exitCode == 0) {
          logger.debug { "Removed container: $id" }
        } else {
          logger.warn { "Failed to remove container: $id" }
        }
      } catch (e: IOException) {
        logger.warn(e) { "Failed to remove container: $id" }
      } catch (e: RuntimeException) {
        logger.warn(e) { "Failed to remove container: $id" }
      } finally {
        resolvedContainerId = null
      }
    }
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun execute(
    context: ToolContext,
    command: String,
    timeout: Duration?,
  ): Result<ExecutionResult> {
    val argv = buildExecCommand(containerId, command)
    return try {
      val result = runInterruptible(Dispatchers.IO) { runner.run(argv, timeout) }
      Result.success(
        ExecutionResult(
          exitCode = result.exitCode,
          stdout = result.stdout.decodeToString(),
          stderr = result.stderr.decodeToString(),
          timedOut = result.timedOut,
        )
      )
    } catch (e: IOException) {
      Result.failure(EnvironmentException("Failed to start command.", e))
    }
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun readFile(context: ToolContext, path: String): Result<ByteArray> {
    val argv = buildShellCommand(containerId, "cat -- ${shellQuote(path)}")
    return try {
      val result = runInterruptible(Dispatchers.IO) { runner.run(argv, timeout = null) }
      if (result.exitCode != 0) {
        Result.failure(EnvironmentException("File not found: $path"))
      } else {
        Result.success(result.stdout)
      }
    } catch (e: IOException) {
      Result.failure(EnvironmentException("Failed to read file: $path", e))
    }
  }

  @Suppress(
    "GlobalCoroutineDispatchers"
  ) // Blocking subprocess I/O must run off the caller's thread.
  override suspend fun writeFile(
    context: ToolContext,
    path: String,
    content: ByteArray,
  ): Result<Unit> {
    // `docker cp` transfers by path, not by stream, so stage the bytes in a host temp file and copy
    // that in. Binary content is preserved verbatim and there is no argv size limit; the cost is a
    // short-lived host file. Resolve to an absolute container path so `docker cp` has an
    // unambiguous destination regardless of how it treats relative paths.
    val containerPath = if (path.startsWith("/")) path else "$workingDir/$path"
    val id = containerId
    return try {
      runInterruptible(Dispatchers.IO) {
        val staged = Files.createTempFile(WRITE_STAGE_PREFIX, WRITE_STAGE_SUFFIX)
        try {
          Files.write(staged, content)

          // `docker cp` does not create missing parent directories, so create them first.
          val mkdir =
            runner.run(
              buildShellCommand(id, "mkdir -p \"\$(dirname -- ${shellQuote(containerPath)})\""),
              timeout = null,
            )
          if (mkdir.exitCode != 0) {
            return@runInterruptible Result.failure(
              EnvironmentException("Failed to write file: $path")
            )
          }

          val copy =
            runner.run(
              listOf(dockerBinary, "cp", staged.toString(), "$id:$containerPath"),
              timeout = null,
            )
          if (copy.exitCode != 0) {
            Result.failure(EnvironmentException("Failed to write file: $path"))
          } else {
            Result.success(Unit)
          }
        } finally {
          try {
            Files.deleteIfExists(staged)
          } catch (_: IOException) {}
        }
      }
    } catch (e: IOException) {
      Result.failure(EnvironmentException("Failed to write file: $path", e))
    }
  }

  /** Builds the `docker exec` argv that runs [command] via `/bin/sh -c`, layering in [envVars]. */
  private fun buildExecCommand(id: String, command: String): List<String> = buildList {
    add(dockerBinary)
    add("exec")
    add("-w")
    add(workingDir)
    for ((key, value) in envVars) {
      add("-e")
      add("$key=$value")
    }
    add(id)
    add("/bin/sh")
    add("-c")
    add(command)
  }

  /**
   * Builds a `docker exec ... /bin/sh -c <script>` argv for the file operations. Never passes `-t`
   * (a pseudo-TTY): without one, stdout stays byte-exact, which is load-bearing for binary
   * [readFile].
   */
  private fun buildShellCommand(id: String, script: String): List<String> = buildList {
    add(dockerBinary)
    add("exec")
    add("-w")
    add(workingDir)
    add(id)
    add("/bin/sh")
    add("-c")
    add(script)
  }

  /**
   * Runs a lifecycle [command] with no stdin or timeout, translating both a launch failure and a
   * non-zero exit into an [EnvironmentException] carrying [message]. Returns the result on success.
   */
  private fun runStep(command: List<String>, message: String): CommandResult {
    val result =
      try {
        runner.run(command, timeout = null)
      } catch (e: IOException) {
        throw EnvironmentException(message, e)
      }
    if (result.exitCode != 0) throw EnvironmentException(message)
    return result
  }

  private companion object {
    /** Default working directory created inside the container. */
    const val DEFAULT_WORKING_DIR = "/workspace"

    /** Default `docker` CLI binary. */
    const val DEFAULT_DOCKER_BINARY = "docker"

    /** Name parts for the host temp file that [writeFile] stages before `docker cp`. */
    const val WRITE_STAGE_PREFIX = "adk-docker-write"
    const val WRITE_STAGE_SUFFIX = ".bin"

    /** Bounded timeout for the best-effort `docker rm -f` in [close]. */
    val CLOSE_TIMEOUT = 30.seconds

    /**
     * Wraps [value] in single quotes for safe interpolation into a `/bin/sh -c` script, escaping
     * any embedded single quotes. A model-supplied path is the only thing interpolated this way.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  }
}

/**
 * The raw outcome of a single subprocess: its exit code, the bytes captured from stdout and stderr,
 * and whether it was killed for exceeding its timeout.
 */
internal class CommandResult(
  val exitCode: Int,
  val stdout: ByteArray,
  val stderr: ByteArray,
  val timedOut: Boolean = false,
)

/**
 * The seam through which [ContainerEnvironment] shells out. The default implementation drives a
 * real subprocess; tests inject a fake so they can assert on the `docker` argv and map canned
 * results without a Docker daemon.
 */
internal fun interface CommandRunner {
  /**
   * Runs [command] (an argv vector, not a shell string), enforcing [timeout] by killing the
   * process. Returns the captured outcome.
   *
   * @throws IOException if the process cannot be launched.
   */
  fun run(command: List<String>, timeout: Duration?): CommandResult
}

/**
 * The production [CommandRunner]: launches [ProcessBuilder], captures stdout/stderr to temp files
 * (so a chatty process cannot deadlock on a full pipe), and force-kills the process tree on timeout
 * or coroutine cancellation.
 */
internal class ProcessCommandRunner : CommandRunner {
  override fun run(command: List<String>, timeout: Duration?): CommandResult {
    val stdoutFile = Files.createTempFile(STDOUT_PREFIX, LOG_SUFFIX)
    val stderrFile = Files.createTempFile(STDERR_PREFIX, LOG_SUFFIX)
    try {
      val process =
        ProcessBuilder(command)
          .redirectOutput(stdoutFile.toFile())
          .redirectError(stderrFile.toFile())
          .start() // throws IOException if the binary cannot be launched.

      try {
        // These processes are never fed stdin; close it so anything that reads stdin sees EOF
        // immediately instead of blocking.
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

        return CommandResult(
          exitCode = process.exitValue(),
          stdout = Files.readAllBytes(stdoutFile),
          stderr = Files.readAllBytes(stderrFile),
          timedOut = !finished,
        )
      } finally {
        // Coroutine cancellation interrupts waitFor(); kill the tree so the process is not left
        // orphaned. No-op on the normal and timeout paths, where the process has already exited.
        if (process.isAlive) {
          killProcessTree(process)
        }
      }
    } finally {
      try {
        Files.deleteIfExists(stdoutFile)
        Files.deleteIfExists(stderrFile)
      } catch (_: IOException) {}
    }
  }

  /**
   * Force-kills [process] and its descendants. Best-effort, like [LocalEnvironment]: this kills the
   * local `docker exec` client; a process left running inside the container is the container's to
   * reap.
   */
  private fun killProcessTree(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
  }

  private companion object {
    const val STDOUT_PREFIX = "adk-docker-out"
    const val STDERR_PREFIX = "adk-docker-err"
    const val LOG_SUFFIX = ".log"
  }
}
