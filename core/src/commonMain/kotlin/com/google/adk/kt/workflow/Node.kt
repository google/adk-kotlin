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
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * A unit of work in a workflow graph.
 *
 * Node names must be unique within a graph, since the scheduler keys on them.
 *
 * @property name Identifies the node within its graph.
 * @property description What the node does, for humans and for a model that may call it.
 * @property rerunOnResume On resume, whether to run the node again from scratch rather than
 *   completing it with the resuming answer as its output.
 * @property waitForOutput Whether the node stays re-triggerable until it produces an output or a
 *   route, instead of completing when its run completes.
 * @property retryConfig How many times to retry the node when it raises, and with what backoff.
 *   Null does not retry.
 * @property timeout How long an attempt may run before being cancelled and treated as a failure.
 *   Must be positive; null imposes no limit.
 */
@ExperimentalWorkflowApi
interface Node {
  val name: String
  val description: String
    get() = ""

  val rerunOnResume: Boolean
    get() = false

  val waitForOutput: Boolean
    get() = false

  val retryConfig: RetryConfig?
    get() = null

  val timeout: Duration?
    get() = null

  /**
   * Whether the node runs only once every predecessor has completed, receiving all their outputs
   * keyed by node name. A fan-in node overrides this to true.
   */
  val requiresAllPredecessors: Boolean
    get() = false

  /**
   * The user-facing implementation of the node's execution logic.
   *
   * Emits any of:
   * - A raw output value (e.g. String, Int, a data class, List, Map), which becomes this node's
   *   output and is normalized into an [Event] by the engine.
   * - An [Event], which passes through directly (useful for progress emissions or custom events).
   * - `null` or [Unit], both of which signify that no output was produced. [Unit] represents
   *   side-effecting Kotlin lambdas/functions that return no value, while `null` represents the
   *   explicit absence of an output.
   */
  fun runNode(context: NodeContext, nodeInput: Any?): Flow<Any?>
}

/** Validates that [timeout] is positive, since a zero or negative one expires before the run. */
internal fun validateTimeout(timeout: Duration?) {
  require(timeout == null || timeout > Duration.ZERO) {
    "A node timeout must be positive, or null for no limit."
  }
}

/** Validates that [name] is non-empty and contains no '/', '@', or '.' characters. */
internal fun validateNodeName(name: String) {
  require(name.isNotEmpty()) { "A node name must not be empty." }
  // '/' and '@' form node-path segments and '.' separates branch-path segments, so all corrupt it.
  require('/' !in name && '@' !in name && '.' !in name) {
    "A node name must not contain '/', '@', or '.'."
  }
}
