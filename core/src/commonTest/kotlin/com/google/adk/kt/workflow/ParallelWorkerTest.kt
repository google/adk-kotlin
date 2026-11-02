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

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A plain node, not a BaseNode, that doubles the item it is given. */
private class PlainItemDoubler(
  override val name: String,
  override val stateSchema: Schema? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit((nodeInput as Long) * 2)
  }
}

/** Doubles its item and stores the result in session state under `item_<item>`. */
private class StateTaggingDoubler(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val item = nodeInput as Long
    context.updateState("item_$item", item * 2)
    emit(item * 2)
  }
}

/** Emits the item it was given, so how a fan-out split its input is observable. */
private class ItemEcho(override val name: String, override val inputSchema: Schema? = null) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** A plain node whose own timeout, at [stuckItem], escapes it as a cancellation. */
private class SelfTimingOutItem(override val name: String, private val stuckItem: Any?) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == stuckItem) withTimeout(10.milliseconds) { delay(10.seconds) }
    emit(nodeInput)
  }
}

/** Times itself out at [stuckItem]; any other item finishes only after a long delay. */
private class SelfCancelProbe(name: String, private val stuckItem: Long) : BaseNode(name = name) {
  var siblingCompleted = false
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == stuckItem) withTimeout(10.milliseconds) { delay(10.seconds) }
    delay(2.seconds)
    siblingCompleted = true
    emit(nodeInput)
  }
}

/** Fails every attempt at [badItem], under a policy that allows two attempts. */
private class AlwaysFailsAt(name: String, private val badItem: Long) :
  BaseNode(
    name = name,
    config =
      NodeConfig(
        retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
      ),
  ) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == badItem) throw TestNodeException("always")
    emit(nodeInput)
  }
}

/** A plain node that tags the item it is given, failing its first attempt at [flakyItem]. */
private class PlainItemTagger(override val name: String, private val flakyItem: Any? = null) :
  Node {
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == flakyItem && context.attemptCount == 1) {
      throw TestNodeException("first attempt")
    }
    emit("tagged:$nodeInput")
  }
}

/** Doubles each item it is given, one item per activation. */
private class Doubler(name: String) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit((nodeInput as Long) * 2)
  }
}

/** Fails on one nominated item, to show a single bad item fails the whole node. */
private class FailsOn(name: String, private val bad: Long) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == bad) throw TestNodeException("bad item")
    emit(nodeInput)
  }
}

/** Takes [itemTime] per item under an 800 ms timeout. */
private class SlowItem(name: String, private val itemTime: Duration) :
  BaseNode(name = name, config = NodeConfig(timeout = 800.milliseconds)) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    delay(itemTime)
    emit(nodeInput)
  }
}

/** Fails the first attempt at [flakyItem] only; its retry policy lets a retry succeed. */
private class FailsFirstAttemptAt(name: String, private val flakyItem: Long) :
  BaseNode(
    name = name,
    config =
      NodeConfig(
        retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
      ),
  ) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == flakyItem && context.attemptCount == 1) {
      throw TestNodeException("first attempt")
    }
    emit(nodeInput)
  }
}

/** Emits each item unchanged, under the given input and output schemas. */
private class SchemaCheckedEcho(
  name: String,
  inputSchema: Schema? = null,
  outputSchema: Schema? = null,
) : BaseNode(name = name, inputSchema = inputSchema, outputSchema = outputSchema) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** An object schema with one required integer field, `count`. */
private fun countSchema() =
  Schema(
    type = Type.OBJECT,
    properties = mapOf("count" to Schema(type = Type.INTEGER)),
    required = listOf("count"),
  )

/** Each item dispatches a child of its own, which needs the item itself to rerun on resume. */
private class ItemDispatcher(name: String, rerunOnResume: Boolean) :
  BaseNode(name = name, rerunOnResume = rerunOnResume) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(Emitter("leaf", nodeInput)))
  }
}

/** Dispatches [child] on `[1, 2]`, then emits whether its own actions ask to skip summarization. */
private class FanOutSkipSummarizationProbe(override val name: String, private val child: Node) :
  Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNode(child, listOf(1L, 2L))
    emit(context.actions.skipSummarization)
  }
}

/** Dispatches [child] on [items] and emits the list it returns. */
private class FanOutDispatcher(
  override val name: String,
  private val child: Node,
  private val items: List<Any?>,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(child, items))
  }
}

/** Fails on every item with a message naming the item, so failure ordering is observable. */
private class AlwaysFails(name: String) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    throw TestNodeException("$nodeInput failed")
  }
}

/**
 * Records the peak number of items active at once. Each item parks until [releaseAt] of them
 * overlap, so a sequential fan-out never reaches the bar and times out instead.
 */
private class ConcurrencyProbe(name: String, private val releaseAt: Int) : BaseNode(name = name) {
  private val lock = Mutex()
  private val allArrived = CompletableDeferred<Unit>()
  private var active = 0
  var peak = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val nowActive = lock.withLock {
      active += 1
      peak = maxOf(peak, active)
      active
    }
    if (nowActive >= releaseAt) allArrived.complete(Unit)
    withTimeout(10.seconds) { allArrived.await() }
    lock.withLock { active -= 1 }
    emit(nodeInput)
  }
}

/** Finishes item 1 only after item 2 has already emitted, so completion order reverses input. */
private class OutOfOrderWorker(name: String) : BaseNode(name = name) {
  private val secondDone = CompletableDeferred<Unit>()

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val item = nodeInput as Long
    if (item == 1L) {
      withTimeout(10.seconds) { secondDone.await() }
      emit("res-$item")
    } else {
      emit("res-$item")
      secondDone.complete(Unit)
    }
  }
}

/** Interrupts on one nominated item by surfacing a long-running tool id instead of an output. */
private class InterruptsOn(name: String, private val asking: Long) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == asking) emit(Event(longRunningToolIds = setOf("ask-$nodeInput")))
    else emit(nodeInput)
  }
}

/** Interrupts on every item by surfacing a long-running tool id naming the item. */
private class SimultaneousInterrupts(name: String) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(Event(longRunningToolIds = setOf("ask-$nodeInput")))
  }
}

/** A plain node whose own [NodeConfig.timeout] bounds each item when wrapped. */
private class ConfiguredTimeoutItem(override val name: String, private val stuckItem: Long) : Node {
  override val config: NodeConfig = NodeConfig(timeout = 100.milliseconds)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == stuckItem) delay(10.seconds)
    emit(nodeInput)
  }
}

/** Fails one item at once; a sibling only records completion after a long delay. */
private class FailFastProbe(name: String, private val bad: Long) : BaseNode(name = name) {
  var siblingCompleted = false
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == bad) throw TestNodeException("boom")
    delay(10.seconds)
    siblingCompleted = true
    emit(nodeInput)
  }
}

/** Interrupts on one item at once; a sibling only records completion after a short delay. */
private class InterruptFinishProbe(name: String, private val asking: Long) : BaseNode(name = name) {
  var siblingCompleted = false
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == asking) {
      emit(Event(longRunningToolIds = setOf("ask-$nodeInput")))
      return@flow
    }
    delay(200.milliseconds)
    siblingCompleted = true
    emit(nodeInput)
  }
}

/** Interrupts on item 1 at once; item 2 times itself out after a short delay. */
private class InterruptThenSelfCancel(name: String) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == 1L) {
      emit(Event(longRunningToolIds = setOf("ask-1")))
      return@flow
    }
    delay(200.milliseconds)
    withTimeout(10.milliseconds) { delay(10.seconds) }
  }
}

/** A fan-in node that emits the map of its predecessors' outputs. */
private class FanInEcho(override val name: String) : Node {
  override val requiresAllPredecessors: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** Interrupts on item 1 at once; item 2 fails after a short delay. */
private class InterruptThenFailure(name: String) : BaseNode(name = name) {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (nodeInput == 1L) {
      emit(Event(longRunningToolIds = setOf("ask-1")))
      return@flow
    }
    delay(200.milliseconds)
    throw TestNodeException("late failure")
  }
}

class ParallelWorkerTest {

  @Test
  fun run_listInput_runsOncePerItemAndCollectsInInputOrder() {
    // Arrange
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(Doubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(2L, 4L, 6L), events.last().output)
    assertEquals(
      listOf("wf@1/worker@1/worker@1", "wf@1/worker@1/worker@2", "wf@1/worker@1/worker@3"),
      events
        .mapNotNull { it.nodeInfo?.path }
        .filter { it.contains("/worker@") && it.count { c -> c == '/' } == 2 },
    )
  }

  @Test
  fun run_itemFailsOnceUnderRetryPolicy_retriesOnlyThatItem() {
    // Arrange: the node's retry policy applies to each item, not only to the fan-out as a whole.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(FailsFirstAttemptAt("worker", flakyItem = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the second item's retry succeeded under its own path; the fan-out did not rerun,
    // which would have numbered its items on from worker@3.
    assertEquals(listOf(1L, 2L), events.last().output)
    val itemPaths =
      events.mapNotNull { it.nodeInfo?.path }.filter { it.startsWith("wf@1/worker@1/") }.distinct()
    assertEquals(listOf("wf@1/worker@1/worker@1", "wf@1/worker@1/worker@2"), itemPaths)
  }

  @Test
  fun run_itemAlwaysFailsUnderRetryPolicy_retriesOnlyThatItem() {
    // Arrange
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(AlwaysFailsAt("worker", badItem = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    assertFailsWith<TestNodeException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
    }

    // Assert
    val itemErrors = events.filter { it.errorCode != null }.map { it.nodeInfo?.path }
    assertEquals(listOf("wf@1/worker@1/worker@2", "wf@1/worker@1/worker@2"), itemErrors)
    val itemPaths =
      events.mapNotNull { it.nodeInfo?.path }.filter { it.startsWith("wf@1/worker@1/") }.distinct()
    assertEquals(listOf("wf@1/worker@1/worker@1", "wf@1/worker@1/worker@2"), itemPaths)
  }

  @Test
  fun run_itemsMatchSchemas_passThroughUnchanged() {
    // Arrange
    val items = listOf(mapOf("count" to 1L), mapOf("count" to 2L))
    val source = Emitter("source", items)
    val worker =
      parallelNode(
        SchemaCheckedEcho("worker", inputSchema = countSchema(), outputSchema = countSchema())
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(items, events.last().output)
  }

  @Test
  fun run_itemFailsInputSchema_failsWholeNode() {
    // Arrange: the second item's field has the wrong type.
    val source = Emitter("source", listOf(mapOf("count" to 1L), mapOf("count" to "x")))
    val worker = parallelNode(SchemaCheckedEcho("worker", inputSchema = countSchema()))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the worker checks each item's input before running it, so it reports the failure.
    assertContains(error.message!!, "validation error")
    assertEquals(
      listOf("wf@1/worker@1"),
      events.filter { it.errorCode != null }.map { it.nodeInfo?.path },
    )
  }

  @Test
  fun parallelNode_fanInNode_waitsForAllPredecessorsAndRunsOnce() {
    // Arrange
    val a = Emitter("a", "A")
    val b = Emitter("b", "B")
    val worker = parallelNode(FanInEcho("join"))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, a), Edge(Start, b), Edge(a, worker), Edge(b, worker)),
      )

    // Act
    val events = runWorkflow(workflow)

    // Assert: the joined map is the single item.
    assertTrue(worker.requiresAllPredecessors)
    val outputs = events.filter { it.nodeInfo?.path == "wf@1/join@1" && it.output != null }
    assertEquals(listOf(listOf(mapOf("a" to "A", "b" to "B"))), outputs.map { it.output })
  }

  @Test
  fun runNode_dispatchedWorkerWithItemSchema_checksEachItemNotTheList() {
    // Arrange: the item schema describes one object, so it would reject the list itself.
    val items = listOf(mapOf("count" to 1L), mapOf("count" to 2L))
    val worker = parallelNode(SchemaCheckedEcho("worker", inputSchema = countSchema()))
    val dispatcher = FanOutDispatcher("dispatcher", worker, items)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(items, events.last { it.nodeInfo?.path == "wf@1/dispatcher@1" }.output)
  }

  @Test
  fun run_itemFailsOutputSchema_failsWholeNode() {
    // Arrange: the input is unchecked, but the second item's output has a wrong-typed field.
    val source = Emitter("source", listOf(mapOf("count" to 1L), mapOf("count" to "x")))
    val worker = parallelNode(SchemaCheckedEcho("worker", outputSchema = countSchema()))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the item's own output was checked, so the failure sits at that item.
    assertContains(error.message!!, "validation error: output of node 'worker'")
    assertEquals(
      listOf("wf@1/worker@1/worker@2"),
      events.filter { it.errorCode != null }.map { it.nodeInfo?.path },
    )
  }

  @Test
  fun run_eachItem_runsOnOwnSubBranchWithOwnOutput() {
    // Arrange: the worker is terminal, so its aggregate output also counts for the workflow.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(Doubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    val itemEvents = events.filter { it.nodeInfo?.path?.startsWith("wf@1/worker@1/") == true }
    assertEquals(listOf("worker@1", "worker@2"), itemEvents.map { it.branch })
    assertEquals(
      listOf(listOf("wf@1/worker@1/worker@1"), listOf("wf@1/worker@1/worker@2")),
      itemEvents.map { it.nodeInfo?.outputFor },
    )
    val aggregate = events.single { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null }
    assertEquals(listOf("wf@1/worker@1", "wf@1"), aggregate.nodeInfo?.outputFor)
  }

  @Test
  fun run_laterItemsFinishFirst_keepsInputOrder() {
    // Arrange
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(OutOfOrderWorker("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: item 2 emitted first, yet the aggregate list preserves [1, 2] input order.
    assertEquals(listOf("res-1", "res-2"), events.last().output)
  }

  @Test
  fun run_loneValue_isTreatedAsListOfOne() {
    // Arrange
    val source = Emitter("source", 5L)
    val worker = parallelNode(Doubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(10L), events.last().output)
  }

  @Test
  fun run_emptyList_runsNothingAndYieldsEmptyList() {
    // Arrange
    val source = Emitter("source", emptyList<Long>())
    val worker = parallelNode(Doubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(emptyList<Any?>(), events.last().output)
    assertEquals(0, events.count { it.nodeInfo?.path?.endsWith("/worker@1/worker@1") == true })
  }

  @Test
  fun run_maxParallelWorkers_boundsConcurrency() {
    // Arrange: a cap of 2 must let at most two of the six items overlap.
    val source = Emitter("source", listOf(1L, 2L, 3L, 4L, 5L, 6L))
    val probe = ConcurrencyProbe("worker", releaseAt = 2)
    val worker = parallelNode(probe, maxParallelWorkers = 2)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: every item ran, and concurrency reached the cap but never exceeded it.
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), events.last().output)
    assertEquals(2, probe.peak)
  }

  @Test
  fun run_noMaxParallelWorkers_runsItemsConcurrently() {
    // Arrange: with no cap, all three items must be able to run at once.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val probe = ConcurrencyProbe("worker", releaseAt = 3)
    val worker = parallelNode(probe)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: all three overlapped, which a sequential fan-out could never do.
    assertEquals(listOf(1L, 2L, 3L), events.last().output)
    assertEquals(3, probe.peak)
  }

  @Test
  fun parallelNode_plainNode_runsOncePerItemInInputOrder() {
    // Arrange: a node that is not a BaseNode.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(PlainItemDoubler("worker"), maxParallelWorkers = 2)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(2L, 4L, 6L), events.last().output)
    val itemPaths =
      events.mapNotNull { it.nodeInfo?.path }.filter { it.startsWith("wf@1/worker@1/") }.distinct()
    assertEquals(
      listOf("wf@1/worker@1/worker@1", "wf@1/worker@1/worker@2", "wf@1/worker@1/worker@3"),
      itemPaths,
    )
  }

  @Test
  fun parallelNode_nodeWithRetryPolicy_retriesEachItemOnItsOwn() {
    // Arrange: the wrapped node's own configuration applies to each item.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(PlainItemTagger("worker", flakyItem = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("tagged:1", "tagged:2"), events.last().output)
  }

  @Test
  fun parallelNode_workflow_runsTheWholeGraphPerItem() {
    // Arrange: each item runs a nested workflow as itself, not as a copy.
    val inner = Workflow(name = "inner", edges = listOf(Edge(Start, PlainItemTagger("tag"))))
    val source = Emitter("source", listOf(1L, 2L))
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, parallelNode(inner))))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("tagged:1", "tagged:2"), events.last().output)
  }

  @Test
  fun parallelNode_appliedTwice_fansOutOverNestedLists() {
    // Arrange
    val source = Emitter("source", listOf(listOf(1L, 2L), listOf(3L)))
    val worker = parallelNode(parallelNode(PlainItemDoubler("worker")))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(listOf(2L, 4L), listOf(6L)), events.last().output)
  }

  @Test
  fun parallelNode_anyNode_rerunsOnResumeAndKeepsTheName() {
    // Arrange
    val stateSchema = countSchema()

    // Act
    val worker = parallelNode(PlainItemDoubler("worker", stateSchema = stateSchema))

    // Assert
    assertTrue(worker.rerunOnResume)
    assertEquals("worker", worker.name)
    assertEquals(stateSchema, worker.stateSchema)
  }

  @Test
  fun parallelNode_nodeWithTimeout_boundsEachItemNotTheFanOut() {
    // Arrange: one item at a time, each well inside the timeout, but together past it.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker =
      parallelNode(SlowItem("worker", itemTime = 300.milliseconds), maxParallelWorkers = 1)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(1L, 2L, 3L), events.last().output)
    assertEquals(NodeConfig(), worker.config)
  }

  @Test
  fun parallelNode_nodeWithSchemas_declaresNoPortSchemas() {
    // Act
    val worker =
      parallelNode(
        SchemaCheckedEcho("worker", inputSchema = countSchema(), outputSchema = countSchema())
      )

    // Assert: the schemas describe one item, so the fan-out declares none.
    assertNull(worker.inputSchema)
    assertNull(worker.outputSchema)
  }

  @Test
  fun parallelNode_start_isRejected() {
    // Act
    val error = assertFailsWith<WorkflowConfigurationException> { parallelNode(Start) }

    // Assert
    assertEquals("parallelNode cannot wrap Start.", error.message)
  }

  @Test
  fun parallelNode_capBelowOne_isRejected() {
    // Act
    val error =
      assertFailsWith<WorkflowConfigurationException> {
        parallelNode(PlainItemDoubler("worker"), maxParallelWorkers = 0)
      }

    // Assert
    assertContains(error.message!!, "maxParallelWorkers must be at least 1")
  }

  @Test
  fun run_itemDispatchesChild_usesNodesOwnRerunOnResume() {
    // Arrange: each item dispatches a child, which only a node that reruns on resume may do.
    val rerunning = parallelNode(ItemDispatcher("rerunning", rerunOnResume = true))
    val notRerunning = parallelNode(ItemDispatcher("not_rerunning", rerunOnResume = false))
    val source = Emitter("source", listOf(1L, 2L))

    // Act
    val events =
      runWorkflow(
        Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, rerunning)))
      )
    val error =
      assertFailsWith<IllegalStateException> {
        runWorkflow(
          Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, notRerunning)))
        )
      }

    // Assert: both workers rerun, but the items inherit the node's setting, so only the items of
    // the rerunning node may dispatch.
    assertTrue(notRerunning.rerunOnResume)
    assertEquals(listOf(1L, 2L), events.last().output)
    assertContains(error.message!!, "must set rerunOnResume = true")
  }

  @Test
  fun run_itemInterrupts_leavesWholeNodeWaiting() {
    // Arrange: item 2 asks for input while item 1 completes, and a successor follows the worker.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(InterruptsOn("worker", asking = 2L))
    val after = Emitter("after", "AFTER")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, source), Edge(source, worker), Edge(worker, after)),
      )

    // Act
    val events = runWorkflow(workflow)

    // Assert: the item's interrupt is merged onto the node, so it produces no list and waits.
    assertContains(events.flatMap { it.longRunningToolIds }, "ask-2")
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null })
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/after@1" })
  }

  @Test
  fun run_itemFails_failsWholeNode() {
    // Arrange
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(FailsOn("worker", bad = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<TestNodeException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the item's own error fails the run, reported once at the item's path; the worker
    // carries it up without an error event of its own.
    assertContains(error.message!!, "bad item")
    val errorEvents = events.filter { it.errorCode != null }
    assertEquals(listOf("wf@1/worker@1/worker@2"), errorEvents.map { it.nodeInfo?.path })
  }

  @Test
  fun run_itemFailsUnderCap_neverStartsQueuedItems() {
    // Arrange: with a cap of one, items 2 and 3 wait for item 1, which fails.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(FailsOn("worker", bad = 1L), maxParallelWorkers = 1)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    val error =
      assertFailsWith<TestNodeException> {
        runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
      }

    // Assert: the queued items were cancelled before they ran, so only item 1 left events.
    assertContains(error.message!!, "bad item")
    assertEquals(
      setOf("wf@1/worker@1/worker@1"),
      events.mapNotNull { it.nodeInfo?.path }.filter { it.startsWith("wf@1/worker@1/") }.toSet(),
    )
  }

  @Test
  fun run_simultaneousItemFailures_surfacesLowestIndexError() {
    // Arrange
    val source = Emitter("source", listOf("item-0", "item-1"))
    val worker = parallelNode(AlwaysFails("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act + Assert: when multiple items fail in the same window, the lowest-index item wins.
    repeat(10) {
      val error = assertFailsWith<TestNodeException> { runWorkflow(workflow) }
      assertEquals("item-0 failed", error.message)
    }
  }

  @Test
  fun run_itemFails_cancelsSiblings() {
    // Arrange: item 1 fails at once; item 2 would only complete after a long delay.
    val source = Emitter("source", listOf(1L, 2L))
    val probe = FailFastProbe("worker", bad = 1L)
    val worker = parallelNode(probe)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val error = assertFailsWith<TestNodeException> { runWorkflow(workflow) }

    // Assert: the node failed fast and the sibling was cancelled before it could finish.
    assertContains(error.message!!, "boom")
    assertFalse(probe.siblingCompleted)
  }

  @Test
  fun run_itemInterrupts_letsRunningSiblingsFinish() {
    // Arrange: item 1 interrupts at once; item 2 completes after a short delay.
    val source = Emitter("source", listOf(1L, 2L))
    val probe = InterruptFinishProbe("worker", asking = 1L)
    val worker = parallelNode(probe)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: the sibling finished, and the node still waits on the interrupt.
    assertContains(events.flatMap { it.longRunningToolIds }, "ask-1")
    assertTrue(probe.siblingCompleted)
    assertEquals(
      1,
      events.count { it.nodeInfo?.path == "wf@1/worker@1/worker@2" && it.output != null },
    )
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null })
  }

  @Test
  fun run_itemInterruptsUnderCap_neverStartsQueuedItems() {
    // Arrange: with a cap of one, items 2 and 3 wait for item 1, which interrupts.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker =
      parallelNode(
        node<Long, Long>("worker") { _, item ->
          if (item == 1L) emit(Event(longRunningToolIds = setOf("ask-1")))
          item
        },
        maxParallelWorkers = 1,
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert: only item 1 ran, and its interrupt left the node waiting.
    assertContains(events.flatMap { it.longRunningToolIds }, "ask-1")
    assertEquals(0, events.count { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null })
    assertEquals(
      setOf("wf@1/worker@1/worker@1"),
      events.mapNotNull { it.nodeInfo?.path }.filter { it.startsWith("wf@1/worker@1/") }.toSet(),
    )
  }

  @Test
  fun run_siblingFailsAfterAnInterrupt_failsWholeNode() {
    // Arrange: item 1 interrupts at once; item 2 keeps running and then fails.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(InterruptThenFailure("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val error = assertFailsWith<TestNodeException> { runWorkflow(workflow) }

    // Assert: the failure outranks the earlier interrupt.
    assertContains(error.message!!, "late failure")
  }

  @Test
  fun run_siblingFailsAfterAnInterrupt_recordsNoInterruptIds() {
    // Arrange: item 1 interrupts at once; item 2 keeps running and then fails.
    val worker = parallelNode(InterruptThenFailure("worker"))
    val root =
      Context(
        invocationContext = testInvocationContext(),
        node = StubNode("root"),
        eventSink = { _, _ -> },
        nodePath = "",
      )

    // Act
    val context = runBlocking {
      NodeRunner(node = worker, parent = root, runId = "1").run(listOf(1L, 2L))
    }

    // Assert: the node failed, and it is not also waiting on item 1's interrupt.
    assertContains(context.requireNodeState().failure?.cause?.message.orEmpty(), "late failure")
    assertTrue(context.interruptIds.isEmpty())
  }

  @Test
  fun run_siblingCancelsItselfAfterAnInterrupt_propagatesTheCancellation() {
    // Arrange: item 1 interrupts at once; item 2 keeps running and then times itself out.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(InterruptThenSelfCancel("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act + Assert: the item's own cancellation outranks the earlier interrupt.
    assertFailsWith<CancellationException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect {} }
    }
  }

  @Test
  fun run_itemCancelsItself_cancelsSiblings() {
    // Arrange: item 2 times itself out at once; item 1 would only finish after a delay.
    val source = Emitter("source", listOf(1L, 2L))
    val probe = SelfCancelProbe("worker", stuckItem = 2L)
    val worker = parallelNode(probe)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    assertFailsWith<CancellationException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect {} }
    }

    // Assert
    assertFalse(probe.siblingCompleted)
  }

  @Test
  fun run_itemCancelsItself_propagatesInsteadOfDroppingTheItem() {
    // Arrange: item 2's cancellation comes from its own timeout, not from a failing sibling.
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(SelfTimingOutItem("worker", stuckItem = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    assertFailsWith<CancellationException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
    }

    // Assert
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null })
  }

  @Test
  fun run_multipleItemsInterrupt_mergesAllInterruptIdsOntoNode() {
    // Arrange: both items emit an interrupt in the same turn, and the workflow records the node's
    // merged interrupts in its checkpoint.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(SimultaneousInterrupts("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val ctx =
      testInvocationContext().copy(resumabilityConfig = ResumabilityConfig(isResumable = true))

    // Act
    val events = runBlocking { workflow.runAsync(ctx).toList() }

    // Assert: both items' interrupt ids are emitted and merged onto the worker node's state.
    assertEquals(listOf("ask-1", "ask-2"), events.flatMap { it.longRunningToolIds })
    val checkpoint = events.last { it.actions.agentState != null }.actions.agentState.toString()
    assertContains(checkpoint, "ask-1")
    assertContains(checkpoint, "ask-2")
  }

  @Test
  fun parallelNode_itemExceedsTimeout_timesOutThatItem() {
    // Arrange: the wrapped node's timeout applies per item.
    val source = Emitter("source", listOf(1L, 2L))
    val worker = parallelNode(ConfiguredTimeoutItem("worker", stuckItem = 2L))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
    val events = mutableListOf<Event>()

    // Act
    assertFailsWith<NodeTimeoutException> {
      runBlocking { workflow.runAsync(testInvocationContext()).collect { events.add(it) } }
    }

    // Assert: item 2 timed out under its item path.
    assertEquals(
      listOf("wf@1/worker@1/worker@2"),
      events.filter { it.errorCode != null }.map { it.nodeInfo?.path },
    )
  }

  @Test
  fun runner_parallelNodeOverList_storesEachItemAndTheListOutput() {
    // Arrange
    val source = Emitter("source", listOf(1L, 2L, 3L))
    val worker = parallelNode(StateTaggingDoubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val session = runWorkflowAsApp(workflow)

    // Assert: items finish in any order, so their stored outputs are sorted by path.
    val itemOutputs =
      session.events
        .filter { it.nodeInfo?.path.orEmpty().startsWith("wf@1/worker@1/") && it.output != null }
        .map { it.nodeInfo?.path.orEmpty() to it.output }
        .sortedBy { it.first }
    assertEquals<List<Pair<String, Any?>>>(
      listOf(
        "wf@1/worker@1/worker@1" to 2L,
        "wf@1/worker@1/worker@2" to 4L,
        "wf@1/worker@1/worker@3" to 6L,
      ),
      itemOutputs,
    )
    val workerEvent =
      session.events.single { it.nodeInfo?.path == "wf@1/worker@1" && it.output != null }
    assertEquals(listOf(2L, 4L, 6L), workerEvent.output)
    assertEquals<List<Any?>>(
      listOf(2L, 4L, 6L),
      listOf("item_1", "item_2", "item_3").map { session.state[it] },
    )
  }

  @Test
  fun parallelNode_textContentWithJsonList_fansOutOverItsElements() {
    // Arrange: a message, such as the user's, carries the list as JSON text.
    val source = Emitter("source", Content(parts = listOf(Part(text = "[1, 2, 3]"))))
    val worker = parallelNode(PlainItemDoubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(2L, 4L, 6L), events.last().output)
  }

  @Test
  fun parallelNode_contentWithNonTextPart_runsAsOneItem() {
    // Arrange
    val message =
      Content(parts = listOf(Part(text = "[1, 2]"), Part(functionCall = FunctionCall(name = "f"))))
    val source = Emitter("source", message)
    val worker = parallelNode(ItemEcho("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(message), events.last().output)
  }

  @Test
  fun parallelNode_textThatIsNotJson_runsAsOneRawTextItem() {
    // Arrange
    val source = Emitter("source", Content(parts = listOf(Part(text = "hello"))))
    val worker = parallelNode(ItemEcho("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("hello"), events.last().output)
  }

  @Test
  fun parallelNode_jsonObjectTextIntoStringSchemaItem_staysRawText() {
    // Arrange
    val source = Emitter("source", Content(parts = listOf(Part(text = "{\"a\":1}"))))
    val worker = parallelNode(ItemEcho("worker", inputSchema = Schema(type = Type.STRING)))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("{\"a\":1}"), events.last().output)
  }

  @Test
  fun parallelNode_functionNodeTakingContent_getsTheMessageAsOneItem() {
    // Arrange
    val source = Emitter("source", Content(parts = listOf(Part(text = "[1, 2]"))))
    val worker = parallelNode(node<Content, String>("worker") { _, message -> message.text() })
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("[1, 2]"), events.last().output)
  }

  @Test
  fun parallelNode_textContentWithJsonScalar_runsOnTheParsedValue() {
    // Arrange
    val source = Emitter("source", Content(parts = listOf(Part(text = "5"))))
    val worker = parallelNode(PlainItemDoubler("worker"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf(10L), events.last().output)
  }

  @Test
  fun parallelNode_jsonLikeTextIntoFunctionNodeWithStringSchema_staysRawText() {
    // Arrange: the node's type accepts anything, but its schema asks for a string.
    val worker =
      parallelNode(
        node<Any?, String>("worker", inputSchema = Schema(type = Type.STRING)) { _, text ->
          text.toString()
        }
      )

    // Act
    val outputs =
      listOf("5", "{\"a\":1}", "hello").map { text ->
        val source = Emitter("source", Content(parts = listOf(Part(text = text))))
        runWorkflow(
            Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))
          )
          .last()
          .output
      }

    // Assert: each message arrives as the raw text, as for a plain node with a string schema.
    assertEquals(listOf(listOf("5"), listOf("{\"a\":1}"), listOf("hello")), outputs)
  }

  @Test
  fun parallelNode_jsonObjectTextIntoStringFunctionNode_staysRawText() {
    // Arrange
    val source = Emitter("source", Content(parts = listOf(Part(text = "{\"a\":1}"))))
    val worker = parallelNode(node<String, String>("worker") { _, text -> text })
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, source), Edge(source, worker)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(listOf("{\"a\":1}"), events.last().output)
  }

  @Test
  fun runNode_itemSkipsSummarization_parallelNodeAsksCallerToSkipIt() {
    // Arrange
    val worker =
      parallelNode(
        node<Long, Long>("worker") { context, item ->
          if (item == 2L) context.actions.skipSummarization = true
          item
        }
      )
    val probe = FanOutSkipSummarizationProbe("probe", worker)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, probe)))

    // Act
    val events = runWorkflow(workflow)

    // Assert
    assertEquals(true, events.last { it.nodeInfo?.path == "wf@1/probe@1" }.output)
  }
}
