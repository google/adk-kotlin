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
import kotlin.time.Duration

/**
 * The exception type wrapped in [Result.failure] by [BaseEnvironment] operations.
 *
 * An environment wraps this in [Result.failure] for recoverable failures (e.g. a missing file, an
 * unreadable path, or a failure to launch a command). The [message] is intended to be forwarded to
 * the model as a tool error, so it MUST be precise and self-contained, and MUST NOT leak sensitive
 * internal detail. Implementations should only wrap [EnvironmentException] in [Result.failure].
 */
class EnvironmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Result of a command execution.
 *
 * @property exitCode The exit code of the process.
 * @property stdout Standard output captured from the process.
 * @property stderr Standard error captured from the process.
 * @property timedOut Whether the execution exceeded the timeout.
 */
data class ExecutionResult(
  val exitCode: Int = 0,
  val stdout: String = "",
  val stderr: String = "",
  val timedOut: Boolean = false,
) {
  /** Whether the command completed on its own with a zero exit code. */
  val isSuccess: Boolean
    get() = exitCode == 0 && !timedOut
}

/**
 * Interface for code execution environments.
 *
 * An environment provides the ability to execute shell commands, read files, and write files within
 * a working directory. Concrete implementations include local subprocess execution, sandboxed
 * execution, container environments, and cloud-hosted environments.
 *
 * Methods are `suspend` and may be invoked concurrently from parallel coroutines. This interface
 * does no synchronization — when implementing an environment, keep concurrency in mind: overlapping
 * operations can race on the shared working directory.
 *
 * [execute], [readFile], and [writeFile] return a [Result] whose failure case is an
 * [EnvironmentException] with a message intended to be surfaced to the model; implementations
 * should only wrap [EnvironmentException] in [Result.failure].
 *
 * Lifecycle:
 * 1. Construct the environment.
 * 2. Call [initialize] before first use.
 * 3. Use [execute], [readFile], [writeFile].
 * 4. Call [close] when done.
 */
@ExperimentalEnvironmentApi
interface BaseEnvironment {
  /** The absolute path to the environment's working directory. */
  val workingDir: String

  /**
   * Initialize the environment (e.g. create the working directory).
   *
   * Called before first use. The default implementation is a no-op; implementations should ensure
   * this method is idempotent.
   */
  suspend fun initialize() {}

  /**
   * Release resources held by the environment.
   *
   * Called when the environment is no longer needed. The default implementation is a no-op;
   * implementations should ensure this method is idempotent.
   */
  suspend fun close() {}

  /**
   * Execute a shell command in the working directory.
   *
   * Ordinary process outcomes (non-zero exit, timeout) are returned in the [ExecutionResult]; a
   * failure to launch the command is a [Result.failure] wrapping an [EnvironmentException].
   *
   * @param command The shell command string to execute.
   * @param timeout Maximum execution time; `null` means no limit.
   * @return A [Result] wrapping an [ExecutionResult] (exit code, stdout, stderr, timeout status),
   *   or a [Result.failure] with an [EnvironmentException] if the command could not be launched.
   */
  suspend fun execute(command: String, timeout: Duration? = null): Result<ExecutionResult>

  /**
   * Read a file from the environment filesystem.
   *
   * @param path Absolute or working-dir-relative path to the file.
   * @return A [Result] wrapping the raw file contents, or a [Result.failure] with an
   *   [EnvironmentException] if the file does not exist or cannot be read.
   */
  suspend fun readFile(path: String): Result<ByteArray>

  /**
   * Write content to a file in the environment's filesystem.
   *
   * Parent directories are created automatically if they do not exist.
   *
   * @param path Absolute or working-dir-relative path to the file.
   * @param content The raw bytes to write.
   * @return [Result.success] on success, or a [Result.failure] with an [EnvironmentException] if
   *   the write fails.
   */
  suspend fun writeFile(path: String, content: ByteArray): Result<Unit>
}
