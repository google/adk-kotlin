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

package com.google.adk.kt.tools.environment

import com.google.adk.kt.environment.EnvironmentException
import com.google.adk.kt.logging.Logger
import kotlin.time.Duration.Companion.seconds

/** Tool names exposed by [EnvironmentToolset]. */
internal const val TOOL_EXECUTE = "Execute"
internal const val TOOL_READ_FILE = "ReadFile"
internal const val TOOL_EDIT_FILE = "EditFile"
internal const val TOOL_WRITE_FILE = "WriteFile"

/** Parameter keys. */
internal const val PARAM_COMMAND = "command"
internal const val PARAM_PATH = "path"
internal const val PARAM_START_LINE = "start_line"
internal const val PARAM_END_LINE = "end_line"
internal const val PARAM_OLD_STRING = "old_string"
internal const val PARAM_NEW_STRING = "new_string"
internal const val PARAM_CONTENT = "content"

/** Response keys. */
internal const val KEY_STATUS = "status"
internal const val KEY_ERROR = "error"
internal const val KEY_STDOUT = "stdout"
internal const val KEY_STDERR = "stderr"
internal const val KEY_EXIT_CODE = "exit_code"
internal const val KEY_CONTENT = "content"
internal const val KEY_MESSAGE = "message"
internal const val KEY_TOTAL_LINES = "total_lines"

/** Response status values. */
internal const val STATUS_OK = "ok"
internal const val STATUS_ERROR = "error"

/** Default character limit for truncating tool output (stdout/stderr/file content). */
internal const val DEFAULT_MAX_OUTPUT_CHARS = 30_000

/** Default per-command execution timeout for the Execute tool. */
internal val DEFAULT_TIMEOUT = 30.seconds

/**
 * Builds the system instruction injected on each LLM call, establishing the workspace and
 * tool-selection rules for the environment rooted at [workingDir].
 */
internal fun environmentInstruction(workingDir: String): String =
  """
  Your environment is at `$workingDir`

  # Environment Rules

  DO:
  - Chain sequential, dependent commands with `&&` in a single `$TOOL_EXECUTE` call
  - To read existing files, always use `$TOOL_READ_FILE`; use `$TOOL_EDIT_FILE` to modify existing files and `$TOOL_WRITE_FILE` to create a new file or fully overwrite one
  - Give paths relative to the workspace root (absolute paths inside the workspace also work)

  DON'T:
  - Use `$TOOL_EXECUTE` to run `cat`, `head`, or `tail` when `$TOOL_READ_FILE` can do the job
  - Combine `$TOOL_EDIT_FILE` or `$TOOL_READ_FILE` with `$TOOL_EXECUTE` in the same response (call the file tool first, then `$TOOL_EXECUTE` in the next turn)
  - Use multiple `$TOOL_EXECUTE` calls for dependent commands (they run in parallel)
  """
    .trimIndent()

/** Builds a standard error response map. */
internal fun errorResult(message: String): Map<String, Any> =
  mapOf(KEY_STATUS to STATUS_ERROR, KEY_ERROR to message)

/**
 * Maps a [Result] failure from an environment operation into a tool error response.
 *
 * Per the environment contract the only wrapped exception is [EnvironmentException], whose message
 * is forwarded to the model. Any other [Throwable] is a contract violation: it is logged and
 * re-thrown.
 */
internal fun Throwable.toEnvironmentErrorResponse(logger: Logger): Map<String, Any> {
  if (this is EnvironmentException) {
    return errorResult(message ?: "An unspecified environment error occurred.")
  }
  logger.warn(this) {
    "BaseEnvironment returned Result.failure wrapping an unrecognized exception type " +
      "(${this::class.simpleName})."
  }
  throw this
}

/** Truncates [text] to [maxChars], appending a notice with the original length when truncated. */
internal fun truncateOutput(text: String, maxChars: Int): String =
  if (text.length <= maxChars) text
  else text.take(maxChars) + "\n... (truncated, ${text.length} total chars)"

/**
 * Splits [text] into lines for numbering, each ending in `\n`. The stdlib `lines()` discards the
 * terminators and appends a trailing empty element when the text ends in a line break, so we drop
 * that element and re-add a `\n` per line. Endings are normalized to `\n` (the original `\r\n`/`\r`
 * is not preserved) and empty input yields no lines — both fine, since the result is only shown to
 * the model.
 */
internal fun splitLinesKeepEnds(text: String): List<String> {
  if (text.isEmpty()) return emptyList()
  val lines = text.lines()
  val trimmed = if (text.last() == '\n' || text.last() == '\r') lines.dropLast(1) else lines
  return trimmed.map { "$it\n" }
}
