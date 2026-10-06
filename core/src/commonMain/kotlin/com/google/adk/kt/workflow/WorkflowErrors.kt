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

/** Encapsulates the failure of a node activation along with the node path where it occurred. */
internal data class NodeExecutionFailure(val cause: Throwable, val nodePath: String)

/**
 * A node exceeded its configured timeout. An ordinary exception, so a timed-out node is still
 * eligible for retry.
 */
@ExperimentalWorkflowApi
class NodeTimeoutException(val nodeName: String, val timeout: Duration, cause: Throwable? = null) :
  NodeExecutionException("NodeTimeoutError", "Node '$nodeName' timed out after $timeout.", cause)

/**
 * Raised when a node's runtime input fails validation. Reports `typeName =
 * "NodeInputValidationError"`.
 */
@ExperimentalWorkflowApi
class NodeInputValidationException(message: String, cause: Throwable? = null) :
  NodeExecutionException("NodeInputValidationError", message, cause)

/** A workflow graph failed validation. Raised when the graph is built, not when it runs. */
@ExperimentalWorkflowApi
class GraphValidationException(message: String) : IllegalArgumentException(message)

/** Thrown when a workflow or node configuration is invalid. */
@ExperimentalWorkflowApi
class WorkflowConfigurationError(message: String) : IllegalArgumentException(message)

/**
 * Aborts a dispatching node when a child node pauses for input. Extends [Throwable] rather than
 * [Exception] so `catch (Exception)` in user code does not swallow it.
 */
internal class NodeInterruptedException : Throwable("Node interrupted.")

/**
 * Propagates a dynamically dispatched child node's failure ([error] at [errorNodePath]) to the
 * caller.
 */
internal class DynamicNodeFailedException(val error: Throwable, val errorNodePath: String) :
  RuntimeException("Dynamic node failed.", error)
