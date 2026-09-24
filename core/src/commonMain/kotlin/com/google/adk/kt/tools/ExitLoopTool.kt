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
 * A tool that allows an agent to exit a loop.
 *
 * This tool sets the `escalate` and `skipSummarization` flags in the [ToolContext], signaling that
 * the agent should terminate its execution loop.
 */
class ExitLoopTool :
  BaseTool(
    name = "exit_loop",
    description = "Exits the loop.\n\nCall this function only when you are instructed to do so.\n",
  ) {

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    context.actions.escalate = true
    context.actions.skipSummarization = true
    return Unit
  }
}
