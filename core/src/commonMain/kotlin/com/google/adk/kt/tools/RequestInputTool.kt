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

import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * Built-in long-running tool that asks the user a question and pauses the invocation until they
 * respond.
 *
 * The tool defers (returns [Unit]), so the framework marks the call long-running and pauses until
 * the caller injects a matching `FunctionResponse`; the tool is not re-run on resume. The call name
 * `adk_request_input` matches Python ADK's `request_input` for cross-language interop.
 */
class RequestInputTool :
  BaseTool(
    name = REQUEST_INPUT_FUNCTION_CALL_NAME,
    description = DESCRIPTION,
    isLongRunning = true,
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      // Long-running note on the declaration only; the `description` property stays clean.
      description = InternalToolHelpers.withLongRunningNote(description),
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              MESSAGE_ARG to
                Schema(
                  type = Type.STRING,
                  description = "The question or prompt to display to the user.",
                ),
              RESPONSE_SCHEMA_ARG to
                Schema(
                  type = Type.OBJECT,
                  description =
                    "JSON Schema describing the expected response format. Use {\"type\": " +
                      "\"string\"} for free-text, {\"type\": \"boolean\"} for yes/no, or a " +
                      "structured object schema for complex input.",
                ),
            ),
          required = listOf(MESSAGE_ARG),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    // Mirror Python: the mandatory-arg error fires only when the parameter is absent, not when it
    // is present but empty or not a string.
    if (!args.containsKey(MESSAGE_ARG)) {
      return InternalToolHelpers.missingMandatoryParamsError(name, listOf(MESSAGE_ARG))
    }
    // Defer: returning Unit pauses the invocation until a FunctionResponse is injected.
    return Unit
  }

  private companion object {
    const val REQUEST_INPUT_FUNCTION_CALL_NAME = "adk_request_input"
    const val MESSAGE_ARG = "message"
    const val RESPONSE_SCHEMA_ARG = "response_schema"
    const val DESCRIPTION =
      "Ask the user a question and wait for their response. Use this when you need clarification " +
        "or additional information before proceeding."
  }
}
