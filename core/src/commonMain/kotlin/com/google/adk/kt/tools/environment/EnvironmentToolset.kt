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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.jvm.JvmOverloads

/**
 * System instruction appended on each LLM call. It names no workspace path, since an environment
 * may keep a separate workspace per session.
 */
private val ENVIRONMENT_INSTRUCTION =
  """
  You have a workspace for running commands and reading and writing files.

  # Environment Rules

  DO:
  - Chain sequential, dependent commands with `&&` in a single `$TOOL_EXECUTE` call
  - To read existing files, always use `$TOOL_READ_FILE`. Use `$TOOL_EDIT_FILE` to modify existing files
  - Give paths relative to the workspace root (absolute paths inside the workspace also work)

  DON'T:
  - Use `$TOOL_EXECUTE` to run `cat`, `head`, or `tail` when `$TOOL_READ_FILE` can do the job
  - Combine `$TOOL_EDIT_FILE` or `$TOOL_READ_FILE` with `$TOOL_EXECUTE` in the same response (call the file tool first, then `$TOOL_EXECUTE` in the next turn)
  - Use multiple `$TOOL_EXECUTE` calls for dependent commands (they run in parallel)
  - Use the same file in two calls of one response (the calls run in parallel, so a read can miss a write and one of two writes is lost)
  """
    .trimIndent()

/**
 * Toolset that gives an agent `Execute`, `ReadFile`, `EditFile` and `WriteFile` tools over an
 * [Environment] and appends tool-selection rules to the system instruction on each LLM call.
 * `Execute` and `ReadFile` output is truncated to [maxOutputChars] characters, and each `Execute`
 * command times out after 30 seconds.
 *
 * @param environment The environment used to execute commands and perform file I/O.
 * @param maxOutputChars Maximum characters of stdout, stderr or file content returned to the model,
 *   30,000 by default; must not be negative.
 */
@ExperimentalEnvironmentApi
class EnvironmentToolset
@JvmOverloads
constructor(
  private val environment: Environment,
  private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) : Toolset {

  init {
    require(maxOutputChars >= 0) { "maxOutputChars must not be negative, was $maxOutputChars." }
  }

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    environment.initialize()
    return listOf(
      ExecuteTool(environment, maxOutputChars),
      ReadFileTool(environment, maxOutputChars),
      EditFileTool(environment),
      WriteFileTool(environment),
    )
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest =
    llmRequest.appendInstructions(Content(parts = listOf(Part(text = ENVIRONMENT_INSTRUCTION))))

  /** Forwards to [Environment.close] on every call, relying on it being idempotent. */
  override fun close() {
    environment.close()
  }
}
