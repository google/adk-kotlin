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
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.jvm.JvmSynthetic

/**
 * Marks the edges DSL receivers so that, inside a `route { }` routing map, the enclosing builder's
 * functions cannot be called without an explicit receiver. An edge call written there, such as
 * `a.then(b)`, fails to compile instead of adding the edge to the enclosing `edges` or `workflow`
 * block.
 */
@DslMarker internal annotation class EdgesDslMarker

/**
 * Returns the edges declared by [block] for a [Workflow]; [workflow] builds both in one step.
 *
 * ```
 * val graphEdges = edges {
 *   Start.then(classify).route {
 *     on("billing") then billing
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
 * @throws IllegalStateException if an `on` was never completed with `then`.
 */
@ExperimentalWorkflowApi
fun edges(block: EdgesDslBlock<EdgesBuilder>): List<Edge> {
  val builder = EdgesBuilder()
  with(block) { builder.declare() }
  return builder.build()
}

/**
 * The receiver that collects the edges declared in an [edges][com.google.adk.kt.workflow.edges] or
 * [workflow] block. A node is identified by its instance, so create each node once and reuse it. To
 * add a tool as a step, convert it once with [asNode] and reuse that node.
 */
@ExperimentalWorkflowApi
@EdgesDslMarker
class EdgesBuilder internal constructor() {

  private val edges = mutableListOf<Edge>()

  /** Routed edges started by [on] that no [then] has completed yet. */
  private val pendingEdges = mutableListOf<PendingEdge>()

  /** Connects this node to [node] and returns [node], so a chain continues from it. */
  @CanIgnoreReturnValue
  infix fun Node.then(node: Node): Node {
    edges += Edge(this, node)
    return node
  }

  /**
   * Connects [first], [second], and each node in [rest] in order by adding an edge from each node
   * to the next. Returns the last node so the chain can continue from it: `chain(Start, a, b)` is
   * short for `Start.then(a).then(b)`.
   */
  @CanIgnoreReturnValue
  fun chain(first: Node, second: Node, vararg rest: Node): Node =
    rest.fold(first then second) { last, node -> last then node }

  /**
   * Connects this node to [first], [second], and each node in [rest] in order by adding an edge
   * from each node to the next, and returns the last node. `a.chain(b, c)` is short for
   * `a.then(b).then(c)`. Unlike the `chain` overload that takes the first node as an argument, this
   * form can continue a fluent chain, such as after [joinTo].
   */
  @CanIgnoreReturnValue
  fun Node.chain(first: Node, second: Node, vararg rest: Node): Node =
    rest.fold(this then first then second) { last, node -> last then node }

  /**
   * Fans out by connecting this node to each node in [targets] so they run concurrently, as in
   * `a.then(nodes(b, c))`. Returns [targets], which [joinTo] connects to a [JoinNode] to continue
   * once they all complete.
   */
  @CanIgnoreReturnValue
  infix fun Node.then(targets: NodeGroup): NodeGroup {
    for (node in targets.nodes) edges += Edge(this, node)
    return targets
  }

  /**
   * Fans out by connecting this node to each of [targets], such as a computed list, and returns
   * them as a [NodeGroup], like `then(nodes(...))`. An empty collection adds no edges.
   */
  @CanIgnoreReturnValue
  infix fun Node.then(targets: Collection<Node>): NodeGroup = then(NodeGroup(targets.toList()))

  /**
   * Groups [nodes] without connecting them, for a fan-out `then` or a [joinTo], as in
   * `a.then(nodes(b, c))` or `nodes(b, c).joinTo(join)`. Prefer it over `listOf(b, c)` for a
   * fan-out or a join, because `listOf` infers the nodes' closest common supertype. For some
   * built-in nodes of different types, such as a [Workflow] and a [JoinNode], that supertype is the
   * framework-internal `BaseNode`, so the call fails to compile.
   */
  fun nodes(vararg nodes: Node): NodeGroup = NodeGroup(nodes.toList())

  /**
   * Fans in by connecting each node of this group to [join], which runs once all of them complete.
   * Returns [join] so the chain continues from it: `a.then(nodes(b, c)).joinTo(join).then(d)`.
   */
  @CanIgnoreReturnValue
  infix fun NodeGroup.joinTo(join: JoinNode): Node {
    for (node in nodes) edges += Edge(node, join)
    return join
  }

  /**
   * Fans in by connecting each node in this collection, such as a computed list, to [join], like
   * `nodes(...).joinTo(join)`. Returns [join] so the chain continues from it. An empty collection
   * adds no edges.
   */
  @CanIgnoreReturnValue
  infix fun Collection<Node>.joinTo(join: JoinNode): Node = NodeGroup(toList()).joinTo(join)

  /**
   * Fans in: connects [first] and each of [rest] to this join, which runs once all of them
   * complete. Returns the join, so a chain continues from it.
   */
  @CanIgnoreReturnValue
  fun JoinNode.joinFrom(first: Node, vararg rest: Node): Node = nodes(first, *rest).joinTo(this)

  /**
   * Starts an edge that fires when this node emits [route] as a [Route.Tag]; [then] completes it.
   */
  infix fun Node.on(route: String): PendingEdge = on(Route.Tag(route))

  /**
   * Starts an edge that fires when this node emits [route] as a [Route.Num]; [then] completes it.
   */
  infix fun Node.on(route: Int): PendingEdge = on(Route.Num(route.toLong()))

  /**
   * Starts an edge that fires when this node emits [route] as a [Route.Flag]; [then] completes it.
   */
  infix fun Node.on(route: Boolean): PendingEdge = on(Route.Flag(route))

  /** Starts an edge that fires when this node emits [route]; [then] completes it. */
  infix fun Node.on(route: Route): PendingEdge =
    PendingEdge(this, listOf(route)).also { pendingEdges += it }

  /**
   * Starts one edge that fires once when this node emits any of [routes] (built with [anyOf]);
   * [then] completes it.
   */
  infix fun Node.on(routes: RouteSet): PendingEdge =
    PendingEdge(this, routes.routes).also { pendingEdges += it }

  /** Completes this routed edge at [node] and returns [node], so a chain continues from it. */
  @CanIgnoreReturnValue
  infix fun PendingEdge.then(node: Node): Node {
    pendingEdges.remove(this)
    edges += Edge(from, node, routes)
    return node
  }

  /** Groups string routes for [on], so one edge fires on any of them. */
  fun anyOf(first: String, second: String, vararg rest: String): RouteSet =
    RouteSet(listOf(first, second, *rest).map(Route::Tag))

  /** Groups number routes for [on], so one edge fires on any of them. */
  fun anyOf(first: Int, second: Int, vararg rest: Int): RouteSet =
    RouteSet((listOf(first, second) + rest.toList()).map { Route.Num(it.toLong()) })

  /** Groups routes for [on], so one edge fires on any of them. */
  fun anyOf(first: Route, second: Route, vararg rest: Route): RouteSet =
    RouteSet(listOf(first, second, *rest))

  /**
   * Adds this node's routing map, with an edge for each route [block] maps to a node. A routing map
   * ends the statement; connect its targets onward in separate statements. An empty map throws
   * [IllegalArgumentException], and an entry not completed with `then` throws
   * [IllegalStateException].
   */
  infix fun Node.route(block: EdgesDslBlock<RouteMapBuilder>) {
    val routes = RouteMapBuilder(this)
    with(block) { routes.declare() }
    routes.built = true
    check(routes.pendingEntries.isEmpty()) {
      "on(...) in the routing map of node '$name' has no target; complete it with then(node)."
    }
    val routeEdges = routes.edges
    require(routeEdges.isNotEmpty()) { "The routing map of node '$name' needs at least one entry." }
    edges += routeEdges
  }

  /** Returns the edges declared so far, failing if an [on] was left without a target. */
  internal fun build(): List<Edge> {
    check(pendingEdges.isEmpty()) {
      "on(...) from node '${pendingEdges.first().from.name}' has no target; complete it with" +
        " then(node)."
    }
    return edges.toList()
  }
}

/**
 * An edge started by [EdgesBuilder.on], which has a source and its routes but no target yet.
 * [EdgesBuilder.then] completes it.
 */
@ExperimentalWorkflowApi
class PendingEdge internal constructor(internal val from: Node, internal val routes: List<Route>)

/**
 * Nodes grouped by `nodes(...)` or a fan-out `then`, which [EdgesBuilder.joinTo] connects to a
 * [JoinNode].
 */
@ExperimentalWorkflowApi class NodeGroup internal constructor(internal val nodes: List<Node>)

/**
 * Routes that select a single edge, which fires once when its source emits any of them. Separate
 * edges to the same target would fire once each. Build one with `anyOf(...)` in the workflow DSL.
 */
@ExperimentalWorkflowApi
class RouteSet internal constructor(routes: List<Route>) {
  internal val routes: List<Route> = routes.distinct()
}

/**
 * The receiver of an [EdgesBuilder.route] block, which maps each route the source node may emit to
 * a target node, written `on("billing") then billing` or `"billing" then billing`. Mapping one
 * route to several nodes fans out on that route.
 */
@ExperimentalWorkflowApi
@EdgesDslMarker
class RouteMapBuilder internal constructor(private val from: Node) {

  internal val edges = mutableListOf<Edge>()

  /** Entries started by [on] not yet completed by [RouteMapEntry.then]. */
  internal val pendingEntries = mutableListOf<RouteMapEntry>()

  /** Whether [EdgesBuilder.route] has already collected this map's edges. */
  internal var built = false

  /** Starts an entry for [route] as a [Route.Tag]; [RouteMapEntry.then] completes it. */
  fun on(route: String): RouteMapEntry = on(Route.Tag(route))

  /** Starts an entry for [route] as a [Route.Num]; [RouteMapEntry.then] completes it. */
  fun on(route: Int): RouteMapEntry = on(Route.Num(route.toLong()))

  /** Starts an entry for [route] as a [Route.Flag]; [RouteMapEntry.then] completes it. */
  fun on(route: Boolean): RouteMapEntry = on(Route.Flag(route))

  /** Starts an entry for [route]; [RouteMapEntry.then] completes it. */
  fun on(route: Route): RouteMapEntry =
    RouteMapEntry(this, listOf(route)).also { pendingEntries += it }

  /**
   * Starts an entry whose edge fires once when the source node emits any of [routes] (built with
   * [anyOf]); [RouteMapEntry.then] completes it.
   */
  fun on(routes: RouteSet): RouteMapEntry =
    RouteMapEntry(this, routes.routes).also { pendingEntries += it }

  /**
   * Leads to [node] when the source node emits this string as a [Route.Tag]; short for
   * `on(this).then(node)`.
   */
  @JvmSynthetic
  infix fun String.then(node: Node) {
    on(this) then node
  }

  /**
   * Leads to [node] when the source node emits this number as a [Route.Num]; short for
   * `on(this).then(node)`.
   */
  @JvmSynthetic
  infix fun Int.then(node: Node) {
    on(this) then node
  }

  /**
   * Leads to [node] when the source node emits this value as a [Route.Flag]; short for
   * `on(this).then(node)`.
   */
  @JvmSynthetic
  infix fun Boolean.then(node: Node) {
    on(this) then node
  }

  /** Leads to [node] when the source node emits this route; short for `on(this).then(node)`. */
  @JvmSynthetic
  infix fun Route.then(node: Node) {
    on(this) then node
  }

  /**
   * Leads to [node] when the source node emits any of these routes; short for
   * `on(this).then(node)`.
   */
  @JvmSynthetic
  infix fun RouteSet.then(node: Node) {
    on(this) then node
  }

  /**
   * Leads to [first], [second], and each of [rest] when the source node emits this string as a
   * [Route.Tag].
   */
  @JvmSynthetic
  fun String.then(first: Node, second: Node, vararg rest: Node) {
    on(this).then(first, second, *rest)
  }

  /**
   * Leads to [first], [second], and each of [rest] when the source node emits this number as a
   * [Route.Num].
   */
  @JvmSynthetic
  fun Int.then(first: Node, second: Node, vararg rest: Node) {
    on(this).then(first, second, *rest)
  }

  /**
   * Leads to [first], [second], and each of [rest] when the source node emits this value as a
   * [Route.Flag].
   */
  @JvmSynthetic
  fun Boolean.then(first: Node, second: Node, vararg rest: Node) {
    on(this).then(first, second, *rest)
  }

  /** Leads to [first], [second], and each of [rest] when the source node emits this route. */
  @JvmSynthetic
  fun Route.then(first: Node, second: Node, vararg rest: Node) {
    on(this).then(first, second, *rest)
  }

  /**
   * Leads to [first], [second], and each of [rest] when the source node emits any of these routes.
   */
  @JvmSynthetic
  fun RouteSet.then(first: Node, second: Node, vararg rest: Node) {
    on(this).then(first, second, *rest)
  }

  /**
   * Leads to [node] when no other route of the source node matches, including when it emits none.
   */
  fun otherwise(node: Node) {
    add(node, listOf(Route.Default))
  }

  /**
   * Leads to [first], [second], and each of [rest] when no other route of the source node matches,
   * including when it emits none.
   */
  fun otherwise(first: Node, second: Node, vararg rest: Node) {
    for (node in listOf(first, second, *rest)) otherwise(node)
  }

  /** Groups string routes for [on], so one entry fires on any of them. */
  fun anyOf(first: String, second: String, vararg rest: String): RouteSet =
    RouteSet(listOf(first, second, *rest).map(Route::Tag))

  /** Groups number routes for [on], so one entry fires on any of them. */
  fun anyOf(first: Int, second: Int, vararg rest: Int): RouteSet =
    RouteSet((listOf(first, second) + rest.toList()).map { Route.Num(it.toLong()) })

  /** Groups routes for [on], so one entry fires on any of them. */
  fun anyOf(first: Route, second: Route, vararg rest: Route): RouteSet =
    RouteSet(listOf(first, second, *rest))

  /** Completes [entry] with an edge to [node]. */
  internal fun complete(entry: RouteMapEntry, node: Node) {
    pendingEntries.remove(entry)
    add(node, entry.routes)
  }

  /**
   * Adds an edge to [node] for [routes], failing once [EdgesBuilder.route] has collected the map.
   */
  private fun add(node: Node, routes: List<Route>) {
    check(!built) {
      "The routing map of node '${from.name}' was already built; complete its entries inside" +
        " route { }."
    }
    edges += Edge(from, node, routes)
  }
}

/**
 * A routing-map entry started by [RouteMapBuilder.on] whose routes have no target yet. [then]
 * completes it.
 */
@ExperimentalWorkflowApi
class RouteMapEntry
internal constructor(private val routeMap: RouteMapBuilder, internal val routes: List<Route>) {

  /** Leads to [node] when the source node emits any of this entry's routes. */
  infix fun then(node: Node) {
    routeMap.complete(this, node)
  }

  /**
   * Leads to [first], [second], and each of [rest], with one edge to each, when the source node
   * emits any of this entry's routes.
   */
  fun then(first: Node, second: Node, vararg rest: Node) {
    for (node in listOf(first, second, *rest)) then(node)
  }
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
