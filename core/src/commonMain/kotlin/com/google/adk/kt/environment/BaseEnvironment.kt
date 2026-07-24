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

/**
 * Exception thrown by [BaseEnvironment] operations (e.g. a missing file, an unreadable path, or a
 * failure to launch a command). The [message] may be surfaced to the model as a tool error, so it
 * must be precise and free of sensitive internal detail.
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
 * operations can race on the shared working directory. Operations report failure by throwing
 * [EnvironmentException].
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

  /** Whether the environment has been initialized. */
  val isInitialized: Boolean

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
   * Ordinary process failures (non-zero exit, timeout) are returned as an [ExecutionResult]; only a
   * failure to launch the command throws [EnvironmentException].
   *
   * @param command The shell command string to execute.
   * @param timeout Maximum execution time in milliseconds; `null` means no limit.
   * @return An [ExecutionResult] with exit code, stdout, stderr, and timeout status.
   */
  suspend fun execute(command: String, timeout: Long? = null): ExecutionResult

  /**
   * Read a file from the environment filesystem.
   *
   * @param path Absolute or working-dir-relative path to the file.
   * @return The raw file contents as bytes.
   * @throws EnvironmentException if the file does not exist or cannot be read.
   */
  suspend fun readFile(path: String): ByteArray

  /**
   * Write content to a file in the environment's filesystem.
   *
   * Parent directories are created automatically if they do not exist.
   *
   * @param path Absolute or working-dir-relative path to the file.
   * @param content The raw bytes to write.
   */
  suspend fun writeFile(path: String, content: ByteArray)
}
