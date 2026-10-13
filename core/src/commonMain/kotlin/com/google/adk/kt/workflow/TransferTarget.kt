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

/**
 * Where a `transfer_to_agent` hands off to: the [agent] to run next and the [parent] it runs under.
 */
internal data class TransferTarget(val agent: BaseAgent, val parent: Context)

/**
 * Resolves the agent [targetName] names for a transfer requested by [current], which ran as
 * [currentContext] under [parentContext], and the context the target runs under. Only a child, the
 * parent, or a sibling of [current] can be reached.
 *
 * @throws IllegalArgumentException for a transfer to itself, a transfer the flags forbid, or a
 *   target that is unknown or unrelated to [current].
 */
internal fun resolveTransferTarget(
  targetName: String,
  current: BaseAgent,
  currentContext: Context,
  parentContext: Context,
): TransferTarget {
  require(targetName != current.name) { "Agent '$targetName' cannot transfer to itself." }
  current.subAgents
    .firstOrNull { it.name == targetName }
    ?.let {
      return TransferTarget(it, currentContext)
    }
  val parent = current.parentAgent
  // Checked before siblings, so a parent with a sub-agent named after itself still wins.
  if (parent != null && parent.name == targetName) {
    require(!current.disallowTransferToParent) {
      "Cannot transfer from '${current.name}' to parent agent '$targetName':" +
        " disallow_transfer_to_parent is set."
    }
    // The parent runs again where its earlier run ran, else at the outermost context.
    val chain = generateSequence(currentContext) { it.parent }.toList()
    val parentRun = chain.firstOrNull { it.node.name == targetName }
    return TransferTarget(parent, parentRun?.parent ?: chain.last())
  }
  parent
    ?.subAgents
    ?.firstOrNull { it.name == targetName }
    ?.let { sibling ->
      require(!current.disallowTransferToPeers) {
        "Cannot transfer from '${current.name}' to peer agent '$targetName':" +
          " disallow_transfer_to_peers is set."
      }
      return TransferTarget(sibling, parentContext)
    }
  val root = current.rootAgent
  requireNotNull(root.findAgent(targetName)) { "Transfer target agent '$targetName' not found." }
  throw IllegalArgumentException(
    "Cannot transfer from '${current.name}' to unrelated agent '$targetName'.\n" +
      "Available agents: ${agentNames(root).joinToString(", ")}"
  )
}

/** Returns the names of [agent] and every agent below it, depth first. */
private fun agentNames(agent: BaseAgent): List<String> =
  listOf(agent.name) + agent.subAgents.flatMap(::agentNames)
