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
@file:OptIn(AdkJavaInteropApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.interop.unwrapFutureFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible

/**
 * The default for node bodies: `Dispatchers.IO` without its thread limit, since a body that waits
 * for a child holds its thread, and bodies that each wait would otherwise use up the limit.
 */
internal val defaultBodyDispatcher: CoroutineDispatcher =
  Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

internal actual fun <I, O> NodeBody<I, O>.onWorker(
  dispatcher: CoroutineDispatcher?
): suspend FlowCollector<Event>.(Context, I) -> O = { context, input ->
  runOnWorker(dispatcher ?: defaultBodyDispatcher) { this@onWorker.apply(context, input) }
}

/** Runs [block] on [dispatcher], interrupting it on cancel; see [NodeBody] for failures. */
@Suppress("SuspendBlocks")
private suspend fun <T> runOnWorker(dispatcher: CoroutineDispatcher, block: () -> T): T {
  val job = currentCoroutineContext().job
  return try {
    runInterruptible(dispatcher) {
      try {
        block()
      } catch (e: InterruptedException) {
        // runInterruptible turns every InterruptedException into a cancellation.
        throw BodyInterrupted(e)
      } catch (e: CancellationException) {
        throw ownCancellation(e)
      }
    }
  } catch (e: CancellationException) {
    if (job.isCancelled) throw e
    // A dispatcher that rejects the body, such as a shut-down executor, cancels only the body.
    throw generateSequence(e.cause) { it.cause }.firstOrNull { it !is CancellationException }
      ?: NodeExecutionException("The dispatcher cancelled the node body.", e)
  } catch (e: Exception) {
    // A future's join() wraps a child's failure or pause, such as from InteropContextView.runNode.
    val cause = (e as? BodyInterrupted)?.interrupt ?: e.unwrapFutureFailure()
    // After a cancel, such as a timeout, how the body ended does not matter, so the cancel wins.
    if (job.isCancelled) throw CancellationException("The node body ended after a cancel.", cause)
    if (cause is CancellationException) throw ownCancellation(cause)
    throw cause
  } catch (e: NodeInterruptedException) {
    // A pause after a cancel, such as a timeout, must not turn the cancel into a wait.
    if (job.isCancelled) throw CancellationException("The node body ended after a cancel.", e)
    throw e
  }
}

/** Rethrown as is, the body's own CancellationException would read as a cancel of the node. */
private fun ownCancellation(e: CancellationException) =
  NodeExecutionException(
    "The node body threw a CancellationException without a cancel of the node.",
    e,
  )

private class BodyInterrupted(val interrupt: InterruptedException) : RuntimeException(interrupt)
