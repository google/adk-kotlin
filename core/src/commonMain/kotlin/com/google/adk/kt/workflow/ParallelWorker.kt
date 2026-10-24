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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)
@file:JvmName("ParallelWorkers")

package com.google.adk.kt.workflow

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.ValueCoercer
import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Schema
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore

/**
 * Returns a node that runs [node] once per item of a list input concurrently and outputs the items'
 * outputs as a list in input order. A text-only [Content] input, unless [node] is a function node
 * taking [Content], is read as JSON so a JSON list fans out; text that is not JSON arrives as a
 * string. Any other non-list input runs as a single item.
 *
 * The returned node takes [node]'s name, description, [Node.stateSchema] and
 * [Node.requiresAllPredecessors], and declares no input or output schema. [node]'s [Node.config]
 * and schemas apply to each item; the fan-out itself has a default [NodeConfig].
 *
 * @param node Single-item node to execute for each list element.
 * @param maxParallelWorkers Maximum number of items to run concurrently; `null` means no limit.
 * @throws WorkflowConfigurationException if [node] is [Start] or [maxParallelWorkers] is less
 *   than 1.
 */
@ExperimentalWorkflowApi
@JvmOverloads
fun parallelNode(node: Node, maxParallelWorkers: Int? = null): Node {
  if (node === Start) {
    throw WorkflowConfigurationException("parallelNode cannot wrap Start.")
  }
  checkMaxParallelWorkers(maxParallelWorkers)
  return ParallelWorkerNode(node, maxParallelWorkers)
}

/** Throws [WorkflowConfigurationException] if [maxParallelWorkers] is set and less than 1. */
internal fun checkMaxParallelWorkers(maxParallelWorkers: Int?) {
  if (maxParallelWorkers != null && maxParallelWorkers < 1) {
    throw WorkflowConfigurationException("maxParallelWorkers must be at least 1.")
  }
}

/** Node that fans [node] out over a list input. */
internal class ParallelWorkerNode(private val node: Node, maxParallelWorkers: Int?) : Node {
  private val fanOut = ParallelWorker(node, maxParallelWorkers)

  override val name: String = node.name
  override val description: String = node.description
  override val rerunOnResume: Boolean = true
  override val stateSchema: Schema? = node.stateSchema

  override val requiresAllPredecessors: Boolean
    get() = node.requiresAllPredecessors

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(fanOut.mapOverItems(context, nodeInput))
  }
}

/** Executes [worker] concurrently for each item in a list input and collects outputs in order. */
internal class ParallelWorker(private val worker: Node, private val maxParallelWorkers: Int?) {

  /** Maps [worker] over [nodeInput]: a list, a text-only [Content] read as JSON, or one item. */
  suspend fun mapOverItems(context: Context, nodeInput: Any?): List<Any?> {
    val input = if (nodeInput is Content) unwrapContent(nodeInput) else nodeInput
    val items = if (input is List<*>) input else listOf(input)
    if (items.isEmpty()) return emptyList()
    return FanOut(context, items).run().map { it.output }
  }

  /**
   * Reads a text-only [content], such as a user message, as JSON so a JSON list fans out, unless
   * [worker] takes [Content]. Text that is not JSON, or a non-list value for a `String` worker,
   * stays raw text.
   */
  private fun unwrapContent(content: Content): Any? {
    if (content.parts.isEmpty() || content.parts.any { it.text == null }) return content
    val itemType = (worker as? FunctionNode<*, *>)?.inputCodec?.classifier
    if (itemType == Content::class) return content
    val read = ValueCoercer.coerceContent(content, Schema())
    val takesString =
      itemType == String::class || worker.inputSchema?.let(SchemaUtils::acceptsString) == true
    return if (read !is List<*> && takesString) content.text() else read
  }

  /**
   * One fan-out over [items]. An item that fails or cancels itself cancels the others; after an
   * interrupt, items waiting for a slot don't start and running ones finish.
   */
  // The item coroutines share tasks, finished, firstStopped and interrupted, all within run()'s
  // coroutineScope: tasks is complete before any item starts (LAZY), and the others are atomics.
  @OptIn(ExperimentalAtomicApi::class)
  @Suppress("UnsafeCoroutineCrossing")
  private inner class FanOut(private val context: Context, private val items: List<Any?>) {
    private val parentState = context.requireNodeState()
    private val limit = maxParallelWorkers?.let { Semaphore(it) }
    private val tasks = mutableListOf<Deferred<Context>>()
    private val finished = List(items.size) { AtomicReference<Context?>(null) }
    private val firstStopped = AtomicInt(-1)
    private val interrupted = AtomicBoolean(false)

    suspend fun run(): List<Context> = coroutineScope {
      for ((index, item) in items.withIndex()) {
        val runId = parentState.nextChildRunId(worker.name)
        // LAZY: every task exists before any item runs, so stopOthers sees all of them.
        tasks.add(async(start = CoroutineStart.LAZY) { runItem(index, item, runId) })
      }
      startInOrder()
      collect()
    }

    private suspend fun runItem(index: Int, item: Any?, runId: String): Context {
      val ctx =
        try {
          context.runNodeForContext(worker, item, runId, useSubBranch = true)
        } catch (e: CancellationException) {
          currentCoroutineContext().ensureActive()
          stopOthers(index)
          throw e
        }
      finished[index].store(ctx)
      val state = ctx.requireNodeState()
      if (state.failure != null) {
        stopOthers(index)
      } else if (state.interruptIds.isNotEmpty()) {
        // Queued items no longer start; running ones finish so their interrupt ids merge.
        interrupted.store(true)
      }
      return ctx
    }

    private fun stopOthers(index: Int) {
      if (!firstStopped.compareAndSet(-1, index)) return
      // Queued items first, so a slot that a cancelled running item frees cannot start one.
      for ((siblingIndex, sibling) in tasks.withIndex().reversed()) {
        if (siblingIndex != index && !sibling.isCompleted) sibling.cancel()
      }
    }

    /** Starts items in input order as slots free; a cancelled item frees its slot on completion. */
    private suspend fun startInOrder() {
      for ((index, task) in tasks.withIndex()) {
        if (limit != null) {
          limit.acquire()
          task.invokeOnCompletion { limit.release() }
        }
        // An interrupt stops only items that had to wait for a slot.
        val queued = maxParallelWorkers != null && index >= maxParallelWorkers
        if (firstStopped.load() == -1 && !(queued && interrupted.load())) task.start()
        else task.cancel()
      }
    }

    /**
     * Awaits every item and surfaces the outcome: the lowest-index failure, else an item's own
     * cancellation, else the merged interrupts. A failed or cancelled node records no interrupts.
     */
    private suspend fun collect(): List<Context> {
      var ownCancellation: CancellationException? = null
      val completed =
        tasks.withIndex().mapNotNull { (index, task) ->
          try {
            task.await()
          } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            finished[index].load()?.let {
              return@mapNotNull it
            }
            // Only an item that cancelled itself propagates its cancellation, not a queued item
            // cancelled after an interrupt or a failure.
            if (firstStopped.load() == index && ownCancellation == null) ownCancellation = e
            null
          }
        }
      if (completed.any { it.actions.skipSummarization }) context.actions.skipSummarization = true
      completed
        .firstNotNullOfOrNull { it.requireNodeState().failure }
        ?.let { throw DynamicNodeFailedException(it.cause, it.nodePath) }
      ownCancellation?.let { throw it }
      parentState.addInterruptIds(completed.flatMap { it.interruptIds })
      if (completed.any { it.interruptIds.isNotEmpty() }) throw NodeInterruptedException()
      return completed
    }
  }
}
