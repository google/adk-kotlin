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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.models.LlmRequest

/** Base interface for toolsets. */
interface Toolset : AutoCloseable {

  /**
   * Return all tools in the toolset based on the provided context.
   *
   * @param readonlyContext Context used to filter tools available to the agent. Defaults to `null`,
   *   meaning no filtering context is supplied and all tools in the toolset are returned. Toolsets
   *   that do not filter by context may ignore this parameter.
   * @return A list of tools available under the specified context.
   */
  suspend fun getTools(readonlyContext: ReadonlyContext? = null): List<BaseTool>

  /**
   * Performs cleanup and releases resources held by the toolset.
   *
   * NOTE: This method is invoked, for example, at the end of an agent server's lifecycle or when
   * the toolset is no longer needed. Implementations should ensure that any open connections,
   * files, or other managed resources are properly released to prevent leaks.
   */
  override fun close() {}

  /** Allows the toolset to process the LLM request. */
  suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest =
    llmRequest
}
