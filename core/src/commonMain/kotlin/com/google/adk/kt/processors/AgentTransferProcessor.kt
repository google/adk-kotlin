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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.TransferToAgentTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * An [LlmRequestProcessor] that adds agent transfer capabilities to the LLM request.
 *
 * This processor identifies potential transfer targets (sub-agents, parent, and peers), constructs
 * instructions for the LLM on how to use them, and adds a [TransferToAgentTool] to the request.
 */
internal class AgentTransferProcessor : LlmRequestProcessor {
  override suspend fun process(
    context: InvocationContext,
    request: LlmRequest,
    emitEvent: suspend (Event) -> Unit,
  ): LlmRequest {
    val agent = context.agent
    val targets = findTransferTargets(agent)

    if (targets.isEmpty()) {
      return request
    }

    val transferInstructions = buildTransferInstructions(agent, targets)
    val transferTool = TransferToAgentTool(targets.map { it.name })

    return request
      .appendInstructions(Content(parts = listOf(Part(text = transferInstructions))))
      .appendTools(listOf(transferTool))
  }

  private fun buildTargetAgentsInfo(targetAgent: BaseAgent) =
    """|Agent name: ${targetAgent.name}
       |Agent description: ${targetAgent.description}"""
      .trimMargin()

  private fun buildTransferInstructions(agent: BaseAgent, targets: List<BaseAgent>): String {
    val toolName = TransferToAgentTool.TRANSFER_TO_AGENT_TOOL_NAME
    val availableAgentNames = targets.map { it.name }.sorted()
    val formattedAgentNames = availableAgentNames.joinToString(", ") { "`$it`" }

    val agentsInfo = targets.joinToString("\n\n") { buildTargetAgentsInfo(it) }

    return buildString {
      append(
        """|You have a list of other agents to transfer to:
           |
           |$agentsInfo
           |
           |If you are the best to answer the question according to your description,
           |you can answer it.
           |
           |If another agent is better for answering the question according to its
           |description, call `$toolName` function to transfer the question to that
           |agent. When transferring, do not generate any text other than the function
           |call.
           |
           |**NOTE**: the only available agents for `$toolName` function are
           |$formattedAgentNames."""
          .trimMargin()
      )

      val parent = agent.parentAgent
      if (parent != null && !agent.disallowTransferToParent) {
        append(
          "\n\nIf neither you nor the other agents are best for the question, transfer to your parent agent ${parent.name}."
        )
      }
    }
  }
}

/**
 * Returns the agents [agent] may transfer control to: its sub-agents, and (when permitted) its
 * parent and peers.
 *
 * Exposed at package scope so the output-schema gating ([BasicRequestProcessor] and
 * [OutputSchemaProcessor]) can tell whether [AgentTransferProcessor] will attach a
 * `transfer_to_agent` tool to the request, since that tool is subject to the same Gemini 2.x
 * "response schema cannot be combined with tools" limitation as user-declared tools.
 */
internal fun findTransferTargets(agent: BaseAgent): List<BaseAgent> {
  val targets = buildList {
    // Sub-agents are always potential targets.
    addAll(agent.subAgents)

    val parent = agent.parentAgent
    if (parent != null) {
      // Parent agent is a potential target if allowed.
      if (!agent.disallowTransferToParent) {
        add(parent)
      }

      // Peer agents are potential targets if allowed.
      if (!agent.disallowTransferToPeers) {
        addAll(parent.subAgents.filter { it.name != agent.name })
      }
    }
  }

  val uniqueTargets = targets.distinctBy { it.name }
  require(uniqueTargets.size == targets.size) {
    val duplicateNames = targets.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    "Duplicate agent names found in transfer targets: ${duplicateNames.joinToString()}. Agent names must be unique within reachable transfer scope."
  }
  return uniqueTargets
}
