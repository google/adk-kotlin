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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow

/**
 * A graph of nodes, and a [Node] itself, so graphs nest: a workflow drops into another workflow
 * wherever an ordinary node goes.
 *
 * Running it schedules nodes as their predecessors complete, so independent branches run
 * concurrently and a chain runs in order. The graph is assembled when the workflow is constructed;
 * a malformed graph currently surfaces at run time rather than being rejected at construction.
 *
 * @property edges The graph. A workflow with no edges runs nothing and produces nothing.
 * @property maxConcurrency The most nodes to run at once. Null does not limit.
 * @property config The workflow's own retry policy and execution timeout, applied whether it runs
 *   as the root of an invocation or nested inside another graph. A workflow reports a child's
 *   failure rather than raising, so its retry policy re-runs the whole graph on a child failure;
 *   each retry runs the graph fresh, since replaying already-produced children is a later change.
 */
@ExperimentalWorkflowApi
class Workflow(
  name: String,
  val edges: List<Edge> = emptyList(),
  description: String = "",
  val maxConcurrency: Int? = null,
  rerunOnResume: Boolean = true,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
) :
  BaseNode(
    name = name,
    description = description,
    rerunOnResume = rerunOnResume,
    waitForOutput = waitForOutput,
    config = config,
  ) {

  /** The assembled graph, or null when the workflow has no edges. */
  internal val graph: Graph? = if (edges.isEmpty()) null else Graph.of(edges)

  init {
    validateNodeName(name)
    require(maxConcurrency == null || maxConcurrency >= 1) {
      "maxConcurrency must be at least 1, or null for no limit."
    }
  }

  /**
   * Runs this workflow as the root of an invocation, which is how a runner drives it. It runs
   * through the node runner, like a nested workflow, so its own timeout and retry policy apply at
   * the root instead of being ignored there. Nested inside another graph it runs through [runNode].
   */
  fun runAsync(context: InvocationContext): Flow<Event> = channelFlow {
    if (graph == null) return@channelFlow
    val sink = EventSink { event -> send(event) }
    // A synthetic root parent at the empty path lets the node runner rebuild this workflow's own
    // context at the same path the direct run used, so node paths, authors and branches are kept.
    val rootParent =
      Context(invocationContext = context, node = this@Workflow, eventSink = sink, nodePath = "")
    val result =
      NodeRunner(node = this@Workflow, parent = rootParent, runId = "1").run(context.userContent)
    result.requireNodeState().failure?.let { throw it.cause }
  }

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val graph = graph ?: return@flow
    // Child events are attributed to the workflow, not to the node inside it.
    context.eventAuthor = name
    Scheduler(this@Workflow, graph, context).run(nodeInput)
  }
}
