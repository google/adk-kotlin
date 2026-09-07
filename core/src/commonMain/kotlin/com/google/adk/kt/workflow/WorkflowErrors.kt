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

/**
 * A node failure that reports under a [typeName] other than this class's own, so a failure raised
 * in one ADK implementation is matched by the same name in another.
 */
@ExperimentalWorkflowApi
open class NodeExecutionException(val typeName: String, message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * A node exceeded its configured timeout. An ordinary exception, so a timed-out node is still
 * eligible for retry.
 */
@ExperimentalWorkflowApi
class NodeTimeoutException(val nodeName: String, val timeout: Duration, cause: Throwable? = null) :
  NodeExecutionException("NodeTimeoutError", "Node '$nodeName' timed out after $timeout.", cause)

/**
 * A workflow graph failed validation. Raised when the graph is built, not when it runs. Graph
 * validation lands in a later change, so nothing raises this yet.
 */
@ExperimentalWorkflowApi
class GraphValidationException(message: String) : IllegalArgumentException(message)

/**
 * A dynamically dispatched child node interrupted, so the node that dispatched it cannot finish.
 * Thrown past user code and caught by the node runner, which reads the interrupt ids off the
 * context rather than from this exception. Dynamic dispatch lands in a later change, so nothing
 * raises this yet.
 */
internal class NodeInterruptedException : RuntimeException("Node interrupted.")

/**
 * A dynamically dispatched child node failed; carries the failure to the dispatching node. Dynamic
 * dispatch lands in a later change, so nothing raises this yet.
 */
internal class DynamicNodeFailedException(val error: Throwable, val errorNodePath: String) :
  RuntimeException("Dynamic node failed.", error)
