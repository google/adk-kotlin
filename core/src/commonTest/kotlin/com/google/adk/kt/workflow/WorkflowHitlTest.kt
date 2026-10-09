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
import com.google.adk.kt.agents.TypedData
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

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

/** Pauses on a long-running call and asks to transfer to another agent in the same event. */
private class TransferAfterInterrupt(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(
      Event(
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

/** Fails the first time it ever runs and succeeds after, so a retry of its workflow recovers. */
private class FailsFirstRun(override val name: String) : Node {
  private var runs = 0

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    runs += 1
    if (runs == 1) throw NodeExecutionException("RuntimeError", "first run fails")
    emit("recovered")
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

/** A runner over a resumable workflow, driven a turn at a time. */
private class WorkflowSession(workflow: Workflow) {
  private val runner =
    InMemoryRunner(
      App(
        appName = "wf_test",
        rootNode = workflow,
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

/** What `reply` received in a turn run by [sessionAwaitingAnswer]. */
private fun List<Event>.replyInput(): Any? =
  (single { it.nodeInfo?.path == "wf@1/reply@1" }.output as Map<*, *>)["received"]

/** An object schema with an integer `count`. */
private val COUNT_SCHEMA =
  Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))

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
    assertEquals(3, events.single { it.nodeInfo?.path == "wf@1/ok@1" && it.output != null }.output)
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
}
