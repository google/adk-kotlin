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
import com.google.adk.kt.types.Part
import com.google.adk.kt.workflow.Node
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future

/**
 * Java-friendly view of a [Context]: each method calls the `suspend` method of the same name and
 * returns a [CompletableFuture] of its result, so Java callers never pass a `Continuation`. Each
 * call runs in the caller's OpenTelemetry context, on the calling thread until it first suspends
 * (so [runNode] children get their run IDs in call order) and then on [Dispatchers.IO], as
 * [PublisherRunner] runs agents. Each call is canceled with its future and with the `Job` given to
 * [of], if any, or otherwise when the node run that owns the context, if any, ends, so await each
 * call before the node finishes.
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
