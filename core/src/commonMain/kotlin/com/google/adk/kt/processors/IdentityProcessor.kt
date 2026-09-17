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
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role

/**
 * A processor that gives the agent its identity from the framework.
 *
 * It appends a system instruction telling the model its internal [name][LlmAgent.name] and, when
 * present, its [description][LlmAgent.description], so the model can reason about who it is in
 * multi-agent setups.
 */
internal class IdentityProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val agent = context.agent as? LlmAgent ?: return request

    val identity = buildString {
      append("You are an agent. Your internal name is \"")
      append(agent.name)
      append("\".")
      if (agent.description.isNotEmpty()) {
        append(" The description about you is \"")
        append(agent.description)
        append("\".")
      }
    }

    return request.appendInstructions(Content.fromText(Role.SYSTEM, identity))
  }
}
