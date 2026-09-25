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
import com.google.adk.kt.agents.toCallbackContext
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Role

/**
 * A processor that handles agent instructions.
 *
 * It processes both the static instruction and the resolved [com.google.adk.kt.agents.Instruction]
 * from the agent and adds them to the request.
 *
 * Behavior:
 * - Static instruction: Always appended to the system instruction (if present)
 * - Resolved instruction:
 *     - If static instruction is present: Added as user content.
 *     - If static instruction is missing: Appended to the system instruction.
 *
 * This logic allows for context caching optimization where static content comes first in the prompt
 * (as system instruction), followed by dynamic content.
 */
internal class InstructionsProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val agent = context.agent as? LlmAgent ?: return request

    // Handle static instruction, always appending it if present. The [Content] is sent verbatim —
    // placeholder substitution is intentionally NOT applied to the static prefix to preserve
    // byte-stability for context caching.
    val withStatic = agent.staticInstruction?.let { request.appendInstructions(it) } ?: request

    val resolved = agent.canonicalInstruction(context.toReadonlyContext()) ?: return withStatic
    val instruction =
      resolved.copy(
        parts =
          resolved.parts.map { part ->
            part.text?.let { text ->
              part.copy(
                text =
                  InstructionStateInjector.injectSessionState(context.toCallbackContext(), text)
              )
            } ?: part
          }
      )

    return if (agent.staticInstruction == null) {
      // No static instruction, so add the resolved instruction to system instruction.
      withStatic.appendInstructions(instruction)
    } else {
      // Text instructions resolve without a role; as user content they need one.
      require(instruction.role == null || instruction.role == Role.USER) {
        "Instruction content must have role '${Role.USER}'"
      }
      withStatic.appendContent(instruction.copy(role = Role.USER))
    }
  }
}
