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

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.BaseNode
import com.google.adk.kt.workflow.NodeConfig
import com.google.adk.kt.workflow.validateNodeName
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.reactivestreams.Publisher

/**
 * Java-friendly base for implementing a workflow node. A Java subclass overrides [runNodeJava] to
 * set routes or state on the context and return a Reactive Streams [Publisher] of node outputs,
 * which this base adapts to the `Flow`-returning [runNode]. To run other nodes from [runNodeJava],
 * pass `rerunOnResume = true` and use [InteropContextView.runNode].
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
abstract class BasePublisherNode
@JvmOverloads
constructor(
  name: String,
  description: String = "",
  rerunOnResume: Boolean = false,
  waitForOutput: Boolean = false,
  config: NodeConfig = NodeConfig(),
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
  stateSchema: Schema? = null,
) :
  BaseNode(
    name,
    description,
    rerunOnResume,
    waitForOutput,
    config,
    inputSchema,
    outputSchema,
    stateSchema,
  ) {

  init {
    validateNodeName(name)
  }

  /** Creates a node with [config] and every other property at its default. */
  constructor(
    name: String,
    config: NodeConfig,
  ) : this(name, description = "", rerunOnResume = false, waitForOutput = false, config = config)

  /**
   * Produces this node's outputs; because Reactive Streams forbids null, publish nothing to produce
   * none. This base requests each output only after the engine has processed the previous one,
   * whereas subscription-time code, such as a `Flowable.defer` inside `Flowable.concat`, can run
   * before earlier outputs are processed, so build any output that depends on them when it is
   * requested. Because this base requests outputs one at a time, a publisher that checks demand
   * itself (such as `PublishProcessor` or `Flowable.create` with the `ERROR`, `DROP`, or `LATEST`
   * strategy) fails or loses outputs when it emits a burst, so apply `onBackpressureBuffer()` to
   * it.
   */
  protected abstract fun runNodeJava(
    context: Context,
    nodeInput: Any?,
  ): Publisher<out @JvmWildcard Any>

  final override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> =
    runNodeJava(context, nodeInput).asFlowOneAtATime().catch { e ->
      val failure = e.unwrapFutureFailure()
      if (failure !is CancellationException) throw failure
      // Only this node's own cancellation may end its run as canceled; any other one fails it.
      currentCoroutineContext().ensureActive()
      throw if (failure === e) CompletionException(e) else e
    }
}

/**
 * Returns the underlying failure that composed futures wrapped in [CompletionException]s or
 * [ExecutionException]s, such as a paused child from [InteropContextView.runNode], so the runtime,
 * retry policies, and error events see it.
 */
private fun Throwable.unwrapFutureFailure(): Throwable {
  var failure: Throwable = this
  while (failure is CompletionException || failure is ExecutionException) {
    failure = failure.cause ?: return this
  }
  return failure
}
