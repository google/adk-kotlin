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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.FinishTaskTool
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.taskWaitsInAnotherInvocation
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.SingleCollectorLiveConnection
import com.google.adk.kt.processors.STREAM_FINAL_ANSWER_KEY
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Transcription
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A live-only model whose connections each answer their inputs with the turns of their own script,
 * in connect order. Connects and closes go to [log], and a send of the text [stallOn] never
 * completes.
 */
private class LiveNodeModel(
  override val name: String,
  private val scripts: List<List<Content>>,
  private val log: MutableList<String> = mutableListOf(),
  private val stallOn: String? = null,
) : Model {
  val connections = mutableListOf<LiveNodeConnection>()
  val requests = mutableListOf<LlmRequest>()

  override suspend fun connect(request: LlmRequest): LiveConnection {
    log += "$name connect"
    requests += request
    val turns = checkNotNull(scripts.getOrNull(connections.size)) { "$name connected unscripted" }
    return LiveNodeConnection(name, turns, log, stallOn).also { connections += it }
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    error("$name runs only live")
  }
}

/**
 * Answers each input, including history that ends with a user turn, with its next scripted turn,
 * and ends once closed.
 */
private class LiveNodeConnection(
  private val name: String,
  private val turns: List<Content>,
  private val log: MutableList<String>,
  private val stallOn: String?,
) : SingleCollectorLiveConnection() {
  val received = mutableListOf<Content>()
  var closed = false
    private set

  private val replies = Channel<Content>(Channel.UNLIMITED)
  private var next = 0

  fun receivedTexts(): List<String> = received.flatMap { it.parts }.mapNotNull { it.text }

  fun receivedResponses(): List<FunctionResponse> =
    received.flatMap { it.parts }.mapNotNull { it.functionResponse }

  override fun responses(): Flow<LlmResponse> = flow {
    val turn = replies.receiveCatching().getOrNull() ?: return@flow
    when {
      turn === GO_AWAY -> emit(LlmResponse(goAway = LiveServerGoAway(timeLeft = null)))
      turn.role == SPOKEN ->
        emit(LlmResponse(outputTranscription = Transcription(turn.text()), partial = true))
      turn.role == Role.USER -> emit(LlmResponse(inputTranscription = Transcription(turn.text())))
      else -> emit(LlmResponse(content = turn, turnComplete = true))
    }
  }

  override suspend fun sendHistory(history: List<Content>) {
    received += history
    if (history.lastOrNull()?.role == Role.USER) answer()
  }

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // Holds the sender with this input taken off the queue but not yet sent.
    if (content.text() == stallOn) awaitCancellation()
    received += content
    answer()
  }

  override suspend fun sendRealtime(input: RealtimeInput) {
    answer()
  }

  override suspend fun closeSession() {
    log += "$name close"
    closed = true
    replies.cancel()
  }

  private fun answer() {
    // A server sends what it heard with the turn after it, and a go-away right after a turn.
    do {
      val turn = turns.getOrNull(next++) ?: return
      val unused = replies.trySend(turn)
    } while (turn.role == Role.USER || turns.getOrNull(next) === GO_AWAY)
  }
}

/** A script entry that sends a go-away without a resumption handle, so the agent reconnects. */
private val GO_AWAY = modelMessage("(server going away)")

/** The role of a script entry the model speaks; see [spoke]. */
private const val SPOKEN = "spoken"

/** A script entry for a partial transcription of the model saying [text]. */
private fun spoke(text: String) = Content(role = SPOKEN, parts = listOf(Part(text = text)))

/** A script entry for the model hearing the user say [text]. */
private fun heard(text: String) = userMessage(text)

private fun finishTask(vararg args: Pair<String, Any?>) =
  modelFunctionCall("finish_task", mapOf(*args))

/** A script that acknowledges each of [heard], then calls `finish_task` with [result]. */
private fun ackThenFinish(vararg heard: String, result: String): List<Content> =
  heard.map { modelMessage("Acknowledged: $it") } + finishTask("result" to result)

/** The non-empty text of each event. */
private fun List<Event>.replies(): List<String> =
  map { it.contentText() }.filter { it.isNotEmpty() }

/**
 * Runs [workflow] live as an app's root on [queue] with [runConfig] and [sessionService], resumable
 * if [resumable], handing each event to [onEvent] as the caller receives it. A run that never ends
 * fails.
 */
private suspend fun runLiveWorkflow(
  workflow: Workflow,
  queue: LiveRequestQueue,
  runConfig: RunConfig? = null,
  resumable: Boolean = false,
  sessionService: SessionService = InMemorySessionService(),
  onEvent: (Event) -> Unit = {},
): List<Event> {
  val resumability = ResumabilityConfig(isResumable = true).takeIf { resumable }
  val app = App(appName = "app", rootNode = workflow, resumabilityConfig = resumability)
  val runner =
    InMemoryRunner(app = app, sessionService = sessionService).apply { allowsLiveNodeRoot = true }
  return withTimeout(30.seconds) {
    runner.runLive("user", "live", queue, runConfig).onEach(onEvent).toList()
  }
}

/**
 * A node that starts [task] with runNode on its own input, the task's output standing as its own.
 */
private fun started(task: LlmAgent): Node =
  node<Any?, Any?>("start_${task.name}", rerunOnResume = true) { context, input ->
    context.runNode(task, nodeInput = input, useAsOutput = true)
  }

/** Covers agent nodes of a live workflow run: which run live, and how they share the input. */
class WorkflowLiveTest {

  @Test
  fun taskNode_startedByRunNode_runsLiveAndHandsItsFinishArgumentsOn() = runBlocking {
    // Arrange: the task asks once, then finishes on the user's second message.
    val model =
      LiveNodeModel(
        "writer",
        listOf(listOf(modelMessage("Which topic?"), finishTask("result" to "memo"))),
      )
    val writer = started(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))
    queue.sendContent(userMessage("about live"))

    // Act: the queue stays open, so only the task's finish ends the node.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, writer), Edge(writer, CaptureInput("capture")))),
        queue,
      )

    assertEquals(
      listOf(mapOf("result" to "memo"), mapOf("received" to mapOf("result" to "memo"))),
      events.mapNotNull { it.output },
    )
    assertEquals(listOf("write", "about live"), model.connections.single().receivedTexts())
    assertTrue(model.connections.single().closed)
  }

  @Test
  fun taskNode_startedByRunNode_answersOnlyAFailedFinishTaskThroughTheQueue() = runBlocking {
    // Arrange: the first finish_task lacks `result`, so the model gets an error and retries.
    val model =
      LiveNodeModel("writer", listOf(listOf(finishTask(), finishTask("result" to "memo"))))
    val writer = started(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, writer))), queue)

    // Assert: the model got only the error, and the success answer is not left in the queue.
    val answers = model.connections.single().receivedResponses()
    assertEquals(listOf("finish_task"), answers.map { it.name })
    assertContains(answers.single().response["error"].toString(), "validation errors")
    assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.single())
    queue.close()
    assertNull(queue.receiveRequest())
  }

  @Test
  fun taskNode_startedByRunNode_reconnectInsideItsRound_hearsTheUsersEarlierTurns() = runBlocking {
    // Arrange: the server sends a go-away right after the task's question, so the task reconnects.
    val log = mutableListOf<String>()
    val model =
      LiveNodeModel(
        "writer",
        listOf(
          listOf(modelMessage("Which topic?"), GO_AWAY),
          listOf(finishTask("result" to "memo")),
        ),
        log = log,
      )
    val writer = started(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: the caller answers the question, which only the reconnected task can answer.
    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, writer))), queue) { event ->
        if (event.contentText() == "Which topic?") queue.sendContent(userMessage("about live"))
      }

    // Assert: the new session's history keeps the task's own user turn, as its scope does.
    assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.single())
    assertEquals(listOf("writer connect", "writer close", "writer connect", "writer close"), log)
    assertEquals(
      listOf("write", "Which topic?", "about live"),
      model.connections[1].receivedTexts(),
    )
  }

  @Test
  fun taskNode_startedByRunNode_storesItsStreamsFinalAnswersInItsOwnScope() = runBlocking {
    val model =
      LiveNodeModel(
        "writer",
        listOf(listOf(modelFunctionCall("monitor", emptyMap()), finishTask("result" to "memo"))),
      )
    val monitor = DummyTool("monitor") { _, _ -> flow<String> { awaitCancellation() } }
    val writer =
      LlmAgent(name = "writer", model = model, tools = listOf(monitor), mode = AgentMode.TASK)
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(writer)))),
        queue,
        sessionService = sessions,
      )

    // Assert: the stopped stream's answer is in the task's scope, where its call is.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val call = stored.single { event -> event.functionCalls().any { it.name == "monitor" } }
    val finalAnswer = stored.single { it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true }
    assertNotNull(call.isolationScope)
    assertEquals(call.isolationScope, finalAnswer.isolationScope)
    // The caller receives the answer too, not only the session.
    assertEquals(
      listOf(finalAnswer.id),
      events.filter { it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true }.map { it.id },
    )
  }

  @Test
  fun taskNode_startedByRunNode_finishingResumably_doesNotEndAsAHandoff() = runBlocking {
    val model = LiveNodeModel("writer", listOf(listOf(finishTask("result" to "memo"))))
    val writer = started(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, writer))), queue, resumable = true)

    // Assert: only a handoff ends the agent's run early for a resume.
    assertEquals(listOf(mapOf("result" to "memo")), events.mapNotNull { it.output })
    assertEquals(0, events.count { it.author == "writer" && it.actions.endOfAgent })
  }

  @Test
  fun taskNode_startedByRunNode_callerStopsAtTheFirstStoppedStream_storesTheOthers() = runBlocking {
    // Arrange: one response starts two streams and finishes the task, which stops both.
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("first_monitor", emptyMap())),
        Part(functionCall = FunctionCall("second_monitor", emptyMap())),
        Part(functionCall = FunctionCall("finish_task", mapOf("result" to "memo"))),
      )
    val monitors =
      listOf("first_monitor", "second_monitor").map { name ->
        DummyTool(name) { _, _ -> flow<String> { awaitCancellation() } }
      }
    val writer =
      LlmAgent(
        name = "writer",
        model = LiveNodeModel("writer", listOf(listOf(turn))),
        tools = monitors,
        mode = AgentMode.TASK,
      )
    val sessions = InMemorySessionService()
    val app = App(appName = "app", rootNode = Workflow("wf", listOf(Edge(Start, started(writer)))))
    val runner =
      InMemoryRunner(app = app, sessionService = sessions).apply { allowsLiveNodeRoot = true }
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: the caller stops collecting at the first stopped stream's answer.
    val unused =
      withTimeout(30.seconds) {
        runner.runLive("user", "live", queue).first {
          it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true
        }
      }

    // Assert: both streams are still recorded as stopped.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val records = stored.filter { it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true }
    assertEquals(
      listOf("first_monitor", "second_monitor"),
      records.flatMap { it.functionResponses() }.map { it.name }.sorted(),
    )
  }

  @Test
  fun taskNode_startedByRunNode_failingAfterTheUserSpoke_failsAsTheTask() = runBlocking {
    // Arrange: the task hears the user, then its connection goes away and cannot reopen.
    val model = LiveNodeModel("writer", listOf(listOf(heard("hello"), GO_AWAY)))
    val writer = started(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))
    val errors = mutableListOf<Event>()

    val failure =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, writer))), queue) { event ->
          if (event.errorCode != null) errors += event
        }
      }

    assertContains(failure.message.orEmpty(), "connected unscripted")
    assertEquals(listOf("writer"), errors.map { it.author })
  }

  @Test
  fun taskNode_startedByRunNode_failingBeforeItsFirstEvent_failsAsTheTask() = runBlocking {
    val writer =
      started(
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", emptyList()),
          mode = AgentMode.TASK,
        )
      )
    val errors = mutableListOf<Event>()

    val failure =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, writer))), LiveRequestQueue()) { event ->
          if (event.errorCode != null) errors += event
        }
      }

    assertContains(failure.message.orEmpty(), "connected unscripted")
    assertEquals(listOf("writer"), errors.map { it.author })
  }

  @Test
  fun taskNodes_startedByRunNode_inputSentOnTheFirstTasksOutputReachesTheSecond() = runBlocking {
    // Arrange: the second task opens on the first task's output, then reads the caller's input.
    val firstModel = LiveNodeModel("first", listOf(ackThenFinish("first start", result = "first")))
    val secondModel =
      LiveNodeModel(
        "second",
        listOf(ackThenFinish("opening turn", "second start", result = "second")),
      )
    val first = started(LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK))
    val second = started(LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first start"))
    queue.sendContent(userMessage("first end"))

    // Act: the caller sends the next messages as soon as it sees the first task finish.
    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, first), Edge(first, second))), queue) {
        event ->
        if (event.output == mapOf("result" to "first")) {
          queue.sendContent(userMessage("second start"))
          queue.sendContent(userMessage("second end"))
        }
      }

    assertEquals(
      listOf("first start", "first end"),
      firstModel.connections.single().receivedTexts(),
    )
    assertEquals(
      listOf("""{"result":"first"}""", "second start", "second end"),
      secondModel.connections.single().receivedTexts(),
    )
    assertEquals(mapOf("result" to "second"), events.mapNotNull { it.output }.last())
  }

  @Test
  fun taskNodes_startedByRunNode_withACallersHandle_onlyTheFirstSessionResumesIt() = runBlocking {
    // Arrange: the first task finishes on the user's input, the second on its opening turn.
    val firstModel = LiveNodeModel("first", listOf(listOf(finishTask("result" to "a"))))
    val secondModel = LiveNodeModel("second", listOf(listOf(finishTask("result" to "b"))))
    val first = started(LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK))
    val second = started(LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("go"))
    val handle = SessionResumptionConfig(handle = "h-1")

    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, first), Edge(first, second))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(sessionResumption = handle)),
      )

    // Assert: the second task starts a new session, so it is sent its opening turn and finishes.
    val sessions = listOf(firstModel.requests.single(), secondModel.requests.single())
    assertEquals(
      listOf("h-1", null),
      sessions.map { it.liveConnectConfig.sessionResumption?.handle },
    )
    assertEquals(
      listOf(mapOf("result" to "a"), mapOf("result" to "b")),
      events.mapNotNull { it.output },
    )
  }

  @Test
  fun taskNode_startedByRunNode_streamCalledWithFinishTask_isRecordedAsStopped() = runBlocking {
    val stored = runStreamWithClosingCall(FunctionCall("finish_task", mapOf("result" to "memo")))

    assertContains(lastMonitorAnswer(stored), "stopped streaming")
    // The record follows the finish_task answer, and a later reader still finds the task finished.
    val scope = checkNotNull(stored.single { FinishTaskTool.isTerminalResponse(it) }.isolationScope)
    val delegation = FunctionCall(name = "writer", id = scope)
    assertFalse(taskWaitsInAnotherInvocation(stored, delegation, invocationId = "later"))
  }

  @Test
  fun taskNode_startedByRunNode_streamCalledWithATransfer_isRecordedAsStopped() = runBlocking {
    val transfer = FunctionCall("transfer_to_agent", mapOf("agent_name" to "helper"))

    val stored = runStreamWithClosingCall(transfer)

    assertContains(lastMonitorAnswer(stored), "stopped streaming")
  }

  /**
   * Runs a live task that starts the streaming tool `monitor` in the same response as
   * [closingCall], which ends its run, and returns the stored session events.
   */
  private suspend fun runStreamWithClosingCall(closingCall: FunctionCall): List<Event> {
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("monitor", emptyMap())),
        Part(functionCall = closingCall),
      )
    val helperModel = DummyModel("helper") { flowOf(LlmResponse(content = modelMessage("helped"))) }
    val writer =
      LlmAgent(
        name = "writer",
        model = LiveNodeModel("writer", listOf(listOf(turn))),
        tools = listOf(DummyTool("monitor") { _, _ -> flow<String> { awaitCancellation() } }),
        subAgents = listOf(LlmAgent(name = "helper", model = helperModel, description = "Helps.")),
        mode = AgentMode.TASK,
      )
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(writer)))),
        queue,
        sessionService = sessions,
      )

    return checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
  }

  /** The status of the last answer [events] give the `monitor` call. */
  private fun lastMonitorAnswer(events: List<Event>): String =
    events
      .flatMap { it.functionResponses() }
      .last { it.name == "monitor" }
      .response["status"]
      .toString()

  @Test
  fun taskNode_startedByRunNode_connectionLostWithAStreamRunning_recordsTheStreamAsStopped() =
    runBlocking {
      // Arrange: the task starts a stream, then its connection goes away and cannot reopen.
      val turn = modelMessage(Part(functionCall = FunctionCall("monitor", emptyMap())))
      val writer =
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", listOf(listOf(turn, GO_AWAY))),
          tools = listOf(DummyTool("monitor") { _, _ -> flow<String> { awaitCancellation() } }),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      val workflow = Workflow("wf", listOf(Edge(Start, started(writer))))
      val failure =
        assertFailsWith<IllegalStateException> {
          runLiveWorkflow(workflow, queue, sessionService = sessions)
        }

      assertContains(failure.message.orEmpty(), "connected unscripted")
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      assertContains(lastMonitorAnswer(stored), "stopped streaming")
      // Written straight to the session, the answer still carries the task's scope, as its call
      // does.
      val call = stored.single { event -> event.functionCalls().any { it.name == "monitor" } }
      val finalAnswer = stored.single { it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true }
      assertNotNull(call.isolationScope)
      assertEquals(call.isolationScope, finalAnswer.isolationScope)
    }

  @Test
  fun taskNode_startedByRunNode_connectionLostWithAStreamRunning_storesTheStopInItsNode() =
    runBlocking {
      // Arrange: as above, the task's connection goes away for good while its stream runs.
      val turn = modelMessage(Part(functionCall = FunctionCall("monitor", emptyMap())))
      val writer =
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", listOf(listOf(turn, GO_AWAY))),
          tools = listOf(DummyTool("monitor") { _, _ -> flow<String> { awaitCancellation() } }),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      // Act:
      val unused =
        assertFailsWith<IllegalStateException> {
          runLiveWorkflow(
            Workflow("wf", listOf(Edge(Start, started(writer)))),
            queue,
            sessionService = sessions,
          )
        }

      // Assert: the failed round stores the stop under the task's node, where its call is.
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      val call = stored.single { event -> event.functionCalls().any { it.name == "monitor" } }
      val stop = stored.single { it.customMetadata?.get(STREAM_FINAL_ANSWER_KEY) == true }
      val path = assertNotNull(call.nodeInfo?.path)
      assertEquals(path, stop.nodeInfo?.path)
    }

  @Test
  fun taskNode_startedByRunNode_callbackTransferOnAPartialResponse_storesTheHandoff() =
    runBlocking {
      val helperModel =
        DummyModel("helper") { flowOf(LlmResponse(content = modelMessage("helped"))) }
      val handOff = AfterModelCallback { context, response ->
        context.actions.transferToAgent = "helper"
        response
      }
      val writer =
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", listOf(listOf(spoke("Let me")))),
          subAgents =
            listOf(LlmAgent(name = "helper", model = helperModel, description = "Helps.")),
          afterModelCallbacks = listOf(handOff),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      val unused =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)))),
          queue,
          sessionService = sessions,
        )

      // Assert: the handoff is stored, as a resumed run needs it to see that the task ended.
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      assertTrue(stored.any { it.author == "writer" && it.actions.transferToAgent == "helper" })
    }

  @Test
  fun chatNode_turnBasedDelegatingToALiveTask_hearsTheTasksAnswer() = runBlocking {
    // Arrange: the chat runs turn-based here, but the task it delegates to runs live.
    val chatRequests = mutableListOf<LlmRequest>()
    val chatModel =
      DummyModel("planner") { request ->
        chatRequests += request
        if (chatRequests.size == 1) {
          flowOf(modelFunctionCallResponse("booker", mapOf("request" to "book a trip")))
        } else {
          flowOf(LlmResponse(content = modelMessage("Booked.")))
        }
      }
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))))
    val booker =
      LlmAgent(name = "booker", model = taskModel, description = "Books.", mode = AgentMode.TASK)
    val planner = LlmAgent(name = "planner", model = chatModel, subAgents = listOf(booker))

    val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), LiveRequestQueue())

    assertEquals(1, taskModel.connections.size)
    val answer = chatRequests[1].contents.flatMap { it.parts }.mapNotNull { it.functionResponse }
    assertEquals(mapOf("result" to "booked"), answer.single { it.name == "booker" }.response)
    assertEquals(listOf("Booked."), events.filter { it.author == "planner" }.replies())
  }

  @Test
  fun taskNodes_startedByRunNode_aroundAPlainNode_eachReadsOnlyItsOwnInput() = runBlocking {
    // Arrange: each task acknowledges every input but its last, and finishes on the last.
    val log = mutableListOf<String>()
    val firstModel =
      LiveNodeModel("first", listOf(ackThenFinish("first start", result = "first")), log = log)
    val secondModel =
      LiveNodeModel(
        "second",
        listOf(ackThenFinish("plain", "second start", result = "second")),
        log = log,
      )
    var plainInput: Any? = null
    val plain =
      node<Any?, String>("plain") { _, input ->
        plainInput = input
        "plain"
      }
    val first = started(LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK))
    val second = started(LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first start"))
    queue.sendContent(userMessage("first end"))

    // Act: the second task's messages are sent only once the plain node has run.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, first), Edge(first, plain), Edge(plain, second))),
        queue,
      ) { event ->
        if (event.output == "plain") {
          queue.sendContent(userMessage("second start"))
          queue.sendContent(userMessage("second end"))
        }
      }

    // Assert: the plain node's output is the second task's opening turn.
    assertEquals(
      listOf(mapOf("result" to "first"), "plain", mapOf("result" to "second")),
      events.mapNotNull { it.output },
    )
    assertEquals(mapOf("result" to "first"), plainInput)
    assertEquals(
      listOf("first start", "first end"),
      firstModel.connections.single().receivedTexts(),
    )
    assertEquals(
      listOf("plain", "second start", "second end"),
      secondModel.connections.single().receivedTexts(),
    )
    assertEquals(listOf("first connect", "first close", "second connect", "second close"), log)
    assertEquals(
      listOf("Acknowledged: first start", "Acknowledged: plain", "Acknowledged: second start"),
      events.replies(),
    )
  }

  @Test
  fun taskNode_startedByRunNode_liveTransfer_endsTheTaskAndRunsTheTarget() = runBlocking {
    // Arrange: the live task hears the user, then hands them to a sub-agent, which answers.
    val log = mutableListOf<String>()
    val helperRequests = mutableListOf<LlmRequest>()
    val helperModel =
      DummyModel("helper") { request ->
        helperRequests += request
        flowOf(LlmResponse(content = modelMessage("helped")))
      }
    val helper = LlmAgent(name = "helper", model = helperModel, description = "Helps.")
    val routerModel =
      LiveNodeModel(
        "router",
        listOf(
          listOf(
            heard("I need help"),
            modelFunctionCall("transfer_to_agent", mapOf("agent_name" to "helper")),
          )
        ),
        log = log,
      )
    val router =
      LlmAgent(
        name = "router",
        model = routerModel,
        subAgents = listOf(helper),
        mode = AgentMode.TASK,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("help me"))

    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, started(router)))), queue, resumable = true)

    // Assert: the transfer closes the task's connection and marks the task done for a resume.
    assertEquals(listOf("router connect", "router close"), log)
    assertEquals(emptyList(), routerModel.connections.single().receivedResponses())
    assertEquals(listOf("helped"), events.filter { it.author == "helper" }.replies())
    assertEquals(1, events.count { it.author == "router" && it.actions.endOfAgent })
    // What the task heard is the user's own turn, so the target hears it too.
    assertContains(
      helperRequests.single().contents.flatMap { it.parts }.mapNotNull { it.text },
      "I need help",
    )
  }

  @Test
  fun taskNodes_startedByRunNode_firstFinishesBeforeSendingATakenInput_dropsIt() = runBlocking {
    // Arrange: the first task finishes on "first start" while its sender holds "first end" unsent.
    val log = mutableListOf<String>()
    val firstModel =
      LiveNodeModel(
        "first",
        listOf(listOf(finishTask("result" to "first"))),
        log = log,
        stallOn = "first end",
      )
    val secondModel =
      LiveNodeModel(
        "second",
        listOf(ackThenFinish("""{"result":"first"}""", result = "second")),
        log = log,
      )
    val first = started(LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK))
    val second = started(LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first start"))
    queue.sendContent(userMessage("first end"))
    queue.sendContent(userMessage("second end"))

    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, first), Edge(first, second))), queue)

    // Assert: neither task receives the held input, and the second reads what came after it.
    assertEquals(
      listOf(mapOf("result" to "first"), mapOf("result" to "second")),
      events.mapNotNull { it.output },
    )
    assertEquals(listOf("first start"), firstModel.connections.single().receivedTexts())
    assertEquals(
      listOf("""{"result":"first"}""", "second end"),
      secondModel.connections.single().receivedTexts(),
    )
    assertEquals(listOf("first connect", "first close", "second connect", "second close"), log)
  }

  @Test
  fun taskNodes_startedByRunNodeAtOnce_failTheRunInsteadOfSplittingTheInput() = runBlocking {
    // Arrange: neither task can finish without input, so both read at once.
    val leftModel = LiveNodeModel("left", listOf(emptyList()))
    val rightModel = LiveNodeModel("right", listOf(emptyList()))
    val left = LlmAgent(name = "left", model = leftModel, mode = AgentMode.TASK)
    val right = LlmAgent(name = "right", model = rightModel, mode = AgentMode.TASK)
    val errors = mutableListOf<Event>()

    val failure =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(left)), Edge(Start, started(right)))),
          LiveRequestQueue(),
        ) { event ->
          if (event.errorCode != null) errors += event
        }
      }

    // Assert: the caller sees the failure as an error event too, and both connections are closed.
    assertContains(failure.message.orEmpty(), "already reading its LiveRequestQueue")
    assertEquals(listOf(failure.message), errors.map { it.errorMessage })
    assertEquals(
      listOf(true, true),
      listOf(leftModel, rightModel).map { it.connections.single().closed },
    )
  }
}
