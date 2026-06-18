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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema

/**
 * Internal tool used for the output schema workaround.
 *
 * This tool lets the model produce its final response when an output schema is configured alongside
 * other tools on a model that cannot use a response schema and tools at the same time (see
 * [com.google.adk.kt.models.canUseOutputSchemaWithTools]). The model is instructed to call this
 * tool with its final answer in the required schema format instead of emitting text directly.
 *
 * Follows the Java ADK `SetModelResponseTool`: the [outputSchema] is exposed directly as the tool's
 * parameters, so only top-level object schemas are supported. The Python ADK's
 * `SetModelResponseTool` additionally handles list/primitive schemas by wrapping them in synthetic
 * `items`/`response` parameters; that is intentionally not replicated here.
 *
 * @property outputSchema The schema the model's final response must conform to.
 */
internal class SetModelResponseTool(private val outputSchema: Schema) :
  BaseTool(
    name = NAME,
    description =
      "Set your final response using the required output schema. After using any other tools " +
        "needed to complete the task, always call set_model_response with your final answer in " +
        "the specified schema format.",
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description, parameters = outputSchema)

  /**
   * Validates [args] against [outputSchema] and returns them unchanged.
   *
   * This tool is a marker for the final response; it does not perform any side effects beyond
   * returning its arguments, which the flow captures as the structured final response.
   *
   * Validation is strict (parity with the Java/Python ADK): args that do not conform to
   * [outputSchema] throw, which propagates as a tool execution error rather than being saved as
   * best-effort text. Unless an `onToolError` callback recovers, the exception propagates up
   * through `handleFunctionCalls` -> `processModelResponse` and is surfaced by the `onModelError`
   * pipeline in `LlmAgentTurn` (failing the invocation if unrecovered); it does not produce an
   * error function-response event by default. This differs from the direct-schema path in
   * `LlmAgent.maybeSaveOutputToState`, which logs the error and stores the raw output instead of
   * failing. The asymmetry is intentional: in the workaround path the structured value is produced
   * by a tool call, so a schema mismatch is treated as a tool execution error rather than a
   * best-effort text result.
   */
  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any> {
    SchemaUtils.validateMapOnSchema(args, outputSchema, argsName = "Output").getOrThrow()
    return args
  }

  companion object {
    const val NAME: String = "set_model_response"
  }
}
