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

/**
 * Toolset providing tools to interact with an environment.
 *
 * Tools provided:
 * - **Execute** — run shell commands
 * - **ReadFile** — read file contents
 * - **EditFile** — surgical text replacement
 * - **WriteFile** — create/overwrite files
 *
 * The toolset injects an environment-level system instruction on each LLM call that establishes
 * environment identity and tool selection rules. `Execute`/`ReadFile` output is truncated to
 * [maxOutputChars].
 *
 * Lifecycle: the toolset holds no state; [close] forwards to [Environment.close], which is required
 * to be idempotent.
 *
 * @param environment The environment used to execute commands and perform file I/O.
 * @param maxOutputChars Maximum character limit for stdout/stderr/file truncation.
 */
@ExperimentalEnvironmentApi
class EnvironmentToolset(
  private val environment: Environment,
  private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) : Toolset {

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    environment.initialize(readonlyContext)
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
  ): LlmRequest {
    environment.initialize(toolContext.context)
    return llmRequest.appendInstructions(
      Content(parts = listOf(Part(text = environmentInstruction())))
    )
  }

  /** Closes the environment. Forwarded unconditionally; [Environment.close] is idempotent. */
  override fun close() {
    environment.close()
  }
}
