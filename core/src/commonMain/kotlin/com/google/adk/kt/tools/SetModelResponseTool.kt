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
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.serialization.Json
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

  /** The arguments [run] validated, by function call id; only these end the turn. */
  private val validatedResponses = concurrentMutableMapOf<String, Map<String, Any?>>()

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description, parameters = outputSchema)

  /**
   * Validates [args] against [outputSchema], records them as the call's structured response, and
   * returns them unchanged; a callback that answers for this tool without running it never ends the
   * turn.
   *
   * Validation is strict: non-conforming [args] throw a tool execution error that fails the
   * invocation unless an `onToolError` callback recovers, unlike the direct-schema path in
   * `LlmAgent.maybeSaveOutputToState`, which logs the mismatch and stores the raw text.
   */
  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    SchemaUtils.validateMapOnSchema(args, outputSchema, argsName = "Output").getOrThrow()
    validatedResponses[context.functionCallId.orEmpty()] = args
    return args
  }

  /** Returns the JSON of the arguments [run] validated for [functionCallId], or `null` if none. */
  internal fun validatedResponseJson(functionCallId: String?): String? =
    validatedResponses[functionCallId.orEmpty()]?.let { Json.toJsonString(it) }

  companion object {
    const val NAME: String = "set_model_response"
  }
}
