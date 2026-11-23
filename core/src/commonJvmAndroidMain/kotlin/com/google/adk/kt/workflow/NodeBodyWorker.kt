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
import com.google.adk.kt.agents.JavaInteropHooks
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.interop.unwrapFutureFailure
import com.google.adk.kt.telemetry.TelemetryContextElement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible

/**
 * The default for node bodies: `Dispatchers.IO` without its thread limit, since a body that waits
 * for a child holds its thread, and bodies that each wait would otherwise use up the limit.
 */
internal val defaultBodyDispatcher: CoroutineDispatcher =
  Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

// Only the node's coroutine emits into its flow: the worker hands each event over a channel.
@OptIn(ExperimentalAtomicApi::class)
@Suppress("UnsafeCoroutineCrossing")
internal actual fun <I, O> NodeBody<I, O>.onWorker(
  dispatcher: CoroutineDispatcher?
): suspend FlowCollector<Event>.(Context, I) -> O = { context, input ->
  val events = this
  val body = this@onWorker
  val state = context.requireNodeState()
  coroutineScope {
    val emitter = JavaBodyEmitter(state.node.name, coroutineContext.job)
    // Children keep elements such as MDCContext(); the view supplies the job, IO and body's span.
    val childContext =
      coroutineContext
        .minusKey(Job)
        .minusKey(ContinuationInterceptor)
        .minusKey(TelemetryContextElement)
    val hooks = JavaInteropHooks(emit = emitter::emit, childContext = childContext)
    // A second body on this state would take over the first one's calls.
    check(state.javaInteropHooks.compareAndSet(null, hooks)) {
      "Another Java FunctionNode body already runs with the Context of node" +
        " '${state.node.name}'; run each node through the workflow, or with runNode or" +
        " runNodeBlocking of InteropContextView, not by collecting its Node.runNode flow."
    }
    val result = async {
      // A failure or pause, caught here, lets the accepted emit calls go out first.
      outcomeOf {
        runOnWorker(dispatcher ?: defaultBodyDispatcher) {
          try {
            body.apply(context, input)
          } finally {
            // Also here, so a helper's call after the body ends fails at once, even one that read
            // the hooks before.
            state.javaInteropHooks.compareAndSet(hooks, null)
            emitter.close()
          }
        }
      }
    }
    // Runs even if the run's dispatcher rejects the task, so that serving always ends.
    result.invokeOnCompletion {
      state.javaInteropHooks.compareAndSet(hooks, null)
      emitter.close()
    }
    try {
      emitter.serve(events)
    } catch (t: Throwable) {
      // Ends the children a body may wait for in join(), which ignores the interrupt.
      context.activationJob?.cancel()
      throw t
    }
    result.await().getOrThrow()
  }
}

/** Runs [block] and returns how it ended; a cancellation is rethrown, never returned. */
private inline fun <T> outcomeOf(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: CancellationException) {
    throw e
  } catch (t: Throwable) {
    Result.failure(t)
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

/**
 * Carries a Java body's `InteropContextView.emit` calls to its node's coroutine, which emits them
 * in order.
 */
private class JavaBodyEmitter(private val nodeName: String, private val nodeJob: Job) {
  private class Call(val event: Event) {
    val stored = CompletableFuture<Unit>()
  }

  // A call the node never emits fails, so no thread waits for it forever.
  private val calls =
    Channel<Call>(
      Channel.UNLIMITED,
      onUndeliveredElement = {
        it.stored.completeExceptionally(CancellationException("The node stopped before the event."))
      },
    )
  @Volatile private var bodyEnded = false

  /**
   * Runs on a body's thread: hands [event] to the node and, unless it is partial, waits until the
   * run has stored it.
   */
  fun emit(event: Event) {
    // Checked here, so the body can catch the rejection, as a Kotlin body can.
    requireNoOutput(nodeName, event)
    val call = Call(event)
    // The job check covers the moment between a cancel and the channel's own cancel.
    if (nodeJob.isCancelled || calls.trySend(call).isFailure) {
      check(!bodyEnded) {
        "InteropContextView.emit was called after its Java FunctionNode body ended."
      }
      throw CancellationException("The node was cancelled before InteropContextView.emit.")
    }
    if (!event.partial) awaitStored(call.stored)
  }

  /** Emits each call into [events] until the body ends; runs on the node's coroutine. */
  suspend fun serve(events: FlowCollector<Event>) {
    try {
      for (call in calls) {
        try {
          // The collector's emit checks this too; a call queued before a cancel must not go out.
          currentCoroutineContext().ensureActive()
          events.emit(call.event)
        } catch (t: Throwable) {
          // Both the calling thread and the node see the failure, such as a rejected event.
          call.stored.completeExceptionally(t)
          throw t
        }
        call.stored.complete(Unit)
      }
    } finally {
      // Explicit cause: no-arg cancel() fails to link against kotlinx-coroutines < 1.11.
      calls.cancel(cause = null)
    }
  }

  /** Ends serving once the calls already sent are emitted; called when the body ends. */
  fun close() {
    bodyEnded = true
    val unused = calls.close(cause = null) // Explicit cause, as in serve.
  }

  /**
   * Waits for [stored], rethrowing the emit's own failure. An interrupt ends the wait and stays
   * set, so the body's next blocking call sees it too.
   */
  private fun awaitStored(stored: CompletableFuture<Unit>) {
    try {
      stored.get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw CancellationException("Interrupted while waiting for the event's store.", e)
    }
  }
}
