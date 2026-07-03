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

/**
 * Abstract base class for defining and executing tools.
 *
 * @property name The name of the tool.
 * @property description The description of the tool.
 * @property isLongRunning Whether the tool's final result will be delivered out-of-band. When
 *   `true`, the framework marks the call as long-running and uses the tool's return value as the
 *   function-response payload. Returning `Unit` means "no response yet": the FR event is suppressed
 *   so the function-call event (which carries the call id in `longRunningToolIds` and is thus the
 *   turn's final response) ends the turn without re-invoking the model. A non-`Unit` return --
 *   including an explicit empty `Map` -- is treated as a real response and emitted. (`Unit`
 *   suppression aligns with Python; Java instead always emits `{}`.) The `longRunningToolIds` id
 *   also drives the resumable-mode pause gate so the invocation can be resumed later via a
 *   user-injected function-response.
 * @property customMetadata The custom metadata of the tool.
 */
abstract class BaseTool(
  val name: String,
  val description: String,
  val isLongRunning: Boolean = false,
  val customMetadata: Map<String, Any> = emptyMap(),
) : AutoCloseable {
  /** Returns the underlying function declaration. */
  abstract fun declaration(): FunctionDeclaration?

  /**
   * Executes the tool and returns its result.
   *
   * The result must be JSON-native: a [Map], [List], [String], number, [Boolean], or `null`; a
   * non-[Map] result is wrapped under [RESULT_KEY]. Returning a non-JSON type such as a Kotlin data
   * class throws when the resulting event is persisted. To return structured data, return a [Map],
   * or expose the function via [com.google.adk.kt.annotations.Tool], which converts data classes
   * (and enums, lists, and nested structures) into Maps automatically.
   */
  abstract suspend fun run(context: ToolContext, args: Map<String, Any>): Any

  /**
   * Processes the LLM request before it is sent.
   *
   * Tools can override this to attach instructions, artifacts, or other data to the request. By
   * default, this implementation appends the tool itself to the [LlmRequest], making it available
   * for use by the LLM.
   */
  open suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest {
    return llmRequest.appendTools(listOf(this))
  }

  override fun close() {}

  companion object {
    const val RESULT_KEY = "result"
  }
}
