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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.events.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow

/**
 * A graph of nodes. A workflow is both a [BaseAgent] (so it can be run as the root agent of an
 * application) and a [Node] (so it nests inside another workflow as an ordinary node).
 *
 * Running it schedules nodes as their predecessors complete, so independent branches run
 * concurrently and a chain runs in order. The graph is assembled when the workflow is constructed;
 * a malformed graph currently surfaces at run time rather than being rejected at construction.
 *
 * @property edges The graph. A workflow with no edges runs nothing and produces nothing.
 * @property maxConcurrency The most nodes to run at once. Null does not limit.
 */
@ExperimentalWorkflowApi
class Workflow(
  name: String,
  val edges: List<Edge> = emptyList(),
  override val description: String = "",
  val maxConcurrency: Int? = null,
  override val rerunOnResume: Boolean = true,
  override val waitForOutput: Boolean = false,
  subAgents: List<BaseAgent> = emptyList(),
  beforeAgentCallbacks: List<BeforeAgentCallback> = emptyList(),
  afterAgentCallbacks: List<AfterAgentCallback> = emptyList(),
  disallowTransferToParent: Boolean = false,
  disallowTransferToPeers: Boolean = false,
) :
  BaseAgent(
    name = name,
    description = description,
    subAgents = subAgents,
    beforeAgentCallbacks = beforeAgentCallbacks,
    afterAgentCallbacks = afterAgentCallbacks,
    disallowTransferToParent = disallowTransferToParent,
    disallowTransferToPeers = disallowTransferToPeers,
  ),
  Node {

  /** The assembled graph, or null when the workflow has no edges. */
  internal val graph: Graph? = if (edges.isEmpty()) null else Graph.of(edges)

  init {
    validateNodeName(name)
    require(maxConcurrency == null || maxConcurrency >= 1) {
      "maxConcurrency must be at least 1, or null for no limit."
    }
  }

  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = channelFlow {
    val graph = graph ?: return@channelFlow
    val sink = EventSink { event -> send(event) }
    val rootContext =
      NodeContext(invocationContext = context, node = this@Workflow, eventSink = sink)
    rootContext.eventAuthor = name
    Scheduler(this@Workflow, graph, rootContext).run(nodeInput = context.userContent)
    rootContext.failure?.let { throw it.cause }
  }

  override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> = flow {
    val graph = graph ?: return@flow
    // Child events are attributed to the workflow, not to the node inside it.
    context.eventAuthor = name
    Scheduler(this@Workflow, graph, context).run(nodeInput)
  }
}
