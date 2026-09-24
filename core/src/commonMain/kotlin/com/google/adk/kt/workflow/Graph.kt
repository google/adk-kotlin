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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.logging.LoggerFactory

/**
 * A workflow graph, assembled but not yet validated. Nodes are derived from the edges, deduplicated
 * by identity and kept in the order they first appear, so scheduling is deterministic.
 */
@ExperimentalWorkflowApi
internal class Graph private constructor(val nodes: List<Node>, val edges: List<Edge>) {

  /** Names of the nodes with no outgoing edge; their outputs are the graph's own. */
  val terminalNodeNames: Set<String> =
    nodes
      .asSequence()
      .filter { it.name != START_NODE_NAME }
      .map { it.name }
      .filterNot { name -> edges.any { it.from.name == name } }
      .toSet()

  /** Internal unmodifiable lookup table of nodes keyed by name. */
  private val nodesByName: Map<String, Node> = nodes.associateBy { it.name }

  /** Returns the node called [name], or throws when the graph has none. */
  fun node(name: String): Node =
    nodesByName[name] ?: throw IllegalArgumentException("No node named '$name' in the graph.")

  /** Returns the names of the nodes that [nodeName] triggers, given the routes it emitted. */
  fun nodesTriggeredBy(nodeName: String, emittedRoutes: List<Route>?): List<String> {
    val outgoing = edges.filter { it.from.name == nodeName }
    val triggered = mutableListOf<String>()
    var matchedSpecificRoute = false
    val defaultTargets = mutableListOf<String>()

    for (edge in outgoing) {
      if (edge.isUnconditional) {
        triggered.add(edge.to.name)
      } else if (edge.routes.contains(Route.Default)) {
        defaultTargets.add(edge.to.name)
      } else if (emittedRoutes != null && edge.routes.any { it in emittedRoutes }) {
        triggered.add(edge.to.name)
        matchedSpecificRoute = true
      }
    }

    // Every default edge fires together, so a fallback can fan out like any other route.
    if (!matchedSpecificRoute) triggered.addAll(defaultTargets)

    if (triggered.isEmpty() && outgoing.any { !it.isUnconditional }) {
      // Routing edges exist but nothing matched: warn so a route typo is distinguishable from a
      // deliberate dead end. Only the node name is logged, never the emitted routes.
      logger.warn {
        "Node '$nodeName' has routing edges but no emitted route matched; the branch will end."
      }
    }
    return triggered
  }

  /** Returns the names of the nodes with an edge into [nodeName]. */
  fun predecessorsOf(nodeName: String): Set<String> =
    edges.filter { it.to.name == nodeName }.map { it.from.name }.toSet()

  companion object {
    private val logger = LoggerFactory.getLogger(Graph::class)

    /** Builds a graph from [edges]. */
    fun of(edges: List<Edge>): Graph {
      val nodes = mutableListOf<Node>()
      for (edge in edges) {
        for (node in listOf(edge.from, edge.to)) {
          if (nodes.none { it === node }) nodes.add(node)
        }
      }
      // Node names form the `name@runId` path segments, so they are checked when the graph is
      // built. An agent name is looser than a node name, so an agent whose name a graph cannot
      // carry is rejected here rather than part way through a run.
      for (node in nodes) validateNodeName(node.name)
      val graph = Graph(nodes.toList(), edges)
      // TODO: the rest of graph validation is added in a later change; until then a malformed
      // graph fails at run time rather than being rejected here.
      return graph
    }
  }
}
