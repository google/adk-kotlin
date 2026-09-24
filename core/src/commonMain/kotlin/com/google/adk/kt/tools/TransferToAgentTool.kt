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
 * A specialized [BaseTool] for agent transfer with enum constraints.
 *
 * This tool enhances the base transfer_to_agent behavior by adding enum constraints to the
 * agent_name parameter. This prevents LLMs from hallucinating invalid agent names by restricting
 * choices to only valid agents.
 *
 * @property agentNames List of valid agent names that can be transferred to.
 */
internal class TransferToAgentTool(val agentNames: List<String>) :
  BaseTool(
    name = TRANSFER_TO_AGENT_TOOL_NAME,
    description =
      """
      |Transfer the question to another agent.
      |
      |This tool hands off control to another agent when it's more suitable to
      |answer the user's question according to the agent's description.
      """
        .trimMargin(),
  ) {

  companion object {
    const val TRANSFER_TO_AGENT_TOOL_NAME = "transfer_to_agent"
  }

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "agent_name" to
                Schema(
                  type = Type.STRING,
                  description = "the agent name to transfer to.",
                  enum = agentNames,
                )
            ),
          required = listOf("agent_name"),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    (args["agent_name"] as? String)?.let { context.actions.transferToAgent = it }
    return Unit
  }
}
