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

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.webserver.loaders.ServedApps
import org.slf4j.LoggerFactory

/**
 * Utility class for generating Graphviz DOT representations of agent structures.
 *
 * @param servedApps Where to read each app's root agent from.
 */
internal class AgentGraphGenerator(private val servedApps: ServedApps) {

  enum class HighlightDirection {
    NONE,
    FORWARD,
    REVERSE,
  }

  companion object Colors {
    private val logger = LoggerFactory.getLogger(AgentGraphGenerator::class.java)

    private const val COLOR_DARK_GREEN = "#0F5223"
    private const val COLOR_LIGHT_GREEN = "#69CB87"
    private const val COLOR_LIGHT_GRAY = "#B0B0B0"
    private const val COLOR_BACKGROUND = "#FAFAFA"
  }

  /**
   * Generates a Graphviz DOT representation of the agent structure.
   *
   * @param agentName The name of the agent to generate the graph for.
   * @param highlightPairs A list of pairs of node names to highlight in the graph.
   * @return The Graphviz DOT representation of the agent structure.
   */
  fun generateGraph(
    agentName: String,
    highlightPairs: List<Pair<String, String>> = emptyList(),
  ): String {
    val agent = servedApps.loadRoot(agentName) as? BaseAgent ?: return ""
    return generateGraph(agent, highlightPairs)
  }

  /**
   * Generates a Graphviz DOT representation of the agent structure.
   *
   * @param rootAgent The root agent to generate the graph for.
   * @param highlightPairs A list of pairs of node names to highlight in the graph.
   * @return The Graphviz DOT representation of the agent structure.
   */
  fun generateGraph(rootAgent: BaseAgent, highlightPairs: List<Pair<String, String>>): String {
    val statements = mutableListOf<String>()
    val visitedNodes = mutableSetOf<String>()
    buildGraphRecursive(statements, rootAgent, highlightPairs, visitedNodes)

    return buildString {
      // Strict, as in Python ADK: an edge listed twice is drawn once.
      appendLine("strict digraph \"agent_schema\" {")
      appendLine("  graph${formatAttributes("fontcolor" to COLOR_BACKGROUND)}")
      for (statement in statements) {
        appendLine("  $statement")
      }
      append("}")
    }
  }

  private fun buildGraphRecursive(
    statements: MutableList<String>,
    agent: BaseAgent,
    highlightPairs: List<Pair<String, String>>,
    visitedNodes: MutableSet<String>,
  ) {
    val agentName = getNodeName(agent)
    if (agentName.isNotEmpty() && visitedNodes.add(agentName)) {
      statements += createNode(agent, highlightPairs)
    }

    for (subAgent in agent.subAgents) {
      val subAgentName = getNodeName(subAgent)
      statements += createEdge(agentName, subAgentName, highlightPairs)
      buildGraphRecursive(statements, subAgent, highlightPairs, visitedNodes)
    }

    if (agent is LlmAgent) {
      for (tool in agent.tools) {
        val toolName = getNodeName(tool)
        if (toolName.isNotEmpty() && visitedNodes.add(toolName)) {
          statements += createNode(tool, highlightPairs)
        }
        statements += createEdge(agentName, toolName, highlightPairs)
      }
    }
  }

  private fun createNode(toolOrAgent: Any, highlightPairs: List<Pair<String, String>>): String {
    val name = getNodeName(toolOrAgent)
    val shape = getNodeShape(toolOrAgent)
    val caption = getNodeCaption(toolOrAgent)
    val isHighlighted = isNodeHighlighted(name, highlightPairs)

    val style = if (isHighlighted) "filled" else "rounded"
    val color = if (isHighlighted) COLOR_DARK_GREEN else COLOR_LIGHT_GRAY
    val nodeAttributes =
      formatAttributes(
        "label" to caption,
        "shape" to shape,
        "fontcolor" to COLOR_LIGHT_GRAY,
        "style" to style,
        "color" to color,
      )
    return "${quote(name)}$nodeAttributes"
  }

  private fun createEdge(
    fromName: String,
    toName: String,
    highlightPairs: List<Pair<String, String>>,
  ): String {
    if (fromName.isEmpty() || toName.isEmpty()) {
      throw IllegalArgumentException("Edge names cannot be empty: from='$fromName', to='$toName'")
    }

    val edgeAttributes =
      when (isEdgeHighlighted(fromName, toName, highlightPairs)) {
        HighlightDirection.FORWARD -> formatAttributes("color" to COLOR_LIGHT_GREEN)
        HighlightDirection.REVERSE ->
          formatAttributes("color" to COLOR_LIGHT_GREEN, "dir" to "back")
        HighlightDirection.NONE ->
          formatAttributes("color" to COLOR_LIGHT_GRAY, "arrowhead" to "none")
      }
    return "${quote(fromName)} -> ${quote(toName)}$edgeAttributes"
  }

  private fun getNodeName(toolOrAgent: Any): String {
    return when (toolOrAgent) {
      is BaseAgent -> toolOrAgent.name
      is BaseTool -> toolOrAgent.name
      else -> {
        logger.warn("Unsupported type for getNodeName: {}", toolOrAgent.javaClass.name)
        "unknown_${toolOrAgent.hashCode()}"
      }
    }
  }

  private fun getNodeCaption(toolOrAgent: Any): String {
    val name = getNodeName(toolOrAgent)
    return when (toolOrAgent) {
      is BaseAgent -> "🤖 $name"
      is AgentTool -> "🤖 $name"
      is FunctionTool -> "🔧 $name"
      is BaseTool -> "🔧 $name"
      else -> {
        logger.warn("Unsupported type for getNodeCaption: {}", toolOrAgent.javaClass.name)
        "❓ $name"
      }
    }
  }

  private fun getNodeShape(toolOrAgent: Any): String {
    return when (toolOrAgent) {
      is BaseAgent -> "ellipse"
      is FunctionTool -> "box"
      is BaseTool -> "box"
      else -> {
        logger.warn("Unsupported type for getNodeShape: {}", toolOrAgent.javaClass.name)
        "egg"
      }
    }
  }

  private fun isNodeHighlighted(
    nodeName: String,
    highlightPairs: List<Pair<String, String>>,
  ): Boolean {
    return highlightPairs.any { pair -> pair.first == nodeName || pair.second == nodeName }
  }

  private fun isEdgeHighlighted(
    fromName: String,
    toName: String,
    highlightPairs: List<Pair<String, String>>,
  ): HighlightDirection {
    for (pair in highlightPairs) {
      val pairFrom = pair.first
      val pairTo = pair.second
      if (fromName == pairFrom && toName == pairTo) {
        return HighlightDirection.FORWARD
      }
      if (fromName == pairTo && toName == pairFrom) {
        return HighlightDirection.REVERSE
      }
    }
    return HighlightDirection.NONE
  }
}

/** Formats DOT attributes as ` [key="value", ...]`. */
private fun formatAttributes(vararg attributes: Pair<String, String>): String =
  attributes.joinToString(prefix = " [", postfix = "]") { (key, value) -> "$key=${quote(value)}" }

/** Quotes [value] as a DOT string, escaping backslashes and double quotes. */
private fun quote(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}
