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

import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.ExperimentalWorkflowApi

/**
 * A node's progress within one workflow run. Mutable and scoped to a single run of the scheduling
 * loop; the parts a resumable session persists go through [toCheckpoint].
 */
internal class NodeState(
  /** Where this node stands in the current run (inactive, running, waiting, completed, ...). */
  var status: NodeStatus = NodeStatus.INACTIVE,
  /** Ids of the interrupts this node is currently waiting on; empty unless [status] is waiting. */
  var interrupts: List<String> = emptyList(),
  /**
   * Counts the activations this node has had, so each gets a distinct path segment. Kept apart from
   * [runId] because [runId] may be a caller-supplied string, and mixing the two could collide: a
   * node that runs as `foo@1` (generated), then is dispatched with the explicit id `"bar"`, then
   * runs again must not reuse `foo@1`, so the counter keeps advancing (2, 3, ...) independently.
   */
  var runCounter: Int = 0,
  /** The current activation's id, forming the `name@runId` path segment; null before the first. */
  var runId: String? = null,
) {

  /** Assigns and returns the next activation id for this node. */
  fun nextRunId(): String {
    runCounter += 1
    return runCounter.toString()
  }

  /**
   * Returns the snapshot a resumable session records for this node. The shape is shared across ADK
   * implementations, so the keys and the numeric status are contractual. `resume_inputs` is always
   * empty here: this engine re-derives a node's answers from the session's own events each turn
   * rather than persisting them in the checkpoint.
   */
  fun toCheckpoint(): TypedData.MapValue =
    TypedData.MapValue(
      mapOf(
        "status" to TypedData.IntValue(status.code),
        "interrupts" to TypedData.ListValue(interrupts.map { TypedData.StringValue(it) }),
        "resume_inputs" to TypedData.MapValue(emptyMap()),
      )
    )

  /** Returns a fresh state for a new activation, carrying the run counter forward. */
  fun forNewRun(): NodeState = NodeState(runCounter = runCounter)
}

/**
 * A queued reason to run a node: the input the triggering node produced, and the branch it should
 * run on.
 *
 * @property input The value the triggering node produced, handed to the node as its input.
 * @property useSubBranch Whether the node runs on its own sub-branch, derived when it was fanned
 *   out (the triggering element had more than one successor).
 * @property branch The branch the node inherits, overriding the parent's: the triggering node's own
 *   branch for a single successor, or the common prefix its predecessors forked from for a join.
 */
internal data class Trigger(
  val input: Any?,
  val useSubBranch: Boolean = false,
  val branch: String? = null,
)
