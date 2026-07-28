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
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import kotlin.jvm.JvmOverloads
import kotlin.time.Duration

/**
 * The exception an [Environment] wraps in [Result.failure] for recoverable failures (a missing
 * file, an unreadable path, a command that fails to launch). Its [message] is forwarded to the
 * model as a tool error, so it MUST be precise, self-contained, and free of sensitive internal
 * detail. Implementations should wrap only [EnvironmentException] in [Result.failure].
 */
@ExperimentalEnvironmentApi
class EnvironmentException @JvmOverloads constructor(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * Result of a command execution.
 *
 * @property exitCode The exit code of the process.
 * @property stdout Standard output captured from the process.
 * @property stderr Standard error captured from the process.
 * @property timedOut Whether the execution exceeded the timeout.
 */
@ExperimentalEnvironmentApi
data class ExecutionResult(
  val exitCode: Int = 0,
  val stdout: String = "",
  val stderr: String = "",
  val timedOut: Boolean = false,
)

/**
 * A workspace where an agent can run shell commands and read and write files.
 *
 * [execute], [readFile] and [writeFile] take the caller's [Context], so one instance can keep a
 * separate workspace per session and persist what identifies it with [Context.updateState]; they
 * may run concurrently, and this interface does no synchronization. Call [initialize] before first
 * use and [close] when done.
 */
@ExperimentalEnvironmentApi
interface Environment : AutoCloseable {

  /**
   * Prepares resources shared by all sessions, such as a local directory or a client connection.
   *
   * Per-session setup belongs in the first operation, which receives a [Context]. The default
   * implementation is a no-op; implementations must be idempotent and safe to call concurrently.
   */
  suspend fun initialize() {}

  /**
   * Releases resources held by the environment.
   *
   * The default implementation is a no-op; implementations must be idempotent and safe to call even
   * if [initialize] never ran.
   */
  override fun close() {}

  /**
   * Executes a shell command in the calling session's workspace.
   *
   * A non-zero exit or a timeout is reported in the [ExecutionResult]; failing to launch the
   * command is a [Result.failure].
   *
   * @param context The caller's context, identifying the calling session.
   * @param command The shell command string to execute.
   * @param timeout Maximum execution time; `null` means no limit.
   * @return The execution result, or an [EnvironmentException] failure if the command could not be
   *   launched.
   */
  suspend fun execute(
    context: Context,
    command: String,
    timeout: Duration? = null,
  ): Result<ExecutionResult>

  /**
   * Reads a file from the calling session's workspace.
   *
   * @param context The caller's context, identifying the calling session.
   * @param path Absolute path, or a path relative to the workspace root.
   * @return The raw file contents, or an [EnvironmentException] failure if the file does not exist
   *   or cannot be read.
   */
  suspend fun readFile(context: Context, path: String): Result<ByteArray>

  /**
   * Writes a file in the calling session's workspace, creating missing parent directories.
   *
   * @param context The caller's context, identifying the calling session.
   * @param path Absolute path, or a path relative to the workspace root.
   * @param content The raw bytes to write.
   * @return Success, or an [EnvironmentException] failure if the write fails.
   */
  suspend fun writeFile(context: Context, path: String, content: ByteArray): Result<Unit>
}
