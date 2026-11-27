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
import com.google.adk.kt.agents.BackgroundToolCalls
import com.google.adk.kt.agents.FinishTaskTool
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.taskWaitsInAnotherInvocation
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.SingleCollectorLiveConnection
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.processors.OUT_OF_TURN_ANSWER_KEY
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.SaveHookArtifactService
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.nonBlockingTool
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A live-only model whose connections each answer their inputs with the turns of their own script,
 * in connect order, completing each turn only if [completesTurns]. Connects and closes go to [log],
 * and a send of the text [stallOn] never completes.
 */
private class LiveNodeModel(
  override val name: String,
  private val scripts: List<List<Content>>,
  private val log: MutableList<String> = mutableListOf(),
  private val stallOn: String? = null,
  private val completesTurns: Boolean = true,
) : Model {
  val connections = mutableListOf<LiveNodeConnection>()
  val requests = mutableListOf<LlmRequest>()
  /** Completes once this model first connects. */
  val connected = CompletableDeferred<Unit>()
  /** Completes once this model connects a second time. */
  val reconnected = CompletableDeferred<Unit>()

  override suspend fun connect(request: LlmRequest): LiveConnection {
    log += "$name connect"
    requests += request
    val turns = checkNotNull(scripts.getOrNull(connections.size)) { "$name connected unscripted" }
    return LiveNodeConnection(name, turns, log, stallOn, completesTurns).also {
      connections += it
      connected.complete(Unit)
      if (connections.size == 2) reconnected.complete(Unit)
    }
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
  private val completesTurns: Boolean,
) : SingleCollectorLiveConnection() {
  val received = mutableListOf<Content>()
  /** The realtime audio sent to this connection, each chunk's bytes read as text. */
  val receivedAudio = mutableListOf<String>()
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
      else -> emit(LlmResponse(content = turn, turnComplete = completesTurns))
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
    if (input is RealtimeInput.Audio) receivedAudio += input.blob.data?.decodeToString().orEmpty()
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

/** Realtime audio whose bytes spell [text]. */
private fun audioSaying(text: String) =
  RealtimeInput.Audio(Blob("audio/pcm", data = text.encodeToByteArray()))

/** `Start -> intake -> classify`, where classify routes RETRY back to intake and DONE to `done`. */
private fun retryLoop(intake: Node, classify: Node) =
  Workflow(
    "wf",
    listOf(
      Edge(Start, intake),
      Edge(intake, classify),
      Edge(classify, intake, Route.Tag("RETRY")),
      Edge(classify, CaptureInput("done"), Route.Tag("DONE")),
    ),
  )

/** The non-empty text of each event. */
private fun List<Event>.replies(): List<String> =
  map { it.contentText() }.filter { it.isNotEmpty() }

/** A plugin that marks each event it sees, so a test can tell which events went through it. */
private object MarkingPlugin : Plugin {
  const val KEY = "marked"
  override val name = "marking"

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event =
    event.copy(customMetadata = event.customMetadata.orEmpty() + (KEY to true))
}

/** The events that reference a saved live recording. */
private fun List<Event>.recordings(): List<Event> = filter { event ->
  event.content?.parts?.any { it.fileData != null } == true
}

/** Whether this event carries inline audio. */
private fun Event.hasAudio(): Boolean = content?.parts?.any { it.inlineData != null } == true

/** A model turn that speaks a short audio chunk. */
private fun audio() =
  Content(
    role = Role.MODEL,
    parts = listOf(Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = ByteArray(8)))),
  )

/** The function calls and answers in [author]'s events, in order, as "call x" and "answer x". */
private fun List<Event>.functionSteps(author: String): List<String> =
  filter { it.author == author }
    .flatMap { event ->
      event.functionCalls().map { "call ${it.name}" } +
        event.functionResponses().map { "answer ${it.name}" }
    }

/** A task agent that books trips, run live on [model]. */
private fun booker(model: LiveNodeModel) =
  LlmAgent(name = "booker", model = model, description = "Books trips.", mode = AgentMode.TASK)

/** A chat agent on [model] that delegates to [tasks]. */
private fun planner(model: LiveNodeModel, vararg tasks: LlmAgent) =
  LlmAgent(name = "planner", model = model, subAgents = tasks.toList(), mode = AgentMode.CHAT)

/**
 * Runs [workflow] live as an app's root on [queue] with [runConfig], [sessionService] and
 * [artifactService], resumable if [resumable], handing each event to [onEvent] as the caller
 * receives it. A run that never ends fails.
 */
private suspend fun runLiveWorkflow(
  workflow: Workflow,
  queue: LiveRequestQueue,
  runConfig: RunConfig? = null,
  resumable: Boolean = false,
  sessionService: SessionService = InMemorySessionService(),
  artifactService: ArtifactService = InMemoryArtifactService(),
  plugins: List<Plugin> = emptyList(),
  onEvent: (Event) -> Unit = {},
): List<Event> {
  val resumability = ResumabilityConfig(isResumable = true).takeIf { resumable }
  val app =
    App(appName = "app", rootNode = workflow, resumabilityConfig = resumability, plugins = plugins)
  val runner =
    InMemoryRunner(app = app, sessionService = sessionService, artifactService = artifactService)
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

/**
 * Runs the chat [chat] builds on its sub-agents, which hands off to `support` beside delegating to
 * a `booker` task, live from a node until support talks, logging every connect and close to [log].
 */
private suspend fun runCallbackHandoffBesideATask(
  log: MutableList<String>,
  chat: (subAgents: List<LlmAgent>) -> LlmAgent,
): List<Event> {
  val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
  val supportModel = LiveNodeModel("support", listOf(listOf(modelMessage("Hi, support."))), log)
  val support = LlmAgent(name = "support", model = supportModel, description = "Helps.")
  val planner = chat(listOf(booker(taskModel), support))
  val queue = LiveRequestQueue()
  queue.sendContent(userMessage("plan a trip"))
  return runLiveWorkflow(
    Workflow("wf", listOf(Edge(Start, started(planner)))),
    queue,
    resumable = true,
  ) { event ->
    if (event.contentText() == "Hi, support.") queue.close()
  }
}

/**
 * Asserts the chat's task ran first and the chat then handed over to support without reconnecting,
 * ending its own run only after the task answered.
 */
private fun assertHandsOverAfterTheTask(events: List<Event>, log: List<String>) {
  assertEquals(
    listOf(
      "planner connect",
      "planner close",
      "booker connect",
      "booker close",
      "support connect",
      "support close",
    ),
    log,
  )
  // So a resume before the task answered still answers it.
  val answered = events.indexOfFirst { e -> e.functionResponses().any { it.name == "booker" } }
  val ended = events.indexOfFirst { it.author == "planner" && it.actions.endOfAgent }
  assertTrue(answered in 0 until ended)
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
  fun taskNode_startedByRunNode_finishTaskOutsideTheEnum_answersAnErrorAndTheModelRetries() =
    runBlocking {
      // Arrange: the output takes only YES or NO, and the first finish_task sends MAYBE.
      val schema =
        Schema(
          type = Type.OBJECT,
          properties = mapOf("answer" to Schema(type = Type.STRING, enum = listOf("YES", "NO"))),
          required = listOf("answer"),
        )
      val model =
        LiveNodeModel(
          "confirm",
          listOf(listOf(finishTask("answer" to "MAYBE"), finishTask("answer" to "YES"))),
        )
      val confirm =
        started(
          LlmAgent(name = "confirm", model = model, outputSchema = schema, mode = AgentMode.TASK)
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("confirm"))

      val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, confirm))), queue)

      // Assert: the model hears the rule but not its value, and the node ends with the retry.
      val error =
        model.connections.single().receivedResponses().single().response["error"] as String
      assertContains(error, "not one of the schema's enum values")
      assertFalse("MAYBE" in error)
      assertEquals(mapOf("answer" to "YES"), events.mapNotNull { it.output }.single())
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
  fun taskNode_startedByRunNode_toolThrows_answersTheModelWithAnErrorAndFinishes() = runBlocking {
    // Arrange: the task calls a tool that throws, then finishes once it hears the error.
    val model =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelFunctionCall("check_calendar", id = "c1"), finishTask("result" to "x"))),
      )
    val calendar =
      DummyTool(name = "check_calendar", declares = true) { _, _ ->
        throw RuntimeException("calendar unavailable")
      }
    val task =
      LlmAgent(name = "booker", model = model, mode = AgentMode.TASK, tools = listOf(calendar))
    val sessionService = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(task)))),
        queue,
        sessionService = sessionService,
      )

    // Assert: the model heard the error, the task finished, and the call kept its answer.
    val error = mapOf("error" to "Invoking `check_calendar()` failed with an internal error.")
    val heard = model.connections.single().receivedResponses()
    assertEquals(listOf("c1" to error), heard.map { it.id to it.response })
    assertEquals(mapOf("result" to "x"), events.mapNotNull { it.output }.single())
    val stored = assertNotNull(sessionService.getSession(SessionKey("app", "user", "live"))).events
    val calls = stored.flatMap { it.functionCalls() }.filter { it.name == "check_calendar" }
    val answers = stored.flatMap { it.functionResponses() }.filter { it.name == "check_calendar" }
    assertEquals(listOf("c1"), calls.map { it.id })
    assertEquals(listOf("c1" to error), answers.map { it.id to it.response })
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
    val finalAnswer = stored.single { it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true }
    assertNotNull(call.isolationScope)
    assertEquals(call.isolationScope, finalAnswer.isolationScope)
    // The caller receives the answer too, not only the session.
    assertEquals(
      listOf(finalAnswer.id),
      events.filter { it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true }.map { it.id },
    )
  }

  @Test
  fun taskNode_startedByRunNode_finishingWithABackgroundCallRunning_stopsTheCallAndFinishes() {
    runBlocking(BackgroundToolCalls) {
      // Arrange: the task calls a tool that never answers, beside its finish_task.
      val stopped = CompletableDeferred<Unit>()
      val lookup =
        nonBlockingTool("lookup") {
          try {
            awaitCancellation()
          } finally {
            stopped.complete(Unit)
          }
        }
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("lookup", emptyMap())),
          Part(functionCall = FunctionCall("finish_task", mapOf("result" to "memo"))),
        )
      val writer =
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", listOf(listOf(turn))),
          tools = listOf(lookup),
          mode = AgentMode.TASK,
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, started(writer)))), queue)

      // Assert: the round's end stopped the call, and the task still finished.
      assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.single())
      assertTrue(stopped.isCompleted)
    }
  }

  @Test
  fun taskNode_startedByRunNode_freshRestartWhileABackgroundCallRuns_keepsTheCall() {
    runBlocking(BackgroundToolCalls) {
      // Arrange: a go-away with no handle follows the call, so the task restarts while it runs.
      val model =
        LiveNodeModel(
          "writer",
          listOf(
            listOf(modelFunctionCall("lookup", id = "b1"), GO_AWAY),
            listOf(finishTask("result" to "memo")),
          ),
        )
      val lookup =
        nonBlockingTool("lookup") {
          model.reconnected.await()
          "found"
        }
      val writer =
        LlmAgent(name = "writer", model = model, tools = listOf(lookup), mode = AgentMode.TASK)
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, started(writer)))), queue)

      // Assert: the new session opened with the running call, which its answer then paired.
      assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.single())
      val history = model.requests[1].contents.flatMap { it.parts }
      assertEquals(listOf("lookup"), history.mapNotNull { it.functionCall?.name })
      assertEquals(listOf("lookup"), model.connections[1].receivedResponses().map { it.name })
    }
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
    val runner = InMemoryRunner(app = app, sessionService = sessions)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: the caller stops collecting at the first stopped stream's answer.
    val unused =
      withTimeout(30.seconds) {
        runner.runLive("user", "live", queue).first {
          it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true
        }
      }

    // Assert: both streams are still recorded as stopped.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val records = stored.filter { it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true }
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
    val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))))
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
      ) { event ->
        if (event.contentText() == "helped") queue.close()
      }

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
      val finalAnswer = stored.single { it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true }
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
      val stop = stored.single { it.customMetadata?.get(OUT_OF_TURN_ANSWER_KEY) == true }
      val path = assertNotNull(call.nodeInfo?.path)
      assertEquals(path, stop.nodeInfo?.path)
    }

  @Test
  fun taskNode_startedByRunNode_callbackTransferOnAPartialResponse_storesTheHandoff() =
    runBlocking {
      val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))))
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
        ) { event ->
          if (event.contentText() == "helped") queue.close()
        }

      // Assert: the handoff is stored, as a resumed run needs it to see that the task ended.
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      assertTrue(stored.any { it.author == "writer" && it.actions.transferToAgent == "helper" })
    }

  @Test
  fun taskNodes_startedByRunNode_afterACaughtFailure_theNextOneStillConnects() = runBlocking {
    // Arrange: the first task cannot connect, and its caller goes on to a second task.
    val failing =
      LlmAgent(
        name = "failing",
        model = LiveNodeModel("failing", emptyList()),
        mode = AgentMode.TASK,
      )
    val writerModel = LiveNodeModel("writer", listOf(listOf(finishTask("result" to "memo"))))
    val writer = LlmAgent(name = "writer", model = writerModel, mode = AgentMode.TASK)
    var failure: Throwable? = null
    val starter =
      node<Any?, Any?>("starter", rerunOnResume = true) { context, _ ->
        failure = runCatching { context.runNode(failing) }.exceptionOrNull()
        context.runNode(writer, nodeInput = "write")
      }

    val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, starter))), LiveRequestQueue())

    // Assert: the failed task gave up the run, so the second one, on the same caller, connected.
    assertNotNull(failure)
    assertEquals(1, writerModel.connections.size)
    assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.first())
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
  fun taskNodes_startedByRunNode_withSaveLiveBlob_eachRecordsOnlyItsOwnAudio() = runBlocking {
    // Arrange: the first task finishes before its turn completes, leaving its audio unsaved.
    val firstModel =
      LiveNodeModel(
        "first",
        listOf(listOf(audio(), finishTask("result" to "a"))),
        completesTurns = false,
      )
    val secondModel = LiveNodeModel("second", listOf(listOf(audio(), finishTask("result" to "b"))))
    val firstTask =
      LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK, outputKey = "first_out")
    val first = started(firstTask)
    val second = started(LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("speak"))

    // Act: the caller answers the first task's speech by voice and the second's by text.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, first), Edge(first, second))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
      ) { event ->
        if (event.hasAudio()) {
          when (event.author) {
            "first" ->
              queue.sendRealtime(RealtimeInput.Audio(Blob("audio/pcm", data = ByteArray(8))))
            "second" -> queue.sendContent(userMessage("go on"))
          }
        }
      }

    // Assert: the user's and the first task's audio are saved apart from the second task's.
    assertEquals(listOf("user", "first", "second"), events.recordings().map { it.author })
    // The first task's finish stays its last event, so the task still reads as finished, and it
    // carries the task's output write.
    val firstScope = events.first { it.author == "first" }.isolationScope
    val last = events.last { it.isolationScope == firstScope }
    assertEquals(listOf("finish_task"), last.functionResponses().map { it.name })
    assertEquals(listOf(last), events.filter { "first_out" in it.actions.stateDelta })
  }

  @Test
  fun taskNode_startedByRunNode_storeHangsOnTheModelsRecording_keepsTheUsersRecording() =
    runBlocking {
      // Arrange: both sides' audio is left unsaved at the task's end, and the model's save hangs.
      val model =
        LiveNodeModel(
          "writer",
          listOf(listOf(audio(), finishTask("result" to "memo"))),
          completesTurns = false,
        )
      val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
      // The user's recording saves first; the model's, second, never finishes.
      val hangsOnTheModelsAudio = SaveHookArtifactService {
        if (it == 1) withContext(NonCancellable) { delay(60.seconds) }
      }
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      // Act: the caller answers the task's speech by voice, and the task then finishes.
      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          artifactService = hangsOnTheModelsAudio,
        ) { event ->
          if (event.hasAudio()) {
            queue.sendRealtime(RealtimeInput.Audio(Blob("audio/pcm", data = ByteArray(8))))
          }
        }

      assertEquals(mapOf("result" to "memo"), events.mapNotNull { it.output }.single())
      assertEquals(listOf("user"), events.recordings().map { it.author })
    }

  @Test
  fun taskNode_startedByRunNode_finishesWhileATurnsRecordingSaves_keepsTheRecording() =
    runBlocking {
      // Arrange: the task's turn completes and saves its audio slowly; the caller's reply draws
      // finish_task, which ends the round while that save still runs.
      val model = LiveNodeModel("writer", listOf(listOf(audio(), finishTask("result" to "memo"))))
      val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
      val slowStore = SaveHookArtifactService { delay(200) }
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          artifactService = slowStore,
        ) { event ->
          if (event.hasAudio()) queue.sendContent(userMessage("done"))
        }

      // Assert: the recording is out once, authored by the task, and the finish is still last.
      assertEquals(listOf("writer"), events.recordings().map { it.author })
      val scope = events.first { it.author == "writer" }.isolationScope
      assertEquals(
        listOf("finish_task"),
        events.last { it.isolationScope == scope }.functionResponses().map { it.name },
      )
    }

  @Test
  fun taskNode_cancelledWithAStreamAndAudio_storesThemInItsScopeWithTheRunsMetadata() =
    runBlocking {
      // Arrange: the task speaks and starts a stream; a sibling node then fails, cancelling it.
      val streaming = CompletableDeferred<Unit>()
      val script = listOf(audio(), modelFunctionCall("monitor", emptyMap()))
      val writer =
        LlmAgent(
          name = "writer",
          model = LiveNodeModel("writer", listOf(script), completesTurns = false),
          tools =
            listOf(
              DummyTool("monitor") { _, _ ->
                flow<String> {
                  streaming.complete(Unit)
                  awaitCancellation()
                }
              }
            ),
          mode = AgentMode.TASK,
        )
      val failing =
        node<Any?, Any?>("failing") { _, _ ->
          streaming.await()
          error("sibling failed")
        }
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("write"))

      // Act: answering the speech brings the call.
      val unused =
        assertFailsWith<IllegalStateException> {
          runLiveWorkflow(
            Workflow("wf", listOf(Edge(Start, started(writer)), Edge(Start, failing))),
            queue,
            runConfig =
              RunConfig(
                customMetadata = mapOf("tag" to "x"),
                live = LiveRunConfig(saveLiveBlob = true),
              ),
            sessionService = sessions,
            plugins = listOf(MarkingPlugin),
          ) { event ->
            if (event.author == "writer" && event.hasAudio()) {
              queue.sendContent(userMessage("go on"))
            }
          }
        }

      // Assert: the cancelled task's audio and stream ending are stored where its call is.
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      assertContains(lastMonitorAnswer(stored), "stopped streaming")
      val callScope = stored.single { it.functionCalls().isNotEmpty() }.isolationScope
      assertNotNull(callScope)
      val recording = stored.recordings().single()
      assertEquals("writer", recording.author)
      assertEquals(callScope, recording.isolationScope)
      assertEquals("x", recording.customMetadata?.get("tag"))
      assertEquals(true, recording.customMetadata?.get(MarkingPlugin.KEY))
    }

  @Test
  fun taskNode_cancelledWhileItsRoundEndSaves_keepsTheRecording(): Unit = runBlocking {
    // Arrange: the task speaks without completing the turn, then finishes; its round's end saves
    // the audio slowly, and a sibling node fails during that save, cancelling the task.
    val script = listOf(audio(), finishTask("result" to "memo"))
    val model = LiveNodeModel("writer", listOf(script), completesTurns = false)
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    val saving = CompletableDeferred<Unit>()
    val slowStore = SaveHookArtifactService {
      saving.complete(Unit)
      delay(500)
    }
    val failing =
      node<Any?, Any?>("failing") { _, _ ->
        saving.await()
        error("sibling failed")
      }
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: answering the speech brings the finish.
    val unused =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)), Edge(Start, failing))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          sessionService = sessions,
          artifactService = slowStore,
        ) { event ->
          if (event.author == "writer" && event.hasAudio()) queue.sendContent(userMessage("go on"))
        }
      }

    // Assert: the save the cancel cut into still lands, once, in the task's scope.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val recording = stored.recordings().single()
    assertEquals("writer", recording.author)
    assertNotNull(recording.isolationScope)
  }

  @Test
  fun taskNode_cancelledWhileAnEarlierTurnsRecordingSaves_keepsTheRecording(): Unit = runBlocking {
    // Arrange: the task's turn completes and starts saving its audio slowly; a sibling node then
    // fails, cancelling the task while that save runs.
    val model = LiveNodeModel("writer", listOf(listOf(audio())))
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    val saving = CompletableDeferred<Unit>()
    val slowStore = SaveHookArtifactService {
      saving.complete(Unit)
      delay(500)
    }
    val failing =
      node<Any?, Any?>("failing") { _, _ ->
        saving.await()
        error("sibling failed")
      }
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act:
    val unused =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)), Edge(Start, failing))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          sessionService = sessions,
          artifactService = slowStore,
        )
      }

    // Assert: the earlier turn's recording is still stored, in the task's scope.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val recording = stored.recordings().single()
    assertEquals("writer", recording.author)
    assertNotNull(recording.isolationScope)
  }

  @Test
  fun taskNode_startedByRunNode_failingAfterSpeaking_keepsItsAudioFromTheNextTask() = runBlocking {
    // Arrange: the first task speaks, then fails; the node goes on to a second task.
    val first =
      LlmAgent(
        name = "first",
        model = LiveNodeModel("first", listOf(listOf(audio(), GO_AWAY)), completesTurns = false),
        mode = AgentMode.TASK,
      )
    val second =
      LlmAgent(
        name = "second",
        model = LiveNodeModel("second", listOf(listOf(finishTask("result" to "done")))),
        mode = AgentMode.TASK,
      )
    val orchestrator =
      node<Any?, Any?>("orchestrator", rerunOnResume = true) { context, input ->
        try {
          context.runNode(first, nodeInput = input)
        } catch (e: DynamicNodeFailedException) {
          // The second task takes over.
        }
        context.runNode(second, nodeInput = "take over", useAsOutput = true)
      }
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act:
    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, orchestrator))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
        sessionService = sessions,
      )

    // Assert: the failed task's audio is saved as its own, not as the second task's.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    assertEquals(listOf("first"), stored.recordings().map { it.author })
  }

  @Test
  fun taskNode_startedByRunNode_speaksAsItCallsATool_storesTheCallWithoutTheAudio() = runBlocking {
    // Arrange: one model frame carries both speech and a tool call, as Gemini Live can send.
    val speakingCall =
      Content(
        role = Role.MODEL,
        parts = audio().parts + Part(functionCall = FunctionCall("lookup", emptyMap(), id = "c1")),
      )
    val model =
      LiveNodeModel("writer", listOf(listOf(speakingCall, finishTask("result" to "memo"))))
    val writer =
      LlmAgent(
        name = "writer",
        model = model,
        tools = listOf(DummyTool("lookup") { _, _ -> "found" }),
        mode = AgentMode.TASK,
      )
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act:
    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(writer)))),
        queue,
        sessionService = sessions,
      )

    // Assert: the call is stored beside its answer, and the audio stays out of the session.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    val calls = stored.filter { it.author == "writer" }.flatMap { it.functionCalls() }
    assertEquals(listOf("lookup", "finish_task"), calls.map { it.name })
    val answers = stored.flatMap { it.functionResponses() }
    assertEquals(listOf("lookup", "finish_task"), answers.map { it.name })
    assertEquals(emptyList(), stored.filter { it.hasAudio() })
  }

  @Test
  fun taskNode_startedByRunNode_keepsItsLiveModelsAudioOutOfTheSession() = runBlocking {
    val model = LiveNodeModel("writer", listOf(listOf(audio(), finishTask("result" to "memo"))))
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: the caller answers the task's speech, and the task then finishes.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(writer)))),
        queue,
        sessionService = sessions,
      ) { event ->
        if (event.hasAudio()) queue.sendContent(userMessage("done"))
      }

    // Assert: the caller hears the speech, but the session keeps it out, as for a live agent.
    assertTrue(events.any { it.hasAudio() })
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    assertEquals(emptyList(), stored.filter { it.hasAudio() })
  }

  @Test
  fun taskNode_startedByRunNode_callbackTransfersOnAPartialAudioFrame_recordsItsAudioOnly() =
    runBlocking {
      // Arrange: the model's only frame mixes speech text and audio, so it is partial.
      val partialAudioModel =
        object : Model {
          override val name = "writer"

          override suspend fun connect(request: LlmRequest): LiveConnection =
            object : SingleCollectorLiveConnection() {
              override fun responses(): Flow<LlmResponse> =
                flowOf(
                  LlmResponse(
                    content = audio(),
                    outputTranscription = Transcription(text = "hi", finished = false),
                    partial = true,
                  )
                )

              override suspend fun sendHistory(history: List<Content>) {}

              override suspend fun sendContent(content: Content, partial: Boolean) {}

              override suspend fun sendRealtime(input: RealtimeInput) {}

              override suspend fun closeSession() {}
            }

          override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
            flow {}
        }
      val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("ok"))))
      val helper = LlmAgent(name = "helper", model = helperModel, description = "Helps.")
      // A callback's transfer on that frame makes the round store it whole, as the handoff.
      val writer =
        LlmAgent(
          name = "writer",
          model = partialAudioModel,
          subAgents = listOf(helper),
          afterModelCallbacks =
            listOf(
              AfterModelCallback { context, response ->
                context.actions.transferToAgent = "helper"
                response
              }
            ),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("go"))

      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(writer)))),
          queue,
          sessionService = sessions,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          resumable = true,
        ) { event ->
          if (event.contentText() == "ok") queue.close()
        }

      // Assert: the caller gets the whole handoff with its audio, which is recorded as an artifact
      // and kept out of the session.
      assertTrue(
        events.any { it.hasAudio() && !it.partial && it.actions.transferToAgent == "helper" }
      )
      assertEquals(listOf("writer"), events.recordings().map { it.author })
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      assertEquals(emptyList(), stored.filter { it.hasAudio() })
    }

  @Test
  fun taskNode_startedByRunNode_callbackTransfersOnAnAudioFrame_storesTheHandoffForAResume() =
    runBlocking {
      // Arrange: the model's only frame mixes speech text and audio, and a callback hands off on
      // it.
      var writerCalls = 0
      val audioModel =
        object : Model {
          override val name = "writer"

          override suspend fun connect(request: LlmRequest): LiveConnection {
            writerCalls++
            return object : SingleCollectorLiveConnection() {
              override fun responses(): Flow<LlmResponse> =
                flowOf(
                  LlmResponse(
                    content = audio(),
                    outputTranscription = Transcription(text = "hi", finished = false),
                    partial = true,
                  )
                )

              override suspend fun sendHistory(history: List<Content>) {}

              override suspend fun sendContent(content: Content, partial: Boolean) {}

              override suspend fun sendRealtime(input: RealtimeInput) {}

              override suspend fun closeSession() {}
            }
          }

          override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
            writerCalls++
            return flow {}
          }
        }
      val helper =
        LlmAgent(
          name = "helper",
          model = DummyModel("helper") { flowOf(LlmResponse(content = modelMessage("helped"))) },
          description = "Helps.",
        )
      val writer =
        LlmAgent(
          name = "writer",
          model = audioModel,
          subAgents = listOf(helper),
          afterModelCallbacks =
            listOf(
              AfterModelCallback { context, response ->
                context.actions.transferToAgent = "helper"
                response
              }
            ),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val app =
        App(
          appName = "app",
          rootNode = Workflow("wf", listOf(Edge(Start, started(writer)))),
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      val runner = InMemoryRunner(app = app, sessionService = sessions)
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("go"))
      // The caller stops at the handoff, before the target runs.
      val handoff =
        withTimeout(30.seconds) {
          runner.runLive("user", "live", queue).first { it.actions.transferToAgent == "helper" }
        }

      // Act: resume the interrupted invocation.
      val resumed =
        withTimeout(30.seconds) {
          runner
            .runAsync("user", "live", invocationId = handoff.invocationId, newMessage = null)
            .toList()
        }

      // Assert: the handoff is stored without its audio, so the resume runs the target, not the
      // task again.
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      val storedHandoff = stored.single { it.id == handoff.id }
      assertEquals("helper", storedHandoff.actions.transferToAgent)
      assertFalse(storedHandoff.hasAudio())
      assertEquals(listOf("helped"), resumed.filter { it.author == "helper" }.replies())
      assertEquals(1, writerCalls)
    }

  @Test
  fun taskNode_startedByRunNode_transferLeavingUserAudioUnsaved_isStillEndedByTheTask() =
    runBlocking {
      // Arrange: the caller only speaks, and the task transfers before its turn completes.
      val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))))
      val helper = LlmAgent(name = "helper", model = helperModel, description = "Helps.")
      val routerModel =
        LiveNodeModel(
          "router",
          listOf(listOf(modelFunctionCall("transfer_to_agent", mapOf("agent_name" to "helper")))),
          completesTurns = false,
        )
      val router =
        LlmAgent(
          name = "router",
          model = routerModel,
          subAgents = listOf(helper),
          mode = AgentMode.TASK,
        )
      val queue = LiveRequestQueue()
      queue.sendRealtime(RealtimeInput.Audio(Blob("audio/pcm", data = ByteArray(8))))

      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(router)))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          resumable = true,
        ) { event ->
          if (event.contentText() == "helped") queue.close()
        }

      // Assert: the user's audio is saved under the user, and the task still ends its own run.
      assertEquals(listOf("user"), events.recordings().map { it.author })
      assertEquals(1, events.count { it.author == "router" && it.actions.endOfAgent })
    }

  @Test
  fun taskNode_startedByRunNode_liveTransfer_endsTheTaskAndRunsTheTarget() = runBlocking {
    // Arrange: the live task hears the user, then hands them to a chat sub-agent, which answers.
    val log = mutableListOf<String>()
    val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))), log = log)
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

    // Act: the user hangs up once the helper has answered.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, started(router)))),
        queue,
        resumable = true,
      ) { event ->
        if (event.contentText() == "helped") queue.close()
      }

    // Assert: the transfer closes the task's connection and marks the task done for a resume.
    assertEquals(listOf("router connect", "router close", "helper connect", "helper close"), log)
    assertEquals(emptyList(), routerModel.connections.single().receivedResponses())
    assertEquals(listOf("helped"), events.filter { it.author == "helper" }.replies())
    assertEquals(1, events.count { it.author == "router" && it.actions.endOfAgent })
    // What the task heard is the user's own turn, so the target hears it too.
    assertContains(helperModel.connections.single().receivedTexts(), "I need help")
  }

  @Test
  fun taskNode_startedByRunNode_backgroundAnswerTransfer_endsTheTaskAndRunsTheTarget() {
    runTest(BackgroundToolCalls) {
      // Arrange: the live task calls a background tool whose answer hands the user to a helper.
      val log = mutableListOf<String>()
      val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))), log = log)
      val helper = LlmAgent(name = "helper", model = helperModel, description = "Helps.")
      val routerModel =
        LiveNodeModel("router", listOf(listOf(modelFunctionCall("route", emptyMap()))), log = log)
      val router =
        LlmAgent(
          name = "router",
          model = routerModel,
          subAgents = listOf(helper),
          tools = listOf(nonBlockingTool("route", transferTo = "helper") { "routed" }),
          mode = AgentMode.TASK,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("help me"))

      // Act: the user hangs up once the helper has answered.
      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(router)))),
          queue,
          resumable = true,
          sessionService = sessions,
        ) { event ->
          if (event.contentText() == "helped") queue.close()
        }

      // Assert: the round ends at the answer, the helper runs once, and nothing waits on time.
      assertEquals(listOf("router connect", "router close", "helper connect", "helper close"), log)
      assertEquals(emptyList(), routerModel.connections.single().receivedResponses())
      assertEquals(1, events.count { it.author == "router" && it.actions.endOfAgent })
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      val answers = stored.flatMap { it.functionResponses() }.filter { it.name == "route" }
      assertEquals(listOf(mapOf("result" to "routed")), answers.map { it.response })
      assertEquals(0L, testScheduler.currentTime)
    }
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
  fun taskNodes_startedByRunNode_laterTaskStartsAfterAnEarlierOneIsCancelled() = runBlocking {
    // Arrange: the first task never finishes; the second finishes on its opening turn.
    val log = mutableListOf<String>()
    val waiterModel = LiveNodeModel("waiter", listOf(emptyList()), log = log)
    val writerModel =
      LiveNodeModel("writer", listOf(listOf(finishTask("result" to "memo"))), log = log)
    val waiter = LlmAgent(name = "waiter", model = waiterModel, mode = AgentMode.TASK)
    val writer = LlmAgent(name = "writer", model = writerModel, mode = AgentMode.TASK)
    val starter =
      node<Any?, Any?>("starter", rerunOnResume = true) { context, _ ->
        coroutineScope {
          val waiting = launch { context.runNode(waiter) }
          waiterModel.connected.await()
          // The writer starts while the cancelled waiter may still be unwinding.
          waiting.cancel()
          context.runNode(writer, nodeInput = "write")
        }
      }

    val events = runLiveWorkflow(Workflow("wf", listOf(Edge(Start, starter))), LiveRequestQueue())

    // Assert: the cancelled task gave up the run, so the next one connected and finished.
    assertEquals(
      setOf("waiter connect", "waiter close", "writer connect", "writer close"),
      log.toSet(),
    )
    assertContains(events.mapNotNull { it.output }, mapOf("result" to "memo"))
  }

  @Test
  fun taskNodes_startedByRunNodeAtOnce_secondFailsBeforeItConnects() = runBlocking {
    // Arrange: neither task can finish without input, so the first keeps the run.
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

    // Assert: both tasks are named, the caller gets an error event, and only one task connected.
    val message = failure.message.orEmpty()
    assertContains(message, "left")
    assertContains(message, "right")
    assertEquals(listOf(failure.message), errors.map { it.errorMessage })
    val connections = leftModel.connections + rightModel.connections
    assertEquals(1, connections.size)
    assertTrue(connections.single().closed)
  }

  @Test
  fun taskNode_inTheGraph_runsLiveAndHandsItsFinishArgumentsOn() = runBlocking {
    // Arrange: the task asks once, then finishes on the user's second message.
    val model =
      LiveNodeModel(
        "writer",
        listOf(listOf(modelMessage("Which topic?"), finishTask("result" to "memo"))),
      )
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
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
  fun taskNodes_inTheGraphAroundAPlainNode_eachReadsOnlyItsOwnInput() = runBlocking {
    // Arrange: each task acknowledges every input but its last, and finishes on the last.
    val log = mutableListOf<String>()
    val firstModel =
      LiveNodeModel("first", listOf(ackThenFinish("first start", result = "first")), log = log)
    val secondModel =
      LiveNodeModel("second", listOf(ackThenFinish("plain", result = "second")), log = log)
    val first = LlmAgent(name = "first", model = firstModel, mode = AgentMode.TASK)
    val plain = CaptureInput("plain")
    val second = LlmAgent(name = "second", model = secondModel, mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first start"))
    queue.sendContent(userMessage("first end"))

    // Act: the caller speaks to the second task while the plain node is still emitting its output.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, first), Edge(first, plain), Edge(plain, second))),
        queue,
      ) { event ->
        if (event.output == mapOf("received" to mapOf("result" to "first"))) {
          queue.sendRealtime(audioSaying("second end"))
        }
      }

    // Assert: the plain node's output is the second task's opening turn.
    assertEquals(
      listOf(
        mapOf("result" to "first"),
        mapOf("received" to mapOf("result" to "first")),
        mapOf("result" to "second"),
      ),
      events.mapNotNull { it.output },
    )
    assertEquals(
      listOf("first start", "first end"),
      firstModel.connections.single().receivedTexts(),
    )
    val secondConnection = secondModel.connections.single()
    assertEquals(listOf("""{"received":{"result":"first"}}"""), secondConnection.receivedTexts())
    assertEquals(listOf("second end"), secondConnection.receivedAudio)
    assertEquals(listOf("first connect", "first close", "second connect", "second close"), log)
  }

  @Test
  fun taskNode_inANestedWorkflow_runsLive() = runBlocking {
    val model = LiveNodeModel("inner_task", listOf(ackThenFinish("inner start", result = "inner")))
    val task = LlmAgent(name = "inner_task", model = model, mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("inner start"))
    queue.sendContent(userMessage("inner end"))

    val events =
      runLiveWorkflow(
        Workflow("outer", listOf(Edge(Start, Workflow("inner", listOf(Edge(Start, task)))))),
        queue,
      )

    assertEquals(listOf(mapOf("result" to "inner")), events.mapNotNull { it.output })
    assertEquals(listOf("Acknowledged: inner start"), events.replies())
    assertEquals(listOf("inner start", "inner end"), model.connections.single().receivedTexts())
  }

  @Test
  fun taskNodes_inANestedWorkflowAndAfterIt_eachReadsOnlyItsOwnInput() = runBlocking {
    // Arrange: a task in a nested workflow, then a plain node, then a task in the outer workflow.
    val innerModel =
      LiveNodeModel("inner_task", listOf(ackThenFinish("inner start", result = "inner")))
    val outerModel = LiveNodeModel("outer_task", listOf(ackThenFinish("prep", result = "outer")))
    val inner =
      Workflow(
        "inner",
        listOf(
          Edge(Start, LlmAgent(name = "inner_task", model = innerModel, mode = AgentMode.TASK))
        ),
      )
    val prep = node<Any?, String>("prep") { _, _ -> "prep" }
    val outerTask = LlmAgent(name = "outer_task", model = outerModel, mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("inner start"))
    queue.sendContent(userMessage("inner end"))

    // Act: the outer task's message is sent only once the plain node has run.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, inner), Edge(inner, prep), Edge(prep, outerTask))),
        queue,
      ) { event ->
        if (event.output == "prep") queue.sendContent(userMessage("outer end"))
      }

    assertEquals(
      listOf(mapOf("result" to "inner"), "prep", mapOf("result" to "outer")),
      events.mapNotNull { it.output },
    )
    assertEquals(
      listOf("inner start", "inner end"),
      innerModel.connections.single().receivedTexts(),
    )
    assertEquals(listOf("prep", "outer end"), outerModel.connections.single().receivedTexts())
  }

  @Test
  fun taskNode_reenteredByACappedRetryEdge_runsLiveAgainOnTheRetryInputAlone() = runBlocking {
    // Arrange: intake's first request is unclear, so classify sends it back once, at most.
    val log = mutableListOf<String>()
    val model =
      LiveNodeModel(
        "intake",
        listOf(
          listOf(finishTask("result" to "um")),
          listOf(modelMessage("Sorry, what was it?"), finishTask("result" to "move my visit")),
        ),
        log = log,
      )
    val intake = LlmAgent(name = "intake", model = model, mode = AgentMode.TASK)
    var asks = 0
    val classify =
      node<Any?, Any?>("classify") { context, input ->
        val said = (input as Map<*, *>)["result"].toString()
        if ("move" !in said && ++asks < 2) {
          context.routes = listOf(Route.Tag("RETRY"))
          "Not understood. Ask again."
        } else {
          context.routes = listOf(Route.Tag("DONE"))
          said
        }
      }
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val handle = SessionResumptionConfig(handle = "h-1")

    // Act: the caller answers the second run of intake only once classify has sent it back.
    val events =
      runLiveWorkflow(
        retryLoop(intake, classify),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(sessionResumption = handle)),
      ) { event ->
        if (event.output == "Not understood. Ask again.") {
          queue.sendContent(userMessage("move my visit"))
        }
      }

    assertEquals(
      listOf(
        mapOf("result" to "um"),
        "Not understood. Ask again.",
        mapOf("result" to "move my visit"),
        "move my visit",
        mapOf("received" to "move my visit"),
      ),
      events.mapNotNull { it.output },
    )
    // Assert: each run talked on its own connection, the second one without the first one's turns.
    assertEquals(listOf("intake connect", "intake close", "intake connect", "intake close"), log)
    assertEquals(listOf("hello"), model.connections[0].receivedTexts())
    assertEquals(
      listOf("Not understood. Ask again.", "move my visit"),
      model.connections[1].receivedTexts(),
    )
    // Each run has its own path and scope, and only the first session resumes the caller's.
    assertEquals(
      setOf("wf@1/intake@1", "wf@1/intake@2").map { it to it }.toSet(),
      events.filter { it.author == "intake" }.map { it.nodeInfo?.path to it.isolationScope }.toSet(),
    )
    assertEquals(
      listOf("h-1", null),
      model.requests.map { it.liveConnectConfig.sessionResumption?.handle },
    )
  }

  @Test
  fun taskNode_reenteredUntilTheRetryCap_endsThroughTheExitEdge() = runBlocking {
    // Arrange: intake never hears a clear request, so classify sends it back twice, then gives up.
    val model = LiveNodeModel("intake", List(3) { listOf(finishTask("result" to "um")) })
    val intake = LlmAgent(name = "intake", model = model, mode = AgentMode.TASK)
    var asks = 0
    val classify =
      node<Any?, Any?>("classify") { context, _ ->
        val retry = ++asks < 3
        context.routes = listOf(Route.Tag(if (retry) "RETRY" else "DONE"))
        if (retry) "Ask again." else "out of scope"
      }
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))

    val events = runLiveWorkflow(retryLoop(intake, classify), queue)

    val um = mapOf("result" to "um")
    assertEquals(
      listOf(
        um,
        "Ask again.",
        um,
        "Ask again.",
        um,
        "out of scope",
        mapOf("received" to "out of scope"),
      ),
      events.mapNotNull { it.output },
    )
    assertEquals(
      listOf(listOf("hello"), listOf("Ask again."), listOf("Ask again.")),
      model.connections.map { it.receivedTexts() },
    )
    assertTrue(model.connections.all { it.closed })
  }

  @Test
  fun taskNode_inTheGraph_callerHangsUpBeforeItFinishes_endsTheRunWithoutAnOutput() = runBlocking {
    val model = LiveNodeModel("writer", listOf(listOf(modelMessage("Which topic?"))))
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("write"))

    // Act: the caller hangs up on the task's question.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, writer), Edge(writer, CaptureInput("capture")))),
        queue,
      ) { event ->
        if (event.contentText() == "Which topic?") queue.close()
      }

    // Assert: the task produced no output, so the next node never ran.
    assertEquals(emptyList(), events.mapNotNull { it.output })
    assertTrue(model.connections.single().closed)
  }

  @Test
  fun taskNode_inTheGraph_nextResumableRunAfterAHangUp_restartsTheGraph() = runBlocking {
    // Arrange: the caller hangs up on the task's question, then calls again on the same session.
    val model =
      LiveNodeModel(
        "writer",
        listOf(listOf(modelMessage("Which topic?")), listOf(finishTask("result" to "cats"))),
      )
    val writer = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    var prepRuns = 0
    val prep =
      node<Any?, Any?>("prep") { _, _ ->
        prepRuns++
        "go"
      }
    val workflow =
      Workflow(
        "wf",
        listOf(Edge(Start, prep), Edge(prep, writer), Edge(writer, CaptureInput("capture"))),
      )
    val sessions = InMemorySessionService()
    val firstCall = LiveRequestQueue()
    firstCall.sendContent(userMessage("write"))
    val firstEvents =
      runLiveWorkflow(workflow, firstCall, resumable = true, sessionService = sessions) { event ->
        if (event.contentText() == "Which topic?") firstCall.close()
      }
    assertEquals(listOf("go"), firstEvents.mapNotNull { it.output })
    val secondCall = LiveRequestQueue()
    secondCall.sendContent(userMessage("cats"))

    val events = runLiveWorkflow(workflow, secondCall, resumable = true, sessionService = sessions)

    // Assert: the new invocation runs the graph from its start, and the task finishes this time.
    assertEquals(2, prepRuns)
    assertEquals(
      listOf("go", mapOf("result" to "cats"), mapOf("received" to mapOf("result" to "cats"))),
      events.mapNotNull { it.output },
    )
    assertEquals(0, events.count { it.author == "writer" && it.actions.endOfAgent })
    // A task's scope is its node path, so a new call on the session continues its conversation.
    assertEquals(
      listOf("go", "write", "Which topic?", "cats"),
      model.connections[1].receivedTexts(),
    )
  }

  @Test
  fun taskNode_inAParallelNodeOverTwoItems_secondItemFailsBeforeItConnects() = runBlocking {
    // Arrange: the first item cannot finish without input, so it keeps the run's live node.
    val model = LiveNodeModel("writer", listOf(emptyList()))
    val writer = parallelNode(LlmAgent(name = "writer", model = model, mode = AgentMode.TASK))
    val items = node<Any?, List<String>>("items") { _, _ -> listOf("a", "b") }

    val failure =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, items), Edge(items, writer))),
          LiveRequestQueue(),
        )
      }

    // Assert: a live run runs one live node at a time, so only the first item connected.
    assertContains(failure.message.orEmpty(), "one live node at a time")
    assertTrue(model.connections.single().closed)
  }

  @Test
  fun taskNode_inAParallelNodeWithOneWorker_runsEachItemLiveInTurn() = runBlocking {
    val model =
      LiveNodeModel(
        "writer",
        listOf(listOf(finishTask("result" to "A")), listOf(finishTask("result" to "B"))),
      )
    val task = LlmAgent(name = "writer", model = model, mode = AgentMode.TASK)
    val writer = parallelNode(task, maxParallelWorkers = 1)
    val items = node<Any?, List<String>>("items") { _, _ -> listOf("a", "b") }

    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, items), Edge(items, writer))),
        LiveRequestQueue(),
      )

    val (a, b) = listOf(mapOf("result" to "A"), mapOf("result" to "B"))
    assertEquals(listOf(listOf("a", "b"), a, b, listOf(a, b)), events.mapNotNull { it.output })
    assertEquals(listOf(listOf("a"), listOf("b")), model.connections.map { it.receivedTexts() })
    assertTrue(model.connections.all { it.closed })
  }

  @Test
  fun taskNodes_aroundARouter_routeOnTheFirstTasksCheckedOutput() = runBlocking {
    // Arrange: confirm first finishes without its answer, hears the error, and answers again.
    val log = mutableListOf<String>()
    val confirmModel =
      LiveNodeModel(
        "confirm",
        listOf(listOf(finishTask(), finishTask("answer" to "YES"))),
        log = log,
      )
    val answer = Schema(type = Type.STRING, enum = listOf("YES", "NO"))
    val confirm =
      LlmAgent(
        name = "confirm",
        model = confirmModel,
        mode = AgentMode.TASK,
        outputSchema =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("answer" to answer),
            required = listOf("answer"),
          ),
      )
    val route =
      node<Any?, Any?>("route") { context, input ->
        context.routes = listOf(Route.Tag((input as Map<*, *>)["answer"].toString()))
        "slot 2"
      }
    val bookModel =
      LiveNodeModel("book", listOf(listOf(finishTask("result" to "booked"))), log = log)
    val book = LlmAgent(name = "book", model = bookModel, mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("yes"))

    val events =
      runLiveWorkflow(
        Workflow(
          "wf",
          listOf(
            Edge(Start, confirm),
            Edge(confirm, route),
            Edge(route, book, Route.Tag("YES")),
            Edge(route, CaptureInput("kept"), Route.Tag("NO")),
          ),
        ),
        queue,
      )

    // Assert: only the rejected call was answered, and the router took YES from the checked output.
    val answers = confirmModel.connections.single().receivedResponses()
    assertEquals(listOf("finish_task"), answers.map { it.name })
    assertContains(answers.single().response["error"].toString(), "validation errors")
    assertEquals(
      listOf(mapOf("answer" to "YES"), "slot 2", mapOf("result" to "booked")),
      events.mapNotNull { it.output },
    )
    assertEquals(listOf("slot 2"), bookModel.connections.single().receivedTexts())
    assertEquals(listOf("confirm connect", "confirm close", "book connect", "book close"), log)
  }

  @Test
  fun taskNode_inTheGraph_liveTransfer_failsOnceItsConnectionCloses() = runBlocking {
    // Arrange: the task hands the user to a chat sub-agent, which a graph node can't run live yet.
    val log = mutableListOf<String>()
    val helperModel = LiveNodeModel("helper", listOf(listOf(modelMessage("helped"))), log = log)
    val routerModel =
      LiveNodeModel(
        "router",
        listOf(listOf(modelFunctionCall("transfer_to_agent", mapOf("agent_name" to "helper")))),
        log = log,
      )
    val router =
      LlmAgent(
        name = "router",
        model = routerModel,
        subAgents = listOf(LlmAgent(name = "helper", model = helperModel, description = "Helps.")),
        mode = AgentMode.TASK,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("help me"))

    val failure =
      assertFailsWith<UnsupportedOperationException> {
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, router))), queue)
      }

    assertEquals("Agent node 'router' cannot transfer in a live run yet.", failure.message)
    assertEquals(listOf("router connect", "router close"), log)
  }

  @Test
  fun chatNode_liveRun_talksUntilTheQueueCloses() = runBlocking {
    val model = LiveNodeModel("greeter", listOf(listOf(modelMessage("Hi!"), modelMessage("Bye!"))))
    val greeter = LlmAgent(name = "greeter", model = model, mode = AgentMode.CHAT)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))

    // Act: the user answers the first reply, then hangs up after the second.
    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, greeter))), queue) { event ->
        when (event.contentText()) {
          "Hi!" -> queue.sendContent(userMessage("goodbye"))
          "Bye!" -> queue.close()
        }
      }

    assertEquals(listOf("Hi!", "Bye!"), events.filter { it.author == "greeter" }.replies())
    val connection = model.connections.single()
    assertEquals(listOf("hello", "goodbye"), connection.receivedTexts())
    assertTrue(connection.closed)
    assertEquals(emptyList(), events.mapNotNull { it.output })
  }

  @Test
  fun chatNode_inANestedWorkflow_talksLiveUntilTheQueueCloses() = runBlocking {
    val model = LiveNodeModel("greeter", listOf(listOf(modelMessage("Hi!"), modelMessage("Bye!"))))
    val greeter = LlmAgent(name = "greeter", model = model, mode = AgentMode.CHAT)
    val inner = Workflow("inner", listOf(Edge(Start, greeter)))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))

    // Act: the user answers the first reply, then hangs up after the second.
    val events =
      runLiveWorkflow(Workflow("outer", listOf(Edge(Start, inner))), queue) { event ->
        when (event.contentText()) {
          "Hi!" -> queue.sendContent(userMessage("goodbye"))
          "Bye!" -> queue.close()
        }
      }

    assertEquals(listOf("Hi!", "Bye!"), events.replies())
    assertEquals(listOf("hello", "goodbye"), model.connections.single().receivedTexts())
    assertTrue(model.connections.single().closed)
  }

  @Test
  fun chatNode_liveTaskDelegation_closesItsConnectionWhileTheTaskRuns() = runBlocking {
    // Arrange: the chat delegates the booking; the task asks for a date and finishes on the answer.
    val log = mutableListOf<String>()
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?"), finishTask("result" to "booked Friday"))),
        log = log,
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book a trip"))),
          listOf(modelMessage("Booked.")),
        ),
        log = log,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("plan a trip"))

    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
      ) { event ->
        when (event.contentText()) {
          "Which date?" -> queue.sendContent(userMessage("Friday"))
          "Booked." -> queue.close()
        }
      }

    // Assert: the task is the queue's only reader, and the chat resumes on a new connection.
    assertEquals(
      listOf(
        "planner connect",
        "planner close",
        "booker connect",
        "booker close",
        "planner connect",
        "planner close",
      ),
      log,
    )
    assertEquals("Friday", taskModel.connections.single().receivedTexts().last())
    // The chat's new connection does not hear the user's reply to its task.
    assertEquals(listOf("plan a trip"), chatModel.connections[1].receivedTexts())
    val answer = chatModel.connections[1].receivedResponses().single()
    assertEquals("booker", answer.name)
    assertEquals(mapOf("result" to "booked Friday"), answer.response)
    assertEquals(
      "Booked.",
      events.last { it.author == "planner" && it.content != null }.contentText(),
    )
  }

  // A node records no answer for a background call its round's end stopped, so none ever comes.
  @Test
  fun chatNode_liveTaskDelegationWhileABackgroundCallRuns_leavesTheCallOutOfTheNextRound() {
    runBlocking(BackgroundToolCalls) {
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))))
      val chatModel =
        LiveNodeModel(
          "planner",
          listOf(
            listOf(
              modelFunctionCall("lookup", id = "b1"),
              modelFunctionCall("booker", mapOf("request" to "book a trip")),
            ),
            listOf(modelMessage("Booked.")),
          ),
        )
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          tools = listOf(nonBlockingTool("lookup") { awaitCancellation() }),
          subAgents = listOf(booker(taskModel)),
          mode = AgentMode.CHAT,
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("plan a trip"))

      // Act: the user's next words make the chat delegate, which ends its round.
      val events =
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
          if (event.functionCalls().any { it.name == "lookup" }) {
            queue.sendContent(userMessage("book it"))
          }
          if (event.contentText() == "Booked.") queue.close()
        }

      // Assert: the call is stored, but the chat's next round opens without it.
      assertTrue(events.any { event -> event.functionCalls().any { it.name == "lookup" } })
      val history = chatModel.requests[1].contents.flatMap { it.parts }
      assertEquals(listOf("booker"), history.mapNotNull { it.functionCall?.name })
    }
  }

  @Test
  fun chatNode_startedByRunNode_liveTransferBesideATaskDelegation_handsOverOnceTheTaskAnswers() =
    runBlocking {
      // Arrange: in one turn, the chat delegates the booking and transfers to support.
      val log = mutableListOf<String>()
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book a trip"))),
          Part(functionCall = FunctionCall("transfer_to_agent", mapOf("agent_name" to "support"))),
        )
      val chatModel = LiveNodeModel("planner", listOf(listOf(turn)), log)
      val supportModel = LiveNodeModel("support", listOf(listOf(modelMessage("Hi, support."))), log)
      val support = LlmAgent(name = "support", model = supportModel, description = "Helps.")
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("plan a trip"))

      val events =
        runLiveWorkflow(
          Workflow(
            "wf",
            listOf(Edge(Start, started(planner(chatModel, booker(taskModel), support)))),
          ),
          queue,
        ) { event ->
          if (event.contentText() == "Hi, support.") queue.close()
        }

      // Assert: the task runs first; then the chat hands over without reconnecting, and support
      // talks.
      assertEquals(
        listOf(
          "planner connect",
          "planner close",
          "booker connect",
          "booker close",
          "support connect",
          "support close",
        ),
        log,
      )
      val unused =
        events.flatMap { it.functionResponses() }.single { it.name == "transfer_to_agent" }
      assertEquals(
        "Hi, support.",
        events.last { it.author == "support" && it.content != null }.contentText(),
      )
    }

  @Test
  fun chatNode_startedByRunNode_backgroundAnswerTransferBesideATask_handsOverOnceTheTaskAnswers() {
    runTest(BackgroundToolCalls) {
      // Arrange: one chat turn delegates the booking and calls a tool that hands over to support.
      val log = mutableListOf<String>()
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("route", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book a trip"))),
        )
      val chatModel = LiveNodeModel("planner", listOf(listOf(turn)), log)
      val supportModel = LiveNodeModel("support", listOf(listOf(modelMessage("Hi, support."))), log)
      val support = LlmAgent(name = "support", model = supportModel, description = "Helps.")
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          subAgents = listOf(booker(taskModel), support),
          tools = listOf(nonBlockingTool("route", transferTo = "support") { "routed" }),
          mode = AgentMode.CHAT,
        )
      val sessions = InMemorySessionService()
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("plan a trip"))

      val events =
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(planner)))),
          queue,
          sessionService = sessions,
        ) { event ->
          if (event.contentText() == "Hi, support.") queue.close()
        }

      // Assert: the task runs first, then the chat hands over once, and nothing waits on time.
      assertEquals(
        listOf(
          "planner connect",
          "planner close",
          "booker connect",
          "booker close",
          "support connect",
          "support close",
        ),
        log,
      )
      assertEquals(emptyList(), chatModel.connections.single().receivedResponses())
      val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
      val answers = stored.flatMap { it.functionResponses() }.filter { it.name == "route" }
      assertEquals(listOf(mapOf("result" to "routed")), answers.map { it.response })
      assertEquals(
        "Hi, support.",
        events.last { it.author == "support" && it.content != null }.contentText(),
      )
      assertEquals(0L, testScheduler.currentTime)
    }
  }

  @Test
  fun chatNode_startedByRunNode_liveToolCallbackTransferBesideATask_handsOverAfterTheTask() =
    runBlocking {
      // Arrange: one chat turn looks something up and delegates the booking; a callback on the
      // lookup hands off to support.
      val log = mutableListOf<String>()
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("lookup", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book a trip"))),
        )
      val handOff = AfterToolCallback { context, _, _, result ->
        context.actions.transferToAgent = "support"
        result
      }
      val chatModel = LiveNodeModel("planner", listOf(listOf(turn)), log)

      val events =
        runCallbackHandoffBesideATask(log) { subAgents ->
          LlmAgent(
            name = "planner",
            model = chatModel,
            subAgents = subAgents,
            tools = listOf(DummyTool(name = "lookup", onRun = { _, _ -> "found" })),
            afterToolCallbacks = listOf(handOff),
            mode = AgentMode.CHAT,
          )
        }

      assertHandsOverAfterTheTask(events, log)
    }

  @Test
  fun chatNode_startedByRunNode_liveModelCallbackTransferBesideATask_handsOverAfterTheTask() =
    runBlocking {
      // Arrange: the chat says it books while it delegates, and a callback screening that speech
      // hands off to support.
      val log = mutableListOf<String>()
      val speakingModel =
        object : Model {
          override val name = "planner"

          override suspend fun connect(request: LlmRequest): LiveConnection {
            log += "planner connect"
            return object : SingleCollectorLiveConnection() {
              override fun responses(): Flow<LlmResponse> =
                flowOf(
                  LlmResponse(
                    content = modelFunctionCall("booker", mapOf("request" to "book a trip")),
                    outputTranscription = Transcription(text = "Booking.", finished = false),
                  )
                )

              override suspend fun sendHistory(history: List<Content>) {}

              override suspend fun sendContent(content: Content, partial: Boolean) {}

              override suspend fun sendRealtime(input: RealtimeInput) {}

              override suspend fun closeSession() {
                log += "planner close"
              }
            }
          }

          override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
            flow {}
        }
      val handOff = AfterModelCallback { context, response ->
        context.actions.transferToAgent = "support"
        response
      }

      val events =
        runCallbackHandoffBesideATask(log) { subAgents ->
          LlmAgent(
            name = "planner",
            model = speakingModel,
            subAgents = subAgents,
            afterModelCallbacks = listOf(handOff),
            mode = AgentMode.CHAT,
          )
        }

      assertHandsOverAfterTheTask(events, log)
    }

  @Test
  fun chatNode_startedByRunNode_resumedAfterTheTaskBeforeACallbackHandoff_handsOver() =
    runBlocking {
      // Arrange: a callback on the chat's lookup hands off to support beside a booking task.
      val log = mutableListOf<String>()
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("lookup", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book a trip"))),
        )
      val chatModel = LiveNodeModel("planner", listOf(listOf(turn)), log)
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val supportModel =
        DummyModel("support") { flowOf(LlmResponse(content = modelMessage("Hi, support."))) }
      val support = LlmAgent(name = "support", model = supportModel, description = "Helps.")
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          subAgents = listOf(booker(taskModel), support),
          tools = listOf(DummyTool(name = "lookup", onRun = { _, _ -> "found" })),
          afterToolCallbacks =
            listOf(
              AfterToolCallback { context, _, _, result ->
                context.actions.transferToAgent = "support"
                result
              }
            ),
          mode = AgentMode.CHAT,
        )
      val app =
        App(
          appName = "app",
          rootNode = Workflow("wf", listOf(Edge(Start, started(planner)))),
          resumabilityConfig = ResumabilityConfig(isResumable = true),
        )
      val runner = InMemoryRunner(app = app)
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("plan a trip"))
      // The caller stops once the task answers, before the handoff.
      val answer =
        withTimeout(30.seconds) {
          runner.runLive("user", "live", queue).first { event ->
            event.functionResponses().any { it.name == "booker" }
          }
        }

      // Act: resume the stopped invocation.
      val resumed =
        withTimeout(30.seconds) {
          runner
            .runAsync("user", "live", invocationId = answer.invocationId, newMessage = null)
            .toList()
        }

      // Assert: the chat hands over to support without connecting again.
      assertEquals(
        listOf("planner connect", "planner close", "booker connect", "booker close"),
        log,
      )
      assertEquals(listOf("Hi, support."), resumed.filter { it.author == "support" }.replies())
    }

  @Test
  fun chatNode_liveInputSentOnTheDelegation_reachesTheTask() = runBlocking {
    // Arrange: the task asks for a date and finishes on the answer.
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?"), finishTask("result" to "booked Friday"))),
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book a trip"))),
          listOf(modelMessage("Booked.")),
        ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("plan a trip"))

    // Act: the caller answers as soon as it sees the chat delegate.
    var chatClosedOnDelegation = false
    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
      ) { event ->
        if (event.functionCalls().any { it.name == "booker" }) {
          chatClosedOnDelegation = chatModel.connections[0].closed
          queue.sendContent(userMessage("Friday"))
        }
        if (event.contentText() == "Booked.") queue.close()
      }

    // Assert: the delegation reached the caller only once the chat had closed its connection.
    assertTrue(chatClosedOnDelegation)
    assertEquals(listOf("plan a trip"), chatModel.connections[0].receivedTexts())
    assertEquals("Friday", taskModel.connections.single().receivedTexts().last())
  }

  @Test
  fun chatNode_liveTurnDelegatingTwoTasks_runsThemOneAfterTheOther() = runBlocking {
    // Arrange: one chat turn delegates two tasks, and each task asks a question before finishing.
    val log = mutableListOf<String>()
    val bookerModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?"), finishTask("result" to "booked"))),
        log = log,
      )
    val payerModel =
      LiveNodeModel(
        "payer",
        listOf(listOf(modelMessage("Which card?"), finishTask("result" to "paid"))),
        log = log,
      )
    val twoDelegations =
      modelMessage(
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
        Part(functionCall = FunctionCall("payer", mapOf("request" to "pay"))),
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(twoDelegations), listOf(modelMessage("Done."))),
        log = log,
      )
    val payer =
      LlmAgent(name = "payer", model = payerModel, description = "Pays.", mode = AgentMode.TASK)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book and pay"))

    // Act: the caller answers each question as it arrives.
    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(bookerModel), payer)))),
        queue,
      ) { event ->
        when (event.contentText()) {
          "Which date?" -> queue.sendContent(userMessage("Friday"))
          "Which card?" -> queue.sendContent(userMessage("Visa"))
          "Done." -> queue.close()
        }
      }

    // Assert: each task reads the queue in turn and hears only its own answer.
    assertEquals(
      listOf(
        "planner connect",
        "planner close",
        "booker connect",
        "booker close",
        "payer connect",
        "payer close",
        "planner connect",
        "planner close",
      ),
      log,
    )
    assertEquals(
      listOf("""{"request":"book"}""", "Friday"),
      bookerModel.connections.single().receivedTexts(),
    )
    assertEquals(
      listOf("""{"request":"pay"}""", "Visa"),
      payerModel.connections.single().receivedTexts(),
    )
    assertEquals(
      listOf("booker", "payer"),
      chatModel.connections[1].receivedResponses().map { it.name },
    )
  }

  @Test
  fun chatNode_queueClosesDuringLiveTaskDelegation_leavesTheDelegationUnanswered() = runBlocking {
    // Arrange: the chat delegates the booking, and the task asks a question.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(modelMessage("Which date?"))), log = log)
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(modelFunctionCall("booker", mapOf("request" to "book a trip")))),
        log = log,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("plan a trip"))

    // Act: the user hangs up while the task waits for an answer.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
      ) { event ->
        if (event.contentText() == "Which date?") queue.close()
      }

    // Assert: the task ends without an output, and the chat leaves the delegation unanswered.
    assertEquals(listOf("planner connect", "planner close", "booker connect", "booker close"), log)
    assertEquals(emptyList(), events.mapNotNull { it.output })
    assertEquals(emptyList(), events.flatMap { it.functionResponses() }.map { it.name })
  }

  @Test
  fun chatNode_nextRunAfterAHangUpDuringATask_resumesTheTask() = runBlocking {
    // Arrange: the first run's task asks a question, and the user hangs up.
    val log = mutableListOf<String>()
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?")), listOf(finishTask("result" to "booked"))),
        log = log,
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book a trip"))),
          listOf(modelMessage("Booked.")),
        ),
        log = log,
      )
    val workflow = Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel)))))
    val sessions = InMemorySessionService()
    val firstQueue = LiveRequestQueue()
    firstQueue.sendContent(userMessage("plan a trip"))
    val unused =
      runLiveWorkflow(workflow, firstQueue, sessionService = sessions) { event ->
        if (event.contentText() == "Which date?") firstQueue.close()
      }
    log.clear()

    // Act: the user calls back with the answer to the task's question.
    val secondQueue = LiveRequestQueue()
    secondQueue.sendContent(userMessage("Friday"))
    val events =
      runLiveWorkflow(workflow, secondQueue, sessionService = sessions) { event ->
        if (event.contentText() == "Booked.") secondQueue.close()
      }

    // Assert: the task picks up where it stopped, and the chat then hears its answer.
    assertEquals(listOf("booker connect", "booker close", "planner connect", "planner close"), log)
    assertEquals("Friday", taskModel.connections[1].receivedTexts().last())
    assertEquals("booker", chatModel.connections[1].receivedResponses().single().name)
    assertEquals(listOf("Booked."), events.filter { it.author == "planner" }.replies())
  }

  @Test
  fun chatNode_nextRunWithItsQueueClosedBeforeItStarts_stillResumesTheTask() = runBlocking {
    // Arrange: the first run hangs up while its task waits for an answer.
    val log = mutableListOf<String>()
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?")), listOf(finishTask("result" to "booked"))),
        log = log,
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(modelFunctionCall("booker", mapOf("request" to "book a trip")))),
        log = log,
      )
    val workflow = Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel)))))
    val sessions = InMemorySessionService()
    val firstQueue = LiveRequestQueue()
    firstQueue.sendContent(userMessage("plan a trip"))
    val unused =
      runLiveWorkflow(workflow, firstQueue, sessionService = sessions) { event ->
        if (event.contentText() == "Which date?") firstQueue.close()
      }
    log.clear()

    // Act: the caller queues the answer and closes the queue before the second run starts.
    val secondQueue = LiveRequestQueue()
    secondQueue.sendContent(userMessage("Friday"))
    secondQueue.close()
    val unusedEvents = runLiveWorkflow(workflow, secondQueue, sessionService = sessions)

    // Assert: the task still resumes and reads the queued answer; the chat opens no round.
    assertEquals(listOf("booker connect", "booker close"), log)
    assertEquals("Friday", taskModel.connections[1].receivedTexts().last())
  }

  @Test
  fun chatNode_hangUpDuringTheTaskTheNextRunResumes_opensNoChatRound() = runBlocking {
    // Arrange: the first run's task asks a question; the user answers it in a second run.
    val log = mutableListOf<String>()
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?")), listOf(finishTask("result" to "booked"))),
        log = log,
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(modelFunctionCall("booker", mapOf("request" to "book a trip")))),
        log = log,
      )
    val workflow = Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel)))))
    val sessions = InMemorySessionService()
    val firstQueue = LiveRequestQueue()
    firstQueue.sendContent(userMessage("plan a trip"))
    val unused =
      runLiveWorkflow(workflow, firstQueue, sessionService = sessions) { event ->
        if (event.contentText() == "Which date?") firstQueue.close()
      }
    log.clear()

    // Act: the user hangs up again once the resumed task has answered.
    val secondQueue = LiveRequestQueue()
    secondQueue.sendContent(userMessage("Friday"))
    val events =
      runLiveWorkflow(workflow, secondQueue, sessionService = sessions) { event ->
        if (event.author == "booker" && event.output != null) secondQueue.close()
      }

    // Assert: the task's answer is stored for the chat, which opens no round after the hang-up.
    assertEquals(listOf("booker connect", "booker close"), log)
    assertTrue(events.any { event -> event.functionResponses().any { it.name == "booker" } })
  }

  @Test
  fun chatNode_hangUpWhileTheNextRunResumesTwoTasks_stillStartsTheSecond() = runBlocking {
    // Arrange: the first run delegates two tasks and hangs up during the first.
    val log = mutableListOf<String>()
    val bookerModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?")), listOf(finishTask("result" to "booked"))),
        log = log,
      )
    val payerModel =
      LiveNodeModel("payer", listOf(listOf(finishTask("result" to "paid"))), log = log)
    val twoDelegations =
      modelMessage(
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
        Part(functionCall = FunctionCall("payer", mapOf("request" to "pay"))),
      )
    val chatModel = LiveNodeModel("planner", listOf(listOf(twoDelegations)), log = log)
    val payer =
      LlmAgent(name = "payer", model = payerModel, description = "Pays.", mode = AgentMode.TASK)
    val workflow =
      Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(bookerModel), payer))))
    val sessions = InMemorySessionService()
    val firstQueue = LiveRequestQueue()
    firstQueue.sendContent(userMessage("book and pay"))
    val unused =
      runLiveWorkflow(workflow, firstQueue, sessionService = sessions) { event ->
        if (event.contentText() == "Which date?") firstQueue.close()
      }
    log.clear()

    // Act: the second run resumes both tasks; the user hangs up once the first has answered.
    val secondQueue = LiveRequestQueue()
    secondQueue.sendContent(userMessage("Friday"))
    val events =
      runLiveWorkflow(workflow, secondQueue, sessionService = sessions) { event ->
        if (event.author == "booker" && event.output != null) secondQueue.close()
      }

    // Assert: the hang-up still lets the second task run, but the chat opens no round after it.
    assertEquals(listOf("booker connect", "booker close", "payer connect", "payer close"), log)
    assertEquals(
      listOf("booker", "payer"),
      events.flatMap { it.functionResponses() }.map { it.name }.filter { it != "finish_task" },
    )
  }

  @Test
  fun chatNode_liveTurnMixingToolAndTask_keepsTheToolAnswerOutOfTheQueue() = runBlocking {
    // Arrange: one chat turn calls a plain tool and delegates the booking, which asks a question.
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(modelMessage("Which date?"), finishTask("result" to "booked"))),
      )
    val mixedTurn =
      modelMessage(
        Part(functionCall = FunctionCall("lookup", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel =
      LiveNodeModel("planner", listOf(listOf(mixedTurn), listOf(modelMessage("Booked."))))
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(DummyTool("lookup", onRun = { _, _ -> mapOf("weather" to "sunny") })),
        mode = AgentMode.CHAT,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    // Act: the caller answers the task's question, then hangs up once the chat replies.
    var chatClosedWhenDelegationSeen: Boolean? = null
    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
        if (event.functionCalls().any { it.name == "booker" }) {
          chatClosedWhenDelegationSeen = chatModel.connections[0].closed
        }
        when (event.contentText()) {
          "Which date?" -> queue.sendContent(userMessage("Friday"))
          "Booked." -> queue.close()
        }
      }

    // Assert: the task got the answer, and only the chat's new connection got the tool's answer.
    assertEquals("Friday", taskModel.connections.single().receivedTexts().last())
    assertEquals(
      listOf("call lookup", "call booker", "answer lookup"),
      events.functionSteps("planner"),
    )
    assertEquals(emptyList(), taskModel.connections.single().receivedResponses())
    assertEquals(emptyList(), chatModel.connections[0].receivedResponses())
    assertEquals(
      listOf("lookup", "booker"),
      chatModel.connections[1].receivedResponses().map { it.name },
    )
    // A plain tool answers at once, so the delegating step is held until the chat's connection
    // ends.
    assertEquals(true, chatClosedWhenDelegationSeen)
  }

  @Test
  fun chatNode_liveTurnWhoseSingleTurnToolPausesBesideATask_storesTheChatsCall() = runBlocking {
    // Arrange: one chat turn calls a single-turn helper, whose long-running call pauses it, and
    // delegates the booking.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val helper =
      LlmAgent(
        name = "helper",
        description = "Checks.",
        mode = AgentMode.SINGLE_TURN,
        model = DummyModel("helper") { flowOf(modelFunctionCallResponse("approve", id = "a1")) },
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("helper", mapOf("request" to "check"))),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel = LiveNodeModel("planner", listOf(listOf(turn)), log)
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("check and book"))

    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel), helper)))),
        queue,
        sessionService = sessions,
      )

    // Assert: the chat's call is stored beside the helper's pause, and the task waits for resume.
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    assertEquals(listOf("call helper", "call booker"), stored.functionSteps("planner"))
    assertEquals(listOf("call approve"), stored.functionSteps("helper"))
    assertEquals(listOf("planner connect", "planner close"), log)
  }

  @Test
  fun chatNode_liveTurnPausingAfterItSpoke_emitsItsRecording() = runBlocking {
    // Arrange: the chat speaks, then calls a single-turn helper that pauses, beside a task.
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))))
    val helper =
      LlmAgent(
        name = "helper",
        description = "Checks.",
        mode = AgentMode.SINGLE_TURN,
        model = DummyModel("helper") { flowOf(modelFunctionCallResponse("approve", id = "a1")) },
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
      )
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("helper", mapOf("request" to "check"))),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel = LiveNodeModel("planner", listOf(listOf(audio(), turn)), completesTurns = false)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("check and book"))

    // Act: answering the speech brings the calls, and the helper's pause ends the run.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel), helper)))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
      ) { event ->
        if (event.author == "planner" && event.hasAudio()) queue.sendContent(userMessage("go on"))
      }

    // Assert: the paused round's speech reaches the caller as the chat's recording.
    assertEquals(listOf("planner"), events.recordings().map { it.author })
  }

  @Test
  fun chatNode_liveTurnDeferringALongRunningCallBesideATask_letsTheCallerAnswerIt() = runBlocking {
    // Arrange: one chat turn calls a long-running tool that defers its answer, and delegates.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("approve", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    // The model's reply to the answer ends the delegating step and is dropped; the chat speaks
    // again on its next connection, after the task.
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(turn, modelMessage("Checking.")), listOf(modelMessage("Approved."))),
        log,
      )
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
        mode = AgentMode.CHAT,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    // Act: the caller answers the deferred call as soon as it sees it, and hangs up once the chat
    // replies after the task.
    val events =
      runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
        if (event.contentText() == "Approved.") queue.close()
        event
          .functionCalls()
          .firstOrNull { it.name == "approve" }
          ?.let { call ->
            val answer =
              FunctionResponse(id = call.id, name = "approve", response = mapOf("ok" to true))
            queue.sendContent(
              Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
            )
          }
      }

    // Assert: the chat's connection got the answer, the task ran, then the chat talked again.
    assertEquals("approve", chatModel.connections.first().receivedResponses().single().name)
    assertEquals(
      listOf(
        "planner connect",
        "planner close",
        "booker connect",
        "booker close",
        "planner connect",
        "planner close",
      ),
      log,
    )
    assertEquals(listOf("call approve", "call booker"), events.functionSteps("planner"))
    assertContains(events.functionSteps(Role.USER), "answer booker")
    assertEquals(listOf("Approved."), events.filter { it.author == "planner" }.replies())
  }

  @Test
  fun chatNode_callerAnswersADeferredCallAndTheModelRepliesWithoutARole_endsTheStep() =
    runBlocking {
      // Arrange: as above, but the reply is audio whose content has no role, as Gemini Live sends.
      val log = mutableListOf<String>()
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("approve", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
        )
      val audio = Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2))
      val spoken = Content(role = null, parts = listOf(Part(inlineData = audio)))
      val chatModel =
        LiveNodeModel(
          "planner",
          listOf(listOf(turn, spoken), listOf(modelMessage("Approved."))),
          log,
        )
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          subAgents = listOf(booker(taskModel)),
          tools =
            listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
          mode = AgentMode.CHAT,
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("book"))

      // Act: the caller answers the deferred call and hangs up once the chat talks again.
      val unused =
        withTimeout(10.seconds) {
          runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
            if (event.contentText() == "Approved.") queue.close()
            event
              .functionCalls()
              .firstOrNull { it.name == "approve" }
              ?.let { call ->
                val answer =
                  FunctionResponse(id = call.id, name = "approve", response = mapOf("ok" to true))
                queue.sendContent(
                  Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
                )
              }
          }
        }

      // Assert: the role-less reply ended the step, so the task ran and the chat talked again.
      assertEquals("approve", chatModel.connections.first().receivedResponses().single().name)
      assertEquals(
        listOf(
          "planner connect",
          "planner close",
          "booker connect",
          "booker close",
          "planner connect",
          "planner close",
        ),
        log,
      )
    }

  @Test
  fun chatNode_modelSpeaksWithoutARoleBeforeTheCallerAnswersADeferredCall_keepsTheStepOpen() =
    runBlocking {
      // Arrange: after its delegating turn the chat speaks role-less audio, then talks again.
      val log = mutableListOf<String>()
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("approve", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
        )
      val audio = Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2))
      val spoken = Content(role = null, parts = listOf(Part(inlineData = audio)))
      val chatModel =
        LiveNodeModel(
          "planner",
          listOf(
            listOf(turn, spoken, modelMessage("Still here.")),
            listOf(modelMessage("Booked.")),
          ),
          log,
        )
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          subAgents = listOf(booker(taskModel)),
          tools =
            listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
          mode = AgentMode.CHAT,
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("book"))

      // Act: the caller talks twice instead of answering the deferred call.
      val events =
        withTimeout(10.seconds) {
          runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
            if (event.contentText() == "Booked.") queue.close()
            if (event.functionCalls().any { it.name == "approve" }) {
              queue.sendContent(userMessage("hello?"))
              queue.sendContent(userMessage("still there?"))
            }
          }
        }

      // Assert: the role-less speech stayed part of the step, which ended at the next turn.
      assertEquals(
        listOf(audio),
        events
          .filter { it.author == "planner" && it.hasAudio() }
          .map { it.content?.parts?.single()?.inlineData },
      )
      assertEquals(listOf("Booked."), events.filter { it.author == "planner" }.replies())
    }

  @Test
  fun chatNode_modelSpeaksWithoutARoleBeforeTheCallerAnswers_theCallerHearsItFirst() = runBlocking {
    // Arrange: after its delegating turn the chat speaks role-less audio, then replies to the
    // caller's answer.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("approve", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val audio = Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2))
    val spoken = Content(role = null, parts = listOf(Part(inlineData = audio)))
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(turn, spoken, modelMessage("Approved.")), listOf(modelMessage("Booked."))),
        log,
      )
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
        mode = AgentMode.CHAT,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))
    var approveId: String? = null

    // Act: the caller talks on seeing the calls, and answers the deferred call only once it
    // hears the speech.
    val unused =
      withTimeout(10.seconds) {
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
          if (event.contentText() == "Booked.") queue.close()
          event
            .functionCalls()
            .firstOrNull { it.name == "approve" }
            ?.let {
              approveId = it.id
              queue.sendContent(userMessage("hello?"))
            }
          if (event.author == "planner" && event.hasAudio()) {
            val answer =
              FunctionResponse(id = approveId, name = "approve", response = mapOf("ok" to true))
            queue.sendContent(
              Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
            )
          }
        }
      }

    // Assert: the caller heard the speech in time to answer, so the task ran.
    assertEquals("approve", chatModel.connections.first().receivedResponses().single().name)
    assertEquals(listOf("booker connect", "booker close"), log.filter { "booker" in it })
  }

  @Test
  fun chatNode_recordingSavedWhileADelegatingStepWaitsOnTheCaller_reachesTheCaller() = runBlocking {
    // Arrange: the chat speaks, then delegates beside a deferred call; its speech saves slowly,
    // so the recording arrives while the step waits on the caller.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("approve", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(listOf(audio(), turn, modelMessage("Approved.")), listOf(modelMessage("Booked."))),
        log,
      )
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
        mode = AgentMode.CHAT,
      )
    val slowStore = SaveHookArtifactService { delay(200) }
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))
    var approveId: String? = null

    // Act: the caller goes on after the speech, and answers the deferred call once it gets the
    // recording.
    val events =
      withTimeout(10.seconds) {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, planner))),
          queue,
          runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
          artifactService = slowStore,
        ) { event ->
          if (event.contentText() == "Booked.") queue.close()
          if (event.author == "planner" && event.hasAudio()) {
            queue.sendContent(userMessage("go on"))
          }
          event.functionCalls().firstOrNull { it.name == "approve" }?.let { approveId = it.id }
          if (
            event.author == "planner" && event.content?.parts?.any { it.fileData != null } == true
          ) {
            val answer =
              FunctionResponse(id = approveId, name = "approve", response = mapOf("ok" to true))
            queue.sendContent(
              Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
            )
          }
        }
      }

    // Assert: the recording reached the caller and did not end the step, which ended at the reply.
    assertEquals(listOf("planner"), events.recordings().map { it.author })
    assertEquals("approve", chatModel.connections.first().receivedResponses().single().name)
  }

  @Test
  fun chatNode_callbackHandsOffOnSpeechWhileADelegatingStepWaits_defersTheHandoff() = runBlocking {
    // Arrange: the chat delegates beside a deferred call, and a callback hands off on its next
    // speech.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val supportModel = LiveNodeModel("support", listOf(listOf(modelMessage("Hi, support."))), log)
    val support = LlmAgent(name = "support", model = supportModel, description = "Helps.")
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("approve", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel = LiveNodeModel("planner", listOf(listOf(turn, spoke("Handing you over."))), log)
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel), support),
        tools = listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
        afterModelCallbacks =
          listOf(
            AfterModelCallback { context, response ->
              if (response.outputTranscription != null) {
                context.actions.transferToAgent = "support"
              }
              response
            }
          ),
        mode = AgentMode.CHAT,
      )
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    // Act: the caller answers the deferred call, which brings the speech.
    val unused =
      withTimeout(10.seconds) {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, started(planner)))),
          queue,
          resumable = true,
          sessionService = sessions,
        ) { event ->
          if (event.author == "booker" && event.output != null) queue.close()
          event
            .functionCalls()
            .firstOrNull { it.name == "approve" }
            ?.let { call ->
              val answer =
                FunctionResponse(id = call.id, name = "approve", response = mapOf("ok" to true))
              queue.sendContent(
                Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
              )
            }
        }
      }

    // Assert: the turn did not hand over before the task, and the handoff is stored for later,
    // though a callback set it on a partial transcription.
    assertEquals(listOf("planner connect", "planner close", "booker connect", "booker close"), log)
    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    assertEquals(
      listOf("support"),
      stored.mapNotNull { it.customMetadata?.get("adk_deferred_handoff") },
    )
  }

  @Test
  fun chatNode_earlierTurnsDeferredCallUnanswered_roleLessReplyStillEndsTheStep() = runBlocking {
    // Arrange: an earlier turn leaves a deferred call unanswered; the next turn delegates beside
    // another, which the caller answers, and the model replies role-less.
    val log = mutableListOf<String>()
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
    val turn =
      modelMessage(
        Part(functionCall = FunctionCall("approve", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val audio = Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2))
    val spoken = Content(role = null, parts = listOf(Part(inlineData = audio)))
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("hold", emptyMap()), turn, spoken),
          listOf(modelMessage("Booked.")),
        ),
        log,
      )
    val deferred = { name: String ->
      DummyTool(name = name, isLongRunning = true, onRun = { _, _ -> Unit })
    }
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(deferred("hold"), deferred("approve")),
        mode = AgentMode.CHAT,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    // Act: the caller leaves `hold` unanswered, goes on, and answers `approve`.
    val unused =
      withTimeout(10.seconds) {
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
          if (event.contentText() == "Booked.") queue.close()
          if (event.functionCalls().any { it.name == "hold" }) queue.sendContent(userMessage("go"))
          event
            .functionCalls()
            .firstOrNull { it.name == "approve" }
            ?.let { call ->
              val answer =
                FunctionResponse(id = call.id, name = "approve", response = mapOf("ok" to true))
              queue.sendContent(
                Content(role = Role.USER, parts = listOf(Part(functionResponse = answer)))
              )
            }
        }
      }

    // Assert: the role-less reply ended the step, so the task ran.
    assertEquals(listOf("booker connect", "booker close"), log.filter { "booker" in it })
  }

  @Test
  fun chatNode_liveTaskDelegationWithACallersHandle_onlyTheFirstSessionResumesIt() = runBlocking {
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))))
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book a trip"))),
          listOf(modelMessage("Booked.")),
        ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("plan a trip"))
    val handle = SessionResumptionConfig(handle = "h-1")

    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(sessionResumption = handle)),
      ) { event ->
        if (event.contentText() == "Booked.") queue.close()
      }

    // Assert: later sessions start fresh, so the chat's second one is sent the task's answer.
    val sessions = listOf(chatModel.requests[0], taskModel.requests.single(), chatModel.requests[1])
    assertEquals(
      listOf("h-1", null, null),
      sessions.map { it.liveConnectConfig.sessionResumption?.handle },
    )
    assertEquals("booker", chatModel.connections[1].receivedResponses().single().name)
  }

  @Test
  fun chatNodes_liveInParallel_secondFailsBeforeItConnects() = runBlocking {
    val leftModel = LiveNodeModel("left", listOf(emptyList()))
    val rightModel = LiveNodeModel("right", listOf(emptyList()))
    val left = LlmAgent(name = "left", model = leftModel, mode = AgentMode.CHAT)
    val right = LlmAgent(name = "right", model = rightModel, mode = AgentMode.CHAT)

    val failure =
      assertFailsWith<IllegalStateException> {
        runLiveWorkflow(
          Workflow("wf", listOf(Edge(Start, left), Edge(Start, right))),
          LiveRequestQueue(),
        )
      }

    assertContains(failure.message.orEmpty(), "cannot start while live node")
    assertEquals(1, (leftModel.connections + rightModel.connections).size)
  }

  @Test
  fun chatNode_liveDelegatedTaskCutOffMidSpeech_savesItsAudioAndStaysFinished() = runBlocking {
    // Arrange: the task speaks, then finishes on the user's reply before its turn completes.
    val taskModel =
      LiveNodeModel(
        "booker",
        listOf(listOf(audio(), finishTask("result" to "booked"))),
        completesTurns = false,
      )
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book"))),
          listOf(modelMessage("Booked.")),
        ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    // Act: the caller answers the task by voice, then hangs up once the chat replies.
    val events =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
        runConfig = RunConfig(live = LiveRunConfig(saveLiveBlob = true)),
      ) { event ->
        if (event.author == "booker" && event.hasAudio()) {
          queue.sendRealtime(RealtimeInput.Audio(Blob("audio/pcm", data = ByteArray(8))))
        }
        if (event.contentText() == "Booked.") queue.close()
      }

    // Assert: the task keeps its audio, and its finish stays the last event in its scope.
    assertEquals(listOf("user", "booker"), events.recordings().map { it.author })
    val taskScope = events.first { it.author == "booker" }.isolationScope
    val last = events.last { it.isolationScope == taskScope }
    assertEquals(listOf("finish_task"), last.functionResponses().map { it.name })
    assertEquals(listOf("Booked."), events.filter { it.author == "planner" }.replies())
  }

  @Test
  fun chatNode_liveTurnDeferringALongRunningCallBesideATask_talksAgainBeforeItIsAnswered() =
    runBlocking {
      // Arrange: one chat turn calls a long-running tool that defers its answer, and delegates.
      val log = mutableListOf<String>()
      val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log)
      val turn =
        modelMessage(
          Part(functionCall = FunctionCall("approve", emptyMap())),
          Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
        )
      val chatModel =
        LiveNodeModel(
          "planner",
          listOf(listOf(turn, modelMessage("Still here.")), listOf(modelMessage("Booked."))),
          log,
        )
      val planner =
        LlmAgent(
          name = "planner",
          model = chatModel,
          subAgents = listOf(booker(taskModel)),
          tools =
            listOf(DummyTool(name = "approve", isLongRunning = true, onRun = { _, _ -> Unit })),
          mode = AgentMode.CHAT,
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("book"))

      // Act: the caller speaks instead of answering the deferred call, and hangs up once the chat
      // talks again.
      val unused =
        runLiveWorkflow(Workflow("wf", listOf(Edge(Start, planner))), queue) { event ->
          if (event.contentText() == "Booked.") queue.close()
          if (event.functionCalls().any { it.name == "approve" }) {
            queue.sendContent(userMessage("still there?"))
          }
        }

      // Assert: as in ADK Python, the chat reconnects after the task with the call left open.
      assertEquals(
        listOf("booker connect", "planner connect"),
        log.drop(2).filter { "connect" in it },
      )
      // The new connection's history has the open call and the task's answer, and no other answer.
      val replayed = chatModel.connections[1].received.flatMap { it.parts }
      assertContains(replayed.mapNotNull { it.functionCall?.name }, "approve")
      assertEquals(listOf("booker"), replayed.mapNotNull { it.functionResponse?.name })
    }

  @Test
  fun chatNode_liveTurnMixingAStreamAndATask_recordsTheStreamStopped() = runBlocking {
    val mixedTurn =
      modelMessage(
        Part(functionCall = FunctionCall("monitor", emptyMap())),
        Part(functionCall = FunctionCall("booker", mapOf("request" to "book"))),
      )
    val chatModel =
      LiveNodeModel("planner", listOf(listOf(mixedTurn), listOf(modelMessage("Booked."))))
    val taskModel = LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))))
    val planner =
      LlmAgent(
        name = "planner",
        model = chatModel,
        subAgents = listOf(booker(taskModel)),
        tools = listOf(DummyTool("monitor") { _, _ -> flow<String> { awaitCancellation() } }),
        mode = AgentMode.CHAT,
      )
    val sessions = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("book"))

    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner))),
        queue,
        sessionService = sessions,
      ) { event ->
        if (event.contentText() == "Booked.") queue.close()
      }

    val stored = checkNotNull(sessions.getSession(SessionKey("app", "user", "live"))).events
    assertContains(lastMonitorAnswer(stored), "stopped streaming")
  }

  @Test
  fun chatNode_queueClosesAfterTheTaskAnswers_doesNotReconnect() = runBlocking {
    val log = mutableListOf<String>()
    val taskModel =
      LiveNodeModel("booker", listOf(listOf(finishTask("result" to "booked"))), log = log)
    val chatModel =
      LiveNodeModel(
        "planner",
        listOf(
          listOf(modelFunctionCall("booker", mapOf("request" to "book a trip"))),
          listOf(modelMessage("Booked.")),
        ),
        log = log,
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("plan a trip"))

    // Act: the user hangs up once the task has answered.
    val unused =
      runLiveWorkflow(
        Workflow("wf", listOf(Edge(Start, planner(chatModel, booker(taskModel))))),
        queue,
      ) { event ->
        if (event.author == "booker" && event.output != null) queue.close()
      }

    assertEquals(listOf("planner connect", "planner close", "booker connect", "booker close"), log)
  }
}
