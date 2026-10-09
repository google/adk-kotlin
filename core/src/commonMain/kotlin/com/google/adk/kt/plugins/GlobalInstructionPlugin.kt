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

package com.google.adk.kt.plugins

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.processors.InstructionStateInjector
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import kotlin.jvm.JvmOverloads

/**
 * Prepends an app-wide instruction (such as a common identity or set of rules) to the system
 * instruction of every model request so all agents in the app share it. Mirrors Python ADK's
 * `GlobalInstructionPlugin`.
 *
 * ```kotlin
 * val company = GlobalInstructionPlugin("You work for Acme.")
 * val rules = GlobalInstructionPlugin({ context -> loadRules(context.userId) }, name = "rules")
 * InMemoryRunner(agent = agent, plugins = listOf(company, rules))
 * ```
 */
class GlobalInstructionPlugin : Plugin {
  override val name: String
  private val resolveInstruction: suspend (CallbackContext) -> String?

  /**
   * Creates a plugin that applies [globalInstruction], filling in session state placeholders such
   * as `{user_name}` for each request, as an agent's text instruction does. An instruction that
   * resolves to an empty string leaves the request unchanged. If a placeholder names a missing
   * state variable, [beforeModel] throws [IllegalArgumentException] unless the placeholder is
   * marked optional, as in `{nickname?}`.
   *
   * @param name The unique name of the plugin instance.
   */
  @JvmOverloads
  constructor(globalInstruction: String, name: String = DEFAULT_NAME) {
    this.name = name
    resolveInstruction = { context ->
      InstructionStateInjector.injectSessionState(context, globalInstruction)
    }
  }

  /**
   * Creates a plugin that applies the instruction [instructionProvider] returns for each request,
   * with no placeholders filled in. A `null` or empty instruction leaves the request unchanged.
   *
   * @param name The unique name of the plugin instance.
   */
  @JvmOverloads
  constructor(
    instructionProvider: suspend (ReadonlyContext) -> String?,
    name: String = DEFAULT_NAME,
  ) {
    this.name = name
    resolveInstruction = instructionProvider
  }

  override suspend fun beforeModel(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    val instruction = resolveInstruction(context)
    if (instruction.isNullOrEmpty()) return CallbackChoice.Continue(request)
    val existing = request.config.systemInstruction
    val parts =
      if (existing == null || existing.parts.isEmpty()) {
        listOf(Part(text = instruction))
      } else {
        // A blank line separates the texts, as LlmRequest.appendInstructions separates its parts.
        listOf(Part(text = "$instruction\n\n")) + existing.parts
      }
    val systemInstruction = existing?.copy(parts = parts) ?: Content(parts = parts)
    return CallbackChoice.Continue(
      request.copy(config = request.config.copy(systemInstruction = systemInstruction))
    )
  }

  companion object {
    private const val DEFAULT_NAME = "global_instruction"
  }
}
