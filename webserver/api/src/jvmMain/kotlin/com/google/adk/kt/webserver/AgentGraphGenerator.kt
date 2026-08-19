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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.webserver.loaders.AgentLoader
import guru.nidi.graphviz.attribute.Arrow
import guru.nidi.graphviz.attribute.Color
import guru.nidi.graphviz.attribute.Label
import guru.nidi.graphviz.attribute.Shape
import guru.nidi.graphviz.attribute.Style
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory
import guru.nidi.graphviz.model.Link
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.model.MutableNode
import org.slf4j.LoggerFactory

/**
 * Utility class for generating Graphviz DOT representations of agent structures.
 *
 * @param agentLoader The agent loader to use for loading agents.
 */
class AgentGraphGenerator(private val agentLoader: AgentLoader) {

  enum class HighlightDirection {
    NONE,
    FORWARD,
    REVERSE,
  }

  companion object Colors {
    private val logger = LoggerFactory.getLogger(AgentGraphGenerator::class.java)

    private val COLOR_DARK_GREEN = Color.rgb("#0F5223")
    private val COLOR_LIGHT_GREEN = Color.rgb("#69CB87")
    private val COLOR_LIGHT_GRAY = Color.rgb("#B0B0B0")
    private val COLOR_BACKGROUND = Color.rgb("#FAFAFA")
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
    val agent = agentLoader.loadAgent(agentName) ?: return ""
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
    val graph =
      Factory.mutGraph("agent_schema").setDirected(true).graphAttrs().add(COLOR_BACKGROUND.font())

    val visitedNodes = mutableSetOf<String>()
    buildGraphRecursive(graph, rootAgent, highlightPairs, visitedNodes)

    return Graphviz.fromGraph(graph).render(Format.DOT).toString()
  }

  private fun buildGraphRecursive(
    graph: MutableGraph,
    agent: BaseAgent,
    highlightPairs: List<Pair<String, String>>,
    visitedNodes: MutableSet<String>,
  ) {
    val agentName = getNodeName(agent)
    if (agentName.isNotEmpty() && visitedNodes.add(agentName)) {
      graph.add(createNode(agent, highlightPairs))
    }

    for (subAgent in agent.subAgents) {
      val subAgentName = getNodeName(subAgent)
      graph.add(
        Factory.mutNode(agentName).addLink(createLink(agentName, subAgentName, highlightPairs))
      )
      buildGraphRecursive(graph, subAgent, highlightPairs, visitedNodes)
    }

    if (agent is LlmAgent) {
      for (tool in agent.tools) {
        val toolName = getNodeName(tool)
        if (toolName.isNotEmpty() && visitedNodes.add(toolName)) {
          graph.add(createNode(tool, highlightPairs))
        }
        graph.add(
          Factory.mutNode(agentName).addLink(createLink(agentName, toolName, highlightPairs))
        )
      }
    }
  }

  private fun createNode(
    toolOrAgent: Any,
    highlightPairs: List<Pair<String, String>>,
  ): MutableNode {
    val name = getNodeName(toolOrAgent)
    val shape = getNodeShape(toolOrAgent)
    val caption = getNodeCaption(toolOrAgent)
    val isHighlighted = isNodeHighlighted(name, highlightPairs)

    val node = Factory.mutNode(name).add(Label.of(caption)).add(shape).add(COLOR_LIGHT_GRAY.font())

    if (isHighlighted) {
      node.add(Style.FILLED)
      node.add(COLOR_DARK_GREEN)
    } else {
      node.add(Style.ROUNDED)
      node.add(COLOR_LIGHT_GRAY)
    }

    return node
  }

  private fun createLink(
    fromName: String,
    toName: String,
    highlightPairs: List<Pair<String, String>>,
  ): Link {
    if (fromName.isEmpty() || toName.isEmpty()) {
      throw IllegalArgumentException("Edge names cannot be empty: from='$fromName', to='$toName'")
    }

    val edgeDirection = isEdgeHighlighted(fromName, toName, highlightPairs)
    return Factory.to(Factory.mutNode(toName)).apply {
      if (edgeDirection != HighlightDirection.NONE) {
        with(COLOR_LIGHT_GREEN)
        if (edgeDirection == HighlightDirection.REVERSE) {
          with(Arrow.NORMAL.dir(Arrow.DirType.BACK))
        } else {
          with(Arrow.NORMAL)
        }
      } else {
        with(COLOR_LIGHT_GRAY, Arrow.NONE)
      }
    }
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

  private fun getNodeShape(toolOrAgent: Any): Shape {
    return when (toolOrAgent) {
      is BaseAgent -> Shape.ELLIPSE
      is FunctionTool -> Shape.BOX
      is BaseTool -> Shape.BOX
      else -> {
        logger.warn("Unsupported type for getNodeShape: {}", toolOrAgent.javaClass.name)
        Shape.EGG
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
