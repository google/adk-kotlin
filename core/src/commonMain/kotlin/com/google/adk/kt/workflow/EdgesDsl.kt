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

@file:JvmName("EdgesDsl")

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.tools.BaseTool
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.jvm.JvmName

/**
 * Marks the edges DSL receivers so that, inside a `thenRoute { }` routing map, the enclosing
 * builder's functions cannot be called without an explicit receiver. An edge call written there,
 * such as `a then b`, fails to compile instead of adding the edge to the enclosing `edges { }` or
 * `workflow { }` block.
 */
@DslMarker internal annotation class EdgesDslMarker

/**
 * Returns the edges declared by [block] for a [Workflow]; [workflow] builds both in one step.
 *
 * ```
 * val graphEdges = edges {
 *   Start.then(classify).thenRoute {
 *     "billing" routesTo billing
 *     otherwise(summarize)
 *   }
 *   billing.then(summarize)
 * }
 * val support = Workflow(name = "support_triage", edges = graphEdges)
 * ```
 *
 * When constructed, the [Workflow] checks the graph these edges form.
 *
 * @throws IllegalArgumentException if a routing map has no entries.
 * @throws IllegalStateException if an [EdgesBuilder.routeOn] was never completed with `to`.
 */
@ExperimentalWorkflowApi
fun edges(block: EdgesDslBlock<EdgesBuilder>): List<Edge> {
  val builder = EdgesBuilder()
  with(block) { builder.declare() }
  return builder.build()
}

/**
 * The receiver that collects the edges declared in an [edges][com.google.adk.kt.workflow.edges] or
 * [workflow] block. A node is identified by its instance, so create each node once and reuse it. A
 * [BaseTool] can be a `then` source or target or a route target, and runs as one node per workflow,
 * built by [BaseTool.asNode]; fan-out and join groups take nodes.
 */
@ExperimentalWorkflowApi
@EdgesDslMarker
class EdgesBuilder internal constructor() {

  private val edges = mutableListOf<Edge>()

  /** Routed edges started by [routeOn] that no [to] has completed yet. */
  private val pendingEdges = mutableListOf<PendingEdge>()

  private val toolNodes = ToolNodeMemo()

  /** Connects this node to [node] and returns [node], so a chain continues from it. */
  @CanIgnoreReturnValue
  infix fun Node.then(node: Node): Node {
    edges += Edge(this, node)
    return node
  }

  /** Connects this node to [tool]'s node and returns that node, so a chain continues from it. */
  @CanIgnoreReturnValue infix fun Node.then(tool: BaseTool): Node = then(toolNodes.nodeOf(tool))

  /** Connects this tool's node to [node] and returns [node], so a chain continues from it. */
  @CanIgnoreReturnValue
  infix fun BaseTool.then(node: Node): Node = toolNodes.nodeOf(this).then(node)

  /**
   * Connects this tool's node to [tool]'s node and returns that node, so a chain continues from it.
   */
  @CanIgnoreReturnValue
  infix fun BaseTool.then(tool: BaseTool): Node =
    toolNodes.nodeOf(this).then(toolNodes.nodeOf(tool))

  /**
   * Fans out: connects this node to [first], [second] and each of [rest], which then run
   * concurrently. A fan-out ends the statement; to continue once they all complete, gather them
   * with [joinFrom].
   */
  fun Node.then(first: Node, second: Node, vararg rest: Node) {
    for (node in listOf(first, second) + rest) edges += Edge(this, node)
  }

  /**
   * Fans in: connects [first] and each of [rest] to this join, which runs once all of them
   * complete. Returns the join, so a chain continues from it.
   */
  @CanIgnoreReturnValue
  fun JoinNode.joinFrom(first: Node, vararg rest: Node): Node {
    for (node in listOf(first) + rest) edges += Edge(node, this)
    return this
  }

  /** Starts an edge that fires when this node emits the [Route.Tag] [route]; [to] completes it. */
  infix fun Node.routeOn(route: String): PendingEdge = routeOn(Route.Tag(route))

  /** Starts an edge that fires when this node emits the [Route.Num] [route]; [to] completes it. */
  infix fun Node.routeOn(route: Int): PendingEdge = routeOn(Route.Num(route.toLong()))

  /** Starts an edge that fires when this node emits the [Route.Flag] [route]; [to] completes it. */
  infix fun Node.routeOn(route: Boolean): PendingEdge = routeOn(Route.Flag(route))

  /** Starts an edge that fires when this node emits [route]; [to] completes it. */
  infix fun Node.routeOn(route: Route): PendingEdge =
    PendingEdge(this, listOf(route)).also { pendingEdges += it }

  /**
   * Starts one edge that fires once when this node emits any of [routes] (built with [anyOf]); [to]
   * completes it.
   */
  infix fun Node.routeOn(routes: RouteSet): PendingEdge =
    PendingEdge(this, routes.routes).also { pendingEdges += it }

  /** Completes this routed edge at [node] and returns [node], so a chain continues from it. */
  @CanIgnoreReturnValue
  infix fun PendingEdge.to(node: Node): Node {
    pendingEdges.remove(this)
    edges += Edge(from, node, routes)
    return node
  }

  /** Completes this routed edge at [tool]'s node and returns that node. */
  @CanIgnoreReturnValue infix fun PendingEdge.to(tool: BaseTool): Node = to(toolNodes.nodeOf(tool))

  /** Groups string routes for [routeOn], so one edge fires on any of them. */
  fun anyOf(first: String, second: String, vararg rest: String): RouteSet =
    RouteSet(listOf(first, second, *rest).map(Route::Tag))

  /** Groups number routes for [routeOn], so one edge fires on any of them. */
  fun anyOf(first: Int, second: Int, vararg rest: Int): RouteSet =
    RouteSet((listOf(first, second) + rest.toList()).map { Route.Num(it.toLong()) })

  /** Groups routes for [routeOn], so one edge fires on any of them. */
  fun anyOf(first: Route, second: Route, vararg rest: Route): RouteSet =
    RouteSet(listOf(first, second, *rest))

  /**
   * Adds this node's routing map, with an edge for each route [block] maps to a node. A routing map
   * ends the statement; connect its targets onward in statements of their own. An empty map throws
   * [IllegalArgumentException].
   */
  infix fun Node.thenRoute(block: EdgesDslBlock<RouteMapBuilder>) {
    val routes = RouteMapBuilder(this, toolNodes)
    with(block) { routes.declare() }
    val routeEdges = routes.edges
    require(routeEdges.isNotEmpty()) { "The routing map of node '$name' needs at least one entry." }
    edges += routeEdges
  }

  /** Returns the edges declared so far, failing if a [routeOn] was left without a target. */
  internal fun build(): List<Edge> {
    check(pendingEdges.isEmpty()) {
      "routeOn from node '${pendingEdges.first().from.name}' has no target; complete it with" +
        " to(node)."
    }
    return edges.toList()
  }
}

/**
 * An edge started by [EdgesBuilder.routeOn], which has a source and its routes but no target yet.
 * [EdgesBuilder.to] completes it.
 */
@ExperimentalWorkflowApi
class PendingEdge internal constructor(internal val from: Node, internal val routes: List<Route>)

/**
 * Routes that select a single edge, which fires once when its source emits any of them. Separate
 * edges to the same target would fire once each. Build one with `anyOf(...)` in the workflow DSL.
 */
@ExperimentalWorkflowApi
class RouteSet internal constructor(routes: List<Route>) {
  internal val routes: List<Route> = routes.distinct()
}

/** The node each tool runs as, matched by identity so a tool used twice is one node. */
@ExperimentalWorkflowApi
internal class ToolNodeMemo {
  private val nodes = mutableListOf<Pair<BaseTool, Node>>()

  fun nodeOf(tool: BaseTool): Node =
    nodes.firstOrNull { it.first === tool }?.second
      ?: tool.asNode().also { nodes += Pair(tool, it) }
}

/**
 * The receiver of an [EdgesBuilder.thenRoute] block, which maps each route the source node may emit
 * to a target node. Mapping one route to several nodes fans out on that route.
 */
@ExperimentalWorkflowApi
@EdgesDslMarker
class RouteMapBuilder
internal constructor(private val from: Node, private val toolNodes: ToolNodeMemo) {

  internal val edges = mutableListOf<Edge>()

  /** Leads to [node] when the source node emits this string as a [Route.Tag]. */
  infix fun String.routesTo(node: Node) {
    Route.Tag(this).routesTo(node)
  }

  /** Leads to [node] when the source node emits this number as a [Route.Num]. */
  infix fun Int.routesTo(node: Node) {
    Route.Num(toLong()).routesTo(node)
  }

  /** Leads to [node] when the source node emits this flag as a [Route.Flag]. */
  infix fun Boolean.routesTo(node: Node) {
    Route.Flag(this).routesTo(node)
  }

  /** Leads to [node] when the source node emits this route. */
  infix fun Route.routesTo(node: Node) {
    edges += Edge(from, node, this)
  }

  /** Leads to [node] along one edge when the source node emits any of these routes. */
  infix fun RouteSet.routesTo(node: Node) {
    edges += Edge(from, node, routes)
  }

  /**
   * Leads to [node] when no other route of the source node matches, including when it emits none.
   */
  fun otherwise(node: Node) {
    Route.Default.routesTo(node)
  }

  /** Leads to [tool]'s node when the source node emits this string as a [Route.Tag]. */
  infix fun String.routesTo(tool: BaseTool) {
    routesTo(toolNodes.nodeOf(tool))
  }

  /** Leads to [tool]'s node when the source node emits this number as a [Route.Num]. */
  infix fun Int.routesTo(tool: BaseTool) {
    routesTo(toolNodes.nodeOf(tool))
  }

  /** Leads to [tool]'s node when the source node emits this flag as a [Route.Flag]. */
  infix fun Boolean.routesTo(tool: BaseTool) {
    routesTo(toolNodes.nodeOf(tool))
  }

  /** Leads to [tool]'s node when the source node emits this route. */
  infix fun Route.routesTo(tool: BaseTool) {
    routesTo(toolNodes.nodeOf(tool))
  }

  /** Leads to [tool]'s node along one edge when the source node emits any of these routes. */
  infix fun RouteSet.routesTo(tool: BaseTool) {
    routesTo(toolNodes.nodeOf(tool))
  }

  /**
   * Leads to [tool]'s node when no other route of the source node matches, including when it emits
   * none.
   */
  fun otherwise(tool: BaseTool) {
    otherwise(toolNodes.nodeOf(tool))
  }

  /** Groups string routes into one entry whose edge fires on any of them. */
  fun anyOf(first: String, second: String, vararg rest: String): RouteSet =
    RouteSet(listOf(first, second, *rest).map(Route::Tag))

  /** Groups number routes into one entry whose edge fires on any of them. */
  fun anyOf(first: Int, second: Int, vararg rest: Int): RouteSet =
    RouteSet((listOf(first, second) + rest.toList()).map { Route.Num(it.toLong()) })

  /** Groups routes into one entry whose edge fires on any of them. */
  fun anyOf(first: Route, second: Route, vararg rest: Route): RouteSet =
    RouteSet(listOf(first, second, *rest))
}

/**
 * A block that declares edges or routes on a [T] builder. In Kotlin, pass a lambda with the builder
 * as its receiver. In Java, pass a lambda that takes the builder as an argument, with no `Unit` to
 * return.
 */
@ExperimentalWorkflowApi
fun interface EdgesDslBlock<in T> {
  /** Declares edges or routes on this builder. */
  fun T.declare()
}
