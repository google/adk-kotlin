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
import com.google.adk.kt.agents.requireLlmAgent
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.BaseTool

/** A processor that appends the agent's tools to the LLM request. */
internal class ToolProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest = request.appendTools(context.requireLlmAgent().tools)
}

/**
 * Returns this agent's own, runtime and toolset tools by name, for re-running a paused tool call.
 */
internal suspend fun LlmAgent.toolsByName(context: InvocationContext): Map<String, BaseTool> {
  val toolsetTools = toolsets.flatMap { it.getTools(context.toReadonlyContext()) }
  return (tools + context.extraTools.values + toolsetTools).associateBy { it.name }
}
