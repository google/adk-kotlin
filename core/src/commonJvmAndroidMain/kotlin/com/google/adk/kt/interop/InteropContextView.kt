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

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.telemetry.childTelemetryOrNull
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.workflow.Node
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future

/**
 * Java-friendly view of a [Context]: each method except [emit] and [runNodeBlocking] calls the
 * `suspend` method of the same name and returns a [CompletableFuture] of its result, so Java
 * callers never pass a `Continuation`; those two instead block and work only in a Java
 * `FunctionNode` body, and [emit] ignores the coroutine context given to [of]. Each
 * future-returning call runs in the caller's OpenTelemetry context, on the calling thread until it
 * first suspends (so [runNode] children get their run IDs in call order) and then on
 * [Dispatchers.IO], as [PublisherRunner] runs agents. Each future-returning call is canceled with
 * its future and with the `Job` given to [of], if any, or otherwise when the node run that owns the
 * context, if any, ends, so await each call before the node finishes.
 */
@AdkJavaInteropApi
class InteropContextView
private constructor(private val context: Context, private val coroutineContext: CoroutineContext) {

  /** Calls [Context.getEvents]. */
  @JvmOverloads
  fun getEvents(
    currentInvocation: Boolean = false,
    currentBranch: Boolean = false,
  ): CompletableFuture<List<Event>> = call { context.getEvents(currentInvocation, currentBranch) }

  /** Calls [Context.listArtifacts]. */
  fun listArtifacts(): CompletableFuture<List<String>> = call { context.listArtifacts() }

  /**
   * Calls [Context.loadArtifact], which loads the latest version when [version] is `null`. The
   * future completes with `null` if there is no such artifact or no artifact service.
   */
  @JvmOverloads
  fun loadArtifact(name: String, version: Int? = null): CompletableFuture<Part?> = call {
    context.loadArtifact(name, version)
  }

  /**
   * Calls [Context.saveArtifact]; the returned future completes with the saved version. Await it
   * before the node emits another output or finishes, because only a later event from the node
   * records the version in the session.
   */
  fun saveArtifact(name: String, artifact: Part): CompletableFuture<Int> = call {
    context.saveArtifact(name, artifact)
  }

  /** Calls [Context.addSessionToMemory]. */
  fun addSessionToMemory(): CompletableFuture<Void?> = call {
    context.addSessionToMemory()
    null
  }

  /** Calls [Context.addEventsToMemory]. */
  @JvmOverloads
  fun addEventsToMemory(
    events: List<Event>,
    customMetadata: Map<String, Any?>? = null,
  ): CompletableFuture<Void?> = call {
    context.addEventsToMemory(events, customMetadata)
    null
  }

  /** Calls [Context.addMemory]. */
  @JvmOverloads
  fun addMemory(
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>? = null,
  ): CompletableFuture<Void?> = call {
    context.addMemory(memories, customMetadata)
    null
  }

  /**
   * Calls [Context.getAuthResponse]. The future completes with `null` if the app has not sent the
   * credential back yet.
   */
  @ExperimentalAuthApi
  fun getAuthResponse(authConfig: AuthConfig): CompletableFuture<AuthCredential?> = call {
    context.getAuthResponse(authConfig)
  }

  /**
   * Calls [Context.runNode] with the options in [config]; the future completes with the child's
   * output, or `null` when it has none. Catch only failures you handle, such as
   * [DynamicNodeFailedException][com.google.adk.kt.workflow.DynamicNodeFailedException]: when
   * [node] pauses for input, the future fails with an internal [Throwable] that must end the
   * calling node's run, so that the calling node waits too. [node] starts when this is called,
   * before the calling node's published events are stored, so pass any data [node] needs as
   * [nodeInput] or write that data to state in an earlier node.
   */
  @ExperimentalWorkflowApi
  @JvmOverloads
  fun runNode(
    node: Node,
    nodeInput: Any? = null,
    config: RunNodeConfig = RunNodeConfig(),
  ): CompletableFuture<Any?> = call {
    context.runNode(
      node,
      nodeInput = nodeInput,
      runId = config.runId,
      useAsOutput = config.useAsOutput,
      useSubBranch = config.useSubBranch,
      overrideBranch = config.overrideBranch,
      overrideIsolationScope = config.overrideIsolationScope,
      raiseOnWait = config.raiseOnWait,
    )
  }

  /**
   * Sends [event] from this node's running Java `FunctionNode` body or a thread it uses, after
   * earlier calls' events and before the output, or after it if a `useAsOutput` child already sent
   * the output. A non-partial call returns once the run has stored the event and passed it on; a
   * partial one returns at once, and its event is neither stored nor given state. The event carries
   * the state written before the call, or with an event another thread sent just before it, so use
   * state only on the thread that calls `emit`.
   *
   * @throws IllegalStateException outside a running Java body or after it ended
   * @throws IllegalArgumentException if [event] carries an output, or if the state schema rejects
   *   the state delta of a non-partial [event], which also fails the node
   * @throws kotlin.coroutines.cancellation.CancellationException once the node is cancelled, or if
   *   the waiting thread is interrupted; that thread stays interrupted, and the event may still be
   *   stored
   */
  @ExperimentalWorkflowApi
  fun emit(event: Event) {
    val hooks =
      checkNotNull(context.javaInteropHooks) {
        "InteropContextView.emit works only while a Java FunctionNode body of this node runs, not" +
          " after it ended. A Kotlin node body calls its suspending emit, and a custom node emits" +
          " from its flow."
      }
    hooks.emit(event)
  }

  /** Sends [content] as a message from this node's Java body, as [emit] with an [Event] does. */
  @ExperimentalWorkflowApi
  fun emit(content: Content) {
    emit(Event(content = content))
  }

  /**
   * Runs [node] with the default [RunNodeConfig] and returns its output, or `null` if it produces
   * none.
   *
   * Pass `null` for [nodeInput] when the child takes no input. Otherwise this behaves like the
   * three-argument [runNodeBlocking] overload, which lists all requirements and exceptions.
   *
   * @throws IllegalArgumentException if [nodeInput] is a [RunNodeConfig]; pass options to the
   *   three-argument overload instead
   */
  @CanIgnoreReturnValue
  @ExperimentalWorkflowApi
  fun runNodeBlocking(node: Node, nodeInput: Any?): Any? {
    require(nodeInput !is RunNodeConfig) {
      "Pass the RunNodeConfig as the third argument of runNodeBlocking, not as the node input."
    }
    return runNodeBlocking(node, nodeInput, RunNodeConfig())
  }

  /**
   * Runs [node] as a child of the current Java `FunctionNode` body and blocks until it finishes.
   * Returns the child's output, or `null` if it produces none.
   *
   * Rules and behavior:
   * - **Setup:** Build the calling node with `rerunOnResume(true)`.
   * - **Threading:** This call blocks the calling thread, not the workflow. If you pass a custom
   *   dispatcher to `FunctionNodes`, make sure it has a free thread for the child's body too.
   * - **Running children in parallel:** Prefer calling [runNode] for each child from the body's
   *   thread and waiting for the returned futures before the body returns. This avoids extra
   *   waiting threads and numbers run IDs in call order.
   * - **Concurrent calls:** If multiple threads call `runNodeBlocking` at the same time, set an
   *   explicit run ID in [config] for each call. Otherwise, auto-numbered IDs depend on thread
   *   timing.
   * - **Child pauses for user input:** If [node] pauses for input, this method throws an internal
   *   [Throwable] rather than an `Exception`. A `catch (Exception e)` block lets it through so the
   *   calling node pauses cleanly. A `catch (Throwable t)` block catches it: the node still waits
   *   for the child, but first emits your fallback as its output.
   * - **Waiting on futures:** If you wait on a [runNode] future, or run `runNodeBlocking` inside
   *   another future, let `ExecutionException` and `CompletionException` propagate. The runtime
   *   unwraps them so the calling node pauses cleanly. Catching `Exception` around `get()` or
   *   `join()` catches that wrapper: the node still waits for the child, but first emits your
   *   fallback as its output.
   *
   * @param config run options matching [runNode], such as an explicit run ID
   * @throws com.google.adk.kt.workflow.DynamicNodeFailedException if [node] fails; its `error`
   *   property holds the child's failure
   * @throws IllegalStateException if called outside a running Java body, or for any reason listed
   *   on [Context.runNode]
   * @throws IllegalArgumentException for any reason listed on [Context.runNode]
   * @throws CancellationException if the calling node is cancelled or the waiting thread is
   *   interrupted. Interrupting the thread cancels this call's run of [node], waits for it to stop,
   *   and leaves the thread's interrupt flag set. If the thread was already interrupted before the
   *   call, a [node] that finishes before the call blocks still returns its result.
   */
  @CanIgnoreReturnValue
  @ExperimentalWorkflowApi
  fun runNodeBlocking(node: Node, nodeInput: Any?, config: RunNodeConfig): Any? {
    val hooks =
      checkNotNull(context.javaInteropHooks) {
        "InteropContextView.runNodeBlocking works only while a Java FunctionNode body runs. A" +
          " Kotlin node body calls the suspending runNode, and a BasePublisherNode uses" +
          " InteropContextView.runNode."
      }
    // A child started on a thread without a span, such as a helper thread, stays in the node's
    // trace, as a Kotlin child does.
    val telemetry = childTelemetryOrNull(hooks.nodeTelemetry) ?: EmptyCoroutineContext
    // A job of its own, under the call's usual parent, lets an interrupt cancel this child alone.
    val call = Job(coroutineContext[Job] ?: context.activationJob)
    val output =
      of(context, hooks.childContext + telemetry + coroutineContext + call)
        .runNode(node, nodeInput, config)
    call.complete()
    return try {
      output.get()
    } catch (e: ExecutionException) {
      throw e.unwrapFutureFailure()
    } catch (e: InterruptedException) {
      call.cancel()
      // join() ignores further interrupts, so the child's run ends before this call does.
      output.handle { _, _ -> }.join()
      Thread.currentThread().interrupt()
      throw CancellationException("Interrupted while waiting for the child node.", e)
    }
  }

  private fun <T> call(block: suspend () -> T): CompletableFuture<T> {
    // Supervise so a failed call does not cancel other calls in the run or in the caller's Job.
    val job = SupervisorJob(coroutineContext[Job] ?: context.activationJob)
    @Suppress("GlobalCoroutineDispatchers") // Use IO for blocking tools in child agents.
    val scope = CoroutineScope(job + Dispatchers.IO)
    // Start undispatched so a runNode child takes its run ID and output claim before returning.
    val future =
      scope.future(callerContext(coroutineContext), CoroutineStart.UNDISPATCHED) {
        // An undispatched start ignores cancellation, so a call after its run ended stops here.
        ensureActive()
        block()
      }
    // The job completes once the call does, so it does not stay under its parent.
    job.complete()
    return future
  }

  companion object {
    /**
     * Returns a view of [context] whose calls also run in [coroutineContext], such as
     * `MDCContext()` from kotlinx-coroutines-slf4j or another dispatcher. If [coroutineContext]
     * contains a `Job`, it replaces the node run as the parent of each call.
     */
    @JvmStatic
    @JvmOverloads
    fun of(
      context: Context,
      coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): InteropContextView = InteropContextView(context, coroutineContext)
  }
}
