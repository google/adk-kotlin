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

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.State

/** Destination for events emitted during a workflow execution. */
fun interface EventSink {
  suspend fun send(event: Event)
}

/** The lifecycle of a node's own output value: not produced, produced, or emitted on the wire. */
internal sealed interface OutputRecord {
  data object None : OutputRecord

  data class Produced(val value: Any?) : OutputRecord

  data class Emitted(val value: Any?) : OutputRecord
}

/** Encapsulates the failure of a node activation along with the node path where it occurred. */
internal data class NodeExecutionFailure(val cause: Throwable, val nodePath: String)

/**
 * The context of one node activation: where it sits in the graph, what it was resumed with, and
 * where it records its results.
 *
 * A node reports its result either by emitting it or by assigning [output]; the runtime emits
 * whatever is not already on an event by the time the node returns.
 */
@ExperimentalWorkflowApi
class NodeContext
internal constructor(
  val invocationContext: InvocationContext,
  val node: Node,
  /** Where this activation's events are sent; one workflow run shares a single sink. */
  internal val eventSink: EventSink,
  /** The context of the node that scheduled this one, or null at the root. */
  val parent: NodeContext? = null,
  /**
   * This activation's id within its node, counting from "1". It is a string because it forms the
   * `name@runId` segment of a node path.
   */
  val runId: String = "1",
  /** 1-based attempt number, which a retry increments. */
  val attemptCount: Int = 1,
  /** Answers to this node's interrupts, keyed by interrupt id. */
  val resumeInputs: Map<String, Any?> = emptyMap(),
  /** Deltas this node accumulates, flushed onto the next event it emits. */
  val actions: EventActions = EventActions(),
  nodePath: String? = null,
) {

  // =========================================================================
  // Public API
  // =========================================================================

  /** This activation's path: `name@runId` segments joined by `/`, rooted at the outermost node. */
  val nodePath: String = nodePath ?: buildNodePath(parent?.nodePath, node.name, runId)

  /** The author stamped on events this node emits. */
  var eventAuthor: String = parent?.eventAuthor ?: node.name

  /**
   * The node's result. Settable once per activation, whether by emitting it or by assigning it.
   *
   * @throws IllegalStateException if set a second time.
   */
  var output: Any?
    get() =
      when (val r = outputRecord) {
        is OutputRecord.None -> null
        is OutputRecord.Produced -> r.value
        is OutputRecord.Emitted -> r.value
      }
    set(value) {
      check(outputRecord is OutputRecord.None) {
        "Node '${node.name}' produced a second output; a node produces at most one output."
      }
      outputRecord = OutputRecord.Produced(value)
    }

  /** Whether an output has been set, which distinguishes "no output" from "the output was null". */
  val hasProducedOutput: Boolean
    get() = outputRecord !is OutputRecord.None

  /** The routes this node selected, read by the scheduler to pick the outgoing edges. */
  var routes: List<Route>? = null
    set(value) {
      field = value
      routesEmitted = false
    }

  /**
   * The ids of the input requests this activation raised and is now waiting on, which the graph
   * pauses on until an answer arrives keyed by that id.
   */
  val interruptIds: Set<String>
    get() = mutableInterruptIds.toSet()

  /**
   * Session state as this node sees it: the session's own state with this activation's pending
   * delta and any transient writes applied. Removed keys are absent.
   */
  val state: Map<String, Any>
    get() = buildMap {
      putAll(invocationContext.session.state.toMap())
      putAll(actions.stateDelta)
      putAll(transientState)
      values.removeAll { it == State.REMOVED }
    }

  /** Records a state change, which rides on the next event this node emits. */
  fun setState(key: String, value: Any) {
    if (key.startsWith(State.TEMP_PREFIX)) transientState[key] = value
    else actions.stateDelta[key] = value
  }

  fun addInterruptIds(ids: Collection<String>) {
    mutableInterruptIds.addAll(ids)
  }

  // =========================================================================
  // Internal Engine API
  // =========================================================================

  /** The lifecycle of this node's own output. */
  internal var outputRecord: OutputRecord = OutputRecord.None

  /** Whether an event carrying the output has already been sent. */
  internal val hasEmittedOutput: Boolean
    get() = outputRecord is OutputRecord.Emitted

  /** Marks this node's output as emitted on the wire; only a produced output may be marked. */
  internal fun markOutputEmitted() {
    when (val r = outputRecord) {
      is OutputRecord.Produced -> outputRecord = OutputRecord.Emitted(r.value)
      is OutputRecord.Emitted -> error("Node '${node.name}' output was already emitted.")
      is OutputRecord.None -> error("Node '${node.name}' has no produced output to emit.")
    }
  }

  /** Whether the selected [routes] have already been dispatched on an emitted event. */
  internal var routesEmitted: Boolean = false

  /** The failure that ended this activation, if any. */
  internal var failure: NodeExecutionFailure? = null

  // =========================================================================
  // Private State
  // =========================================================================

  private val mutableInterruptIds = mutableSetOf<String>()
  private val transientState = mutableMapOf<String, Any>()

  companion object {
    internal fun buildNodePath(parentPath: String?, name: String, runId: String): String =
      BranchPath.appendSegment(parentPath, name, runId, separator = '/')
  }
}
