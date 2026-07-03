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
 * Built-in long-running tool that presents a list of options and pauses the invocation until the
 * user picks one.
 *
 * Like [RequestInputTool] it defers (returns [Unit]) and pauses until the caller injects a
 * `FunctionResponse` with the choice. It also sets `actions.skipSummarization = true` so the
 * pending turn is not summarized, matching Python ADK's `get_user_choice`.
 */
class GetUserChoiceTool :
  BaseTool(
    name = GET_USER_CHOICE_FUNCTION_CALL_NAME,
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
              OPTIONS_ARG to
                Schema(
                  type = Type.ARRAY,
                  items = Schema(type = Type.STRING),
                  description = "The list of options to present to the user.",
                )
            ),
          required = listOf(OPTIONS_ARG),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    // Mirror Python: the mandatory-arg error fires only when the parameter is absent, not when it
    // is present but not a list.
    if (!args.containsKey(OPTIONS_ARG)) {
      return InternalToolHelpers.missingMandatoryParamsError(name, listOf(OPTIONS_ARG))
    }
    // Skip summarization on the pending turn, matching Python ADK's get_user_choice.
    context.actions.skipSummarization = true
    // Defer: returning Unit pauses the invocation until a FunctionResponse is injected.
    return Unit
  }

  private companion object {
    const val GET_USER_CHOICE_FUNCTION_CALL_NAME = "get_user_choice"
    const val OPTIONS_ARG = "options"
    const val DESCRIPTION = "Provides the options to the user and asks them to choose one."
  }
}
