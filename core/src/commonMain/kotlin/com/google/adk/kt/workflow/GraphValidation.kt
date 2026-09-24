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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi

/**
 * Checks a graph is runnable, throwing [GraphValidationException] on the first problem.
 *
 * Every rule here rejects a graph that would otherwise fail confusingly at run time, or not fail at
 * all: a node nothing reaches never runs, and an unconditional cycle never terminates.
 */
internal fun validateGraph(nodes: List<Node>, edges: List<Edge>) {
  val nodeNames = validateUniqueNames(nodes)
  validateStartPresent(nodeNames)
  validateStartEdges(edges)
  validateConnectivity(edges, nodeNames)
  validateNoDuplicateEdges(edges)
  validateDefaultRoutes(edges)
  validateNoUnconditionalCycles(edges, nodeNames)
}

private fun fail(message: String): Nothing =
  throw GraphValidationException("Graph validation failed. $message")

private fun validateUniqueNames(nodes: List<Node>): Set<String> {
  val byName = nodes.groupBy { it.name }
  val duplicates = byName.filterValues { it.size > 1 }.keys.sorted()
  if (duplicates.isNotEmpty()) {
    fail(
      "Duplicate node names found: $duplicates. Distinct node objects share a name. To reuse a" +
        " node, pass the same instance; to keep them distinct, give them different names."
    )
  }
  return byName.keys
}

private fun validateStartPresent(nodeNames: Set<String>) {
  if (START_NODE_NAME !in nodeNames) {
    fail("START node (name: '$START_NODE_NAME') not found in graph nodes.")
  }
}

private fun validateStartEdges(edges: List<Edge>) {
  for (edge in edges) {
    if (edge.from.name == START_NODE_NAME && !edge.isUnconditional) {
      fail(
        "Edges from START must not have routes (edge to ${edge.to.name} has route" +
          " ${edge.routes.joinToString { it.label() }})."
      )
    }
  }
}

/** The route as adk-python prints it: its bare value, or `__DEFAULT__` for the default route. */
private fun Route.label(): String =
  when (this) {
    is Route.Tag -> value
    is Route.Num -> value.toString()
    is Route.Flag -> value.toString()
    Route.Default -> Route.DEFAULT_ROUTE_SENTINEL
  }

private fun validateConnectivity(edges: List<Edge>, nodeNames: Set<String>) {
  val adjacency = edges.groupBy({ it.from.name }, { it.to.name })
  val reachable = mutableSetOf<String>()
  val stack = ArrayDeque(listOf(START_NODE_NAME))
  while (stack.isNotEmpty()) {
    val current = stack.removeLast()
    if (!reachable.add(current)) continue
    adjacency[current]?.forEach { if (it !in reachable) stack.addLast(it) }
  }

  val unreachable = (nodeNames - reachable).sorted()
  if (unreachable.isNotEmpty()) {
    fail("The following nodes are unreachable from START: $unreachable")
  }
  if (edges.any { it.to.name == START_NODE_NAME }) {
    fail("START node must not have incoming edges.")
  }
}

/**
 * Rejects a second edge between the same two nodes unless both are routed and share no route, since
 * an overlapping route would trigger the target twice for the same emitted route.
 */
private fun validateNoDuplicateEdges(edges: List<Edge>) {
  // Null stands for an unconditional edge, which overlaps every other edge to the same target.
  val routesByPair = mutableMapOf<Pair<String, String>, MutableSet<Route?>>()
  for (edge in edges) {
    val routes: Set<Route?> = if (edge.isUnconditional) setOf(null) else edge.routes.toSet()
    val seen = routesByPair.getOrPut(edge.from.name to edge.to.name) { mutableSetOf() }
    if (seen.isNotEmpty() && (null in seen || null in routes || routes.any { it in seen })) {
      fail("Duplicate edge found: from=${edge.from.name}, to=${edge.to.name}")
    }
    seen.addAll(routes)
  }
}

private fun validateDefaultRoutes(edges: List<Edge>) {
  for (edge in edges) {
    if (Route.Default in edge.routes && edge.routes.size > 1) {
      fail(
        "DEFAULT_ROUTE cannot be combined with other routes in a list (edge from=" +
          "${edge.from.name}, to=${edge.to.name}). Use a separate edge for DEFAULT_ROUTE."
      )
    }
  }
}

private fun validateNoUnconditionalCycles(edges: List<Edge>, nodeNames: Set<String>) {
  val adjacency = edges.filter { it.isUnconditional }.groupBy({ it.from.name }, { it.to.name })
  val onStack = mutableSetOf<String>()
  val done = mutableSetOf<String>()
  val path = mutableListOf<String>()

  fun visit(node: String) {
    onStack.add(node)
    path.add(node)
    for (next in adjacency[node].orEmpty()) {
      if (next in onStack) {
        val cycle = path.subList(path.indexOf(next), path.size) + next
        fail(
          "Unconditional cycle detected: ${cycle.joinToString(" -> ")}. A cycle must include at" +
            " least one routed edge, or it never terminates."
        )
      }
      if (next !in done) visit(next)
    }
    path.removeAt(path.lastIndex)
    onStack.remove(node)
    done.add(node)
  }

  for (name in nodeNames) if (name !in done) visit(name)
}
