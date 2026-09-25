/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Tool
import com.google.adk.kt.webserver.models.AgentInfo
import com.google.adk.kt.webserver.models.AppInfo
import java.lang.invoke.MethodHandles
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

/**
 * Describes the app named [appName] and rooted at [rootAgent].
 *
 * Reports every [LlmAgent] the root reaches, whether through sub-agents, an [AgentTool], or an
 * [AgentTool] a [Toolset] returns; an agent of any other type is traversed through rather than
 * reported, so nesting one never hides the agents below it.
 */
internal suspend fun buildAppInfo(appName: String, rootAgent: BaseAgent): AppInfo {
  val walk = AgentWalk()
  walk.visit(rootAgent)

  val agents = LinkedHashMap<String, AgentInfo>()
  for (agent in walk.reported) {
    if (agents.containsKey(agent.name)) {
      // Agent names are not unique app-wide, and the response is keyed by name.
      logger.warn("Reporting only the first agent named '{}'.", agent.name)
      continue
    }
    agents[agent.name] =
      AgentInfo(
        name = agent.name,
        description = agent.description,
        instruction = instructionOf(agent),
        tools = declarationsOf(walk.toolsOf(agent)),
        subAgents = nearestLlmAgentNames(agent),
      )
  }

  return AppInfo(
    name = appName,
    rootAgentName = rootAgent.name,
    description = rootAgent.description,
    language = "kotlin",
    agents = agents,
  )
}

/** One traversal of one agent tree; single use, since it accumulates what it has seen. */
private class AgentWalk {
  // Identity, not name: two agents may share a name, and dropping one silently is the bug.
  private val visited = Collections.newSetFromMap(IdentityHashMap<BaseAgent, Boolean>())
  private val toolsByAgent = IdentityHashMap<LlmAgent, List<BaseTool>>()

  /** Reported agents in the order reached, so the first of a duplicated name wins. */
  val reported = mutableListOf<LlmAgent>()

  /** The tools of [agent], resolved during the walk so its toolsets are enumerated only once. */
  fun toolsOf(agent: LlmAgent): List<BaseTool> = toolsByAgent.getValue(agent)

  suspend fun visit(agent: BaseAgent) {
    if (!visited.add(agent)) return
    if (agent !is LlmAgent) {
      agent.subAgents.forEach { visit(it) }
      return
    }

    reported.add(agent)
    val tools = agent.tools + agent.toolsets.flatMap { enumerate(it) }
    toolsByAgent[agent] = tools

    agent.subAgents.forEach { visit(it) }
    tools.forEach { if (it is AgentTool) visit(it.agent) }
  }

  /** The tools of [toolset], or none when it cannot be enumerated - a partial answer beats none. */
  private suspend fun enumerate(toolset: Toolset): List<BaseTool> =
    try {
      toolset.getTools()
    } catch (e: Exception) {
      // Rethrows when this request was cancelled; a timeout the toolset itself raised is not that.
      currentCoroutineContext().ensureActive()
      // Type only: a failure message can carry caller or model content.
      logger.warn("Skipping a toolset that failed to enumerate: {}", e::class.simpleName)
      emptyList()
    }
}

/**
 * The names of the nearest [LlmAgent]s below [agent] on the sub-agent edge.
 *
 * Agents of other types are transparent, so a workflow agent between two LLM agents does not break
 * the chain; tool edges are excluded, matching the reference implementation.
 */
private fun nearestLlmAgentNames(agent: LlmAgent): List<String> {
  val names = mutableListOf<String>()
  // Construction forbids a second parent, but a list mutated afterwards escapes that check.
  val seen = Collections.newSetFromMap(IdentityHashMap<BaseAgent, Boolean>())

  fun descend(current: BaseAgent) {
    if (!seen.add(current)) return
    for (sub in current.subAgents) {
      if (sub is LlmAgent) names.add(sub.name) else descend(sub)
    }
  }

  descend(agent)
  // Two agents may share a name, and the response holds one entry per name.
  return names.distinct()
}

/** One [Tool] per declaration, as the reference implementation emits; undeclared tools are cut. */
private fun declarationsOf(tools: List<BaseTool>): List<Tool> =
  tools.mapNotNull { declarationOrNull(it) }.map { Tool(functionDeclarations = listOf(it)) }

/** The declaration of [tool], or none when it reports none or throws building one. */
private fun declarationOrNull(tool: BaseTool): FunctionDeclaration? =
  try {
    tool.declaration()
  } catch (e: Exception) {
    // Type only: a failure message can carry caller or model content.
    logger.warn("Skipping a tool that failed to declare itself: {}", e::class.simpleName)
    null
  }

/**
 * The whole instruction of [agent]: its static content first, then the per-turn instruction.
 *
 * Both are prompt material a grader needs, and the static half carries the system instruction
 * whenever it is set, so reporting only one of them can hide the prompt entirely. Only text is
 * rendered, so a static instruction carrying just an image reports nothing.
 */
private fun instructionOf(agent: LlmAgent): String =
  listOf(agent.staticInstruction?.text(separator = "\n").orEmpty(), textOf(agent.instruction))
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/** Renders [instruction] without resolving a provider, which would need an invocation. */
private fun textOf(instruction: Instruction?): String =
  when (instruction) {
    null -> ""
    is Instruction.Text -> instruction.text
    is Instruction.Structured -> instruction.content.text(separator = "\n")
    is Instruction.Provider -> providerMarker(instruction)
  }

/**
 * A placeholder standing in for a provider that was not resolved, never prompt text itself.
 *
 * It names the provider when there is a source-level name to give; the `Instruction { }` factory
 * wraps its argument in a lambda of its own, so only a provider written as a named class has one.
 */
private fun providerMarker(provider: Instruction.Provider): String {
  val name = provider::class.simpleName
  return if (name == null || '$' in name) "<InstructionProvider>"
  else "<InstructionProvider: $name>"
}
