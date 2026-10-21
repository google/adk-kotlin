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
import com.google.adk.kt.agents.findAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.processors.isTransferTarget

/**
 * Where a `transfer_to_agent` hands off to: the [agent] to run next and the [parent] it runs under,
 * or null to run at the top of the invocation, beside its root node.
 */
internal data class TransferTarget(val agent: BaseAgent, val parent: Context?)

/**
 * Follows the `transfer_to_agent` handoffs that [first], a run of [node] under [parent] (null at
 * the top of the invocation), requests, and returns the context of the agent that ends the loop:
 * one that fails, pauses, or finishes without a transfer. [runHop] runs each target under the
 * context it resolved to, or at the top of the invocation for null.
 */
@ExperimentalWorkflowApi
internal suspend fun followHandoffs(
  node: Node,
  first: Context,
  parent: Context?,
  runHop: suspend (agent: BaseAgent, parent: Context?) -> Context,
): Context {
  var parentContext = parent
  var current = node
  var hop = first
  while (true) {
    val state = hop.requireNodeState()
    if (state.failure != null || state.isPaused) return hop
    val targetName = hop.actions.transferToAgent ?: return hop
    require(current is BaseAgent) { "Only agents can request an agent transfer." }
    val target = resolveTransferTarget(targetName, current, hop, parentContext)
    parentContext = target.parent
    current = target.agent
    hop = runHop(target.agent, target.parent)
  }
}

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
 * Returns the peer agent [targetName] names when [current] may hand off to it on its own parent
 * context, or null when [targetName] resolves to [current] itself, a sub-agent, the parent, a
 * non-transfer agent, or an unrelated agent.
 */
internal fun resolveSiblingTransferTarget(targetName: String, current: BaseAgent): BaseAgent? {
  if (targetName == current.name) return null
  if (current.subAgents.any { it.name == targetName }) return null
  val parent = current.parentAgent ?: return null
  if (parent.name == targetName || current.disallowTransferToPeers) return null
  val sibling = parent.subAgents.firstOrNull { it.name == targetName } ?: return null
  return sibling.takeIf { it.isTransferTarget() }
}

/**
 * Throws if [agent] runs as a tool, as a task or single-turn agent does, rather than by transfer.
 */
private fun requireTransferTarget(agent: BaseAgent) {
  require(agent.isTransferTarget()) {
    "Cannot transfer to '${agent.name}': a task or single-turn agent runs as a tool."
  }
}

/** Returns the names of [agent] and every agent below it, depth first. */
private fun agentNames(agent: BaseAgent): List<String> =
  listOf(agent.name) + agent.subAgents.flatMap(::agentNames)
