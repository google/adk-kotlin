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

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.FunctionDeclaration
import kotlin.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Abstract base class for defining and executing tools.
 *
 * @property name The name of the tool.
 * @property description The description of the tool.
 * @property isLongRunning Whether the tool's final result will be delivered out-of-band. When
 *   `true`, the framework marks the call as long-running and uses the tool's return value as the
 *   function-response payload (or suppresses the response entirely if the tool returns `Unit`).
 * @property customMetadata The custom metadata of the tool.
 */
abstract class BaseTool(
  val name: String,
  val description: String,
  val customMetadata: Map<String, Any> = emptyMap(),
) : AutoCloseable {

  /** Returns whether the tool is long running. */
  open val isLongRunning: Boolean = false

  /**
   * Returns the declarative timeout for this tool. If [Duration.INFINITE], no timeout is applied.
   */
  open val timeout: Duration = Duration.INFINITE

  /**
   * Returns the underlying function declaration.
   *
   * @return A [FunctionDeclaration] representing the schema and API surface of the tool, or null if
   *   none exists.
   */
  abstract fun declaration(): FunctionDeclaration?

  /** Executes the tool. */
  abstract suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  /**
   * Processes the LLM request before it is sent.
   *
   * Tools can override this to attach instructions, artifacts, or other data to the request. By
   * default, this implementation appends the tool itself to the [LlmRequest], making it available
   * for use by the LLM.
   *
   * @param toolContext The current tool context.
   * @param llmRequest The original LLM request.
   * @return A potentially modified [LlmRequest] incorporating the tool's requirements.
   */
  open suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest {
    return llmRequest.appendTools(listOf(this))
  }

  /**
   * Cooperative cancellation check point. Checks if the tool execution is still active and throws a
   * [kotlinx.coroutines.CancellationException] if the surrounding coroutine is cancelled or its
   * timeout expired.
   */
  suspend fun ensureActive() {
    currentCoroutineContext().ensureActive()
  }

  /** Closes resources held by the tool when no longer needed. */
  override fun close() {}

  /** Companion object containing constants for tool mechanics. */
  companion object {
    /** Key used to wrap singular primitive or raw execution outputs in a standardized Map. */
    const val RESULT_KEY = "result"
  }
}
