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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.JoinNode
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Route
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * An app's root and everything under it, as the Dev UI reads it from `/dev/build_graph`.
 *
 * Keys are snake_case whatever the server's camelCase setting, because that is how the Dev UI reads
 * this payload.
 */
@Serializable
internal data class AppGraph(val name: String, @SerialName("root_agent") val rootAgent: GraphNode)

/**
 * One node of an app: an agent with its [subAgents] and [tools], a workflow with its [graph], or
 * any other node.
 *
 * @property type How the graph draws the node: `agent`, `workflow`, `join`, `start` or `node`.
 */
@Serializable
internal data class GraphNode(
  val name: String,
  val type: String,
  @SerialName("sub_agents") val subAgents: List<GraphNode>? = null,
  val tools: List<GraphTool>? = null,
  val graph: WorkflowGraph? = null,
)

@Serializable internal data class GraphTool(val name: String, val type: String)

@Serializable
internal data class WorkflowGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

/**
 * An edge between two nodes, named by [GraphNodeRef]; [route] is absent on an unconditional one.
 */
@Serializable
internal data class GraphEdge(
  @SerialName("from_node") val from: GraphNodeRef,
  @SerialName("to_node") val to: GraphNodeRef,
  val route: JsonElement? = null,
)

@Serializable internal data class GraphNodeRef(val name: String, val type: String)

internal const val AGENT_NODE_TYPE = "agent"

internal const val WORKFLOW_NODE_TYPE = "workflow"

internal const val TOOL_NODE_TYPE = "tool"

internal const val JOIN_NODE_TYPE = "join"

/** Describes the app [appName], rooted at [root]. */
internal fun appGraphOf(appName: String, root: Node): AppGraph =
  AppGraph(appName, graphNodeOf(root))

/** Describes [node] and everything under it. */
internal fun graphNodeOf(node: Node): GraphNode =
  when (node) {
    is Workflow -> GraphNode(node.name, typeOf(node), graph = workflowGraphOf(node))
    is BaseAgent ->
      GraphNode(
        node.name,
        typeOf(node),
        subAgents = node.subAgents.map(::graphNodeOf),
        tools = (node as? LlmAgent)?.let(::toolsOf),
      )
    else -> GraphNode(node.name, typeOf(node))
  }

/** Returns the node type string used by the Dev UI graph renderer for [node]. */
private fun typeOf(node: Node): String =
  when (node) {
    is Start -> "start"
    is Workflow -> WORKFLOW_NODE_TYPE
    is BaseAgent -> AGENT_NODE_TYPE
    is JoinNode -> JOIN_NODE_TYPE
    else -> "node"
  }

private fun workflowGraphOf(workflow: Workflow): WorkflowGraph? {
  if (workflow.edges.isEmpty()) return null
  // Preserve first-appearance order and deduplicate by node identity.
  val nodes = mutableListOf<Node>()
  for (edge in workflow.edges) {
    for (node in listOf(edge.from, edge.to)) {
      if (nodes.none { it === node }) nodes.add(node)
    }
  }
  return WorkflowGraph(
    nodes = nodes.map(::graphNodeOf),
    edges = workflow.edges.map { GraphEdge(refOf(it.from), refOf(it.to), routeJsonOrNull(it)) },
  )
}

private fun refOf(node: Node) = GraphNodeRef(node.name, typeOf(node))

/**
 * Encodes [edge]'s routes as null when the edge is unconditional, a single route as its scalar, and
 * any other route list as an array.
 */
private fun routeJsonOrNull(edge: Edge): JsonElement? {
  if (edge.isUnconditional) return null
  val routes = edge.routes.orEmpty()
  return if (routes.size == 1) {
    Json.encodeToJsonElement(Route.serializer(), routes.single())
  } else {
    Json.encodeToJsonElement(ListSerializer(Route.serializer()), routes)
  }
}

/** The agent's tools and toolsets, minus any tool named after a sub-agent. */
private fun toolsOf(agent: LlmAgent): List<GraphTool> {
  val subAgentNames = agent.subAgents.map { it.name }.toSet()
  val names =
    agent.tools.map { it.name } + agent.toolsets.map { it::class.simpleName ?: TOOLSET_NAME }
  return names.filterNot { it in subAgentNames }.map { GraphTool(it, TOOL_NODE_TYPE) }
}

/** The name a toolset without a class name, such as an anonymous object, is drawn under. */
private const val TOOLSET_NAME = "Toolset"
