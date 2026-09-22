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

/**
 * The status of a node in a workflow graph.
 *
 * [code] is serialized into a resumable session's checkpoint, so the numbers are part of the
 * cross-implementation contract and must not be renumbered. Kept internal: only [NodeState] and the
 * scheduler use it today.
 */
internal enum class NodeStatus(val code: Int) {
  /** Not ready to run: at least one predecessor has not triggered it. */
  INACTIVE(0),

  /** Ready to run, waiting for the scheduler to pick it up. */
  PENDING(1),

  /** Running now. */
  RUNNING(2),

  /** Finished and produced its output or route. */
  COMPLETED(3),

  /** Paused, either awaiting a user response or awaiting a re-trigger. */
  WAITING(4),

  /** Raised, with its retry budget exhausted. */
  FAILED(5);

  companion object {
    /** Returns the status with the given wire [code], or null if there is none. */
    fun fromCode(code: Int): NodeStatus? = entries.find { it.code == code }
  }
}
