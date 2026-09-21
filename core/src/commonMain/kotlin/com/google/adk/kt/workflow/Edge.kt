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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A connection between two nodes.
 *
 * An edge carries the set of routes that select it. Most edges select on a single route (or none,
 * for an unconditional edge), so the [Route]/`null` constructor is the common way to build one; the
 * [List] form is for the rarer edge that fires on any of several routes.
 *
 * @property from The node whose completion fires this edge.
 * @property to The node this edge triggers.
 * @property routes The routes that select this edge. An empty list makes the edge unconditional, so
 *   it fires whenever [from] completes.
 */
@ExperimentalWorkflowApi
data class Edge(val from: Node, val to: Node, val routes: List<Route> = emptyList()) {
  /**
   * Builds an edge selected by a single [route], or an unconditional one when [route] is `null`.
   */
  constructor(
    from: Node,
    to: Node,
    route: Route?,
  ) : this(from, to, route?.let(::listOf) ?: emptyList())

  /** Whether this edge fires regardless of the route the source emitted. */
  val isUnconditional: Boolean
    get() = routes.isEmpty()
}

/** Marks where a graph starts. Never runs: the scheduler seeds its successors directly. */
@ExperimentalWorkflowApi
object Start : Node {
  override val name: String = START_NODE_NAME

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = emptyFlow()
}

/** The reserved name of the [Start] sentinel, which callers read through [Start.name]. */
internal const val START_NODE_NAME: String = "__START__"
