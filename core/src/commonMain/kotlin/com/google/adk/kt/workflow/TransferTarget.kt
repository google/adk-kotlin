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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.findAgent
import com.google.adk.kt.agents.runsAsTool
import com.google.adk.kt.annotations.ExperimentalWorkflowApi

/**
 * Where a `transfer_to_agent` hands off to: the [agent] to run next and the [parent] it runs under,
 * or null to run at the top of the invocation, beside its root node.
 */
internal data class TransferTarget(val agent: BaseAgent, val parent: Context?)

/**
 * Resolves the agent [targetName] names for a transfer requested by [current], which ran as
 * [currentContext] under [parentContext] (null at the top of the invocation), and the context the
 * target runs under. Only a child, the parent, or a sibling of [current] can be reached, and a task
 * or single-turn child or sibling is not, as the `transfer_to_agent` tool does not offer it.
 *
 * @throws IllegalArgumentException for a transfer to itself, a transfer the flags forbid, a target
 *   that is a task or single-turn agent, or one that is unknown or unrelated to [current].
 */
internal fun resolveTransferTarget(
  targetName: String,
  current: BaseAgent,
  currentContext: Context,
  parentContext: Context?,
): TransferTarget {
  require(targetName != current.name) { "Agent '$targetName' cannot transfer to itself." }
  current.subAgents
    .firstOrNull { it.name == targetName }
    ?.let {
      requireTransferTarget(it)
      return TransferTarget(it, currentContext)
    }
  val parent = current.parentAgent
  // Checked before siblings, so a parent with a sub-agent named after itself still wins.
  if (parent != null && parent.name == targetName) {
    require(!current.disallowTransferToParent) {
      "Cannot transfer from '${current.name}' to parent agent '$targetName':" +
        " disallow_transfer_to_parent is set."
    }
    // As in ADK Python, the parent runs again where it ran before, or else at the top level.
    val parentRun =
      generateSequence(currentContext) { it.parent }.firstOrNull { it.node.name == targetName }
    return TransferTarget(parent, parentRun?.parent)
  }
  parent
    ?.subAgents
    ?.firstOrNull { it.name == targetName }
    ?.let { sibling ->
      require(!current.disallowTransferToPeers) {
        "Cannot transfer from '${current.name}' to peer agent '$targetName':" +
          " disallow_transfer_to_peers is set."
      }
      requireTransferTarget(sibling)
      return TransferTarget(sibling, parentContext)
    }
  val root = current.rootAgent
  // The name comes from the model, so the message leaves it out.
  requireNotNull(root.findAgent(targetName)) {
    "Agent '${current.name}' requested a transfer to an agent that is not in the agent tree."
  }
  throw IllegalArgumentException(
    "Cannot transfer from '${current.name}' to unrelated agent '$targetName'.\n" +
      "Available agents: ${agentNames(root).joinToString(", ")}"
  )
}

/**
 * Throws if [agent] runs as a tool, as a task or single-turn agent does, rather than by transfer.
 */
private fun requireTransferTarget(agent: BaseAgent) {
  require(!(agent as? LlmAgent)?.mode.runsAsTool) {
    "Cannot transfer to '${agent.name}': a task or single-turn agent runs as a tool."
  }
}

/** Returns the names of [agent] and every agent below it, depth first. */
private fun agentNames(agent: BaseAgent): List<String> =
  listOf(agent.name) + agent.subAgents.flatMap(::agentNames)
