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

package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.TaskCompletedTool
import com.google.adk.kt.tools.TaskCompletedTool.Companion.TASK_COMPLETED_TOOL_NAME
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * An [LlmRequestProcessor] that offers the `task_completed` tool and its instruction to an
 * [LlmAgent] step of a live `SequentialAgent`. A live run has no natural end, so the model needs an
 * explicit way to say it has finished so the next sub-agent can take over. Mirrors ADK Python's
 * `SequentialAgent._run_live_impl`, which attaches the same tool and instruction per step.
 */
internal class TaskCompletionProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    if (!offersTaskCompletedTool(context)) return request
    return request
      .appendTools(listOf(TaskCompletedTool()))
      .appendInstructions(Content(parts = listOf(Part(text = COMPLETION_INSTRUCTION))))
  }

  companion object {
    private const val COMPLETION_INSTRUCTION =
      "If you finished the user's request according to its description, call the " +
        "$TASK_COMPLETED_TOOL_NAME function to exit so the next agents can take over. When " +
        "calling this function, do not generate any text other than the function call."
  }
}

/**
 * Whether a request would be offered the `task_completed` tool: a live run of an [LlmAgent] that is
 * a step of a [SequentialAgent] (its parent is one) and does not already declare such a tool. A
 * caller's own `task_completed` tool wins, as in ADK Python. Shared by [TaskCompletionProcessor]
 * and the output-schema and multi-tool gating, so all three agree on when the tool is present.
 */
internal fun offersTaskCompletedTool(context: InvocationContext): Boolean {
  if (context.liveRequestQueue == null) return false
  val agent = context.agent as? LlmAgent ?: return false
  if (agent.parentAgent !is SequentialAgent) return false
  return agent.tools.none { it.name == TASK_COMPLETED_TOOL_NAME }
}
