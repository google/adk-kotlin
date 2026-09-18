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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The base class for units of work in a workflow graph. A `Workflow` is itself a node, so graphs
 * nest, and every agent is one too.
 *
 * Public only so that `BaseAgent` and `Workflow`, which are public, can extend it. It is framework
 * plumbing: write a node by implementing [Node], not by extending this.
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
 */
@ExperimentalWorkflowApi
@FrameworkInternalApi
abstract class BaseNode(
  final override val name: String,
  override val description: String = "",
  override val rerunOnResume: Boolean = false,
  override val waitForOutput: Boolean = false,
) : Node {

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
  fun run(context: Context, nodeInput: Any?): Flow<Event> = flow {
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
}
