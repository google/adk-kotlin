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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The base class for units of work in a workflow graph. A `Workflow` is itself a node, so graphs
 * nest, and every agent is one too.
 *
 * Public only so that `BaseAgent`, `Workflow`, `JoinNode` and the Java interop base
 * `BasePublisherNode` can extend it. It is framework plumbing: write a node by implementing [Node],
 * or from Java by extending `BasePublisherNode`.
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
 * @property config The node's retry policy and execution timeout.
 * @property inputSchema Validates the node's input before it runs.
 * @property outputSchema Validates the output value the node emits. A value assigned to
 *   `Context.output` and a message-as-output event's content are not checked.
 * @property stateSchema Declares the state keys the node uses. Child nodes inherit it unless they
 *   declare their own.
 */
@ExperimentalWorkflowApi
@FrameworkInternalApi
abstract class BaseNode(
  final override val name: String,
  override val description: String = "",
  override val rerunOnResume: Boolean = false,
  override val waitForOutput: Boolean = false,
  override val config: NodeConfig = NodeConfig(),
  override val inputSchema: Schema? = null,
  override val outputSchema: Schema? = null,
  override val stateSchema: Schema? = null,
) : Node {

  /**
   * Whether the node runs only once every predecessor has completed, receiving all their outputs
   * keyed by node name. A fan-in node overrides this to true.
   */
  override val requiresAllPredecessors: Boolean
    get() = false

  /**
   * Runs the node and normalizes each raw emission of [runNode] into an [Event]: `null` and `Unit`
   * are skipped, a [RequestInput] becomes an `adk_request_input` interrupt event, an [Event] keeps
   * its content and has its `output` validated, and any other value becomes the output.
   */
  fun run(context: Context, nodeInput: Any?): Flow<Event> =
    run(context, nodeInput, validateInput = true)

  /**
   * Runs the node and normalizes its emissions into [Event]s, optionally skipping input validation.
   */
  internal fun run(context: Context, nodeInput: Any?, validateInput: Boolean): Flow<Event> = flow {
    val input = if (validateInput) validateInput(nodeInput) else nodeInput
    val emissions = runNode(context, input)
    emissions.collect { item ->
      when (item) {
        null,
        Unit -> {}
        is RequestInput -> emit(item.toEvent())
        is Event ->
          if (item.output == null) {
            emit(item)
          } else {
            emit(item.copy(output = validateOutput(item.output)))
          }
        // The author stays empty here and is stamped later by the node runner, which knows the
        // node's place in the graph.
        else -> emit(Event(output = validateOutput(item)))
      }
    }
  }

  /**
   * Checks [nodeInput] against [inputSchema], if there is one, and returns the input to run on. A
   * fan-in node receives its predecessors' outputs keyed by name, so the schema applies to each
   * output rather than to the joined map.
   */
  internal open fun validateInput(nodeInput: Any?): Any? {
    val schema = inputSchema ?: return nodeInput
    if (requiresAllPredecessors && nodeInput is Map<*, *>) {
      return nodeInput.entries.associate { (predecessor, output) ->
        val side = "output of '$predecessor' into node '$name'"
        predecessor.toString() to SchemaUtils.validateValue(output, schema, side).getOrThrow()
      }
    }
    return SchemaUtils.validateValue(nodeInput, schema, "input of node '$name'").getOrThrow()
  }

  /** Checks [output] against [outputSchema], if there is one, and returns it. */
  protected open fun validateOutput(output: Any?): Any? {
    val schema = outputSchema ?: return output
    return SchemaUtils.validateValue(output, schema, "output of node '$name'").getOrThrow()
  }
}
