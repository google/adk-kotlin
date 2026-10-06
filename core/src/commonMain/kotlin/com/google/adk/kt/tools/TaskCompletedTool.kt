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

/**
 * A no-argument tool that a step of a live `SequentialAgent` calls when its part of the task is
 * done.
 *
 * A live run has no natural end, so this call is what ends the step and lets the next one start.
 */
internal class TaskCompletedTool :
  BaseTool(
    name = TASK_COMPLETED_TOOL_NAME,
    description = "Signals that the agent has successfully completed the user's question or task.",
  ) {

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(name = name, description = description)

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    TASK_COMPLETED_RESPONSE

  companion object {
    const val TASK_COMPLETED_TOOL_NAME = "task_completed"

    /** What the tool returns, surfaced to the model as its function response. */
    private const val TASK_COMPLETED_RESPONSE = "Task completion signaled."
  }
}
