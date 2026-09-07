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
import com.google.adk.kt.events.Event
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An internal base class for units of work in a workflow graph. A [Workflow] is itself a node, so
 * graphs nest.
 *
 * Subclasses implement [runNode] and emit raw values; [run] normalizes each into an [Event]. Node
 * names must be unique within a graph, since the scheduler keys on them.
 *
 * @property name Identifies the node within its graph.
 * @property description What the node does, for humans and for a model that may call it.
 * @property rerunOnResume On resume, whether to run the node again from scratch rather than
 *   completing it with the resuming answer as its output.
 * @property waitForOutput Whether the node stays re-triggerable until it produces an output or a
 *   route, instead of completing when [runNode] returns. A node that never produces either then
 *   waits forever, which is a graph-authoring error.
 * @property retryConfig How many times to retry the node when it raises, and with what backoff.
 *   Null does not retry.
 * @property timeout How long an attempt may run before being cancelled and treated as a failure.
 *   Must be positive; null imposes no limit.
 */
@ExperimentalWorkflowApi
internal abstract class BaseNode(
  override val name: String,
  override val description: String = "",
  override val rerunOnResume: Boolean = false,
  override val waitForOutput: Boolean = false,
  override val retryConfig: RetryConfig? = null,
  override val timeout: Duration? = null,
) : Node {

  init {
    validateNodeName(name)
    validateTimeout(timeout)
  }

  /**
   * Whether the node runs only once every predecessor has completed, receiving all their outputs
   * keyed by node name. A fan-in node overrides this to true.
   */
  override val requiresAllPredecessors: Boolean
    get() = false

  /**
   * Runs the node and emits its events. It drives [runNode] and normalizes each raw emission into
   * an [Event], so every node behaves the same way at its edges: `null` and `Unit` are skipped, an
   * [Event] passes through directly, and any other value becomes the output.
   */
  fun run(context: NodeContext, nodeInput: Any?): Flow<Event> = flow {
    val emissions = runNode(context, nodeInput)
    emissions.collect { item ->
      when (item) {
        null,
        Unit -> {}
        is Event -> emit(item)
        // The author is left empty here and stamped later by the node runner, which is what knows
        // the node's place in the graph.
        else -> emit(Event(author = "", output = item))
      }
    }
  }

  companion object {
    /**
     * Adapts any [Node] to a [BaseNode] so the engine's execution and normalization loop can run
     * it.
     */
    fun from(node: Node): BaseNode =
      node as? BaseNode
        ?: object :
          BaseNode(
            name = node.name,
            description = node.description,
            rerunOnResume = node.rerunOnResume,
            waitForOutput = node.waitForOutput,
            retryConfig = node.retryConfig,
            timeout = node.timeout,
          ) {
          override val requiresAllPredecessors: Boolean
            get() = node.requiresAllPredecessors

          override fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?> =
            node.runNode(context, nodeInput)
        }
  }
}
