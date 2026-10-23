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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Asks the user for input, which suspends the graph. */
private class Ask(override val name: String, private val request: RequestInput) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(request) }
}

/** Emits a value and then, in the same activation, asks for input. */
private class EmitThenAsk(
  override val name: String,
  private val premature: Any?,
  private val request: RequestInput,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(premature)
    emit(request)
  }
}

/** Pauses on a long-running call that is not a request for input, the way a tool interrupt does. */
private class LongRunningAsk(override val name: String, private val interruptId: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "approve", id = interruptId))),
          ),
        longRunningToolIds = setOf(interruptId),
      )
    )
  }
}

/** Dispatches [child] dynamically via [Context.runNode] and emits whatever it returns. */
private class DispatchAsk(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(child, runId = "c"))
  }
}

/** Emits a fixed value and counts the times it actually ran, so a replay is observable. */
private class RunCountingEmitter(
  override val name: String,
  private val value: Any?,
  override val rerunOnResume: Boolean = false,
) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    emit(value)
  }
}

/**
 * Selects [route] without emitting an output, counting its runs so a route replay is observable.
 */
private class RunCountingRouter(override val name: String, private val route: Route) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    context.routes = listOf(route)
  }
}

/** Dispatches [first] and then [second], and emits both results. */
private class DispatchPair(
  override val name: String,
  private val first: Node,
  private val second: Node,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val a = context.runNode(first, runId = "a")
    val b = context.runNode(second, runId = "b")
    emit(listOf(a, b))
  }
}

/** Delegates its output to [child]; a node that delegates produces no output of its own. */
private class DelegateToChild(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNode(child, runId = "c", useAsOutput = true)
  }
}

/** Emits the answers it resumed with, or dispatches [child] while it has none. */
private class ReportsResumeInputs(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    if (context.resumeInputs.isEmpty()) emit(context.runNode(child, runId = "c"))
    else emit(mapOf("resumed" to context.resumeInputs))
  }
}

/**
 * Asks for input on item 2 once items 1 and 3 have finished, and echoes any other item, counting
 * runs per item.
 */
private class AsksOnSecondItem(override val name: String) : Node {
  val runsByItem = mutableMapOf<Any?, Int>()
  private val othersFinished = mapOf(1L to CompletableDeferred<Unit>(), 3L to CompletableDeferred())

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runsByItem[nodeInput] = (runsByItem[nodeInput] ?: 0) + 1
    if (nodeInput == 2L) {
      othersFinished.values.awaitAll()
      emit(RequestInput(interruptId = "ask_2", message = "item 2?"))
    } else {
      emit("done-$nodeInput")
      othersFinished[nodeInput]?.complete(Unit)
    }
  }
}

/** Asks for input on each item in [asking] and echoes any other item, counting runs per item. */
private class AsksOnItems(override val name: String, private val asking: Set<Long>) : Node {
  val runsByItem = mutableMapOf<Any?, Int>()

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runsByItem[nodeInput] = (runsByItem[nodeInput] ?: 0) + 1
    if (nodeInput in asking) emit(RequestInput(interruptId = "ask_$nodeInput", message = "?"))
    else emit("done-$nodeInput")
  }
}

/** Dispatches [child] concurrently once per input item and emits the results in input order. */
private class DispatchEachConcurrently(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val items = nodeInput as List<*>
    val outputs = coroutineScope {
      items.map { item -> async { context.runNode(child, item, runId = "item$item") } }.awaitAll()
    }
    emit(outputs)
  }
}

/** Fails its first attempt and asks for input on its retry, counting its runs. */
private class FailsOnceThenAsks(override val name: String) : Node {
  var runs = 0
    private set

  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (context.attemptCount == 1) throw NodeExecutionException("RuntimeError", "first attempt")
    emit(RequestInput(interruptId = "ask_1", message = "need input"))
  }
}

/** Asks for input until answered, then only writes state, counting its runs. */
private class AsksThenWritesState(override val name: String, private val interruptId: String) :
  Node {
  var runs = 0
    private set

  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (context.resumeInputs.isEmpty()) emit(RequestInput(interruptId = interruptId))
    else context.updateState(name, "done")
  }
}

/** Writes a state key and emits no output, counting its runs. */
private class WritesStateOnly(override val name: String) : Node {
  var runs = 0
    private set

  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    context.updateState(name, "done")
  }
}

/** Records each input it runs on, asking for input under [interruptId] until it gets an answer. */
private class RecordsInputThenAsks(override val name: String, private val interruptId: String) :
  Node {
  val inputs = mutableListOf<Any?>()

  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    inputs += nodeInput
    if (context.resumeInputs.isEmpty()) emit(RequestInput(interruptId = interruptId))
    else emit("done")
  }
}

/** Answers with a model-style message whose content is its output, the way an agent node does. */
private class MessageOutput(
  override val name: String,
  private val text: String,
  override val outputSchema: Schema? = null,
) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
        content = Content(role = Role.MODEL, parts = listOf(Part(text = text))),
        nodeInfo = NodeInfo(messageAsOutput = true),
      )
    )
  }
}

/**
 * An agent that pauses on a long-running call and hands off to its sub-agent `other` in the same
 * event.
 */
private class TransferAfterInterrupt(name: String) :
  BaseAgent(name = name, subAgents = listOf(IdleAgent("other"))) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    emit(
      Event(
        author = name,
        content =
          Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "approve", id = "t_1"))),
          ),
        longRunningToolIds = setOf("t_1"),
        actions = EventActions(transferToAgent = "other"),
      )
    )
  }
}

/** An agent that does nothing, as a transfer target. */
private class IdleAgent(name: String) : BaseAgent(name = name) {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {}
}

/**
 * Echoes its input, declaring an integer `count`, so a caller's input is validated before it runs.
 */
private class CountEcho(override val name: String) : Node {
  override val inputSchema: Schema =
    Schema(
      type = Type.OBJECT,
      properties = mapOf("count" to Schema(type = Type.INTEGER)),
      required = listOf("count"),
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow { emit(nodeInput) }
}

/** Dispatches [child] with a fixed [input] and emits what it returns. */
private class DispatchWithInput(
  override val name: String,
  private val child: Node,
  private val input: Any?,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(child, nodeInput = input))
  }
}

/** Runs [child] under a numeric run ID through the internal API, then fails its first attempt. */
private class DispatchNumberedThenFailOnce(override val name: String, private val child: Node) :
  Node {
  override val rerunOnResume: Boolean = true
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    context.runNodeUnchecked(child, runId = "1")
    if (context.attemptCount == 1) throw NodeExecutionException("RuntimeError", "first attempt")
    emit("done")
  }
}

/** Runs [child] under run ID `s` with a short timeout while a second call waits on that run. */
private class AbandonsASharedRun(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val outcomes = coroutineScope {
      val first = async {
        withTimeoutOrNull(100.milliseconds) { context.runNode(child, runId = "s") } ?: "timed out"
      }
      val second = async {
        withTimeoutOrNull(5.seconds) { context.runNode(child, runId = "s") } ?: "hung"
      }
      listOf(first.await(), second.await())
    }
    emit(outcomes)
  }
}

/** Emits a progress event and stalls on its first run, counting runs; later runs emit an output. */
private class ReportsProgressThenStallsOnFirstRun(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) {
      emit(Event(content = Content(role = Role.MODEL, parts = listOf(Part(text = "working")))))
      awaitCancellation()
    }
    emit("$name done")
  }
}

/** Emits a progress event and fails on its first run, counting runs; later runs emit [value]. */
private class ReportsProgressThenFailsFirstRun(override val name: String, private val value: Any?) :
  Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) {
      emit(Event(content = Content(role = Role.MODEL, parts = listOf(Part(text = "working")))))
      yield()
      throw NodeExecutionException("RuntimeError", "first run fails")
    }
    if (value != null) emit(value)
  }
}

/** Emits a progress event, then swallows the cancellation of its first run; later runs emit. */
private class ReportsProgressThenSwallowsCancellationOnFirstRun(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) {
      emit(Event(content = Content(role = Role.MODEL, parts = listOf(Part(text = "working")))))
      try {
        awaitCancellation()
      } catch (e: Exception) {}
      return@flow
    }
    emit("$name done")
  }
}

/** Emits a progress event and stalls on its first run; later runs finish without output. */
private class StallsOnceThenFinishesWithoutOutput(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) {
      emit(Event(content = Content(role = Role.MODEL, parts = listOf(Part(text = "working")))))
      awaitCancellation()
    }
  }
}

/** Fails on its first two runs, the second after a delay, and finishes without output after. */
private class FailsTwiceSecondAfterADelay(override val name: String) : Node {
  private var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    yield()
    if (runs == 2) delay(100.milliseconds)
    if (runs <= 2) throw NodeExecutionException("RuntimeError", "run $runs fails")
  }
}

/** Calls [child] twice under run ID `s`, with both calls under one short timeout. */
private class CancelsBothCallsOfASharedRun(override val name: String, private val child: Node) :
  Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val outputs =
      withTimeoutOrNull(100.milliseconds) {
        coroutineScope { List(2) { async { context.runNode(child, runId = "s") } }.awaitAll() }
      }
    emit(outputs ?: "timed out")
  }
}

/** Suspends once and fails on its first run; later runs finish without output. */
private class YieldsThenFailsFirstRun(override val name: String) : Node {
  private var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    yield()
    if (runs == 1) throw NodeExecutionException("RuntimeError", "first run fails")
  }
}

/** Runs a named and an anonymous child, then fails on its first attempt only; retries once. */
private class DispatchThenFailOnce(
  override val name: String,
  private val named: Node,
  private val anonymous: Node,
) : Node {
  override val rerunOnResume: Boolean = true
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val a = context.runNode(named, runId = "n")
    val b = context.runNode(anonymous)
    if (context.attemptCount == 1) throw NodeExecutionException("RuntimeError", "first attempt")
    emit(listOf(a, b))
  }
}

/** Dispatches [child] as `n`, then fails the first time it ever runs, so only a retry succeeds. */
private class DispatchThenFailFirstRun(override val name: String, private val child: Node) : Node {
  private var runs = 0

  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    val result = context.runNode(child, runId = "n")
    if (runs == 1) throw NodeExecutionException("RuntimeError", "first run fails")
    emit(result)
  }
}

/** Waits until cancelled on its first run and emits on any later one, counting its runs. */
private class StallsOnFirstRun(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) awaitCancellation()
    emit("$name done")
  }
}

/** Swallows the cancellation of its first run and returns without output, counting its runs. */
private class SwallowsCancellationOnFirstRun(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) {
      try {
        awaitCancellation()
      } catch (e: Exception) {}
      return@flow
    }
    emit("$name done")
  }
}

/** Suspends once and then fails, counting its runs, so a concurrent caller waits on a failure. */
private class YieldsThenFails(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    yield()
    throw NodeExecutionException("RuntimeError", "failed")
  }
}

/** Dispatches [child] twice at once under run ID `f` and emits whether each call failed. */
private class DispatchSameRunIdConcurrentlyCatching(
  override val name: String,
  private val child: Node,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val failed = coroutineScope {
      List(2) {
          async {
            try {
              context.runNode(child, runId = "f")
              false
            } catch (e: DynamicNodeFailedException) {
              true
            }
          }
        }
        .awaitAll()
    }
    emit(failed)
  }
}

/** Fails the first time it ever runs and succeeds after, so a retry of its workflow recovers. */
private class FailsFirstRun(override val name: String) : Node {
  private var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) throw NodeExecutionException("RuntimeError", "first run fails")
    emit("recovered")
  }
}

/** Asks for input under a fresh interrupt ID on each run, counting its runs. */
private class AsksUnderFreshId(override val name: String) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    emit(RequestInput(message = "?"))
  }
}

/** Asks for input on every run, under an interrupt id naming the run id. */
private class AsksEveryRun(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(RequestInput(interruptId = "${name}_${context.runId}", message = "?"))
  }
}

/** Routes along its `yes` edge on its first run only, so a loop through it goes round once. */
private class LoopsBackOnce(override val name: String) : Node {
  private var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) context.routes = listOf(yes())
  }
}

/** Dispatches [child] twice without naming either run, and emits both results. */
private class DispatchTwice(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(listOf(context.runNode(child), context.runNode(child)))
  }
}

/** Suspends once, then asks for input under [interruptId], counting its runs. */
private class YieldsThenAsks(
  override val name: String,
  private val interruptId: String,
  override val waitForOutput: Boolean = false,
) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    yield()
    emit(RequestInput(interruptId = interruptId, message = "?"))
  }
}

/** Dispatches [child] twice in sequence under run ID `a`, catching the first call's interrupt. */
private class RedispatchAfterInterrupt(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  @Suppress("SwallowedException") // The second call must raise the same interrupt again.
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    try {
      context.runNode(child, runId = "a")
    } catch (t: Throwable) {}
    emit(context.runNode(child, runId = "a"))
  }
}

/** Suspends once before emitting [value], counting its runs, so a concurrent caller overlaps it. */
private class YieldsThenEmits(override val name: String, private val value: Any?) : Node {
  var runs = 0
    private set

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    yield()
    emit(value)
  }
}

/** Dispatches [child] twice at once under the same explicit [runId] and emits both results. */
private class DispatchSameRunIdConcurrently(
  override val name: String,
  private val child: Node,
  private val runId: String,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val results = coroutineScope {
      List(2) { async { context.runNode(child, runId = runId) } }.awaitAll()
    }
    emit(results)
  }
}

/** Dispatches [child] twice at once under one run ID, delegating its output on the second call. */
private class DispatchThenDelegateConcurrently(override val name: String, private val child: Node) :
  Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    coroutineScope {
      val first = async { context.runNode(child, runId = "f") }
      val second = async { context.runNode(child, runId = "f", useAsOutput = true) }
      awaitAll(first, second)
    }
  }
}

/** Calls [child] under run ID `f`, catches its failure, then calls it again under the same ID. */
private class RetriesAFailedChild(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    try {
      context.runNode(child, runId = "f")
    } catch (e: DynamicNodeFailedException) {}
    emit(context.runNode(child, runId = "f"))
  }
}

/** Waits for output, producing none on its first run and [value] on later runs. */
private class EmitsFromItsSecondRun(override val name: String, private val value: Any?) : Node {
  var runs = 0
    private set

  override val waitForOutput: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs > 1) emit(value)
  }
}

/** Calls [child] twice in sequence under run ID `w` and emits both results. */
private class CallsTwice(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(listOf(context.runNode(child, runId = "w"), context.runNode(child, runId = "w")))
  }
}

/** Dispatches [child] under run ID `s`, with a short timeout and one retry. */
private class DispatchWithTimeout(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0),
      timeout = 1.seconds,
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(child, runId = "s"))
  }
}

/** Emits [value], counting its runs atomically so runs on different threads all count. */
@OptIn(ExperimentalAtomicApi::class)
private class AtomicCountingEmitter(override val name: String, private val value: Any?) : Node {
  val runs = AtomicInt(0)

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val unused = runs.addAndFetch(1)
    emit(value)
  }
}

/** Dispatches [child] from [calls] threads at once under the same explicit [runId]. */
private class DispatchSameRunIdInParallel(
  override val name: String,
  private val child: Node,
  private val runId: String,
  private val calls: Int,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val results = coroutineScope {
      List(calls) { async(Dispatchers.Default) { context.runNode(child, runId = runId) } }
        .awaitAll()
    }
    emit(results)
  }
}

/** Dispatches [child] twice under the same explicit [runId] in one activation. */
private class DispatchSameRunIdTwice(
  override val name: String,
  private val child: Node,
  private val runId: String,
) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val first = context.runNode(child, runId = runId)
    val second = context.runNode(child, runId = runId)
    emit(listOf(first, second))
  }
}

/**
 * Delegates its output to [child] (`useAsOutput = true`) and fails on its first attempt after
 * [child] emits, then succeeds on its retry attempt.
 */
private class DelegateThenFailOnce(override val name: String, private val child: Node) : Node {
  var runs = 0
    private set

  override val rerunOnResume: Boolean = true
  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    context.runNode(child, runId = "c", useAsOutput = true)
    if (context.attemptCount == 1) throw NodeExecutionException("RuntimeError", "first attempt")
  }
}

/**
 * Emits a premature output and fails on its first attempt, then succeeds on its retry attempt by
 * writing only a state key (no output, route, or transfer).
 */
private class EmitThenFailOnceThenWriteState(override val name: String, private val key: String) :
  Node {
  var runs = 0
    private set

  override val config: NodeConfig =
    NodeConfig(
      retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    )

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (context.attemptCount == 1) {
      emit(
        Event(
          author = "",
          output = "stale_from_attempt_1",
          actions = EventActions(route = listOf(no()), transferToAgent = "stale_agent"),
        )
      )
      throw NodeExecutionException("RuntimeError", "first attempt")
    }
    context.updateState(key, "ok")
  }
}

/** A runner over a resumable app rooted on [root], driven a turn at a time. */
private class WorkflowSession(root: Node) {
  private val runner =
    InMemoryRunner(
      App(
        appName = "wf_test",
        rootNode = root,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    )
  private val sessionId = "s1"
  private val userId = "u1"

  init {
    runBlocking {
      val session =
        runner.sessionService.createSession(SessionKey(runner.appName, userId, sessionId))
      check(session.key.id == sessionId)
    }
  }

  fun turn(message: Content): List<Event> = runBlocking {
    runner.runAsync(userId = userId, sessionId = sessionId, newMessage = message).toList()
  }

  /** Drives a turn expected to fail, returning the events sent before the failure and the error. */
  fun failingTurn(message: Content): Pair<List<Event>, Throwable> = runBlocking {
    val events = mutableListOf<Event>()
    val error =
      runCatching {
          runner.runAsync(userId = userId, sessionId = sessionId, newMessage = message).collect {
            events.add(it)
          }
        }
        .exceptionOrNull()
    events to checkNotNull(error) { "expected the turn to fail" }
  }

  /** Drives a turn whose events the test does not examine, only its effect on the next one. */
  fun advance(message: Content) {
    val ignored = turn(message)
    check(ignored.isNotEmpty()) {
      "a turn produced no events, so the next turn has nothing to resume from"
    }
  }
}

/** A node's interrupt event as it sits in the session, recorded under [path]. */
private fun requestEvent(path: String, interruptId: String, invocationId: String = "inv") =
  RequestInput(interruptId = interruptId, message = "?")
    .toEvent()
    .copy(author = "wf", invocationId = invocationId, nodeInfo = NodeInfo(path = path))

/** The user's function-response event as it sits in the session. */
private fun answerEvent(
  interruptId: String,
  response: Map<String, Any?>,
  invocationId: String = "inv",
  branch: String? = null,
) =
  Event(
    author = Role.USER,
    invocationId = invocationId,
    branch = branch,
    content = answer(interruptId, response),
  )

private fun answer(
  interruptId: String,
  response: Map<String, Any?>,
  name: String = RequestInput.FUNCTION_CALL_NAME,
) = userFunctionResponse(name, interruptId, response)

/** An answer schema requiring an integer `age`. */
private val AGE_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties = mapOf("age" to Schema(type = Type.INTEGER)),
    required = listOf("age"),
  )

/** A session paused on `ask_1`, whose answer must match [schema] before `reply` receives it. */
private fun sessionAwaitingAnswer(schema: Schema): WorkflowSession {
  val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "?", responseSchema = schema))
  val workflow =
    Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, CaptureInput("reply"))))
  return WorkflowSession(workflow).also { it.advance(userMessage("go")) }
}

@Serializable private data class RemovalTarget(val path: String)

@Serializable private data class RemovalResult(val removed: Boolean)

/** The interrupt id of `remove_file`'s confirmation request in [removalWorkflow]. */
private const val REMOVAL_CONFIRMATION_ID = "wf_tool_confirmation:wf@1/remove_file@1"

/** A workflow passing `{"path": path}` through [remove] into `reply`. */
private fun removalWorkflow(remove: Node, path: String = "/tmp/a"): Workflow {
  val target = Emitter("target", mapOf("path" to path))
  val reply = CaptureInput("reply")
  return Workflow(
    name = "wf",
    edges = listOf(Edge(Start, target), Edge(target, remove), Edge(remove, reply)),
  )
}

/** What the workflow's `reply` node received in this turn. */
private fun List<Event>.replyInput(): Any? =
  (single { it.nodeInfo?.path == "wf@1/reply@1" }.output as Map<*, *>)["received"]

/** A workflow feeding items 1, 2 and 3 through [fanOut] into `reply`. */
private fun fanOutWorkflow(fanOut: Node): Workflow {
  val items = Emitter("items", listOf(1L, 2L, 3L))
  val reply = CaptureInput("reply")
  return Workflow(
    name = "wf",
    edges = listOf(Edge(Start, items), Edge(items, fanOut), Edge(fanOut, reply)),
  )
}

/** An object schema with an integer `count`. */
private val COUNT_SCHEMA =
  Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))

@Serializable private data class TimeOffRequest(val days: Int, val reason: String)

@Serializable
private data class TimeOffDecision(
  val approved: Boolean,
  @SerialName("approved_days") val approvedDays: Int? = null,
)

/** The manager's answer schema, matching [TimeOffDecision]. */
private val TIME_OFF_DECISION_SCHEMA =
  Schema(
    type = Type.OBJECT,
    properties =
      mapOf(
        "approved" to Schema(type = Type.BOOLEAN),
        "approved_days" to Schema(type = Type.INTEGER, nullable = true),
      ),
    required = listOf("approved"),
  )

/**
 * A time-off workflow that approves a request of at most one day and asks a manager otherwise, then
 * reports the decision. A node stands in for the LLM agent that extracts the request.
 */
private fun timeOffWorkflow(days: Int): Workflow {
  val processRequest =
    node<Any?, TimeOffRequest>("process_request") { ctx, _ ->
      val request = TimeOffRequest(days = days, reason = "family trip")
      ctx.updateState("request", mapOf("days" to request.days, "reason" to request.reason))
      request
    }
  val evaluateRequest =
    node<TimeOffRequest, TimeOffDecision>("evaluate_request") { _, request ->
      if (request.days <= 1) {
        TimeOffDecision(approved = true)
      } else {
        requestInput(
          interruptId = "manager_approval",
          message = "Please review this time off request.",
          payload = request,
          responseSchema = TIME_OFF_DECISION_SCHEMA,
        )
      }
    }
  val processDecision =
    node<TimeOffDecision, Unit>("process_decision") { ctx, decision ->
      val requested = ((ctx.state["request"] as Map<*, *>)["days"] as Number).toInt()
      val text =
        if (decision.approved) {
          "Time Off Approved! ${decision.approvedDays ?: requested} out of $requested days granted."
        } else {
          "Time Off Denied."
        }
      emit(Content.fromText(Role.MODEL, text))
    }
  return Workflow(
    name = "wf",
    edges =
      listOf(
        Edge(Start, processRequest),
        Edge(processRequest, evaluateRequest),
        Edge(evaluateRequest, processDecision),
      ),
  )
}

/** The text of the message `process_decision` sent in [this] turn's events. */
private fun List<Event>.decisionMessage(): String? =
  singleOrNull { it.nodeInfo?.path == "wf@1/process_decision@1" && it.content != null }
    ?.content
    ?.text()

class WorkflowHitlTest {

  @Test
  fun requestInput_noInterruptId_generatesOne() {
    // Act: a caller that does not care about the id need not mint one.
    val request = RequestInput(message = "name?")

    // Assert
    assertTrue(request.interruptId.isNotBlank())
  }

  @Test
  fun requestInput_emitted_suspendsTheGraphAndCarriesTheRequest() {
    // Arrange
    val ask =
      Ask(
        "ask",
        RequestInput(
          interruptId = "ask_1",
          message = "Please provide user details.",
          payload = mapOf("fields" to "name, age"),
        ),
      )
    val reply = Emitter("reply", "done")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert
    val requestEvent = events.single { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    val call = requestEvent.functionCalls().single()
    assertEquals(RequestInput.FUNCTION_CALL_NAME, call.name)
    assertEquals("ask_1", call.id)
    assertEquals("Please provide user details.", call.args[RequestInput.MESSAGE_KEY])
    assertEquals(mapOf("fields" to "name, age"), call.args[RequestInput.PAYLOAD_KEY])
    // The interrupt event carries a model role, so the history rewriter keeps it rather than
    // dropping it as empty and orphaning the answering response.
    assertEquals(Role.MODEL, requestEvent.content?.role)
    // The graph suspends, so the node downstream of the request does not run.
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/reply@1" })
  }

  @Test
  fun resume_answeredRequest_completesTheNodeAndRunsItsSuccessor() {
    // Arrange
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))
    val details = mapOf("name" to "John", "age" to 30L)

    // Act
    val events = session.turn(answer("ask_1", details))

    // Assert
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to details), replyEvent.output)
  }

  @Test
  fun requestInput_returnedFromABody_failsAndPointsToRequestInput() {
    // Arrange: an Any? output type would accept the request as a value.
    val ask = node<Any?, Any?>("ask") { _, _ -> RequestInput(interruptId = "ask_1") }

    // Act
    val (events, error) =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))
        .failingTurn(userMessage("go"))

    // Assert
    assertTrue(error is IllegalArgumentException, "expected a misuse error, got $error")
    assertContains(error.message.orEmpty(), "call requestInput(...)")
    assertTrue(events.none { e -> e.functionCalls().isNotEmpty() })
  }

  @Test
  fun requestInput_typedNode_asksAndPassesTheTypedAnswerToItsSuccessor() {
    // Arrange: three days need the manager's approval.
    val session = WorkflowSession(timeOffWorkflow(days = 3))

    // Act
    val asked = session.turn(userMessage("I need three days off for a family trip"))
    val decided =
      session.turn(answer("manager_approval", mapOf("approved" to true, "approved_days" to 2L)))

    // Assert: the first turn asks and stops; the answer then reaches process_decision as a
    // TimeOffDecision.
    val call = asked.flatMap { it.functionCalls() }.single()
    assertEquals("manager_approval", call.id)
    assertEquals("Please review this time off request.", call.args[RequestInput.MESSAGE_KEY])
    assertEquals(
      mapOf("days" to 3L, "reason" to "family trip"),
      call.args[RequestInput.PAYLOAD_KEY],
    )
    assertNotNull(call.args[RequestInput.RESPONSE_SCHEMA_KEY])
    assertNull(asked.decisionMessage())
    assertEquals("Time Off Approved! 2 out of 3 days granted.", decided.decisionMessage())
  }

  @Test
  fun requestInput_deniedAnswer_reachesTheSuccessor() {
    // Arrange
    val session = WorkflowSession(timeOffWorkflow(days = 5))
    session.advance(userMessage("I need five days off"))

    // Act
    val decided = session.turn(answer("manager_approval", mapOf("approved" to false)))

    // Assert
    assertEquals("Time Off Denied.", decided.decisionMessage())
  }

  @Test
  fun requestInput_oneDayRequest_approvesWithoutAsking() {
    // Act: one day is approved without the manager.
    val events = WorkflowSession(timeOffWorkflow(days = 1)).turn(userMessage("One day off, please"))

    // Assert
    assertTrue(events.none { e -> e.functionCalls().isNotEmpty() })
    assertEquals("Time Off Approved! 1 out of 1 days granted.", events.decisionMessage())
  }

  @Test
  fun requestInput_afterEmittedContent_sendsTheMessageThenAsks() {
    // Arrange
    val ask =
      node<Any?, String>("ask") { _, _ ->
        emit(Content.fromText(Role.MODEL, "Checking"))
        requestInput(interruptId = "ask_1", message = "Approve?")
      }
    val reply = CaptureInput("reply")

    // Act
    val events =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply))))
        .turn(userMessage("go"))

    // Assert: a message, then the request; the node outputs nothing and its successor waits.
    val askEvents = events.filter { it.nodeInfo?.path == "wf@1/ask@1" }
    assertEquals("Checking", askEvents.first().content?.text())
    assertEquals("ask_1", askEvents.last().functionCalls().single().id)
    assertTrue(askEvents.none { it.output != null })
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/reply@1" })
  }

  @Test
  fun requestInput_answerThatDoesNotDecode_failsTheSuccessorsInputCheck() {
    // Arrange: an answer with no schema, so only the successor's typed input checks it.
    val ask = node<Any?, TimeOffDecision>("ask") { _, _ -> requestInput(interruptId = "ask_1") }
    val decide = node<TimeOffDecision, Unit>("decide") { _, _ -> }
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, decide))))
    session.advance(userMessage("go"))

    // Act
    val (events, error) = session.failingTurn(answer("ask_1", mapOf("result" to "yes")))

    // Assert
    assertTrue(error is NodeInputValidationException, "expected the successor's check, got $error")
    assertEquals("wf@1/decide@1", events.single { it.errorCode != null }.nodeInfo?.path)
  }

  @Test
  fun requestInput_insideRunCatching_cancelsTheRequest() {
    // Arrange: the body swallows the throw that ends its run and returns a fallback.
    val ask =
      node<Any?, String>("ask") { _, _ ->
        val unused = runCatching { requestInput(interruptId = "ask_1") }
        "fallback"
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply))))

    // Act
    val events = session.turn(userMessage("go"))

    // Assert: no request is sent, and the fallback reaches the successor.
    assertTrue(events.all { it.functionCalls().isEmpty() })
    assertEquals(
      mapOf("received" to "fallback"),
      events.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun requestInput_customNode_asksAndCompletesWithTheAnswer() {
    // Arrange: a custom node asks from its own flow.
    val ask =
      object : Node {
        override val name = "ask"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          requestInput(interruptId = "ask_c", message = "Name?")
        }
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply))))

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer("ask_c", mapOf("result" to "Ada")))

    // Assert
    val call = asked.flatMap { it.functionCalls() }.single()
    assertEquals("ask_c", call.id)
    assertEquals("Name?", call.args[RequestInput.MESSAGE_KEY])
    assertEquals(
      mapOf("received" to "Ada"),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun requestInput_insideCatchException_endsTheRunAndAsks() {
    // Arrange: the throw that ends the run is not an Exception, so this catch does not see it.
    var bodyContinued = false
    val ask =
      node<Any?, String>("ask") { _, _ ->
        try {
          requestInput(interruptId = "ask_1")
        } catch (e: Exception) {
          bodyContinued = true
          "fallback"
        }
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply))))

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer("ask_1", mapOf("result" to "yes")))

    // Assert
    assertFalse(bodyContinued)
    assertTrue(asked.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(
      mapOf("received" to "yes"),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun requestInput_parallelNodeItem_pausesAndCompletesTheListWithTheAnswer() {
    // Arrange: item 2 asks while items 1 and 3 return.
    val runs = mutableMapOf<Long, Int>()
    val worker =
      parallelNode<Long, String>("worker") { _, item ->
        runs[item] = (runs[item] ?: 0) + 1
        if (item == 2L) requestInput(interruptId = "ask_2") else "done-$item"
      }
    val session = WorkflowSession(fanOutWorkflow(worker))

    // Act
    val asked = session.turn(userMessage("go"))
    val runsBeforeAnswer = runs.toMap()
    val answered = session.turn(answer("ask_2", mapOf("result" to "two")))

    // Assert: every item runs in the first turn, the list waits for the answer, and no item body
    // runs twice.
    val requestPath = asked.single { it.functionCalls().isNotEmpty() }.nodeInfo?.path.orEmpty()
    assertTrue(requestPath.substringAfterLast('/').startsWith("worker@"), requestPath)
    assertTrue(asked.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(mapOf(1L to 1, 2L to 1, 3L to 1), runsBeforeAnswer)
    assertEquals(listOf("done-1", "two", "done-3"), answered.replyInput())
    assertEquals(mapOf(1L to 1, 2L to 1, 3L to 1), runs)
  }

  @Test
  fun requestInput_nodeWithRetryPolicy_asksWithoutRetrying() {
    // Arrange
    var runs = 0
    val ask =
      node<Any?, String>(
        "ask",
        config =
          NodeConfig(retryConfig = RetryConfig(maxAttempts = 3, initialDelay = Duration.ZERO)),
      ) { _, _ ->
        runs += 1
        requestInput(interruptId = "ask_1")
      }
    val session = WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))

    // Act
    val events = session.turn(userMessage("go"))

    // Assert
    assertEquals(1, runs)
    assertTrue(events.any { "ask_1" in it.longRunningToolIds })
  }

  @Test
  fun requestInput_rootNode_completesWithTheAnswer() {
    // Arrange
    val ask = node<Any?, TimeOffDecision>("ask") { _, _ -> requestInput(interruptId = "ask_1") }
    val session = WorkflowSession(ask)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("approved" to true)))

    // Assert
    assertEquals(mapOf("approved" to true), events.single { it.output != null }.output)
  }

  @Test
  fun requestInput_afterStateUpdate_requestCarriesTheStateDelta() {
    // Arrange
    val ask =
      node<Any?, String>("ask") { ctx, _ ->
        ctx.updateState("asked", true)
        requestInput(interruptId = "ask_1")
      }

    // Act
    val events =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))
        .turn(userMessage("go"))

    // Assert
    val request = events.single { e -> e.functionCalls().any { it.id == "ask_1" } }
    assertEquals(true, request.actions.stateDelta["asked"])
  }

  @Test
  fun requestInput_dispatchedChild_pausesTheCallerUntilAnswered() {
    // Arrange: the caller dispatches a typed node that asks.
    val child = node<Any?, String>("child") { _, _ -> requestInput(interruptId = "child_ask") }
    val caller = DispatchAsk("caller", child)
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, caller), Edge(caller, reply)))
      )

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer("child_ask", mapOf("result" to "yes")))

    // Assert: one request, sent by the child, not by the caller.
    val requestPath = asked.single { it.functionCalls().isNotEmpty() }.nodeInfo?.path.orEmpty()
    assertTrue(requestPath.substringAfterLast('/').startsWith("child@"), requestPath)
    assertTrue(asked.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(
      mapOf("received" to "yes"),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun requestInput_serializablePayload_sendsItsJsonFormSoTheEventPersists() {
    // Arrange
    val ask =
      node<Any?, String>("ask") { _, _ ->
        requestInput(interruptId = "ask_1", payload = TimeOffRequest(days = 2, reason = "move"))
      }

    // Act
    val asked =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))
        .turn(userMessage("go"))

    // Assert: the payload is plain JSON, so the request event encodes for a session service.
    val request = asked.single { it.functionCalls().isNotEmpty() }
    assertEquals(
      mapOf("days" to 2L, "reason" to "move"),
      request.functionCalls().single().args[RequestInput.PAYLOAD_KEY],
    )
    val unused = adkJson.encodeToString(Event.serializer(), request)
  }

  @Test
  fun requestInput_payloadWithNoJsonForm_failsTheAskingNode() {
    // Arrange
    val ask =
      node<Any?, String>("ask") { _, _ ->
        requestInput<Any>(interruptId = "ask_1", payload = Any())
      }

    // Act
    val (events, error) =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))
        .failingTurn(userMessage("go"))

    // Assert: encoding the payload fails the asking node, and no request is sent.
    assertContains(error.message.orEmpty(), "payload of requestInput")
    assertTrue(events.any { it.nodeInfo?.path == "wf@1/ask@1" && it.errorCode != null })
    assertTrue(events.none { it.functionCalls().isNotEmpty() })
  }

  @Test
  fun requestInput_requestThatCannotBeSent_failsTheAskingNode() {
    // Arrange: a schema default that is not JSON-native cannot be written into the request.
    val ask =
      node<Any?, String>("ask") { _, _ ->
        requestInput(interruptId = "ask_1", responseSchema = Schema(default = Any()))
      }

    // Act
    val (events, error) =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask))))
        .failingTurn(userMessage("go"))

    // Assert: the asking node reports the failure on its own error event.
    assertContains(error.message.orEmpty(), "AnySerializer")
    assertTrue(events.any { it.nodeInfo?.path == "wf@1/ask@1" && it.errorCode != null })
    assertTrue(events.none { it.functionCalls().isNotEmpty() })
  }

  @Test
  fun requestInput_nodeWithTimeout_waitsInsteadOfFailing() {
    // Arrange
    val ask =
      node<Any?, String>("ask", config = NodeConfig(timeout = 5000.milliseconds)) { _, _ ->
        requestInput(interruptId = "ask_1")
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply))))

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer("ask_1", mapOf("result" to "yes")))

    // Assert
    assertTrue(asked.none { it.errorCode != null })
    assertEquals(
      mapOf("received" to "yes"),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun requestInput_rerunOnResume_rerunsTheBodyWithTheAnswer() {
    // Arrange: the body asks on its first run and uses the answer when it runs again.
    val review =
      node<Any?, String>("review", rerunOnResume = true) { ctx, _ ->
        val answer =
          ctx.resumeInputs["human_review"]
            ?: requestInput(interruptId = "human_review", message = "Approve?")
        "review: $answer"
      }
    val session = WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, review))))
    session.advance(userMessage("draft"))

    // Act
    val events = session.turn(answer("human_review", mapOf("result" to "approve")))

    // Assert
    assertEquals(
      "review: approve",
      events.single { it.nodeInfo?.path == "wf@1/review@1" && it.output != null }.output,
    )
  }

  /** Replays a [Content] output in its JSON form, which a rerun `Content` successor reads back. */
  @Test
  fun resume_contentOutputBeforeTheInterrupt_reachesARerunSuccessorAsContent() {
    // Arrange: review asks on its first run and reads draft's replayed output when it runs again.
    val drafted = Content.fromText(Role.MODEL, "hello")
    val draft = node<Any?, Content>("draft") { _, _ -> drafted }
    val review =
      node<Content, String>("review", rerunOnResume = true) { ctx, draftContent ->
        ctx.resumeInputs["human_review"] ?: requestInput(interruptId = "human_review")
        draftContent.text()
      }
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, draft), Edge(draft, review)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("human_review", mapOf("result" to "approve")))

    // Assert
    assertEquals(
      "hello",
      events.firstOrNull { it.nodeInfo?.path == "wf@1/review@1" && it.output != null }?.output,
    )
  }

  @Test
  fun resume_nodeCompletedBeforeTheInterrupt_isReplayedNotRerun() {
    // Arrange
    val prep = RunCountingEmitter("prep", "prep_out")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val reply = CaptureInput("reply")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, prep), Edge(prep, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: prep's output is re-surfaced, but counts for no further path because it did not run.
    assertEquals(1, prep.runs)
    val prepEvent = events.single { it.nodeInfo?.path == "wf@1/prep@1" }
    assertEquals("prep_out", prepEvent.output)
    assertEquals(null, prepEvent.nodeInfo?.outputFor)
  }

  @Test
  fun resume_routingNodeCompletedBeforeTheInterrupt_replaysItsRouteWithoutRerunning() {
    // Arrange: `router` selects the `yes` edge without producing an output, then `ask` suspends.
    val router = RunCountingRouter("router", yes())
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val reply = CaptureInput("reply")
    val unreached = RunCountingEmitter("unreached", "no_out")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, router),
            Edge(router, ask, listOf(yes())),
            Edge(router, unreached, listOf(no())),
            Edge(ask, reply),
          ),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: `router` replays its `yes` route without rerunning, so `ask` and `reply` finish and
    // `unreached` never runs.
    assertEquals(1, router.runs)
    assertEquals(0, unreached.runs)
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to mapOf("v" to 1L)), replyEvent.output)
  }

  @Test
  fun resume_twoParallelInterruptsAnsweredOnSeparateTurns_waitsForBothBeforeJoining() {
    // Arrange: two branches ask in parallel on turn 1; turn 2 answers only the first.
    val askA = Ask("ask_a", RequestInput(interruptId = "a_1", message = "A?"))
    val askB = Ask("ask_b", RequestInput(interruptId = "b_1", message = "B?"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, askA), Edge(Start, askB), Edge(askA, join), Edge(askB, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val turn2 = session.turn(answer("a_1", mapOf("a" to 1L)))
    val turn3 = session.turn(answer("b_1", mapOf("b" to 2L)))

    // Assert: turn 2 keeps `ask_b` waiting so `j` does not run until turn 3 answers `b_1`.
    assertTrue(turn2.none { it.nodeInfo?.path == "wf@1/j@1" })
    val joinEvent = turn3.single { it.nodeInfo?.path == "wf@1/j@1" }
    assertEquals(mapOf("ask_a" to mapOf("a" to 1L), "ask_b" to mapOf("b" to 2L)), joinEvent.output)
  }

  @Test
  fun resume_replayedDelegatingNode_passesItsEncodedOutputToTheJoin() {
    // Arrange: `router` finishes on turn 1 while `ask` waits, so turn 2 replays it into `j`.
    val asText = node<Any?, String>("as_text") { _, _ -> "42" }
    val router =
      node<Any?, Int?>("router", rerunOnResume = true) { ctx, _ ->
        val unused = ctx.runNode(asText, useAsOutput = true)
        null
      }
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "?"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, router), Edge(Start, ask), Edge(router, join), Edge(ask, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("ok" to true)))

    // Assert: the workflow re-sends the replayed output, encoded, and the join receives it.
    val resent = events.filter { it.nodeInfo?.path == "wf@1/router@1" && it.output != null }
    assertEquals(listOf<Any?>(42L), resent.map { it.output })
    val joinOutput = events.single { it.nodeInfo?.path == "wf@1/j@1" }.output as Map<*, *>
    assertEquals(42L, joinOutput["router"])
  }

  @Test
  fun resume_delegatingNodeRerunAfterItsChild_outputsTheEncodedValue() {
    // Arrange: `router` delegates, then asks, so turn 2 reruns it and replays its child.
    val asText = node<Any?, String>("as_text") { _, _ -> "42" }
    val router =
      node<Any?, Int?>("router", rerunOnResume = true) { ctx, _ ->
        val unused = ctx.runNode(asText, useAsOutput = true)
        if (ctx.resumeInputs["ask_1"] == null) requestInput(interruptId = "ask_1")
        null
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, router), Edge(router, CaptureInput("reply"))),
      )
    val session = WorkflowSession(workflow)
    val asked = session.turn(userMessage("go"))

    // Act
    val answered = session.turn(answer("ask_1", mapOf("ok" to true)))

    // Assert
    assertTrue(asked.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(
      42L,
      answered.single { it.nodeInfo?.path == "wf@1/router@1" && it.output != null }.output,
    )
    assertEquals(
      mapOf("received" to 42L),
      answered.single { it.nodeInfo?.path == "wf@1/reply@1" }.output,
    )
  }

  @Test
  fun resumableWorkflow_run_checkpointsEachNodeAndEndsWithEndOfAgent() {
    // Arrange
    val prep = Emitter("prep", "prep_out")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, prep)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the last checkpoint records prep as completed, with no answers kept.
    val checkpoints = events.filter { it.actions.agentState != null }
    assertTrue(checkpoints.size >= 2, "expected a checkpoint on start and on completion")
    val completedPrep = NodeState(status = NodeStatus.COMPLETED).toCheckpoint()
    assertEquals(
      TypedData.MapValue(
        mapOf(NodeState.NODES_KEY to TypedData.MapValue(mapOf("prep" to completedPrep)))
      ),
      checkpoints.last().actions.agentState,
    )
    assertTrue(events.any { it.actions.endOfAgent }, "expected an end-of-agent marker")
  }

  @Test
  fun resume_objectAnswerWithWrongType_failsNamingTheInterrupt() {
    // Arrange
    val session = sessionAwaitingAnswer(AGE_SCHEMA)

    // Act: the age arrives as text, which an integer property rejects rather than parses.
    val error =
      assertFailsWith<IllegalArgumentException> {
        session.turn(answer("ask_1", mapOf("age" to "25")))
      }

    // Assert
    assertContains(error.message!!, "Validation failed for interrupt ask_1")
  }

  @Test
  fun resume_objectAnswerMissingRequiredProperty_failsNamingTheInterrupt() {
    // Arrange
    val session = sessionAwaitingAnswer(AGE_SCHEMA)

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> {
        session.turn(answer("ask_1", mapOf("wrong" to 1L)))
      }

    // Assert
    assertContains(error.message!!, "Validation failed for interrupt ask_1")
  }

  @Test
  fun resume_objectAnswerWithoutOptionalProperty_isNotFilledIn() {
    // Arrange: `age` is optional.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties =
          mapOf("name" to Schema(type = Type.STRING), "age" to Schema(type = Type.INTEGER)),
        required = listOf("name"),
      )
    val session = sessionAwaitingAnswer(schema)

    // Act
    val events = session.turn(answer("ask_1", mapOf("name" to "Ada")))

    // Assert: the omitted `age` stays absent rather than null.
    assertEquals(mapOf("name" to "Ada"), events.replyInput())
  }

  @Test
  fun resume_textAnswerWithoutSchema_isReadAsJson() {
    // Arrange
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "?"))
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, CaptureInput("reply"))))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to """{"v": 1}""")))

    // Assert
    assertEquals(mapOf("v" to 1L), events.replyInput())
  }

  @Test
  fun resume_jsonLikeTextAnswerToStringSchema_staysText() {
    // Arrange
    val session = sessionAwaitingAnswer(Schema(type = Type.STRING))

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to "42")))

    // Assert
    assertEquals("42", events.replyInput())
  }

  @Test
  fun resume_textAnswerToAnyOfWithString_staysText() {
    // Arrange
    val schema = Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    val session = sessionAwaitingAnswer(schema)

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to "42")))

    // Assert
    assertEquals("42", events.replyInput())
  }

  @Test
  fun resume_textAnswerToBooleanSchema_isReadAsJson() {
    // Arrange
    val session = sessionAwaitingAnswer(Schema(type = Type.BOOLEAN))

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to "true")))

    // Assert
    assertEquals(true, events.replyInput())
  }

  @Test
  fun resume_nestedWorkflow_outputsItsTerminalNodesResult() {
    // Arrange: a sub-workflow emits an intermediate output, pauses on an interrupt, and finishes
    // at its terminal node on resume.
    val step1 = Emitter("step1", "X")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val done = Emitter("done", "DONE")
    val sub =
      Workflow(name = "sub", edges = listOf(Edge(Start, step1), Edge(step1, ask), Edge(ask, done)))
    val after = CaptureInput("after")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub), Edge(sub, after)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: `after` runs on the sub-workflow's terminal output, not the intermediate "X".
    val afterEvent = events.single { it.nodeInfo?.path == "wf@1/after@1" }
    assertEquals(mapOf("received" to "DONE"), afterEvent.output)
  }

  @Test
  fun resume_scalarAnswerUnderResult_isUnwrapped() {
    // Arrange
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "name?"))
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to "Ada")))

    // Assert
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to "Ada"), replyEvent.output)
  }

  @Test
  fun resume_valueEmittedBeforeTheInterrupt_isReplacedByTheAnswer() {
    // Arrange: the node emits a value and then pauses on an interrupt in one activation. On resume
    // it must complete with the answer, not the value it emitted before it asked.
    val ask =
      EmitThenAsk(
        "ask",
        premature = "stale",
        request = RequestInput(interruptId = "ask_1", message = "need input"),
      )
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the reply runs on the answer, not on the stale pre-interrupt value.
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to mapOf("v" to 1L)), replyEvent.output)
  }

  @Test
  fun resume_longRunningCallInterrupt_isRecovered() {
    // Arrange: the node pauses on a long-running call that is not a request for input, so only
    // `longRunningToolIds` marks the interrupt for the resume scan to find.
    val ask = LongRunningAsk("ask", "lr_1")
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("lr_1", mapOf("result" to "ok"), name = "approve"))

    // Assert: the answer completes the node and reaches its successor.
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to "ok"), replyEvent.output)
  }

  @Test
  fun resume_dynamicChildThatAsked_completesWithTheAnswer() {
    // Arrange: a node dispatches a child via context.runNode; the child asks for input, so the run
    // suspends. On resume the dispatcher reruns and the child completes with the answer.
    val dispatcher =
      DispatchAsk(
        "dispatch",
        Ask("child", RequestInput(interruptId = "ask_1", message = "need input")),
      )
    val reply = CaptureInput("reply")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the dispatched child completed with the answer, passed on to the successor.
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to mapOf("v" to 1L)), replyEvent.output)
  }

  @Test
  fun resume_dynamicChildFinishedEarlier_isReplayedNotRerun() {
    // Arrange: the dispatcher's first child finishes, then its second child asks for input.
    val first = RunCountingEmitter("first", "a_out")
    val dispatcher =
      DispatchPair(
        "dispatch",
        first,
        Ask("second", RequestInput(interruptId = "ask_1", message = "need input")),
      )
    val reply = CaptureInput("reply")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the rerun dispatcher gets the first child's recorded output without running it.
    assertEquals(1, first.runs)
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to listOf("a_out", mapOf("v" to 1L))), replyEvent.output)
  }

  @Test
  fun resume_delegatedChildCompletedByTheAnswer_becomesTheCallersOutput() {
    // Arrange: the caller delegates its output to a child that asks for input and does not rerun.
    val caller =
      DelegateToChild(
        "caller",
        Ask("child", RequestInput(interruptId = "ask_1", message = "need input")),
      )
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller), Edge(caller, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the answer is the caller's output, and the caller does not fail re-emitting it.
    assertTrue(events.none { it.errorCode != null }, "expected no failed node")
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to mapOf("v" to 1L)), replyEvent.output)
  }

  @Test
  fun resume_interruptRaisedByADescendant_reachesTheNodesResumeInputs() {
    // Arrange: the interrupt comes from a child the node dispatched, not from the node itself.
    val node =
      ReportsResumeInputs(
        "node",
        Ask("child", RequestInput(interruptId = "ask_1", message = "need input")),
      )
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, node), Edge(node, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(
      mapOf("received" to mapOf("resumed" to mapOf("ask_1" to mapOf("v" to 1L)))),
      replyEvent.output,
    )
  }

  @Test
  fun resume_concurrentDynamicChildren_replaysFinishedOnesAndCompletesTheAnsweredOne() {
    // Arrange: item 2 asks for input after items 1 and 3 finish, which suspends the caller.
    val items = Emitter("items", listOf(1L, 2L, 3L))
    val worker = AsksOnSecondItem("worker")
    val fanOut = DispatchEachConcurrently("fan", worker)
    val reply = CaptureInput("reply")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, items), Edge(items, fanOut), Edge(fanOut, reply)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_2", mapOf("result" to "two")))

    // Assert: no item body runs twice, and the answer stands in for item 2's output.
    assertEquals(mapOf<Any?, Int>(1L to 1, 2L to 1, 3L to 1), worker.runsByItem)
    val replyEvent = events.single { it.nodeInfo?.path == "wf@1/reply@1" }
    assertEquals(mapOf("received" to listOf("done-1", "two", "done-3")), replyEvent.output)
  }

  @Test
  fun resume_parallelNodeItemAsked_replaysFinishedItemsAndCompletesTheList() {
    // Arrange: item 2 asks for input while items 1 and 3 finish.
    val worker = AsksOnItems("worker", asking = setOf(2L))
    val session = WorkflowSession(fanOutWorkflow(parallelNode(worker)))
    session.advance(userMessage("go"))
    val runsBeforeAnswer = worker.runsByItem.toMap()

    // Act
    val events = session.turn(answer("ask_2", mapOf("result" to "two")))

    // Assert: items 1 and 3 finished in the first turn and replay, and the answer stands in for
    // item 2's output.
    assertEquals(mapOf<Any?, Int>(1L to 1, 2L to 1, 3L to 1), runsBeforeAnswer)
    assertEquals(mapOf<Any?, Int>(1L to 1, 2L to 1, 3L to 1), worker.runsByItem)
    assertEquals(listOf("done-1", "two", "done-3"), events.replyInput())
  }

  @Test
  fun resume_parallelNodeUnderCap_runsQueuedItemsAfterTheAnswer() {
    // Arrange: with one slot, item 1 asks before items 2 and 3 start.
    val worker = AsksOnItems("worker", asking = setOf(1L))
    val session = WorkflowSession(fanOutWorkflow(parallelNode(worker, maxParallelWorkers = 1)))
    session.advance(userMessage("go"))
    val runsBeforeAnswer = worker.runsByItem.toMap()

    // Act
    val events = session.turn(answer("ask_1", mapOf("result" to "one")))

    // Assert: the queued items ran only once the answer arrived.
    assertEquals(mapOf<Any?, Int>(1L to 1), runsBeforeAnswer)
    assertEquals(mapOf<Any?, Int>(1L to 1, 2L to 1, 3L to 1), worker.runsByItem)
    assertEquals(listOf("one", "done-2", "done-3"), events.replyInput())
  }

  @Test
  fun resume_parallelNodeTwoItemsAnsweredOnSeparateTurns_completesTheList() {
    // Arrange: items 1 and 3 ask for input in the same turn.
    val worker = AsksOnItems("worker", asking = setOf(1L, 3L))
    val session = WorkflowSession(fanOutWorkflow(parallelNode(worker)))
    session.advance(userMessage("go"))

    // Act
    val firstAnswerEvents = session.turn(answer("ask_1", mapOf("result" to "one")))
    val secondAnswerEvents = session.turn(answer("ask_3", mapOf("result" to "three")))

    // Assert: the node keeps waiting until both answers are in, and no item body runs twice.
    assertTrue(firstAnswerEvents.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(listOf("one", "done-2", "three"), secondAnswerEvents.replyInput())
    assertEquals(mapOf<Any?, Int>(1L to 1, 2L to 1, 3L to 1), worker.runsByItem)
  }

  @Test
  fun retry_workflowRetriedWithinATurn_replaysChildrenThatFinishedBeforeTheFailure() {
    // Arrange: `first` finishes, then `flaky` fails once, which retries the sub-workflow.
    val first = RunCountingEmitter("first", "first_out")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, first), Edge(first, FailsFirstRun("flaky"))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the retry replays `first` from the session and reruns only the node that failed.
    assertEquals(1, first.runs)
    assertTrue(events.any { it.output == "recovered" }, "expected the retried workflow to finish")
  }

  @Test
  fun retry_workflowRetriedWithinATurn_replaysADynamicChildOfTheFailedNode() {
    // Arrange: `d` dispatches `child` and fails once; `d` has no retry of its own, so `sub`
    // retries.
    val child = RunCountingEmitter("child", "child_out")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, DispatchThenFailFirstRun("d", child))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the rerun of `d` finds `child`'s output in the session and replays it.
    assertEquals(1, child.runs)
    assertTrue(events.any { it.nodeInfo?.path == "wf@1/sub@1/d@1" && it.output == "child_out" })
  }

  @Test
  fun retry_nodeThatAskedBeforeANestedRetry_keepsItsFirstRequest() {
    // Arrange: `ask` asks, then `flaky` fails, which retries `sub`.
    val ask = AsksUnderFreshId("ask")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, ask), Edge(Start, FailsFirstRun("flaky"))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val session = WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, sub))))

    // Act
    val events = session.turn(userMessage("go"))

    // Assert: the retry replays `ask` as waiting, so the turn carries a single request.
    val requests = events.filter { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals(1, ask.runs)
    assertEquals(1, requests.size)
  }

  @Test
  fun retry_siblingCancelledMidRun_rerunsWhileAFinishedOneReplays() {
    // Arrange: `done` finishes, `stalled` is still running when `flaky` fails and cancels it.
    val done = RunCountingEmitter("done", "done_out")
    val stalled = StallsOnFirstRun("stalled")
    val flaky = FailsFirstRun("flaky")
    val join = JoinNode("j")
    val sub =
      Workflow(
        name = "sub",
        edges =
          listOf(
            Edge(Start, done),
            Edge(Start, stalled),
            Edge(Start, flaky),
            Edge(done, join),
            Edge(stalled, join),
            Edge(flaky, join),
          ),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: only the finished sibling replays; the cancelled one left nothing and runs again.
    assertEquals(1, done.runs)
    assertEquals(2, stalled.runs)
    val joined = events.single { it.nodeInfo?.path == "wf@1/sub@1/j@1" }.output
    assertEquals(
      mapOf("done" to "done_out", "stalled" to "stalled done", "flaky" to "recovered"),
      joined,
    )
  }

  @Test
  fun retry_retriedNode_replaysItsNamedChildAndRerunsAnAnonymousOne() {
    // Arrange: the dispatcher's first attempt runs both children, then fails.
    val named = RunCountingEmitter("named", "named_out")
    val anonymous = RunCountingEmitter("anonymous", "anonymous_out")
    val dispatcher = DispatchThenFailOnce("dispatch", named, anonymous)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the named child keeps its path and replays; the anonymous one gets a fresh id and
    // runs again.
    assertEquals(1, named.runs)
    assertEquals(2, anonymous.runs)
    assertTrue(events.any { it.nodeInfo?.path == "wf@1/dispatch@1/anonymous@2" })
    val dispatchOutput = events.single {
      it.nodeInfo?.path == "wf@1/dispatch@1" && it.output != null
    }
    assertEquals(listOf("named_out", "anonymous_out"), dispatchOutput.output)
  }

  @Test
  fun retry_retriedNode_replaysANamedChildThatFinishedWithoutOutput() {
    // Arrange: the dispatcher's first attempt runs both children, then fails; `named` emits
    // nothing.
    val named = RunCountingEmitter("named", null)
    val anonymous = RunCountingEmitter("anonymous", "anonymous_out")
    val dispatcher = DispatchThenFailOnce("dispatch", named, anonymous)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    WorkflowSession(workflow).advance(userMessage("go"))

    // Assert
    assertEquals(1, named.runs)
  }

  @Test
  fun runNode_sameExplicitRunIdAfterAnInterrupt_replaysTheWaitingChild() {
    // Arrange: the first call's interrupt is caught, and the node calls the same child again.
    val ask = YieldsThenAsks("ask", "ask_1")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, RedispatchAfterInterrupt("caller", ask))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the second call replays the waiting child instead of asking again.
    assertEquals(1, ask.runs)
    val requests = events.filter { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals(1, requests.size)
  }

  @Test
  fun runNode_sameExplicitRunIdAfterAnInterruptOnAWaitForOutputChild_replaysTheWaitingChild() {
    // Arrange: `ask` waits for output, so only its interrupt keeps its run for the second call.
    val ask = YieldsThenAsks("ask", "ask_1", waitForOutput = true)
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, RedispatchAfterInterrupt("caller", ask))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the second call replays the waiting child instead of asking again.
    assertEquals(1, ask.runs)
    val requests = events.filter { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals(1, requests.size)
  }

  @Test
  fun retry_retriedNode_replaysAChildUnderANumericRunIdThatLeftNoOutput() {
    // Arrange: `item` emits nothing, so the session events cannot show that it finished.
    val item = RunCountingEmitter("item", null)
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, DispatchNumberedThenFailOnce("caller", item))),
      )

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the retry replays `item` from the first attempt instead of running it again.
    assertEquals(1, item.runs)
    assertEquals(
      "done",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_firstCallOfASharedRunCancelled_waitingCallRunsTheChild() {
    // Arrange: the first call times out while `stalled` runs; the second call waits on that run.
    val stalled = StallsOnFirstRun("stalled")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, AbandonsASharedRun("caller", stalled))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the waiting call is still active, so it runs `stalled` itself.
    assertEquals(2, stalled.runs)
    assertEquals(
      listOf("timed out", "stalled done"),
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_firstCallOfASharedRunSwallowedItsCancellation_waitingCallRunsTheChild() {
    // Arrange: `swallower` returns without output when the first call times out.
    val swallower = SwallowsCancellationOnFirstRun("swallower")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, AbandonsASharedRun("caller", swallower))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the cut-short run is not shared, so the waiting call runs `swallower` itself.
    assertEquals(2, swallower.runs)
    assertEquals(
      listOf("timed out", "swallower done"),
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_nodeThatFailedAfterAProgressEvent_runsAgain() {
    // Arrange: `flaky` reports progress before it fails, which retries `sub`.
    val flaky = ReportsProgressThenFailsFirstRun("flaky", "flaky done")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, flaky)),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: its error event marks the attempt unfinished, so the retry runs `flaky` again.
    assertEquals(2, flaky.runs)
    assertEquals(
      "flaky done",
      events.single { it.nodeInfo?.path == "wf@1/sub@1/flaky@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_parallelSiblingsThatReportedProgress_bothRunAgain() {
    // Arrange: `progress` reports progress and stalls; `flaky` reports progress, then fails.
    val progress = ReportsProgressThenStallsOnFirstRun("progress")
    val flaky = ReportsProgressThenFailsFirstRun("flaky", value = null)
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, progress), Edge(Start, flaky)),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: neither attempt finished, so the retry runs both again.
    assertEquals(2, progress.runs)
    assertEquals(2, flaky.runs)
    assertEquals(
      "progress done",
      events.single { it.nodeInfo?.path == "wf@1/sub@1/progress@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_siblingThatSwallowedTheCancellationAfterAProgressEvent_runsAgain() {
    // Arrange: `progress` reports progress, then catches the cancellation `flaky`'s failure causes.
    val progress = ReportsProgressThenSwallowsCancellationOnFirstRun("progress")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, progress), Edge(Start, YieldsThenFailsFirstRun("flaky"))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: returning after the cancellation does not count as finishing.
    assertEquals(2, progress.runs)
    assertEquals(
      "progress done",
      events.single { it.nodeInfo?.path == "wf@1/sub@1/progress@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_nodeCancelledThenFinishedOnALaterAttempt_replaysOnTheNextRetry() {
    // Arrange: `flaky` fails twice; `quiet` is cancelled on attempt 1 and finishes on attempt 2.
    val quiet = StallsOnceThenFinishesWithoutOutput("quiet")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, quiet), Edge(Start, FailsTwiceSecondAfterADelay("flaky"))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 3, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    WorkflowSession(workflow).advance(userMessage("go"))

    // Assert: attempt 2 finished `quiet`, so attempt 3 replays it instead of running it again.
    assertEquals(2, quiet.runs)
  }

  @Test
  fun runNode_waitingCallCancelledWithTheFirst_doesNotRunTheChild() {
    // Arrange: one timeout cancels both calls while `stalled` runs.
    val stalled = StallsOnFirstRun("stalled")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, CancelsBothCallsOfASharedRun("caller", stalled))),
      )

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the cancelled waiting call stops instead of starting `stalled` again.
    assertEquals(1, stalled.runs)
    assertEquals(
      "timed out",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_siblingCancelledAfterAProgressEvent_runsAgain() {
    // Arrange: `progress` reports progress and stalls; `flaky` fails once, which retries `sub`.
    val progress = ReportsProgressThenStallsOnFirstRun("progress")
    val flaky = YieldsThenFailsFirstRun("flaky")
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, progress), Edge(Start, flaky)),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the cancelled run left no result, so the retry runs `progress` again.
    assertEquals(2, progress.runs)
    assertEquals(
      "progress done",
      events.single { it.nodeInfo?.path == "wf@1/sub@1/progress@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_retriedNode_replaysASubWorkflowChildThatFinishedWithoutOutput() {
    // Arrange: `sync` ends with a state-only node; `silent` inside it leaves no event.
    val silent = RunCountingEmitter("silent", null)
    val sync =
      Workflow(
        name = "sync",
        edges = listOf(Edge(Start, silent), Edge(silent, WritesStateOnly("writer"))),
      )
    val dispatcher = DispatchThenFailOnce("dispatch", sync, RunCountingEmitter("other", "x"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    WorkflowSession(workflow).advance(userMessage("go"))

    // Assert: the retry replays `sync` whole, so `silent` does not run again.
    assertEquals(1, silent.runs)
  }

  @Test
  fun runNode_sameExplicitRunIdConcurrently_runsTheChildOnceForBoth() {
    // Arrange: both calls start before `fetch` finishes.
    val fetch = YieldsThenEmits("fetch", "fetched")
    val caller = DispatchSameRunIdConcurrently("caller", fetch, runId = "f")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the second call waits for the first and gets the same result.
    assertEquals(1, fetch.runs)
    val output = events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output
    assertEquals(listOf("fetched", "fetched"), output)
  }

  @Test
  fun retry_parallelNodeRetriedByItsWorkflow_replaysFinishedItemsAndRerunsTheFailedOne() {
    // Arrange: items run one at a time; item 2 fails once, which fails `items` and retries `sub`.
    val runs = mutableMapOf<Int, Int>()
    val items =
      parallelNode<Int, Int>("items", maxParallelWorkers = 1) { _, item ->
        runs[item] = (runs[item] ?: 0) + 1
        if (item == 2 && runs[item] == 1) throw NodeExecutionException("RuntimeError", "first run")
        item * 10
      }
    val input = Emitter("input", listOf(1, 2, 3))
    val sub =
      Workflow(
        name = "sub",
        edges = listOf(Edge(Start, input), Edge(input, items)),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, sub)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: item 1 replays, item 2 runs again, and item 3 runs for the first time.
    assertEquals(mapOf(1 to 1, 2 to 2, 3 to 1), runs)
    assertEquals(
      listOf(10L, 20L, 30L),
      events.single { it.nodeInfo?.path == "wf@1/sub@1/items@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_concurrentCallDelegatingASharedChild_emitsTheOutputForTheCaller() {
    // Arrange: the first call runs `fetch` without delegating; the second delegates to it.
    val fetch = YieldsThenEmits("fetch", "fetched")
    val caller = DispatchThenDelegateConcurrently("caller", fetch)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: no event carried the output for `caller`, so `caller` emits it itself.
    assertEquals(1, fetch.runs)
    assertEquals(
      "fetched",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_sameExplicitRunIdAfterAFailure_runsTheChildAgain() {
    // Arrange: `flaky` fails on its first run, and the caller catches that and calls it again.
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, RetriesAFailedChild("caller", FailsFirstRun("flaky")))),
      )

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: a failed child is not cached, so the second call runs it and gets its output.
    assertEquals(
      "recovered",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_sameExplicitRunIdAfterAWaitForOutputChildLeftNoOutput_runsTheChildAgain() {
    // Arrange: `wait` produces nothing on its first run.
    val wait = EmitsFromItsSecondRun("wait", "ready")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, CallsTwice("caller", wait))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the run that left no output is not cached, so the second call runs `wait` again.
    assertEquals(2, wait.runs)
    assertEquals(
      listOf(null, "ready"),
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_childSwallowedTheAttemptCancellation_runsTheChildAgain() {
    // Arrange: `swallower` catches the timeout's cancellation and returns without output.
    val swallower = SwallowsCancellationOnFirstRun("swallower")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, DispatchWithTimeout("caller", swallower))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the run ended by the timeout is not cached, so the retry runs `swallower` again.
    assertEquals(2, swallower.runs)
    assertEquals(
      "swallower done",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_sameExplicitRunIdConcurrentlyOnAFailingChild_failsBothCalls() {
    // Arrange: both calls start before `broken` fails.
    val broken = YieldsThenFails("broken")
    val caller = DispatchSameRunIdConcurrentlyCatching("caller", broken)
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the waiting call gets the shared failure instead of an empty success.
    assertEquals(1, broken.runs)
    assertEquals(
      listOf(true, true),
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun retry_attemptTimedOutWhileItsChildRan_runsTheChildAgain() {
    // Arrange: `stalled` never finishes its first run, so the caller's first attempt times out.
    val stalled = StallsOnFirstRun("stalled")
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, DispatchWithTimeout("caller", stalled))))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the cancelled run is not cached, so the retry runs `stalled` again.
    assertEquals(2, stalled.runs)
    assertEquals(
      "stalled done",
      events.single { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null }.output,
    )
  }

  @Test
  fun runNode_sameExplicitRunIdConcurrentlyOnAnAskingChild_asksOnce() {
    // Arrange: both calls start before `ask` raises its request.
    val ask = YieldsThenAsks("ask", "ask_1")
    val caller = DispatchSameRunIdConcurrently("caller", ask, runId = "f")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller)))

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: the waiting call shares the interrupt instead of asking again.
    assertEquals(1, ask.runs)
    val requests = events.filter { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals(1, requests.size)
  }

  @Test
  @OptIn(ExperimentalAtomicApi::class)
  fun runNode_sameExplicitRunIdFromManyThreads_runsTheChildOnce() {
    repeat(50) {
      // Arrange: eight threads race to run `fetch`, which finishes without suspending.
      val fetch = AtomicCountingEmitter("fetch", "fetched")
      val caller = DispatchSameRunIdInParallel("caller", fetch, runId = "f", calls = 8)
      val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, caller)))

      // Act
      WorkflowSession(workflow).advance(userMessage("go"))

      // Assert: the calls that lose the race share the first run instead of rerunning `fetch`.
      assertEquals(1, fetch.runs.load())
    }
  }

  @Test
  fun runNode_sameExplicitRunIdTwiceInOneActivation_replaysFirstResult() {
    // Arrange: the dispatcher calls `runNode(child, runId = "stable-id")` twice in one activation,
    // both for a value-emitting child and for a `rerunOnResume` child that emits no output.
    val child = RunCountingEmitter("child", "child_out")
    val noOutput = RunCountingEmitter("no_out", null, rerunOnResume = true)
    val dispatcher = DispatchSameRunIdTwice("dispatch", child, runId = "stable-id")
    val noOutDispatcher = DispatchSameRunIdTwice("dispatch_no_out", noOutput, runId = "stable-id")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, dispatcher), Edge(dispatcher, noOutDispatcher)),
      )

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: each second call replays the completed child instead of running it a second time.
    assertEquals(1, child.runs)
    assertEquals(1, noOutput.runs)
    val dispatchOutput = events.single {
      it.nodeInfo?.path == "wf@1/dispatch@1" && it.output != null
    }
    assertEquals(listOf("child_out", "child_out"), dispatchOutput.output)
  }

  @Test
  fun retry_nodesThatSucceedOnAttemptTwo_replayCleanlyOnResume() {
    // Arrange: on turn 1, `caller` delegates to `child@c` and fails on attempt 1 after `child@c`
    // emits, then succeeds on attempt 2 by replaying `child@c`; `writer` emits a premature output
    // and fails on attempt 1, then succeeds on attempt 2 by writing only state; `ask` suspends.
    val child = RunCountingEmitter("child", "delegated_out")
    val caller = DelegateThenFailOnce("caller", child)
    val writer = EmitThenFailOnceThenWriteState("writer", "k")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(
            Edge(Start, caller),
            Edge(Start, writer),
            Edge(Start, ask),
            Edge(caller, join),
            Edge(writer, join),
            Edge(ask, join),
          ),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: neither node reruns on turn 2, `caller` emits its replayed output once, and
    // `writer`'s attempt-1 output was dropped when that attempt failed.
    assertEquals(1, child.runs)
    assertEquals(2, caller.runs)
    assertEquals(1, events.count { it.nodeInfo?.path == "wf@1/caller@1" && it.output != null })
    assertEquals(2, writer.runs)
    val joinEvent = events.single { it.nodeInfo?.path == "wf@1/j@1" }
    assertEquals(
      mapOf("caller" to "delegated_out", "writer" to null, "ask" to mapOf("v" to 1L)),
      joinEvent.output,
    )
  }

  @Test
  fun resume_fannedOutNodeReplayed_preservesItsSubBranchOnReemittedEvent() {
    // Arrange: START fans out to `done` and `ask`, so `done` runs on sub-branch `done@1`.
    val done = RunCountingEmitter("done", "done_out")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, done), Edge(Start, ask), Edge(done, join), Edge(ask, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the re-emitted event for `done` keeps its `done@1` sub-branch.
    assertEquals(1, done.runs)
    val reemitted = events.single { it.nodeInfo?.path == "wf@1/done@1" }
    assertEquals("done@1", reemitted.branch)
  }

  @Test
  fun runId_nodeReenteredAfterResume_takesTheNextRunId() {
    // Arrange: `ask` suspends the first turn; once it is answered, `again` loops back to it.
    val ask = AsksEveryRun("ask")
    val again = LoopsBackOnce("again")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ask), Edge(ask, again), Edge(again, ask, listOf(yes()))),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the resumed run keeps @1 and the re-entry asks afresh as @2. A re-entry reusing @1
    // would replay the answered run instead, surfacing ask@1 twice.
    assertEquals(mapOf("v" to 1L), events.single { it.nodeInfo?.path == "wf@1/ask@1" }.output)
    val request = events.single { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals("wf@1/ask@2", request.nodeInfo?.path)
    assertEquals("ask_2", request.functionCalls().single().id)
  }

  @Test
  fun runId_childDispatchedAgainAfterResume_takesTheNextRunId() {
    // Arrange: the first of two anonymous dispatches of `ask` suspends the first turn.
    val workflow =
      Workflow(name = "wf", edges = listOf(Edge(Start, DispatchTwice("d", AsksEveryRun("ask")))))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the second dispatch asks afresh as @2, which it reaches only once the resumed @1
    // completed with its answer. A replayed dynamic child emits no event, so @1 shows only here.
    val request = events.single { e ->
      e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME }
    }
    assertEquals("wf@1/d@1/ask@2", request.nodeInfo?.path)
    assertEquals("ask_2", request.functionCalls().single().id)
  }

  @Test
  fun answersFor_interruptRaisedBelowTheNode_isIncluded() {
    // Arrange: a grandchild of `d` raised the interrupt.
    val events =
      listOf(requestEvent("wf@1/d@1/c@c", "ask_1"), answerEvent("ask_1", mapOf("v" to 1L)))

    // Act
    val forNode = ResumeScan.answersFor(events, "wf@1/d@1", "inv")
    val forSibling = ResumeScan.answersFor(events, "wf@1/e@1", "inv")

    // Assert
    assertEquals(mapOf<String, Any?>("ask_1" to mapOf("v" to 1L)), forNode)
    assertEquals(emptyMap(), forSibling)
  }

  @Test
  fun answersFor_answerFromAnotherInvocation_resolvesNothing() {
    // Arrange
    val events =
      listOf(
        requestEvent("wf@1/ask@1", "ask_1"),
        answerEvent("ask_1", mapOf("v" to 1L), invocationId = "other"),
      )

    // Act
    val recovered = ResumeScan.scan(events, "wf@1", "inv").getValue("ask@1")

    // Assert
    assertEquals(setOf("ask_1"), recovered.unresolved)
  }

  @Test
  fun resume_nodeThatAskedAfterAFailedAttempt_completesWithTheAnswer() {
    // Arrange: `ask` fails attempt 1 and asks on attempt 2, which suspends the first turn.
    val ask = FailsOnceThenAsks("ask")
    val reply = CaptureInput("reply")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, ask), Edge(ask, reply)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the interrupt cleared attempt 1's error, so the answer completes `ask` unrun.
    assertEquals(2, ask.runs)
    assertEquals(mapOf("v" to 1L), events.replyInput())
  }

  @Test
  fun resume_rerunOnResumeNodeThatFinishedAfterItsAnswer_isNotRunAgain() {
    // Arrange: both nodes ask on turn 1; turn 2 answers `writer`, which reruns and writes state.
    val writer = AsksThenWritesState("writer", "ask_w")
    val other = Ask("other", RequestInput(interruptId = "ask_o", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges =
          listOf(Edge(Start, writer), Edge(Start, other), Edge(writer, join), Edge(other, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))
    session.advance(answer("ask_w", mapOf("v" to 1L)))

    // Act
    val events = session.turn(answer("ask_o", mapOf("v" to 2L)))

    // Assert: `writer` finished after its answer, so turn 3 does not repeat its state write.
    assertEquals(2, writer.runs)
    assertTrue(events.any { it.nodeInfo?.path == "wf@1/j@1" }, "expected the join to run")
  }

  @Test
  fun resume_rerunOnResumeNodeThatFinishedWithoutOutput_isNotRunAgain() {
    // Arrange: `writer` finishes on turn 1 with only a state write while `ask` suspends.
    val writer = WritesStateOnly("writer")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, writer), Edge(Start, ask)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    session.advance(answer("ask_1", mapOf("v" to 1L)))

    // Assert
    assertEquals(1, writer.runs)
  }

  @Test
  fun scan_outputOnAnEventWithAnErrorCode_replaysTheOutput() {
    // Arrange: an agent-style output rides on an event with a non-STOP error code.
    val events =
      listOf(
        Event(
          author = "wf",
          invocationId = "inv",
          output = "cut short",
          errorCode = "MAX_TOKENS",
          nodeInfo = NodeInfo(path = "wf@1/chat@1"),
        )
      )

    // Act
    val recovered = ResumeScan.scan(events, "wf@1", "inv").getValue("chat@1")
    val interception = ResumeScan.intercept(Emitter("chat", "fresh"), recovered)

    // Assert
    assertNull(recovered.errorCode)
    assertFalse(interception.shouldRun)
    assertEquals("cut short", interception.output)
  }

  @Test
  fun scan_partialEvent_isIgnored() {
    // Arrange: a partial streaming chunk arrives after an error event.
    val events =
      listOf(
        Event(
          author = "wf",
          invocationId = "inv",
          errorCode = "RuntimeError",
          nodeInfo = NodeInfo(path = "wf@1/chat@1"),
        ),
        Event(
          author = "wf",
          invocationId = "inv",
          partial = true,
          content = Content(role = Role.MODEL, parts = listOf(Part(text = "partial"))),
          nodeInfo = NodeInfo(path = "wf@1/chat@1", messageAsOutput = true),
        ),
      )

    // Act
    val recovered = ResumeScan.scan(events, "wf@1", "inv").getValue("chat@1")

    // Assert: the partial chunk neither records output nor clears the error.
    assertNull(recovered.output)
    assertEquals("RuntimeError", recovered.errorCode)
    assertFalse(recovered.finishedAfterResume)
  }

  @Test
  fun intercept_finishedRerunNodeThatWaitsForOutput_stillReruns() {
    // Arrange
    val node =
      object : Node {
        override val name = "n"
        override val rerunOnResume = true
        override val waitForOutput = true

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {}
      }
    val answered =
      RecoveredNode().apply {
        interruptIds += "a"
        resolvedResponses["a"] = 1L
        finishedAfterResume = true
      }
    val unasked = RecoveredNode().apply { finishedAfterResume = true }

    // Act
    val afterAnswer = ResumeScan.intercept(node, answered)
    val withoutAsking = ResumeScan.intercept(node, unasked)

    // Assert
    assertTrue(afterAnswer.shouldRun)
    assertTrue(withoutAsking.shouldRun)
  }

  @Test
  fun resume_nodeAfterStartThatRunsAgain_getsTheOriginalUserMessage() {
    // Arrange: `reader` asks on turn 1 and runs again with the answer on turn 2.
    val reader = RecordsInputThenAsks("reader", "ask_r")
    val session = WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, reader))))
    session.advance(userMessage("go"))

    // Act
    session.advance(answer("ask_r", mapOf("v" to 1L)))

    // Assert: both runs read the message that started the invocation, not the answer.
    assertEquals(listOf("go", "go"), reader.inputs.map { (it as Content).parts.single().text })
  }

  @Test
  fun resume_standaloneRootNodeAnswered_completesWithTheAnswer() {
    // Arrange: the app's root is a node that asks, not a workflow.
    val session = WorkflowSession(Ask("ask", RequestInput(interruptId = "ask_1", message = "?")))
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the answer completes the node, which does not ask again.
    assertEquals(mapOf("v" to 1L), events.single { it.output != null }.output)
    assertTrue(
      events.none { e -> e.functionCalls().any { it.name == RequestInput.FUNCTION_CALL_NAME } }
    )
  }

  @Test
  fun retry_rootWorkflowRetriedWithinATurn_replaysChildrenThatFinishedBeforeTheFailure() {
    // Arrange: the root workflow retries once `flaky` fails, after `first` finished.
    val first = RunCountingEmitter("first", "first_out")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, first), Edge(first, FailsFirstRun("flaky"))),
        config =
          NodeConfig(
            retryConfig = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
          ),
      )

    // Act
    val events = WorkflowSession(workflow).turn(userMessage("go"))

    // Assert: like a nested workflow, the retry replays `first` from the session.
    assertEquals(1, first.runs)
    assertTrue(events.any { it.output == "recovered" }, "expected the retried workflow to finish")
  }

  @Test
  fun resume_llmAgentNodeAnsweredLongRunningCall_callsTheModelAgain() {
    // Arrange: the model calls a long-running tool on turn 1, which pauses the workflow.
    val requests = mutableListOf<LlmRequest>()
    val agent =
      LlmAgent(
        name = "agent",
        model =
          DummyModel("model") { request ->
            requests += request
            val modelCalls = requests.size
            flowOf(
              if (modelCalls == 1) modelFunctionCallResponse("approve", id = "call_1")
              else LlmResponse(content = modelMessage("approved"))
            )
          },
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val session = WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, agent))))
    session.advance(userMessage("go"))

    // Act
    session.advance(answer("call_1", mapOf("ok" to true), name = "approve"))

    // Assert: the agent runs again, and its second model request carries the tool's answer.
    assertEquals(2, requests.size)
    assertTrue(
      requests[1].contents.any { c -> c.parts.any { it.functionResponse?.id == "call_1" } },
      "expected the answer to call_1 in the second request",
    )
  }

  @Test
  fun scan_responseOwnedByAnotherInterrupt_doesNotResolveByBranchRunId() {
    // Arrange: the answer to `ask_b` arrives on a branch whose run id equals `a`'s interrupt id.
    val events =
      listOf(
        requestEvent("wf@1/a@1", "1"),
        requestEvent("wf@1/b@1", "ask_b"),
        answerEvent("ask_b", mapOf("result" to "ok"), branch = "wf@1.b@1"),
      )

    // Act
    val recovered = ResumeScan.scan(events, "wf@1", "inv")

    // Assert
    assertEquals(emptyMap(), recovered.getValue("a@1").resolvedResponses)
    assertEquals(mapOf<String, Any?>("ask_b" to "ok"), recovered.getValue("b@1").resolvedResponses)
  }

  @Test
  fun scan_responseOnABranchNamedAfterAnInterrupt_resolvesThatInterrupt() {
    // Arrange: the response answers a different call, made on a branch whose run id is `ask_1`.
    val events =
      listOf(
        requestEvent("wf@1/ask@1", "ask_1"),
        answerEvent("call_9", mapOf("result" to "ok"), branch = "wf@1.task@ask_1"),
      )

    // Act
    val recovered = ResumeScan.scan(events, "wf@1", "inv").getValue("ask@1")

    // Assert
    assertEquals(mapOf<String, Any?>("ask_1" to "ok"), recovered.resolvedResponses)
  }

  @Test
  fun intercept_nothingRecorded_rerunsADynamicNodeButSkipsAStaticOne() {
    // Arrange: history holds the node's path but no output, route or interrupt for it.
    val node = Emitter("n", "out")
    val recovered = RecoveredNode()

    // Act
    val asDynamic = ResumeScan.intercept(node, recovered, dynamic = true)
    val asStatic = ResumeScan.intercept(node, recovered)

    // Assert
    assertTrue(asDynamic.shouldRun)
    assertEquals(false, asStatic.shouldRun)
  }

  @Test
  fun runNode_inputTheChildsSchemaRejects_failsTheCallerBeforeTheChildRuns() {
    // Arrange: `count` is not an integer.
    val dispatcher =
      DispatchWithInput("dispatch", CountEcho("child"), input = mapOf("count" to "five"))
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, dispatcher)))

    // Act
    val (events, error) = WorkflowSession(workflow).failingTurn(userMessage("go"))

    // Assert: the caller reports the failure, and the child never ran.
    assertTrue(error is IllegalArgumentException, "expected a validation failure, got $error")
    val failed = events.single { it.errorCode != null }
    assertEquals("wf@1/dispatch@1", failed.nodeInfo?.path)
    assertTrue(events.none { it.nodeInfo?.path?.startsWith("wf@1/dispatch@1/child") == true })
  }

  @Test
  fun runNode_functionNodeChild_checksTheInputThroughItsOwnConversion() {
    // Arrange: `sum` takes a list of integers, and the second item is not one.
    val sum =
      node("sum", inputSchema = Schema(type = Type.ARRAY, items = Schema(type = Type.INTEGER))) {
        _,
        xs: List<Int> ->
        xs.sum()
      }
    val accepted = DispatchWithInput("ok", sum, input = listOf(1, 2))
    val rejected = DispatchWithInput("bad", sum, input = listOf(1, "s3cret"))

    // Act
    val events =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, accepted))))
        .turn(userMessage("go"))
    val (_, error) =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, rejected))))
        .failingTurn(userMessage("go"))

    // Assert: the caller applies the child's own value-free input check.
    assertEquals(3L, events.single { it.nodeInfo?.path == "wf@1/ok@1" && it.output != null }.output)
    assertTrue(error is NodeInputValidationException, "expected the node's own check, got $error")
    assertFalse("s3cret" in error.message.orEmpty())
  }

  @Test
  fun resume_messageOutputNode_isRehydratedToItsText() {
    // Arrange: the message node finishes on the first turn while its sibling asks for input.
    val chat = MessageOutput("chat", "agent said hi")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, chat), Edge(Start, ask), Edge(chat, join), Edge(ask, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert
    val joinEvent = events.single { it.nodeInfo?.path == "wf@1/j@1" }
    assertEquals(mapOf("chat" to "agent said hi", "ask" to mapOf("v" to 1L)), joinEvent.output)
  }

  @Test
  fun resume_messageOutputNodeWithOutputSchema_isRehydratedToTheParsedValue() {
    // Arrange: the message is JSON text that the node's output schema describes.
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("city" to Schema(type = Type.STRING)))
    val chat = MessageOutput("chat", """{"city": "Paris"}""", outputSchema = schema)
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, chat), Edge(Start, ask), Edge(chat, join), Edge(ask, join)),
      )
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the text comes back as the object, not as a string.
    val joinEvent = events.single { it.nodeInfo?.path == "wf@1/j@1" }
    assertEquals(
      mapOf("chat" to mapOf("city" to "Paris"), "ask" to mapOf("v" to 1L)),
      joinEvent.output,
    )
  }

  @Test
  fun resume_nodeThatFailedInAnEarlierTurn_reruns() {
    // Arrange: the sibling fails after the request was raised, which ends the first turn.
    val flaky = FailsFirstRun("flaky")
    val ask = Ask("ask", RequestInput(interruptId = "ask_1", message = "need input"))
    val join = JoinNode("j")
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, ask), Edge(Start, flaky), Edge(ask, join), Edge(flaky, join)),
      )
    val session = WorkflowSession(workflow)
    val (_, firstError) = session.failingTurn(userMessage("go"))

    // Act
    val events = session.turn(answer("ask_1", mapOf("v" to 1L)))

    // Assert: the failure left no result to replay, so the node ran again and succeeded.
    assertTrue(firstError is NodeExecutionException, "expected the first turn to fail")
    val joinEvent = events.single { it.nodeInfo?.path == "wf@1/j@1" }
    assertEquals(mapOf("ask" to mapOf("v" to 1L), "flaky" to "recovered"), joinEvent.output)
  }

  @Test
  fun resume_nodeCompletingATransfer_checkpointsLikeARealRun() {
    // Arrange
    val node = TransferAfterInterrupt("node")
    val workflow = Workflow(name = "wf", edges = listOf(Edge(Start, node)))
    val session = WorkflowSession(workflow)
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer("t_1", mapOf("result" to "ok"), name = "approve"))

    // Assert: the resumed turn announces the node as a running step, as a real run would.
    val running = NodeState(status = NodeStatus.RUNNING).toCheckpoint()
    val checkpoints = events.mapNotNull { it.actions.agentState }
    assertContains(
      checkpoints,
      TypedData.MapValue(mapOf(NodeState.NODES_KEY to TypedData.MapValue(mapOf("node" to running)))),
    )
  }

  @Test
  fun intercept_answeredNodeThatDoesNotRerun_completesWithAllAnswersAsAMap() {
    // Arrange: two answered interrupts and no recorded output.
    val recovered =
      RecoveredNode().apply {
        interruptIds += listOf("a", "b")
        resolvedResponses += mapOf("a" to 1L, "b" to 2L)
      }

    // Act
    val interception = ResumeScan.intercept(Emitter("n", "out"), recovered)

    // Assert
    assertEquals(false, interception.shouldRun)
    assertEquals(mapOf<String, Any?>("a" to 1L, "b" to 2L), interception.output)
  }

  @Test
  fun intercept_unresolvedInterruptWithPartialProgress_rerunsOnlyWhenRerunOnResumeIsTrue() {
    // Arrange: one interrupt is answered while another is still unresolved.
    val partialProgress =
      RecoveredNode().apply {
        interruptIds += listOf("a", "b")
        resolvedResponses["a"] = 1L
      }
    val noProgress = RecoveredNode().apply { interruptIds += listOf("a", "b") }
    val rerunNode = DispatchAsk("rerun", Emitter("c", "out"))
    val staticNode = Emitter("static", "out")

    // Act
    val rerunWithProgress = ResumeScan.intercept(rerunNode, partialProgress)
    val rerunWithoutProgress = ResumeScan.intercept(rerunNode, noProgress)
    val staticWithProgress = ResumeScan.intercept(staticNode, partialProgress)

    // Assert: only a `rerunOnResume` node that has at least one resolved answer runs again; the
    // others stay waiting on the unresolved interrupt ids.
    assertTrue(rerunWithProgress.shouldRun)
    assertEquals(mapOf<String, Any?>("a" to 1L), rerunWithProgress.resumeInputs)
    assertFalse(rerunWithoutProgress.shouldRun)
    assertEquals(setOf("a", "b"), rerunWithoutProgress.interrupts)
    assertFalse(staticWithProgress.shouldRun)
    assertEquals(setOf("b"), staticWithProgress.interrupts)
  }

  @Test
  fun processRehydratedOutput_outputNoLongerFitsTheSchema_resumesUnvalidated() {
    // Arrange: the schema now requires a property the stored JSON lacks.
    val schema =
      Schema(
        type = Type.OBJECT,
        properties = mapOf("city" to Schema(type = Type.STRING)),
        required = listOf("city"),
      )
    val node = MessageOutput("chat", "", outputSchema = schema)
    val stored = Content(role = Role.MODEL, parts = listOf(Part(text = """{"town": "Paris"}""")))

    // Act
    val output = ResumeScan.processRehydratedOutput(node, stored)

    // Assert
    assertEquals(mapOf("town" to "Paris"), output)
  }

  @Test
  fun processRehydratedOutput_nonJsonOutputForObjectSchema_fails() {
    // Arrange
    val schema =
      Schema(type = Type.OBJECT, properties = mapOf("city" to Schema(type = Type.STRING)))
    val node = MessageOutput("chat", "", outputSchema = schema)
    val stored = Content(role = Role.MODEL, parts = listOf(Part(text = "not json")))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> { ResumeScan.processRehydratedOutput(node, stored) }

    // Assert
    assertContains(error.message!!, "Validation failed for rehydrated output against schema")
  }

  @Test
  fun validateAnswer_answerThatFits_isReturned() {
    // Arrange
    val response = mapOf("count" to 1)

    // Act
    val answer = validateAnswer(COUNT_SCHEMA, response, "ask_1")

    // Assert
    assertEquals(mapOf("count" to 1), answer)
  }

  @Test
  fun validateAnswer_answerTheSchemaRejects_failsNamingTheInterrupt() {
    // Arrange
    val badCount = mapOf("result" to """{"count": "1"}""")
    val badBoolean = mapOf("result" to "yes")

    // Act
    val countError =
      assertFailsWith<IllegalArgumentException> { validateAnswer(COUNT_SCHEMA, badCount, "ask_1") }
    val boolError =
      assertFailsWith<IllegalArgumentException> {
        validateAnswer(Schema(type = Type.BOOLEAN), badBoolean, "a")
      }

    // Assert
    assertContains(countError.message!!, "Validation failed for interrupt ask_1: validation error")
    assertContains(boolError.message!!, "Validation failed for interrupt a")
  }

  @Test
  fun validateAnswer_textInResultEnvelope_isReadAsJsonUnlessTheSchemaTakesAString() {
    // Arrange
    val intOrString =
      Schema(anyOf = listOf(Schema(type = Type.INTEGER), Schema(type = Type.STRING)))
    fun answer(schema: Schema?, text: String) = validateAnswer(schema, mapOf("result" to text), "a")

    // Act
    val unschemed = answer(null, """{"a": 1}""")
    val forString = answer(Schema(type = Type.STRING), "42")
    val forUnion = answer(intOrString, "42")
    val forBoolean = answer(Schema(type = Type.BOOLEAN), "true")

    // Assert
    assertEquals(mapOf("a" to 1L), unschemed)
    assertEquals("42", forString)
    assertEquals("42", forUnion)
    assertEquals(true, forBoolean)
  }

  @Test
  fun processRehydratedOutput_blankText_isNull() {
    // Arrange
    val node = MessageOutput("chat", "", outputSchema = COUNT_SCHEMA)
    val stored = Content(role = Role.MODEL, parts = listOf(Part(text = "  ")))

    // Act
    val output = ResumeScan.processRehydratedOutput(node, stored)

    // Assert
    assertNull(output)
  }

  @Test
  fun processRehydratedOutput_stringSchema_keepsTheText() {
    // Arrange
    val node = MessageOutput("chat", "", outputSchema = Schema(type = Type.STRING))
    val stored = Content(role = Role.MODEL, parts = listOf(Part(text = "42")))

    // Act
    val output = ResumeScan.processRehydratedOutput(node, stored)

    // Assert
    assertEquals("42", output)
  }

  @Test
  fun requestInput_toEventWithAPayloadWithNoJsonForm_throws() {
    // Arrange: a raw payload, as a custom node emits it.
    val request = RequestInput(interruptId = "ask_1", payload = Any())

    // Act
    val error = assertFailsWith<IllegalArgumentException> { request.toEvent() }

    // Assert
    assertContains(error.message.orEmpty(), "AnySerializer")
  }

  @Test
  @OptIn(AdkJavaInteropApi::class)
  fun requestInput_toBuilder_roundTripsEveryProperty() {
    // Arrange
    val request =
      RequestInput(
        interruptId = "ask_1",
        message = "age?",
        payload = mapOf("hint" to "years"),
        responseSchema = AGE_SCHEMA,
      )

    // Act
    val rebuilt = request.toBuilder().build()

    // Assert
    assertEquals(request.copy(), rebuilt)
  }

  @Test
  fun processRehydratedOutput_bareWordForObjectSchema_fails() {
    // Arrange: a lone word is not JSON, so it cannot stand in for the object.
    val node = MessageOutput("chat", "", outputSchema = AGE_SCHEMA)
    val stored = Content(role = Role.MODEL, parts = listOf(Part(text = "hello")))

    // Act
    val error =
      assertFailsWith<IllegalArgumentException> { ResumeScan.processRehydratedOutput(node, stored) }

    // Assert
    assertContains(error.message!!, "Validation failed for rehydrated output against schema")
  }

  @Test
  fun requestInput_unitNode_answerBecomesTheOutput() {
    // Arrange
    val ask = node<Any?, Unit>("ask") { _, _ -> requestInput(interruptId = "ask_u") }
    val session = WorkflowSession(Workflow("wf", listOf(Edge(Start, ask))))

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer("ask_u", mapOf("result" to "yes")))

    // Assert: the answer is the output as saved, though the node returns Unit.
    assertEquals("ask_u", asked.flatMap { it.functionCalls() }.single().id)
    assertEquals("yes", answered.single { it.nodeInfo?.path == "wf@1/ask@1" }.output)
  }

  // -- Confirmation --

  @Test
  fun requireConfirmation_unanswered_asksWithTheInputAndSkipsTheBody() {
    // Arrange
    var runs = 0
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { _, _ ->
        runs += 1
        RemovalResult(removed = true)
      }

    // Act
    val events = WorkflowSession(removalWorkflow(remove)).turn(userMessage("go"))

    // Assert
    val call = events.flatMap { it.functionCalls() }.single()
    assertEquals(REMOVAL_CONFIRMATION_ID, call.id)
    assertEquals(
      "Please approve or reject the tool call remove_file().",
      call.args[RequestInput.MESSAGE_KEY],
    )
    assertEquals(
      mapOf("tool_name" to "remove_file", "args" to mapOf("path" to "/tmp/a")),
      call.args[RequestInput.PAYLOAD_KEY],
    )
    assertTrue(call.args[RequestInput.RESPONSE_SCHEMA_KEY] != null)
    assertEquals(0, runs)
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/reply@1" })
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun requireConfirmation_toBuilderCopy_stillAsks() {
    // Arrange
    var runs = 0
    val always =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { _, _ ->
        runs += 1
        RemovalResult(removed = true)
      }
    val decided =
      node<RemovalTarget, RemovalResult>(
        "remove_file",
        requireConfirmation = { _, target -> target.path.startsWith("/tmp") },
      ) { _, _ ->
        runs += 1
        RemovalResult(removed = true)
      }

    // Act
    val alwaysCopy = always.toBuilder().description("copy").build()
    val decidedCopy = decided.toBuilder().description("copy").build()
    val alwaysCalls =
      WorkflowSession(removalWorkflow(alwaysCopy)).turn(userMessage("go")).flatMap {
        it.functionCalls()
      }
    val decidedCalls =
      WorkflowSession(removalWorkflow(decidedCopy)).turn(userMessage("go")).flatMap {
        it.functionCalls()
      }

    // Assert
    assertEquals(listOf(REMOVAL_CONFIRMATION_ID), alwaysCalls.map { it.id })
    assertEquals(listOf(REMOVAL_CONFIRMATION_ID), decidedCalls.map { it.id })
    assertEquals(0, runs)
  }

  @Test
  fun requireConfirmation_confirmed_runsTheBodyOnceWithTheAnswer() {
    // Arrange
    val seen = mutableListOf<ToolConfirmation?>()
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { ctx, _ ->
        seen += ctx.toolConfirmation
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val events =
      session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("confirmed" to true, "hint" to "ok")))

    // Assert
    assertEquals(mapOf("removed" to true), events.replyInput())
    assertEquals(listOf<ToolConfirmation?>(ToolConfirmation(confirmed = true, hint = "ok")), seen)
  }

  @Test
  fun requireConfirmation_rejected_outputsTheRejectionWithoutRunningTheBody() {
    // Arrange
    var runs = 0
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { _, _ ->
        runs += 1
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("confirmed" to false)))

    // Assert
    assertEquals(mapOf("error" to "This tool call is rejected."), events.replyInput())
    assertEquals(0, runs)
  }

  @Test
  fun requireConfirmation_confirmedNodeWithNoOutput_isNotRerunWhenALaterNodeResumes() {
    // Arrange
    var runs = 0
    val remove = node<Any?, Unit>("remove_file", requireConfirmation = true) { _, _ -> runs += 1 }
    val review = Ask("review", RequestInput(interruptId = "review", message = "Review?"))
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, remove), Edge(remove, review)))
      )
    session.advance(userMessage("go"))
    session.advance(answer("wf_tool_confirmation:wf@1/remove_file@1", mapOf("confirmed" to true)))

    // Act
    session.advance(answer("review", mapOf("ok" to true)))

    // Assert
    assertEquals(1, runs)
  }

  @Test
  fun requireConfirmation_confirmedNodeThatDelegatesItsOutput_writesNoEmptyEventAndIsNotRerun() {
    // Arrange
    var runs = 0
    val child = node<Any?, String>("child") { _, _ -> "child_done" }
    val parent =
      node<Any?, String?>("parent", requireConfirmation = true) { ctx, _ ->
        runs += 1
        val unused = ctx.runNode(child, useAsOutput = true)
        null
      }
    val review = Ask("review", RequestInput(interruptId = "review", message = "Review?"))
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, parent), Edge(parent, review)))
      )
    session.advance(userMessage("go"))
    val confirmed =
      session.turn(answer("wf_tool_confirmation:wf@1/parent@1", mapOf("confirmed" to true)))

    // Act
    session.advance(answer("review", mapOf("ok" to true)))

    // Assert
    val parentEvents = confirmed.filter { it.nodeInfo?.path?.startsWith("wf@1/parent@1") == true }
    assertEquals(
      listOf<Pair<String?, Any?>>("wf@1/parent@1/child@1" to "child_done"),
      parentEvents.map { it.nodeInfo?.path to it.output },
    )
    assertEquals(1, runs)
  }

  @Test
  fun requireConfirmation_parallelNode_asksForEachItemAndRunsOnlyTheConfirmedOnes() {
    // Arrange
    val runs = mutableListOf<Long>()
    val worker =
      parallelNode<Long, String>("worker", requireConfirmation = true) { _, item ->
        runs += item
        "done-$item"
      }
    val session = WorkflowSession(fanOutWorkflow(worker))

    // Act
    val asked = session.turn(userMessage("go"))
    val ids = asked.flatMap { it.functionCalls() }.mapNotNull { it.id }.sorted()
    check(ids.size == 3) { "expected one request per item" }
    session.advance(answer(ids[0], mapOf("confirmed" to true)))
    session.advance(answer(ids[1], mapOf("confirmed" to false)))
    val answered = session.turn(answer(ids[2], mapOf("confirmed" to true)))

    // Assert
    assertTrue(ids.all { it.startsWith("wf_tool_confirmation:wf@1/worker@1/") })
    assertEquals(
      listOf("done-1", mapOf("error" to "This tool call is rejected."), "done-3"),
      answered.replyInput(),
    )
    assertEquals(listOf(1L, 3L), runs)
  }

  @Test
  fun requireConfirmation_nullAnswer_asksAgain() {
    // Arrange
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { _, _ ->
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("result" to null)))

    // Assert
    assertEquals(
      listOf(REMOVAL_CONFIRMATION_ID),
      events.flatMap { it.functionCalls() }.mapNotNull { it.id },
    )
    assertTrue(events.none { it.nodeInfo?.path == "wf@1/reply@1" })
  }

  @Test
  fun requireConfirmation_dispatchedChild_parentReusesItsApprovalAndTheChildAsksSeparately() {
    // Arrange
    val calls = mutableListOf<String>()
    val child =
      node<Any?, String>("child", requireConfirmation = true) { _, _ ->
        calls += "child"
        "child_done"
      }
    val parent =
      node<Any?, Any?>("parent", requireConfirmation = true) { ctx, _ ->
        calls += "parent"
        ctx.runNode(child)
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, parent), Edge(parent, reply)))
      )
    session.advance(userMessage("go"))
    val childAsked =
      session.turn(answer("wf_tool_confirmation:wf@1/parent@1", mapOf("confirmed" to true)))
    val childId = childAsked.flatMap { it.functionCalls() }.mapNotNull { it.id }.single()

    // Act
    val answered = session.turn(answer(childId, mapOf("confirmed" to true)))

    // Assert
    assertEquals("wf_tool_confirmation:wf@1/parent@1/child@1", childId)
    assertEquals(listOf("parent", "parent", "child"), calls)
    assertEquals("child_done", answered.replyInput())
  }

  @Test
  fun requireConfirmation_dispatchedChildRejected_parentOutputsTheRejection() {
    // Arrange
    val calls = mutableListOf<String>()
    val child =
      node<Any?, String>("child", requireConfirmation = true) { _, _ ->
        calls += "child"
        "child_done"
      }
    val parent =
      node<Any?, Any?>("parent", requireConfirmation = true) { ctx, _ ->
        calls += "parent"
        ctx.runNode(child)
      }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, parent), Edge(parent, reply)))
      )
    session.advance(userMessage("go"))
    session.advance(answer("wf_tool_confirmation:wf@1/parent@1", mapOf("confirmed" to true)))

    // Act
    val answered =
      session.turn(
        answer("wf_tool_confirmation:wf@1/parent@1/child@1", mapOf("confirmed" to false))
      )

    // Assert
    assertEquals(listOf("parent", "parent"), calls)
    assertEquals(mapOf("error" to "This tool call is rejected."), answered.replyInput())
  }

  @Test
  fun requireConfirmation_rejectedNode_isReplayedNotRerunWhenASiblingResumes() {
    // Arrange
    var runs = 0
    val received = mutableListOf<Any?>()
    val askA = Ask("node_a", RequestInput(interruptId = "ask_a", message = "Input for A?"))
    val nodeB =
      node<Any?, String>("node_b", requireConfirmation = true) { _, _ ->
        runs += 1
        "result_b"
      }
    val downstreamB =
      node<Any?, String>("downstream_b") { _, input ->
        received += input
        "after_b"
      }
    val join = JoinNode("node_join")
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(
        Workflow(
          name = "wf",
          edges =
            listOf(
              Edge(Start, askA),
              Edge(Start, nodeB),
              Edge(nodeB, downstreamB),
              Edge(askA, join),
              Edge(downstreamB, join),
              Edge(join, reply),
            ),
        )
      )
    session.advance(userMessage("go"))
    val rejected =
      session.turn(answer("wf_tool_confirmation:wf@1/node_b@1", mapOf("confirmed" to false)))

    // Act
    val resumed = session.turn(answer("ask_a", mapOf("result" to "a_done")))

    // Assert
    assertEquals(0, runs)
    assertEquals(listOf<Any?>(mapOf("error" to "This tool call is rejected.")), received)
    assertTrue(rejected.none { it.nodeInfo?.path == "wf@1/reply@1" })
    assertEquals(mapOf("node_a" to "a_done", "downstream_b" to "after_b"), resumed.replyInput())
  }

  @Test
  fun requireConfirmationPredicate_typedInputMatches_asksOnceAndRunsTheBodyAfterApproval() {
    // Arrange
    val checked = mutableListOf<String>()
    var runs = 0
    val remove =
      node<RemovalTarget, RemovalResult>(
        "remove_file",
        requireConfirmation = { _, target ->
          checked += target.path
          target.path.startsWith("/prod")
        },
      ) { _, _ ->
        runs += 1
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove, path = "/prod/db"))

    // Act
    val asked = session.turn(userMessage("go"))
    val answered = session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("confirmed" to true)))

    // Assert
    assertEquals(
      listOf(REMOVAL_CONFIRMATION_ID),
      asked.flatMap { it.functionCalls() }.map { it.id },
    )
    assertEquals(mapOf("removed" to true), answered.replyInput())
    assertEquals(1, runs)
    // The resumed run uses the answer without calling the predicate.
    assertEquals(listOf("/prod/db"), checked)
  }

  @Test
  fun requireConfirmationPredicate_typedInputDoesNotMatch_runsTheBodyWithoutAsking() {
    // Arrange
    val remove =
      node<RemovalTarget, RemovalResult>(
        "remove_file",
        requireConfirmation = { _, target -> target.path.startsWith("/prod") },
      ) { _, _ ->
        RemovalResult(removed = true)
      }

    // Act
    val events = WorkflowSession(removalWorkflow(remove, path = "/tmp/a")).turn(userMessage("go"))

    // Assert
    assertTrue(events.flatMap { it.functionCalls() }.isEmpty())
    assertEquals(mapOf("removed" to true), events.replyInput())
  }

  @Test
  fun requireConfirmationPredicate_rejected_outputsTheRejectionWithoutRunningTheBody() {
    // Arrange
    var runs = 0
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = { _, _ -> true }) {
        _,
        _ ->
        runs += 1
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("confirmed" to false)))

    // Assert
    assertEquals(mapOf("error" to "This tool call is rejected."), events.replyInput())
    assertEquals(0, runs)
  }

  @Test
  fun requireConfirmationPredicate_nullAnswer_asksAgainWithoutDecidingAgain() {
    // Arrange
    var decisions = 0
    val remove =
      node<RemovalTarget, RemovalResult>(
        "remove_file",
        requireConfirmation = { _, _ -> (++decisions) == 1 },
      ) { _, _ ->
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val events = session.turn(answer(REMOVAL_CONFIRMATION_ID, mapOf("result" to null)))

    // Assert
    assertEquals(
      listOf(REMOVAL_CONFIRMATION_ID),
      events.flatMap { it.functionCalls() }.mapNotNull { it.id },
    )
    assertEquals(1, decisions)
  }

  @Test
  fun requireConfirmationPredicate_confirmedNodeWithNoOutput_isNotRerunWhenALaterNodeResumes() {
    // Arrange
    var runs = 0
    val remove =
      node<Any?, Unit>("remove_file", requireConfirmation = { _, _ -> true }) { _, _ -> runs += 1 }
    val review = Ask("review", RequestInput(interruptId = "review", message = "Review?"))
    val session =
      WorkflowSession(
        Workflow(name = "wf", edges = listOf(Edge(Start, remove), Edge(remove, review)))
      )
    session.advance(userMessage("go"))
    session.advance(answer("wf_tool_confirmation:wf@1/remove_file@1", mapOf("confirmed" to true)))

    // Act
    session.advance(answer("review", mapOf("ok" to true)))

    // Assert
    assertEquals(1, runs)
  }

  @Test
  fun requireConfirmationPredicate_parallelNode_eachItemDecidesForItself() {
    // Arrange
    val runs = mutableListOf<Long>()
    val worker =
      parallelNode<Long, String>("worker", requireConfirmation = { _, item -> item > 1L }) { _, item
        ->
        runs += item
        "done-$item"
      }
    val session = WorkflowSession(fanOutWorkflow(worker))

    // Act
    val asked = session.turn(userMessage("go"))

    // Assert
    assertEquals(2, asked.flatMap { it.functionCalls() }.size)
    assertEquals(listOf(1L), runs)
  }

  @Test
  fun requireConfirmationPredicate_rerunOnResume_defaultsToTrueAndRejectsFalse() {
    // Act
    val noRerunError =
      assertFailsWith<WorkflowConfigurationException> {
        node<Any?, Any?>(
          "no_rerun",
          rerunOnResume = false,
          requireConfirmation = { _, _ -> true },
        ) { _, x ->
          x
        }
      }
    val defaulted =
      node<Any?, Any?>("defaulted", requireConfirmation = { _, _ -> true }) { _, x -> x }

    // Assert
    assertContains(noRerunError.message.orEmpty(), "needs rerunOnResume = true")
    assertTrue(defaulted.rerunOnResume)
  }

  @Test
  fun node_noConfirmationArgument_runsWithoutAskingWhileALambdaPicksThePredicateOverload() {
    // Arrange
    val plain = node<Any?, String>("plain") { _, _ -> "ran" }
    val gated =
      node<Any?, String>("gated", requireConfirmation = { _, _ -> true }) { _, _ -> "ran" }
    val reply = CaptureInput("reply")
    val session =
      WorkflowSession(Workflow(name = "wf", edges = listOf(Edge(Start, plain), Edge(plain, reply))))

    // Act
    val events = session.turn(userMessage("go"))

    // Assert
    assertEquals("ran", events.replyInput())
    assertTrue(events.none { it.functionCalls().isNotEmpty() })
    assertFalse(plain.rerunOnResume)
    assertTrue(gated.rerunOnResume)
  }

  @Test
  fun requireConfirmation_rerunOnResume_defaultsToTrueAndRejectsFalse() {
    // Arrange
    val misconfigured: List<() -> FunctionNode<*, *>> =
      listOf(
        { node<Any?, Any?>("a", rerunOnResume = false, requireConfirmation = true) { _, x -> x } },
        {
          parallelNode<Long, Long>("b", rerunOnResume = false, requireConfirmation = true) { _, x ->
            x
          }
        },
      )

    // Act
    val built = node<Any?, Any?>("remove_file", requireConfirmation = true) { _, input -> input }
    val errors = misconfigured.map { assertFailsWith<WorkflowConfigurationException> { it() } }

    // Assert
    assertTrue(built.rerunOnResume)
    for (error in errors) assertContains(error.message.orEmpty(), "needs rerunOnResume = true")
  }

  @Test
  fun requireConfirmation_answerThatIsNotAConfirmation_failsTheTurn() {
    // Arrange
    val remove =
      node<RemovalTarget, RemovalResult>("remove_file", requireConfirmation = true) { _, _ ->
        RemovalResult(removed = true)
      }
    val session = WorkflowSession(removalWorkflow(remove))
    session.advance(userMessage("go"))

    // Act
    val (_, error) =
      session.failingTurn(answer(REMOVAL_CONFIRMATION_ID, mapOf("confirmed" to "yes")))

    // Assert
    assertTrue(error is IllegalArgumentException, "expected a validation failure, got $error")
    assertContains(error.message.orEmpty(), REMOVAL_CONFIRMATION_ID)
  }
}
