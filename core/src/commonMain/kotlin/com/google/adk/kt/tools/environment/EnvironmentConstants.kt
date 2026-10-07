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

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.EnvironmentException
import kotlin.time.Duration.Companion.seconds

// Tool names exposed by EnvironmentToolset.
internal const val TOOL_EXECUTE = "Execute"
internal const val TOOL_READ_FILE = "ReadFile"
internal const val TOOL_EDIT_FILE = "EditFile"
internal const val TOOL_WRITE_FILE = "WriteFile"

// Parameter keys.
internal const val PARAM_COMMAND = "command"
internal const val PARAM_PATH = "path"
internal const val PARAM_START_LINE = "start_line"
internal const val PARAM_END_LINE = "end_line"
internal const val PARAM_OLD_STRING = "old_string"
internal const val PARAM_NEW_STRING = "new_string"
internal const val PARAM_CONTENT = "content"

// Response keys.
internal const val KEY_STATUS = "status"
internal const val KEY_ERROR = "error"
internal const val KEY_STDOUT = "stdout"
internal const val KEY_STDERR = "stderr"
internal const val KEY_EXIT_CODE = "exit_code"
internal const val KEY_CONTENT = "content"
internal const val KEY_MESSAGE = "message"
internal const val KEY_TOTAL_LINES = "total_lines"

// Response status values.
internal const val STATUS_OK = "ok"
internal const val STATUS_ERROR = "error"

/** Default character limit for truncating tool output (stdout/stderr/file content). */
internal const val DEFAULT_MAX_OUTPUT_CHARS = 30_000

/** Default per-command execution timeout for the Execute tool. */
internal val DEFAULT_TIMEOUT = 30.seconds

/** Builds a standard error response map. */
internal fun errorResult(message: String): Map<String, Any> =
  mapOf(KEY_STATUS to STATUS_ERROR, KEY_ERROR to message)

/**
 * Maps a failure from an environment operation to a tool error response carrying the
 * [EnvironmentException] message. Any other [Throwable] violates the environment contract and is
 * rethrown.
 */
@ExperimentalEnvironmentApi
internal fun Throwable.toEnvironmentErrorResponse(): Map<String, Any> {
  if (this !is EnvironmentException) throw this
  return errorResult(message.orEmpty())
}

/** Truncates [text] to [maxChars], appending a notice with the original length when truncated. */
internal fun truncateOutput(text: String, maxChars: Int): String =
  if (text.length <= maxChars) {
    text
  } else {
    text.take(maxChars) + "\n... (truncated, ${text.length} total chars)"
  }
