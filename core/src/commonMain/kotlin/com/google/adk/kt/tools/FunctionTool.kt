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

/**
 * Represents a compile-time generated tool that wraps a function annotated with
 * [com.google.adk.kt.annotations.Tool].
 *
 * The optional confirmation gate (set via the `requiresConfirmation` constructor parameter) makes
 * an invocation pause for human approval before the underlying function runs:
 * - On the first call the tool records a confirmation request via [ToolContext.requestConfirmation]
 *   and returns a placeholder error response without invoking the underlying function.
 * - Once the user supplies a [com.google.adk.kt.events.ToolConfirmation], the tool is re-executed:
 *   if the user confirmed it runs normally; otherwise it returns a rejection error response.
 *
 * The gate has two forms:
 * - Per-call predicate (the primary constructor): a `(args) -> Boolean` is invoked on every call
 *   with the function's `args` and decides whether to gate this invocation, e.g.
 *   `requiresConfirmation = { args -> (args["amount"] as Int) > 1000 }`. The default value `{ false
 *   }` disables the gate entirely.
 * - Boolean (the secondary constructor): a constant flag that gates either every invocation
 *   (`true`) or none (`false`). It is a thin wrapper that lifts the Boolean into a constant
 *   predicate `{ value }`.
 */
abstract class FunctionTool(
  name: String,
  description: String,
  override val isLongRunning: Boolean = false,
  customMetadata: Map<String, Any> = emptyMap(),
  /**
   * Per-call predicate deciding whether this invocation should pause for human confirmation. The
   * default is "never". Pass a constant `{ true }` to gate every invocation, or arbitrary `(args)
   * -> Boolean` logic for dynamic gating. The Boolean secondary constructor is a convenience
   * wrapper around the constant cases.
   */
  protected val requiresConfirmation: (Map<String, Any>) -> Boolean = { false },
) : BaseTool(name, description, customMetadata) {

  /**
   * Boolean convenience constructor: pass `true` to gate every invocation, `false` to skip the gate
   * entirely. Equivalent to passing `{ requiresConfirmation }` to the primary constructor.
   *
   * The `requiresConfirmation` parameter is required (no default) to keep overload resolution
   * unambiguous against the primary constructor; callers who don't need a gate should use the
   * primary constructor and omit `requiresConfirmation` altogether.
   */
  constructor(
    name: String,
    description: String,
    isLongRunning: Boolean = false,
    customMetadata: Map<String, Any> = emptyMap(),
    requiresConfirmation: Boolean,
  ) : this(
    name = name,
    description = description,
    isLongRunning = isLongRunning,
    customMetadata = customMetadata,
    requiresConfirmation = { requiresConfirmation },
  )

  /**
   * Executes the function with the provided [args], optionally utilizing the [context].
   *
   * @param context The current [ToolContext].
   * @param args The extracted arguments provided by the LLM.
   * @return The tool's response, conventionally a `Map<String, Any>`. Non-map values are wrapped
   *   under [BaseTool.RESULT_KEY]. Return `mapOf(ERROR_KEY to "<message>")` to signal an
   *   LLM-visible error. From a long-running tool, return `Unit` to defer the response; see
   *   [BaseTool.isLongRunning].
   */
  abstract suspend fun execute(context: ToolContext, args: Map<String, Any>): Any

  /**
   * Executes the tool. This overrides the generic base method to apply the optional confirmation
   * gate before delegating to [execute].
   */
  final override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    if (requiresConfirmation(args)) {
      val confirmation = context.toolConfirmation
      if (confirmation == null) {
        context.requestConfirmation(
          hint =
            "Please approve or reject the tool call $name() by responding with a " +
              "FunctionResponse with an expected ToolConfirmation payload."
        )
        context.actions.skipSummarization = true
        return mapOf(ERROR_KEY to CONFIRMATION_REQUIRED_ERROR)
      }
      if (!confirmation.confirmed) {
        return mapOf(ERROR_KEY to REJECTED_ERROR)
      }
    }
    return execute(context, args)
  }

  companion object {
    /**
     * A standard note appended to the description of long-running tools. This signals to the
     * generation engine that the tool will yield a pending state.
     */
    const val LONG_RUNNING_OPERATION_NOTE =
      "NOTE: This tool performs a long-running operation. The tool will emit a pending state and wait for an external system to complete execution before returning final results."

    /** Map key under which confirmation-related error placeholders are returned. */
    const val ERROR_KEY: String = "error"

    /** Placeholder error returned on the first call of a confirmation-gated tool. */
    const val CONFIRMATION_REQUIRED_ERROR: String =
      "This tool call requires confirmation, please approve or reject."

    /** Placeholder error returned when the user explicitly rejects a confirmation-gated tool. */
    const val REJECTED_ERROR: String = "This tool call is rejected."
  }
}
