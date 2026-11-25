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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyLiveConnection
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.EndStream
import com.google.adk.kt.testing.DummyLiveStep.Fail
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.connectionDrop
import com.google.adk.kt.testing.goAway
import com.google.adk.kt.testing.handleUpdate
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.storedBytes
import com.google.adk.kt.testing.turnComplete
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.InteractionStatus
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Transcription
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.genai.kotlin.GenAiApiException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** One scripted turn of responses that a single receive collection plays before it ends. */
private typealias ScriptedTurn = List<LlmResponse>

/** The turns one fake connection plays in order, one per receive collection. */
private typealias ConnectionScript = List<ScriptedTurn>

/**
 * Covers reopening a dropped live connection and resuming the session behind it.
 *
 * Most tests leave the queue open, since closing it is the caller hanging up and leaves nothing to
 * resume. Tests that reconnect use `runTest` so the reconnect backoff runs on virtual time.
 */
class LlmAgentLiveReconnectTest {

  /**
   * A [DummyLiveConnection] as these tests check it. It pauses before each frame as a transport
   * does, keeps a turn that called a tool open until an answer goes through, and counts closes. A
   * [brokenClose] throws and leaves a stalled receive running.
   */
  private class ScriptedConnection(
    private val inner: DummyLiveConnection,
    private val beforeFrame: suspend (LlmResponse) -> Unit,
    private val brokenClose: Boolean,
  ) : LiveConnection {
    // Recorded before the send is passed on, so a send the script fails or the fake rejects still
    // counts.
    private val historySends = mutableListOf<List<Content>>()
    private val contentSends = mutableListOf<Content>()
    private val realtimeSends = mutableListOf<RealtimeInput>()
    val sentHistory: List<List<Content>> = historySends
    val sentContent: List<Content> = contentSends
    val sentRealtime: List<RealtimeInput> = realtimeSends

    /** Whether this connection was closed; one nobody closed leaves its pump coroutine running. */
    var isClosed = false
      private set

    /** Completed by the first tool answer that went through; a dropped one leaves the turn open. */
    private val answered = CompletableDeferred<Unit>()

    /** Completed by the first close, which ends a stalled receive unless the close is broken. */
    private val closing = CompletableDeferred<Unit>()

    fun sentTexts(): List<String> = sentContent.mapNotNull { it.parts.singleOrNull()?.text }

    override suspend fun sendHistory(history: List<Content>) {
      historySends.add(history)
      inner.sendHistory(history)
    }

    override suspend fun sendContent(content: Content, partial: Boolean) {
      contentSends += content
      inner.sendContent(content, partial)
      if (content.parts.any { it.functionResponse != null }) answered.complete(Unit)
    }

    override suspend fun sendRealtime(input: RealtimeInput) {
      realtimeSends += input
      inner.sendRealtime(input)
    }

    override fun receive(): Flow<LlmResponse> = flow {
      try {
        inner
          .receive()
          .takeWhile { response ->
            // A real transport suspends here; emitting straight through would starve the send loop.
            yield()
            beforeFrame(response)
            // A server that has nothing more to say keeps the socket open until it is closed.
            if (response == STALL_MARKER) {
              if (brokenClose) awaitCancellation() else closing.await()
            }
            response != STALL_MARKER
          }
          .collect { response ->
            emit(response)
            // Like Gemini 3.x, a turn that called a tool stays open until the answer arrives.
            if (response.content?.parts.orEmpty().any { it.functionCall != null }) {
              withTimeoutOrNull(5.seconds) { answered.await() }
            }
          }
      } catch (e: Throwable) {
        // A scripted failure comes after the same pause as a frame.
        if (e !is CancellationException) yield()
        throw e
      }
    }

    // Recorded, not forwarded: a real socket still reports a drop after the client closes.
    override suspend fun closeSession() {
      isClosed = true
      closing.complete(Unit)
      if (brokenClose) throw IllegalStateException("close failed")
    }
  }

  /** The faults a script's send markers ask for, each on the first send it applies to. */
  private class SendFaults(
    script: ConnectionScript,
    private val beforeSendDrop: suspend () -> Unit,
  ) {
    private var park = script.any { SEND_FAILURE_MARKER in it }
    private var drop = script.any { SEND_DROP_MARKER in it }
    private var cancel = script.any { SEND_CANCEL_MARKER in it }

    suspend fun apply(message: Any) {
      if (message is ContentInput && cancel) {
        cancel = false
        // The Gen AI SDK's send fails this way once its websocket has closed.
        throw CancellationException("Failed to send frame")
      }
      if (message is ContentInput && drop) {
        drop = false
        beforeSendDrop()
        // Yields as a real write would, so the other loop runs before the drop surfaces.
        yield()
        throw connectionDrop()
      }
      if ((message is ContentInput || message is RealtimeInput) && park) {
        park = false
        // Parks, not throws, so the drop cancels it inside `writeRequest`, past the append.
        awaitCancellation()
      }
    }
  }

  /**
   * Hands out one connection per connect, so a reconnect is observable. [queue] is there for a test
   * to pass to the run and reach from its event callbacks, and is closed, as by a caller hanging
   * up, on a reconnect past a cleanly finished script; [brokenClose] applies to every connection.
   */
  private class ReconnectingLiveModel(
    private val scripts: List<ConnectionScript>,
    onSendRealtime: suspend () -> Unit = {},
    onSendContent: suspend (Content) -> Unit = {},
    beforeSendDrop: suspend () -> Unit = {},
    val queue: LiveRequestQueue = LiveRequestQueue(),
    private val beforeFrame: suspend (LlmResponse) -> Unit = {},
    private val brokenClose: Boolean = false,
  ) : Model {
    override val name = "scripted-live"

    /**
     * Whether the script ends on a go-away, transport drop, server error, auth failure, or send
     * drop rather than a clean turn, so reconnects past the end of the script keep failing like an
     * unrecovered outage.
     */
    private val scriptEndsMidOutage: Boolean =
      scripts.lastOrNull()?.lastOrNull()?.lastOrNull().let { last ->
        last != null &&
          (last.goAway != null ||
            last in setOf(DROP_MARKER, SERVER_ERROR_MARKER, AUTH_FAILURE_MARKER, SEND_DROP_MARKER))
      }

    private val faults = scripts.map { SendFaults(it, beforeSendDrop) }

    private val dummy =
      DummyLiveModel.perConnection(
        scripts.map(::stepsOf),
        // Past a clean script, end at once: the caller hung up; past an outage, keep dropping.
        afterScripts =
          if (scriptEndsMidOutage) listOf(Fail(connectionDrop())) else listOf(EndStream),
        onSend = { index, message ->
          if (scriptEndsMidOutage || index < scripts.size) {
            if (message is RealtimeInput) onSendRealtime()
            if (message is ContentInput) onSendContent(message.content)
            faults.getOrNull(index)?.apply(message)
          }
        },
      )

    private val checkedConnections = mutableListOf<ScriptedConnection>()
    private val checkedRequests = mutableListOf<LlmRequest>()

    /** The scripted connections, and past a mid-outage script every later one too. */
    val connections: List<ScriptedConnection> = checkedConnections

    val requests: List<LlmRequest> = checkedRequests

    /** Reconnects past a cleanly finished script, which only a handle held at the close causes. */
    var trailingConnects = 0
      private set

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {}

    override suspend fun connect(request: LlmRequest): LiveConnection {
      val trailing = !scriptEndsMidOutage && checkedConnections.size >= scripts.size
      val connection =
        ScriptedConnection(dummy.connect(request) as DummyLiveConnection, beforeFrame, brokenClose)
      if (trailing) {
        trailingConnects++
        // A held handle survives updates without one, so only a hang-up ends the run here.
        queue.close()
      } else {
        checkedConnections += connection
        checkedRequests += request
      }
      return connection
    }

    private companion object {
      /** The script as [DummyLiveStep]s: its frames in order, with the markers mapped. */
      fun stepsOf(script: ConnectionScript): List<DummyLiveStep> {
        // The SDK reports a dropped socket only after a send on it has failed.
        val dropWaitsForACancelledSend = script.any { SEND_CANCEL_MARKER in it }
        val steps = mutableListOf<DummyLiveStep>()
        for (frame in script.flatten()) {
          when (frame) {
            SEND_FAILURE_MARKER,
            SEND_DROP_MARKER,
            SEND_CANCEL_MARKER -> {}
            // 1006 is the SDK's close code for a dropped socket; reconnect keys on it.
            DROP_MARKER -> {
              if (dropWaitsForACancelledSend) {
                steps += AwaitSent("the send that fails") { it is ContentInput }
              }
              steps += Fail(connectionDrop())
            }
            // An internal server error (1011) is retried like a drop.
            SERVER_ERROR_MARKER ->
              steps += Fail(GenAiApiException(1011, "ConnectionClosed", "internal error"))
            // A refusal, not a drop: same exception family, different status, must not be retried.
            AUTH_FAILURE_MARKER ->
              steps += Fail(GenAiApiException(401, "UNAUTHENTICATED", "bad key"))
            else -> steps += Respond(frame)
          }
        }
        // A script that ends cleanly ends the connection; one that fails ends there.
        if (steps.lastOrNull() !is Fail) steps += EndStream
        return steps
      }
    }
  }

  private fun toolCall(id: String) = modelFunctionCallResponse("get_weather", id = id)

  /** How many times the agent's get_weather tool ran. */
  private var weatherRuns = 0

  private fun text(value: String) = LlmResponse(content = modelMessage(value))

  private fun runnerFor(
    model: ReconnectingLiveModel,
    beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
  ): InMemoryRunner =
    InMemoryRunner(
      agent =
        LlmAgent(
          name = "live_agent",
          model = model,
          instruction = Instruction("be brief"),
          tools =
            listOf(
              DummyTool(name = "get_weather", declares = true) { _, _ ->
                mapOf("tempF" to 72).also { weatherRuns++ }
              }
            ),
          beforeModelCallbacks = beforeModelCallbacks,
        )
    )

  private fun runnerFor(
    vararg scripts: ConnectionScript
  ): Pair<InMemoryRunner, ReconnectingLiveModel> {
    val model = ReconnectingLiveModel(scripts.toList())
    return runnerFor(model) to model
  }

  private suspend fun storedUserTexts(runner: InMemoryRunner): List<String> =
    runner.sessionService
      .getSession(SessionKey(runner.appName, "u", "s"))
      ?.events
      .orEmpty()
      .filter { it.author == "user" }
      .mapNotNull { it.content?.parts?.singleOrNull()?.text }

  /** The session's stored texts in order, each as "author: text". */
  private suspend fun storedTexts(runner: InMemoryRunner): List<String> =
    runner.sessionService
      .getSession(SessionKey(runner.appName, "u", "s"))
      ?.events
      .orEmpty()
      .mapNotNull { event ->
        event.content?.parts?.singleOrNull()?.text?.let { "${event.author}: $it" }
      }

  /** The agent's stored words in order: its texts, and its transcripts as "heard: text". */
  private suspend fun storedModelOutput(runner: InMemoryRunner): List<String> =
    runner.sessionService
      .getSession(SessionKey(runner.appName, "u", "s"))
      ?.events
      .orEmpty()
      .filter { it.author == "live_agent" }
      .mapNotNull { event ->
        event.outputTranscription?.text?.let { "heard: $it" }
          ?: event.content?.parts?.singleOrNull()?.text
      }

  private fun resumptionHandles(model: ReconnectingLiveModel) =
    model.requests.map { it.liveConnectConfig.sessionResumption?.handle }

  /** A connection that hands out its frames and then fails the way a dropped socket does. */
  private fun dropAfter(vararg responses: LlmResponse): ConnectionScript =
    listOf(responses.toList() + DROP_MARKER)

  private fun toolNames(request: LlmRequest) =
    request.config.tools.orEmpty().flatMap {
      it.functionDeclarations.orEmpty().map { fn -> fn.name }
    }

  /**
   * Asserts every connection this run opened was closed, naming the one that was not.
   *
   * Per connection rather than in aggregate: close is idempotent, so a total count can be reached
   * by closing one connection twice while another is never closed at all.
   */
  private fun assertNoConnectionLeaked(model: ReconnectingLiveModel, context: String) {
    assertWithMessage("$context opened no connection to check").that(model.connections).isNotEmpty()
    for ((index, connection) in model.connections.withIndex()) {
      assertWithMessage(
          "$context left connection #${index + 1} of ${model.connections.size} open, so " +
            "its reader coroutine is still running"
        )
        .that(connection.isClosed)
        .isTrue()
    }
  }

  @Test
  fun runLive_transportDrops_reopensAndCarriesOn(): Unit = runTest {
    // A websocket blip mid-conversation must reconnect, not fail the run.
    val (runner, model) =
      runnerFor(
        dropAfter(handleUpdate("h-1"), text("before the drop")),
        listOf(listOf(text("after the drop"), turnComplete())),
      )

    val events = runner.runLive("u", "s", model.queue).toList()

    assertWithMessage("a dropped socket should have been reopened")
      .that(model.connections.size)
      .isEqualTo(2)
    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, "h-1"))
    assertWithMessage("a clean close holding a handle should resume once")
      .that(model.trailingConnects)
      .isEqualTo(1)
    assertWithMessage(
        "the conversation did not continue after the reconnect (${events.size} events)"
      )
      .that(events.any { it.content?.parts?.any { part -> part.text == "after the drop" } == true })
      .isTrue()
  }

  @Test
  fun runLive_transportDropsWithNoHandle_surfacesTheFailure(): Unit = runBlocking {
    // Nothing to resume, and a drop is a failure, so it must reach the caller, not end quietly.
    val (runner, model) = runnerFor(dropAfter(text("before the drop")))

    assertFailsWith<GenAiApiException>("a drop with no handle ended the run silently") {
      runner.runLive("u", "s", LiveRequestQueue()).toList()
    }

    assertWithMessage("there was nothing to resume, so nothing to reopen")
      .that(model.connections.size)
      .isEqualTo(1)
  }

  @Test
  fun runLive_authFailure_isNotRetried(): Unit = runBlocking {
    // A refusal is not a drop; retrying it would loop on a backoff forever, silently.
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), AUTH_FAILURE_MARKER)))

    val failure =
      assertFailsWith<GenAiApiException>("a rejected request should surface") {
        runner.runLive("u", "s", LiveRequestQueue()).toList()
      }

    assertThat(failure.code).isEqualTo(401)
    assertWithMessage("an auth failure must not be retried")
      .that(model.connections.size)
      .isEqualTo(1)
  }

  @Test
  fun runLive_reconnect_stillCarriesTheAgentsPersonaAndTools(): Unit = runTest {
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), text("hello"), goAway())),
        listOf(listOf(text("still here"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertWithMessage("expected an original connect and one reconnect")
      .that(model.requests.size)
      .isEqualTo(2)
    for ((index, request) in model.requests.withIndex()) {
      assertWithMessage("connect #${index + 1} lost the agent's tools")
        .that(toolNames(request))
        .isEqualTo(listOf("get_weather"))
      assertWithMessage("connect #${index + 1} lost the agent's instruction")
        .that(
          request.config.systemInstruction?.parts?.any { it.text?.contains("be brief") == true } ==
            true
        )
        .isTrue()
    }
  }

  @Test
  fun runLive_normalCompletion_leaksNoConnection(): Unit = runBlocking {
    val (runner, model) = runnerFor(listOf(listOf(text("hello"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertNoConnectionLeaked(model, "a run that ended normally")
    assertWithMessage("a clean close without a handle must not reconnect")
      .that(model.trailingConnects)
      .isEqualTo(0)
  }

  @Test
  fun runLive_streamFails_leaksNoConnection(): Unit = runBlocking {
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), AUTH_FAILURE_MARKER)))

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", LiveRequestQueue()).toList() }

    assertNoConnectionLeaked(model, "a run that failed")
  }

  @Test
  fun runLive_dropsThatEachDeliverAFrame_stillExhaustTheBudget(): Unit = runTest {
    // Frames without a completed turn buy no fresh budget, so these drops must still exhaust it.
    val speaksThenDrops = listOf(listOf(handleUpdate("h"), text("partial"), DROP_MARKER))
    val (runner, model) = runnerFor(*Array(12) { speaksThenDrops })

    // The drop that exhausted the budget surfaces as itself.
    assertFailsWith<GenAiApiException>("a run whose every connection drops must end") {
      runner.runLive("u", "s", LiveRequestQueue()).toList()
    }

    // The first connection plus the five reconnects the budget allows.
    assertThat(model.connections.size).isEqualTo(6)
  }

  @Test
  fun runLive_dropsThatEachFollowACompletedTurn_neverExhaustTheBudget(): Unit = runTest {
    // A connection that completed a turn was a healthy session, so its drop resets the budget.
    val answersThenDrops =
      listOf(listOf(handleUpdate("h"), text("hi"), turnComplete(), text("next"), DROP_MARKER))
    val (runner, model) =
      runnerFor(*Array(7) { answersThenDrops }, listOf(listOf(text("done"), turnComplete())))

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(model.connections.size).isEqualTo(8)
  }

  @Test
  fun runLive_completedTurnAfterFailures_restoresTheFullBudget(): Unit = runTest {
    // A completed turn resets the count to zero, so four more failures after it still fit.
    val speaksThenDrops = listOf(listOf(handleUpdate("h"), text("hi"), DROP_MARKER))
    val answersThenDrops =
      listOf(listOf(handleUpdate("h"), text("hi"), turnComplete(), DROP_MARKER))
    val (runner, model) =
      runnerFor(
        *Array(5) { speaksThenDrops },
        answersThenDrops,
        *Array(4) { speaksThenDrops },
        listOf(listOf(text("done"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(model.connections.size).isEqualTo(11)
  }

  @Test
  fun runLive_dropAfterTheCallerClosed_isReportedNotReconnected(): Unit = runBlocking {
    val (runner, model) = runnerFor(dropAfter(handleUpdate("h-1"), text("bye")))
    val queue = LiveRequestQueue()
    queue.close()

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", queue).toList() }

    assertWithMessage("a caller who hung up has nothing to resume")
      .that(model.connections.size)
      .isEqualTo(1)
  }

  @Test
  fun runLive_budgetExhausted_leaksNoConnection(): Unit = runTest {
    // Every attempt opens a connection and the run ends by throwing: the likeliest leak.
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), goAway())))

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", LiveRequestQueue()).toList() }

    assertNoConnectionLeaked(model, "a run that exhausted its reconnect budget")
  }

  @Test
  fun runLive_goAwayDuringCallbackStateSave_releasesTheAppendLock(): Unit = runTest {
    // The go-away cancels the sender while a callback saves state; the cancelled save must still
    // release the append lock, or the resumed session waits on it and fails.
    var stateSaved = false
    val slowStateSave = BeforeModelCallback { context, request ->
      if (
        !stateSaved && request.contents.any { content -> content.parts.any { it.text == "first" } }
      ) {
        stateSaved = true
        withContext(NonCancellable) { delay(300) }
        context.updateState("k", "v")
      }
      CallbackChoice.Continue(request)
    }
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(listOf(handleUpdate("h-1"), goAway())),
          listOf(listOf(text("hello"), turnComplete())),
        )
      )
    val runner = runnerFor(model, beforeModelCallbacks = listOf(slowStateSave))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first"))

    val events =
      runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()

    assertWithMessage("the callback should have saved state while the go-away cancelled it")
      .that(stateSaved)
      .isTrue()
    assertThat(model.connections.size).isEqualTo(2)
    assertWithMessage("the resumed session should complete its turn")
      .that(events.any { it.turnComplete })
      .isTrue()
  }

  @Test
  fun runLive_reconnect_closesEveryConnectionItOpens(): Unit = runTest {
    // A go-away replaces the connection, so the old one and its replacement must both close.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(model.connections.size).isEqualTo(2)
    assertNoConnectionLeaked(model, "a reconnecting run")
  }

  @Test
  fun runLive_handleArrivingAfterTurnComplete_isStillUsed(): Unit = runTest {
    // The handle usually arrives on a later collection; stopping at turnComplete would lose it.
    val (runner, model) =
      runnerFor(
        listOf(listOf(text("first"), turnComplete()), listOf(handleUpdate("h-late"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, "h-late"))
  }

  @Test
  fun runLive_updateWithoutAHandle_keepsTheLastHandleOffered(): Unit = runTest {
    // While the model answers or a call runs, the server's updates offer no handle to resume from.
    val withoutAHandle =
      mapOf(
        "not resumable" to LiveServerSessionResumptionUpdate(resumable = false),
        "consumed index only" to
          LiveServerSessionResumptionUpdate(lastConsumedClientMessageIndex = 7),
        "empty handle" to LiveServerSessionResumptionUpdate(newHandle = "", resumable = false),
      )
    for ((kind, update) in withoutAHandle) {
      val noHandle = LlmResponse(liveSessionResumptionUpdate = update)
      val (runner, model) =
        runnerFor(
          dropAfter(handleUpdate("h-1"), noHandle, handleUpdate("h-2"), noHandle),
          listOf(listOf(text("resumed"), turnComplete())),
        )

      val unused = runner.runLive("u", "s", model.queue).toList()

      assertWithMessage(kind).that(resumptionHandles(model)).isEqualTo(listOf(null, "h-2"))
    }
  }

  @Test
  fun runLive_connectionLostWhileNotResumable_resumesFromTheLastHandle(): Unit = runTest {
    // The model has started answering, so the last update said the session can't be resumed.
    val interruptions =
      mapOf(
        "go-away" to goAway(),
        "abnormal closure" to DROP_MARKER,
        "internal error" to SERVER_ERROR_MARKER,
        "clean close" to null,
      )
    for ((kind, interruption) in interruptions) {
      val (runner, model) =
        runnerFor(
          listOf(listOfNotNull(handleUpdate("h-1"), NOT_RESUMABLE, interruption)),
          listOf(listOf(text("resumed"), turnComplete())),
        )
      // An earlier turn, which a fresh session replays and a resumed one already holds.
      val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
      val earlier = Event(author = Role.USER, content = userMessage("Book a table for two."))
      val unusedEvent = runner.sessionService.appendEvent(session, earlier)

      val unused = runner.runLive("u", "s", model.queue).toList()

      assertWithMessage(kind).that(resumptionHandles(model)).isEqualTo(listOf(null, "h-1"))
      assertWithMessage(kind)
        .that(model.connections[0].sentHistory.single().flatMap { it.parts }.map { it.text })
        .contains("Book a table for two.")
      assertWithMessage(kind).that(model.connections[1].sentHistory).isEmpty()
    }
  }

  @Test
  fun runLive_whenTheSessionKeepsDropping_failsInsteadOfLooping(): Unit = runTest {
    // Every attempt after the go-away drops, so the budget stops the run and surfaces the drop.
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), goAway())))
    val queue = LiveRequestQueue()

    val error = assertFailsWith<GenAiApiException> { runner.runLive("u", "s", queue).toList() }

    assertWithMessage("the drop that exhausted the budget should surface")
      .that(error.code)
      .isEqualTo(1006)
    assertWithMessage("the budget should bound the attempts")
      .that(model.connections.size)
      .isEqualTo(6)
    assertWithMessage("the reconnects should have been spaced out, not hot-looped")
      .that(testScheduler.currentTime)
      .isGreaterThan(0L)
  }

  @Test
  fun runLive_serverKeepsClosingWhileNotResumable_failsAfterTheReconnectLimit(): Unit = runTest {
    // The kept handle resumes each close, but no turn completes, so the run must not loop forever.
    val closesMidAnswer = listOf(listOf(handleUpdate("h-1"), text("partial"), NOT_RESUMABLE))
    val (runner, model) = runnerFor(*Array(7) { closesMidAnswer })

    val failure =
      assertFailsWith<IllegalStateException> { runner.runLive("u", "s", model.queue).toList() }

    assertThat(failure)
      .hasMessageThat()
      .isEqualTo("live session reconnected 5 times without completing a turn")
    // The first connection plus the five reconnects the budget allows.
    assertThat(model.connections.size).isEqualTo(6)
  }

  @Test
  fun runLive_dropDuringSend_doesNotPersistTheUserTurnTwice(): Unit = runTest {
    // The append precedes the write, so without the guard a replay stores the turn twice.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = model.queue
    queue.sendContent(userMessage("only once"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertWithMessage("the replayed request must not append the turn twice")
      .that(storedUserTexts(runner).count { it == "only once" })
      .isEqualTo(1)
  }

  @Test
  fun runLive_dropDuringRealtimeSend_recordsTheChunkOnce(): Unit = runTest {
    // The replay after the drop re-sends the chunk; it must not add it to the recording again.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = model.queue
    queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = byteArrayOf(7))))

    val unused =
      runner.runLive("u", "s", queue, RunConfig(live = LiveRunConfig(saveLiveBlob = true))).toList()

    val key = SessionKey(runner.appName, "u", "s")
    val recording =
      runner.sessionService.getSession(key)?.events.orEmpty().firstOrNull { event ->
        event.content?.parts?.singleOrNull()?.fileData?.fileUri?.contains("input_audio") == true
      }
    assertWithMessage("the caller's audio should have been recorded").that(recording).isNotNull()
    assertThat(runner.artifactService?.storedBytes(checkNotNull(recording), key))
      .isEqualTo(byteArrayOf(7))
    assertThat(model.connections.map { it.sentRealtime.size }).isEqualTo(listOf(1, 1))
  }

  @Test
  fun runLive_realtimeWithStateDelta_recordsTheDeltaBeforeSending(): Unit = runBlocking {
    // A request's state delta is persisted before the send, as in ADK Python.
    val appName = "live_app"
    val sessionService = InMemorySessionService()
    val deltaEventsAtSend = mutableListOf<Int>()
    val model =
      ReconnectingLiveModel(
        listOf(listOf(listOf(text("hi"), turnComplete()))),
        onSendRealtime = {
          val session = sessionService.getSession(SessionKey(appName, "u", "s"))
          deltaEventsAtSend +=
            session?.events.orEmpty().count { it.actions.stateDelta.isNotEmpty() }
        },
      )
    val runner =
      InMemoryRunner(
        agent = LlmAgent(name = "live_agent", model = model),
        appName = appName,
        sessionService = sessionService,
      )
    val queue = LiveRequestQueue()
    val audio = RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = byteArrayOf(7)))
    queue.send(LiveRequest(audio, stateDelta = mapOf("k" to "v")))

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(deltaEventsAtSend).isEqualTo(listOf(1))
  }

  @Test
  fun runLive_dropWhileWritingTheToolAnswer_resendsItOnResume(): Unit = runTest {
    // Re-sends the cut-off tool answer on resume; ADK Python dequeues before writing and loses it.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(model.connections.size).isEqualTo(2)
    val resent =
      model.connections[1].sentContent.flatMap { it.parts }.mapNotNull { it.functionResponse?.id }
    assertWithMessage("the answer the drop cut off must reach the resumed session")
      .that(resent)
      .isEqualTo(listOf("c1"))
  }

  @Test
  fun runLive_dropWhileNotResumableDuringAStreamingCall_resumesAndTheStreamGoesOn(): Unit =
    runTest {
      // A call running in the background keeps the session unresumable; it outlives the drop.
      val resumed = CompletableDeferred<Unit>()
      val updateSent = CompletableDeferred<Unit>()
      var starts = 0
      val monitor =
        DummyTool(name = "monitor") { _, _ ->
          starts++
          flow {
            resumed.await()
            emit("update 1")
          }
        }
      val update = mapOf("result" to "update 1")
      val model =
        ReconnectingLiveModel(
          listOf(
            dropAfter(
              handleUpdate("h-1"),
              modelFunctionCallResponse("monitor", id = "c1"),
              NOT_RESUMABLE,
            ),
            listOf(listOf(text("resumed"), turnComplete())),
          ),
          onSendContent = { content ->
            if (content.parts.any { it.functionResponse?.response == update }) {
              updateSent.complete(Unit)
            }
          },
          // The stream's update reaches the resumed session before its turn ends.
          beforeFrame = { frame ->
            if (frame.content?.parts?.singleOrNull()?.text == "resumed") {
              resumed.complete(Unit)
              updateSent.await()
            }
          },
        )
      val agent = LlmAgent(name = "live_agent", model = model, tools = listOf(monitor))

      val unused = InMemoryRunner(agent = agent).runLive("u", "s", model.queue).toList()

      assertThat(resumptionHandles(model)).isEqualTo(listOf(null, "h-1"))
      assertWithMessage("the stream must not start again on the resumed session")
        .that(starts)
        .isEqualTo(1)
      val answers =
        model.connections[1].sentContent.flatMap { it.parts }.mapNotNull { it.functionResponse }
      assertThat(answers.map { it.id to it.response }).isEqualTo(listOf("c1" to update))
    }

  @Test
  fun runLive_sendCancelledByADroppedSocket_resendsTheRequestOnceAndFirst(): Unit = runTest {
    // The SDK's send fails with a cancellation after a close; only its receive reports the 1006.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), SEND_CANCEL_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = model.queue
    queue.sendContent(userMessage("hello"))
    queue.sendContent(userMessage("second"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(model.connections.size).isEqualTo(2)
    assertWithMessage("the cut-off request goes out first, once")
      .that(model.connections[1].sentTexts())
      .isEqualTo(listOf("hello", "second"))
    assertWithMessage("each request is stored once")
      .that(storedUserTexts(runner))
      .isEqualTo(listOf("hello", "second"))
  }

  @Test
  fun runLive_dropWhileWritingARewrittenMessage_resendsTheRewriteWithoutScreeningAgain(): Unit =
    runTest {
      // The callback ran once and its rewrite was on the wire; a replay must not leak the original.
      var screenings = 0
      val redacting = BeforeModelCallback { _, request ->
        screenings++
        val redacted =
          request.contents.map { content ->
            content.copy(
              parts = content.parts.map { Part(text = it.text?.replace("4111", "****")) }
            )
          }
        CallbackChoice.Continue(request.copy(contents = redacted))
      }
      val handleSeen = CompletableDeferred<Unit>()
      val model =
        ReconnectingLiveModel(
          listOf(
            listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
            listOf(listOf(text("resumed"), turnComplete())),
          ),
          // The drop waits for the handle, so the run resumes rather than failing.
          beforeSendDrop = { handleSeen.await() },
        )
      val queue = model.queue
      queue.sendContent(userMessage("card 4111"))

      val unused =
        runnerFor(model, beforeModelCallbacks = listOf(redacting))
          .runLive("u", "s", queue)
          .onEach { if (it.liveSessionResumptionUpdate != null) handleSeen.complete(Unit) }
          .toList()

      assertThat(model.connections[0].sentTexts()).isEqualTo(listOf("card ****"))
      assertWithMessage("the resumed session gets the rewrite")
        .that(model.connections[1].sentTexts())
        .isEqualTo(listOf("card ****"))
      assertWithMessage("a replay sends what the callback returned without running it again")
        .that(screenings)
        .isEqualTo(1)
    }

  @Test
  fun runLive_blockAfterTheServerSentAHandle_restartsAFreshSessionWithHistory(): Unit = runTest {
    // A block restarts the session: the server's handle would resume the output that was blocked.
    val redacted = modelMessage("redacted")
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              LlmResponse(outputTranscription = Transcription(text = "secret")),
            )
          ),
          listOf(listOf(text("fresh"), turnComplete())),
        )
      )
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, response ->
              if (response.outputTranscription?.text == "secret") {
                LlmResponse(content = redacted)
              } else {
                response
              }
            }
          ),
      )

    val unused = InMemoryRunner(agent = agent).runLive("u", "s", LiveRequestQueue()).toList()

    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
    val history = model.connections[1].sentHistory.single()
    assertWithMessage("the restart's history holds the block")
      .that(history.any { it.parts == redacted.parts })
      .isTrue()
  }

  @Test
  fun runLive_dropWhileTheSenderWaitsBehindAToolAnswer_recordsTheQueuedTurnOnce(): Unit = runTest {
    // The turn queued behind the dropped answer is delivered on the resumed session, recorded once.
    val queue = LiveRequestQueue()
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
          listOf(listOf(text("resumed"), turnComplete())),
        ),
        beforeSendDrop = { queue.sendContent(userMessage("queued")) },
        queue = queue,
      )
    val runner = runnerFor(model)

    val unused = runner.runLive("u", "s", queue).toList()

    assertWithMessage("the replay must persist the turn the drop cancelled")
      .that(storedUserTexts(runner).count { it == "queued" })
      .isEqualTo(1)
    assertThat(model.connections.size).isEqualTo(2)
  }

  @Test
  fun runLive_dropDuringSend_stillDeliversTheRequest(): Unit = runTest {
    // Suppressing the second append must not suppress the re-send; tool answers use this path.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = model.queue
    queue.sendContent(userMessage("only once"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(model.connections.size).isEqualTo(2)
    assertWithMessage("the request the drop cut off must reach the resumed session")
      .that(model.connections[1].sentTexts())
      .isEqualTo(listOf("only once"))
  }

  @Test
  fun runLive_goAwayWithTimeLeft_deliversTheRestOfTheTurnBeforeReconnecting(): Unit = runTest {
    // The server leaves time to finish the turn; reconnecting at once would drop the rest of it.
    val beforeReconnect = mutableListOf<Event>()
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 5.seconds),
            audio(),
            turnComplete(),
            STALL_MARKER,
          )
        ),
        onEvent = { model, event -> if (model.connections.size == 1) beforeReconnect += event },
      )

    assertWithMessage("the model's audio after the go-away should reach the caller")
      .that(
        beforeReconnect.any { it.content?.parts?.any { part -> part.inlineData != null } == true }
      )
      .isTrue()
    assertWithMessage("the end of the turn after the go-away should reach the caller")
      .that(beforeReconnect.any { it.turnComplete })
      .isTrue()
    assertThat(resumptionHandles(run.model)).isEqualTo(listOf(null, "h-1"))
    // The end of the turn ended the drain, so only the first reconnect's backoff came before it.
    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_goAwayWithTimeLeftAndNoTurnEnd_reconnectsASecondBeforeTheTimeRunsOut(): Unit =
    runTest {
      val run =
        runThenResume(
          listOf(
            listOf(
              handleUpdate("h-1"),
              text("speaking"),
              goAway(timeLeft = 5.seconds),
              audio(),
              STALL_MARKER,
            )
          )
        )

      // The drain stops 1 s before the 5 s are up, then waits the first reconnect's backoff.
      assertThat(run.resumedAt).isAtLeast(4_000L)
      assertThat(run.resumedAt).isAtMost(4_250L)
      assertNoConnectionLeaked(run.model, "a drain that ran out of time")
    }

  @Test
  fun runLive_drainWithResumption_resumesFromTheHandleSentAfterTheTurn(): Unit = runTest {
    // The server sends the handle that covers the turn only after it ends.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 5.seconds),
            turnComplete(),
          ),
          listOf(handleUpdate("h-2"), STALL_MARKER),
        ),
        config = RunConfig(live = LiveRunConfig(sessionResumption = SessionResumptionConfig())),
      )

    assertThat(resumptionHandles(run.model)).isEqualTo(listOf(null, "h-2"))
    // The handle ended the drain, so only the first reconnect's backoff came before the reconnect.
    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_drainWithResumptionAndNoHandleAfterTheTurn_reconnectsSoonAfterIt(): Unit = runTest {
    // A handle that never comes must not hold the user's input until the deadline.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 30.seconds),
            turnComplete(),
          ),
          listOf(STALL_MARKER),
        ),
        config = RunConfig(live = LiveRunConfig(sessionResumption = SessionResumptionConfig())),
      )

    // The drain waits a second for the handle, then the first reconnect's backoff.
    assertThat(run.resumedAt).isAtLeast(1_000L)
    assertThat(run.resumedAt).isAtMost(1_250L)
    assertThat(resumptionHandles(run.model)).isEqualTo(listOf(null, "h-1"))
  }

  @Test
  fun runLive_goAwayBetweenTurns_reconnectsAtOnce(): Unit = runTest {
    // With no turn to finish, a drain would only hold the user's next input.
    val run =
      runThenResume(
        listOf(
          listOf(handleUpdate("h-1"), text("hi"), turnComplete()),
          listOf(goAway(timeLeft = 30.seconds), STALL_MARKER),
        )
      )

    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_goAwayWhileTheModelAwaitsAToolAnswer_reconnectsAtOnce(): Unit = runTest {
    // The model waits for the answer, so its turn ends only once the next connection carries it.
    val run =
      runThenResume(
        listOf(
          listOf(handleUpdate("h-1"), toolCall("c1"), goAway(timeLeft = 30.seconds), STALL_MARKER)
        )
      )

    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_goAwayAfterABargeIn_reconnectsAtOnce(): Unit = runTest {
    // The finished transcription that follows the cut-off audio flushes the turn, not reopens it.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            audio(),
            audio().copy(interrupted = true),
            LlmResponse(outputTranscription = Transcription(text = "cut", finished = true)),
            goAway(timeLeft = 30.seconds),
            STALL_MARKER,
          )
        )
      )

    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_interruptionDuringTheDrain_endsIt(): Unit = runTest {
    // The interrupted model listens to input that the stopped sender holds, so waiting is futile.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 30.seconds),
            LlmResponse(interrupted = true),
            STALL_MARKER,
          )
        )
      )

    assertThat(run.resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_goAwayDuringASlowSend_cutsItAndKeepsReading(): Unit = runTest {
    // A send that hangs must not stop the reading; the cut chunk goes to the next connection.
    var slowed = false
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              text("speaking"),
              goAway(timeLeft = 5.seconds),
              text("finishing"),
              turnComplete(),
            )
          ),
          listOf(listOf(text("resumed"), turnComplete())),
        ),
        onSendRealtime = {
          if (!slowed) {
            slowed = true
            delay(6.seconds)
          }
        },
      )
    model.queue.sendRealtime(
      RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = byteArrayOf(7)))
    )
    var resumedAt: Long? = null

    val unused =
      runnerFor(model)
        .runLive("u", "s", model.queue)
        .onEach { if (it.hasText("resumed")) resumedAt = testScheduler.currentTime }
        .toList()

    assertThat(resumedAt).isAtMost(250L)
    assertThat(model.connections[1].sentRealtime).hasSize(1)
  }

  @Test
  fun runLive_goAwayDuringASlowScreen_keepsReadingAndReplaysTheInput(): Unit = runTest {
    // The go-away stops a slow callback at once; the input screens again on the next connection.
    var slowed = false
    val slowScreening = BeforeModelCallback { _, request ->
      if (!slowed) {
        slowed = true
        delay(6.seconds)
      }
      CallbackChoice.Continue(request)
    }
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              text("speaking"),
              goAway(timeLeft = 5.seconds),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("resumed"), turnComplete())),
        )
      )
    val runner = runnerFor(model, beforeModelCallbacks = listOf(slowScreening))
    model.queue.sendContent(userMessage("first"))
    var resumedAt: Long? = null

    val unused =
      runner
        .runLive("u", "s", model.queue)
        .onEach { if (it.hasText("resumed")) resumedAt = testScheduler.currentTime }
        .toList()

    assertThat(resumedAt).isAtLeast(4_000L)
    assertThat(resumedAt).isAtMost(4_250L)
    assertThat(model.connections[1].sentTexts()).isEqualTo(listOf("first"))
    assertWithMessage("the input should be stored once")
      .that(storedUserTexts(runner))
      .isEqualTo(listOf("first"))
  }

  @Test
  fun runLive_inputQueuedDuringTheDrain_goesToTheNextConnectionOnce(): Unit = runTest {
    // Input sent to the connection that is going away would start a turn it cannot finish.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 5.seconds),
            text("finishing"),
            turnComplete(),
          )
        ),
        onEvent = { model, event ->
          if (event.hasText("finishing")) model.queue.sendContent(userMessage("queued"))
        },
      )

    assertThat(run.model.connections[0].sentTexts()).isEmpty()
    assertThat(run.model.connections[1].sentTexts()).isEqualTo(listOf("queued"))
    assertWithMessage("the input should be stored once")
      .that(storedUserTexts(run.runner))
      .isEqualTo(listOf("queued"))
  }

  @Test
  fun runLive_goAwayDuringASend_cutsItAndReplaysItOnceOnTheResumedSession(): Unit = runTest {
    // The go-away stops the sender mid-send, so the reading goes on and the send is redone.
    var screened = 0
    val counting = BeforeModelCallback { _, request ->
      if (request.contents.any { content -> content.parts.any { it.text == "first" } }) screened++
      CallbackChoice.Continue(request)
    }
    val run =
      runWithASendAtTheGoAway(
        listOf(handleUpdate("h-1"), text("speaking"), goAway(timeLeft = 5.seconds), turnComplete()),
        beforeModelCallbacks = listOf(counting),
      )

    assertThat(run.resumedAt).isAtMost(250L)
    assertThat(run.model.connections[0].sentTexts()).isEqualTo(listOf("first"))
    assertThat(run.model.connections[1].sentTexts()).isEqualTo(listOf("first", "second"))
    assertWithMessage("each input should be stored once")
      .that(storedUserTexts(run.runner))
      .isEqualTo(listOf("first", "second"))
    assertWithMessage("the cut input should be screened once").that(screened).isEqualTo(1)
  }

  @Test
  fun runLive_goAwayDuringASendWithNoHandle_restartsWithTheNextInput(): Unit = runTest {
    // The restarted turn reads the queue afresh, so the stopped sender must not take from it.
    val run =
      runWithASendAtTheGoAway(
        listOf(text("speaking"), goAway(timeLeft = 5.seconds), text("finishing"), turnComplete())
      )

    assertThat(resumptionHandles(run.model)).isEqualTo(listOf(null, null))
    assertThat(run.model.connections[1].sentTexts()).isEqualTo(listOf("second"))
    assertWithMessage("the cut input was stored, so the fresh session's history has it")
      .that(run.model.connections[1].sentHistory.single().flatMap { it.parts }.map { it.text })
      .contains("first")
    assertWithMessage("each input should be stored once")
      .that(storedUserTexts(run.runner))
      .isEqualTo(listOf("first", "second"))
  }

  @Test
  fun runLive_responseInHandAtTheDeadline_stillReachesTheCaller(): Unit = runTest {
    // The deadline waits for a response being handled, here one a slow callback screens.
    val slowScreening = AfterModelCallback { _, response ->
      if (response.outputTranscription?.text == "slow") delay(3.seconds)
      response
    }
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              text("speaking"),
              goAway(timeLeft = 2.seconds),
              LlmResponse(outputTranscription = Transcription(text = "slow")),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("resumed"), turnComplete())),
        )
      )
    val agent =
      LlmAgent(name = "live_agent", model = model, afterModelCallbacks = listOf(slowScreening))

    val events = InMemoryRunner(agent = agent).runLive("u", "s", model.queue).toList()

    assertWithMessage("the response screened past the deadline should reach the caller")
      .that(events.any { it.outputTranscription?.text == "slow" })
      .isTrue()
  }

  @Test
  fun runLive_dropDuringTheDrainWithNoHandle_restartsFresh(): Unit = runTest {
    // After a go-away the session ends anyway, so a drop before the turn ends is not a failure.
    val (runner, model) =
      runnerFor(
        listOf(listOf(text("speaking"), goAway(timeLeft = 5.seconds), DROP_MARKER)),
        listOf(listOf(text("fresh"), turnComplete())),
      )

    val events = runner.runLive("u", "s", model.queue).toList()

    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
    assertWithMessage("the fresh session should carry on")
      .that(events.any { it.hasText("fresh") })
      .isTrue()
  }

  @Test
  fun runLive_hangUpDuringTheDrain_endsItAtOnce(): Unit = runTest {
    // Nothing follows a hang-up, so the drain does not wait out the time left.
    val unused =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 30.seconds),
            LlmResponse(content = modelMessage("bye"), partial = true),
            STALL_MARKER,
          )
        ),
        onEvent = { model, event -> if (event.hasText("bye")) model.queue.close() },
      )

    // Only the first reconnect's backoff follows the hang-up.
    assertThat(testScheduler.currentTime).isAtMost(250L)
  }

  @Test
  fun runLive_toolCallDuringTheDrain_restartsFreshWithTheQuestionLast(): Unit = runTest {
    // A resumed session would predate the question, so the model is asked it again instead.
    val beforeTheGoAway =
      mapOf(
        "a handle" to listOf(handleUpdate("h-1")),
        // That update keeps the handle, so only the drain clearing it forces the fresh session.
        "a handle, then an update without one" to listOf(handleUpdate("h-1"), NOT_RESUMABLE),
        "no handle" to emptyList(),
      )
    for ((held, beforeGoAway) in beforeTheGoAway) {
      weatherRuns = 0
      val model =
        ReconnectingLiveModel(
          listOf(
            listOf(
              beforeGoAway +
                listOf(
                  audio(),
                  goAway(timeLeft = 5.seconds),
                  LlmResponse(
                    outputTranscription = Transcription(text = "checking"),
                    partial = true,
                  ),
                  LlmResponse(
                    outputTranscription = Transcription(text = "checking", finished = true)
                  ),
                  toolCall("c1"),
                  STALL_MARKER,
                )
            ),
            listOf(listOf(text("resumed"), turnComplete())),
          )
        )
      val runner = runnerFor(model)
      model.queue.sendContent(userMessage("question"))

      val events = runner.runLive("u", "s", model.queue, RESUMING).toList()

      assertWithMessage(held).that(weatherRuns).isEqualTo(0)
      assertWithMessage(held)
        .that(events.none { event -> event.functionCalls().any { it.id == "c1" } })
        .isTrue()
      assertWithMessage(held).that(resumptionHandles(model)).isEqualTo(listOf(null, null))
      assertWithMessage(held)
        .that(model.connections[1].sentHistory.single().last())
        .isEqualTo(userMessage("question"))
      assertWithMessage(held)
        .that(storedTexts(runner))
        .isEqualTo(listOf("user: question", "live_agent: resumed"))
      assertWithMessage(held).that(storedModelOutput(runner)).isEqualTo(listOf("resumed"))
    }
  }

  @Test
  fun runLive_toolCallDuringTheDrainWithoutInputTranscription_resumesFromTheHandleBeforeIt(): Unit =
    runTest {
      // Spoken turns reach a fresh history only as transcriptions, so it resumes instead.
      val model =
        ReconnectingLiveModel(
          listOf(
            listOf(
              listOf(
                handleUpdate("h-1"),
                audio(),
                NOT_RESUMABLE,
                goAway(timeLeft = 5.seconds),
                toolCall("c1"),
                handleUpdate("h-2"),
                STALL_MARKER,
              )
            ),
            listOf(listOf(text("resumed"), turnComplete())),
          )
        )
      val untranscribed =
        RunConfig(
          live =
            LiveRunConfig(
              sessionResumption = SessionResumptionConfig(),
              inputAudioTranscription = null,
            )
        )

      val events = runnerFor(model).runLive("u", "s", model.queue, untranscribed).toList()

      assertThat(weatherRuns).isEqualTo(0)
      assertThat(events.none { event -> event.functionCalls().any { it.id == "c1" } }).isTrue()
      assertThat(resumptionHandles(model)).isEqualTo(listOf(null, "h-1"))
    }

  @Test
  fun runLive_blockAcrossTheDrainDeadline_restartsFresh(): Unit = runTest {
    // The callback outlasts the deadline and the teardown bound; the block it returns still counts.
    val redacted = modelMessage("[redacted]")
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              audio(),
              goAway(timeLeft = 5.seconds),
              LlmResponse(outputTranscription = Transcription(text = "secret")),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("fresh"), turnComplete())),
        )
      )
    val slowBlock = AfterModelCallback { _, response ->
      if (response.outputTranscription?.text == "secret") {
        delay(30.seconds)
        LlmResponse(content = redacted)
      } else {
        response
      }
    }
    val agent =
      LlmAgent(name = "live_agent", model = model, afterModelCallbacks = listOf(slowBlock))

    val unused = InMemoryRunner(agent = agent).runLive("u", "s", model.queue, RESUMING).toList()

    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
  }

  @Test
  fun runLive_typedTurnTakenAsABlockRestarts_reachesTheFreshSessionOnce(): Unit = runTest {
    // A typed turn sent as the model's output is blocked reaches the fresh session once, stored
    // once.
    val queue = LiveRequestQueue()
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              audio(),
              LlmResponse(outputTranscription = Transcription(text = "secret")),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("fresh"), turnComplete())),
        ),
        beforeFrame = { frame ->
          if (frame.outputTranscription?.text == "secret") {
            queue.sendContent(userMessage("typed"))
            yield()
          }
        },
        queue = queue,
      )
    val blocking = AfterModelCallback { _, response ->
      if (response.outputTranscription?.text == "secret") {
        LlmResponse(content = modelMessage("[redacted]"))
      } else {
        response
      }
    }
    val agent = LlmAgent(name = "live_agent", model = model, afterModelCallbacks = listOf(blocking))
    val runner = InMemoryRunner(agent = agent)

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
    val fresh = model.connections[1]
    val inHistory = fresh.sentHistory.flatten().count { it.parts.any { p -> p.text == "typed" } }
    assertThat(inHistory + fresh.sentTexts().count { it == "typed" }).isEqualTo(1)
    assertThat(storedUserTexts(runner)).isEqualTo(listOf("typed"))
  }

  @Test
  fun runLive_typedTurnTakenAsBlockedSpeechRestarts_sendsItButNotTheSpeech(): Unit = runTest {
    // A typed turn sent as the speech is blocked reaches the fresh session once; the speech is not
    // sent.
    val queue = LiveRequestQueue()
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              audio(),
              inputTranscription("what is the weather", finished = false),
              toolCall("c1"),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("fresh"), turnComplete())),
        ),
        beforeFrame = { frame ->
          if (frame.inputTranscription != null) {
            queue.sendContent(userMessage("typed"))
            yield()
          }
        },
        queue = queue,
      )
    val blocking = BeforeModelCallback { _, request ->
      if (request.contents.any { c -> c.parts.any { it.text == "what is the weather" } }) {
        CallbackChoice.Break(LlmResponse(content = modelMessage("refused")))
      } else {
        CallbackChoice.Continue(request)
      }
    }
    val runner = runnerFor(model, listOf(blocking))

    val unused = runner.runLive("u", "s", queue).toList()

    assertThat(weatherRuns).isEqualTo(0)
    assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
    val fresh = model.connections[1]
    assertThat(fresh.sentTexts()).doesNotContain("what is the weather")
    val inHistory = fresh.sentHistory.flatten().count { it.parts.any { p -> p.text == "typed" } }
    assertThat(inHistory + fresh.sentTexts().count { it == "typed" }).isEqualTo(1)
    assertThat(storedUserTexts(runner).count { it == "typed" }).isEqualTo(1)
  }

  @Test
  fun runLive_toolCallAfterFlushedTextDuringTheDrain_keepsTheTextOutOfTheHistory(): Unit = runTest {
    // A text turn's whole text comes just before its call; stored, it would follow the question.
    val (runner, model) =
      runnerFor(
        listOf(
          listOf(
            LlmResponse(content = modelMessage("Tides "), partial = true),
            goAway(timeLeft = 5.seconds),
            LlmResponse(content = modelMessage("rise."), partial = true),
            text("Tides rise."),
            toolCall("c1"),
            STALL_MARKER,
          )
        ),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    model.queue.sendContent(userMessage("question"))

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(model.connections[1].sentHistory.single().last()).isEqualTo(userMessage("question"))
    assertThat(storedTexts(runner)).doesNotContain("live_agent: Tides rise.")
  }

  @Test
  fun runLive_textDuringTheDrainWithNoCallAfter_isStoredAsBefore(): Unit = runTest {
    // Held only to see whether a call follows; the deadline still lets it through.
    assertHeldTextStoredWhenTheReadingEnds(STALL_MARKER)
  }

  @Test
  fun runLive_transcriptAndTextDuringTheDrainWithNoCallAfter_areStoredInOrder(): Unit = runTest {
    val (runner, model) =
      runnerFor(
        listOf(
          listOf(
            audio(),
            goAway(timeLeft = 5.seconds),
            LlmResponse(outputTranscription = Transcription(text = "checking", finished = true)),
            text("the end"),
            STALL_MARKER,
          )
        ),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val events = runner.runLive("u", "s", model.queue).toList()

    val heard = events.indexOfFirst { it.outputTranscription?.text == "checking" }
    assertThat(heard).isAtLeast(0)
    assertThat(heard).isLessThan(events.indexOfFirst { it.hasText("the end") })
    assertThat(storedModelOutput(runner)).isEqualTo(listOf("heard: checking", "the end", "resumed"))
  }

  @Test
  fun runLive_drainDeadlineWithABrokenClose_stopsReadingAndKeepsTheHeldText(): Unit = runTest {
    // A close that throws and leaves the reading stalled is cut off after a bound.
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(listOf(audio(), goAway(timeLeft = 5.seconds), text("the end"), STALL_MARKER)),
          listOf(listOf(text("resumed"), turnComplete())),
        ),
        brokenClose = true,
      )
    val runner = runnerFor(model)

    val events = runner.runLive("u", "s", model.queue).toList()

    assertThat(events.indexOfFirst { it.hasText("the end") })
      .isLessThan(events.indexOfFirst { it.hasText("resumed") })
    assertThat(storedTexts(runner)).contains("live_agent: the end")
  }

  @Test
  fun runLive_cancelledDuringTheDrain_stopsReadingAndClosesTheConnection(): Unit = runTest {
    // The deadline no longer cancels the reading, so the caller's cancel must stop it on its own.
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              audio(),
              goAway(timeLeft = 30.seconds),
              LlmResponse(content = modelMessage("still"), partial = true),
              STALL_MARKER,
            )
          )
        )
      )
    val seen = CompletableDeferred<Unit>()
    val run = launch {
      runnerFor(model).runLive("u", "s", model.queue).collect {
        if (it.hasText("still")) seen.complete(Unit)
      }
    }

    seen.await()
    run.cancelAndJoin()

    assertThat(testScheduler.currentTime).isLessThan(29_000L)
    assertThat(model.connections.single().isClosed).isTrue()
  }

  @Test
  fun runLive_toolCallAfterAnInProgressTurnEndDuringTheDrain_keepsTheWordsOutOfTheHistory(): Unit =
    runTest {
      // A turn ended while the interaction is in progress can still call a tool, so its words wait.
      val (runner, model) =
        runnerFor(
          listOf(
            listOf(
              audio(),
              goAway(timeLeft = 5.seconds),
              LlmResponse(outputTranscription = Transcription(text = "checking", finished = true)),
              text("Checking."),
              LlmResponse(turnComplete = true, interactionStatus = InteractionStatus.IN_PROGRESS),
            ),
            listOf(toolCall("c1"), STALL_MARKER),
          ),
          listOf(listOf(text("resumed"), turnComplete())),
        )
      model.queue.sendContent(userMessage("question"))

      val unused = runner.runLive("u", "s", model.queue).toList()

      assertThat(model.connections[1].sentHistory.single().last())
        .isEqualTo(userMessage("question"))
      assertThat(storedModelOutput(runner)).isEqualTo(listOf("resumed"))
    }

  @Test
  fun runLive_dropAfterTextDuringTheDrain_storesTheText(): Unit = runTest {
    assertHeldTextStoredWhenTheReadingEnds(DROP_MARKER)
  }

  @Test
  fun runLive_streamEndAfterTextDuringTheDrain_storesTheText(): Unit = runTest {
    assertHeldTextStoredWhenTheReadingEnds(null)
  }

  /** Ends the drain right after a whole text, with [end] or a clean stream end when null. */
  private suspend fun assertHeldTextStoredWhenTheReadingEnds(end: LlmResponse?) {
    val (runner, model) =
      runnerFor(
        listOf(listOfNotNull(audio(), goAway(timeLeft = 5.seconds), text("the end"), end)),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val events = runner.runLive("u", "s", model.queue).toList()

    assertThat(events.indexOfFirst { it.hasText("the end") })
      .isLessThan(events.indexOfFirst { it.hasText("resumed") })
    assertThat(storedTexts(runner)).contains("live_agent: the end")
  }

  @Test
  fun runLive_textEndingTheTurnDuringTheDrain_endsTheDrainAtOnce(): Unit = runTest {
    // No call can follow a turn's end, so the text is not held and the drain stops there.
    val (runner, model) =
      runnerFor(
        listOf(
          listOf(
            audio(),
            goAway(timeLeft = 30.seconds),
            LlmResponse(content = modelMessage("the end"), turnComplete = true),
            STALL_MARKER,
          )
        ),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    var resumedAt: Long? = null

    val unused =
      runner
        .runLive("u", "s", model.queue)
        .onEach { if (it.hasText("resumed")) resumedAt = testScheduler.currentTime }
        .toList()

    assertThat(storedTexts(runner)).contains("live_agent: the end")
    assertThat(resumedAt).isAtMost(250L)
  }

  @Test
  fun runLive_typedTurnTakenAsTheGoAwayCame_reachesTheFreshSessionOnce(): Unit = runTest {
    // The sender has taken the turn but not stored it when the go-away stops it.
    val queue = LiveRequestQueue()
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(
              handleUpdate("h-1"),
              audio(),
              goAway(timeLeft = 5.seconds),
              toolCall("c1"),
              STALL_MARKER,
            )
          ),
          listOf(listOf(text("resumed"), turnComplete())),
        ),
        beforeFrame = { frame ->
          if (frame.goAway != null) {
            queue.sendContent(userMessage("typed"))
            // Lets the sender take the turn before the go-away is read.
            yield()
          }
        },
        queue = queue,
      )
    val runner = runnerFor(model)

    val unused = runner.runLive("u", "s", model.queue, RESUMING).toList()

    assertThat(model.connections[0].sentTexts()).isEmpty()
    val fresh = model.connections[1]
    val inHistory = fresh.sentHistory.flatten().count { it.parts.any { p -> p.text == "typed" } }
    assertThat(inHistory + fresh.sentTexts().count { it == "typed" }).isEqualTo(1)
    assertThat(storedUserTexts(runner)).isEqualTo(listOf("typed"))
  }

  @Test
  fun runLive_toolCallDuringTheDrainWhileTheUserSpeaks_blockedSpeech_restartsFresh(): Unit =
    runTest {
      val blocking = BeforeModelCallback { _, request ->
        if (request.contents.any { c -> c.parts.any { it.text == "what is the weather" } }) {
          CallbackChoice.Break(LlmResponse(content = modelMessage("refused")))
        } else {
          CallbackChoice.Continue(request)
        }
      }
      val model =
        ReconnectingLiveModel(
          listOf(
            listOf(
              listOf(
                handleUpdate("h-1"),
                audio(),
                goAway(timeLeft = 5.seconds),
                inputTranscription("what is the weather", finished = false),
                toolCall("c1"),
                STALL_MARKER,
              )
            ),
            listOf(listOf(text("resumed"), turnComplete())),
          )
        )

      val events =
        runnerFor(model, listOf(blocking)).runLive("u", "s", model.queue, RESUMING).toList()

      assertThat(weatherRuns).isEqualTo(0)
      assertThat(events.indexOfFirst { it.hasText("refused") })
        .isLessThan(events.indexOfFirst { it.hasText("resumed") })
      assertThat(resumptionHandles(model)).isEqualTo(listOf(null, null))
    }

  @Test
  fun runLive_toolCallDuringTheDrainWhileTheUserSpeaks_storesTheSpeechFirst(): Unit = runTest {
    // The speech has no finished copy yet; stored now, the fresh session still gets the question.
    val (runner, model) =
      runnerFor(
        listOf(
          listOf(
            audio(),
            goAway(timeLeft = 5.seconds),
            inputTranscription("what is the weather", finished = false),
            toolCall("c1"),
            STALL_MARKER,
          )
        ),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", model.queue).toList()

    assertThat(weatherRuns).isEqualTo(0)
    val stored = runner.sessionService.getSession(SessionKey(runner.appName, "u", "s"))?.events
    assertThat(stored.orEmpty().mapNotNull { it.inputTranscription?.text })
      .isEqualTo(listOf("what is the weather"))
    val last = model.connections[1].sentHistory.single().last()
    assertThat(last.role).isEqualTo(Role.USER)
    assertThat(last.parts.single().text).isEqualTo("what is the weather")
  }

  @Test
  fun runLive_goAwayWithoutTimeLeft_stopsReadingAtOnce(): Unit = runTest {
    assertStopsReadingAtOnce(goAway())
  }

  @Test
  fun runLive_goAwayWithOneSecondLeft_stopsReadingAtOnce(): Unit = runTest {
    // The drain keeps one second in hand, so this leaves it no time.
    assertStopsReadingAtOnce(goAway(timeLeft = 1.seconds))
  }

  @Test
  fun runLive_goAwayWithoutTimeLeftDuringTheDrain_stopsReadingAtOnce(): Unit = runTest {
    // A later notice that leaves no time overrides the drain an earlier one started.
    val run =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("speaking"),
            goAway(timeLeft = 5.seconds),
            goAway(),
            text("cut off"),
            turnComplete(),
          )
        )
      )

    assertWithMessage("nothing after the second notice should be read")
      .that(run.events.none { it.hasText("cut off") })
      .isTrue()
  }

  @Test
  fun runLive_transferDuringTheDrain_restartsFreshInsteadOfHandingOver(): Unit = runTest {
    // A transfer is a call too, so the parent is asked again rather than handing over.
    val childModel = DummyLiveModel.perConnection(listOf(listOf(Respond(text("child")), EndStream)))
    val parentModel =
      DummyLiveModel.perConnection(
        listOf(
          listOf(
            Respond(handleUpdate("h-1")),
            Respond(text("transferring")),
            Respond(goAway(timeLeft = 30.seconds)),
            Respond(modelTransferToAgentResponse("child")),
            EndStream,
          )
        ),
        afterScripts = listOf(EndStream),
      )
    val child = LlmAgent(name = "child", description = "c", model = childModel)
    val parent = LlmAgent(name = "parent", model = parentModel, subAgents = listOf(child))

    val unused =
      InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue(), RESUMING).toList()

    assertThat(childModel.connections).isEmpty()
    assertThat(parentModel.connectRequests.map { it.liveConnectConfig.sessionResumption?.handle })
      .isEqualTo(listOf(null, null))
  }

  @Test
  fun runLive_callbackTransferDuringTheDrain_handsOverWithoutTheSettlePause(): Unit = runTest {
    // The drain closes this connection anyway, so the child skips the settle pause.
    var childConnectedAt: Long? = null
    val childModel =
      DummyLiveModel.perConnection(
        listOf(listOf(Respond(text("child")), EndStream)),
        onConnect = { childConnectedAt = testScheduler.currentTime },
      )
    val parentModel =
      DummyLiveModel.perConnection(
        listOf(
          listOf(
            Respond(handleUpdate("h-1")),
            Respond(text("talking")),
            Respond(goAway(timeLeft = 30.seconds)),
            Respond(LlmResponse(outputTranscription = Transcription(text = "over"))),
            EndStream,
          )
        ),
        afterScripts = listOf(EndStream),
      )
    val transferring = AfterModelCallback { context, response ->
      context.eventActions.transferToAgent = "child"
      response
    }
    val child = LlmAgent(name = "child", description = "c", model = childModel)
    val parent =
      LlmAgent(
        name = "parent",
        model = parentModel,
        subAgents = listOf(child),
        afterModelCallbacks = listOf(transferring),
      )

    val unused = InMemoryRunner(agent = parent).runLive("u", "s", LiveRequestQueue()).toList()

    assertThat(childConnectedAt).isLessThan(1_000L)
    assertWithMessage("a callback's transfer has no tool answer to pass on")
      .that(
        childModel.connections.single().sent.filterIsInstance<ContentInput>().flatMap {
          it.content.parts.mapNotNull { part -> part.functionResponse }
        }
      )
      .isEmpty()
  }

  @Test
  fun runLive_turnStillInProgressDuringTheDrain_keepsReadingUntilTheModelIsDone(): Unit = runTest {
    // A turn completed while the interaction is still in progress means more output follows.
    val beforeReconnect = mutableListOf<Event>()
    val unused =
      runThenResume(
        listOf(
          listOf(
            handleUpdate("h-1"),
            text("thinking"),
            goAway(timeLeft = 5.seconds),
            LlmResponse(turnComplete = true, interactionStatus = InteractionStatus.IN_PROGRESS),
          ),
          listOf(text("follow-up"), turnComplete()),
        ),
        onEvent = { model, event -> if (model.connections.size == 1) beforeReconnect += event },
      )

    assertWithMessage("output after an in-progress turn end should arrive before the reconnect")
      .that(beforeReconnect.any { it.hasText("follow-up") })
      .isTrue()
  }

  /**
   * The result of [runThenResume]: its runner, model and events, and when the resumed turn came.
   */
  private class ResumedRun(
    val runner: InMemoryRunner,
    val model: ReconnectingLiveModel,
    val events: List<Event>,
    /** The test time at which "resumed" reached the caller, or null if it never did. */
    val resumedAt: Long?,
  )

  /**
   * Runs [first] on the first connection and a resumed turn on the second, passing each event to
   * [onEvent] as it reaches the caller.
   */
  private suspend fun TestScope.runThenResume(
    first: ConnectionScript,
    config: RunConfig? = null,
    resumed: ScriptedTurn = listOf(text("resumed"), turnComplete()),
    onEvent: suspend (model: ReconnectingLiveModel, event: Event) -> Unit = { _, _ -> },
  ): ResumedRun {
    val model = ReconnectingLiveModel(listOf(first, listOf(resumed)))
    val runner = runnerFor(model)
    var resumedAt: Long? = null
    val events =
      runner
        .runLive("u", "s", model.queue, config)
        .onEach {
          if (it.hasText("resumed") && resumedAt == null) resumedAt = testScheduler.currentTime
          onEvent(model, it)
        }
        .toList()
    return ResumedRun(runner, model, events, resumedAt)
  }

  /**
   * Queues "first" and "second", sending "first" slowly once so [script]'s go-away lands mid-send.
   */
  private suspend fun TestScope.runWithASendAtTheGoAway(
    script: ScriptedTurn,
    beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
  ): ResumedRun {
    var slowed = false
    val model =
      ReconnectingLiveModel(
        listOf(listOf(script), listOf(listOf(text("resumed"), turnComplete()))),
        onSendContent = { content ->
          if (!slowed && content.parts.any { it.text == "first" }) {
            slowed = true
            delay(500.milliseconds)
          }
        },
      )
    val runner = runnerFor(model, beforeModelCallbacks)
    model.queue.sendContent(userMessage("first"))
    model.queue.sendContent(userMessage("second"))
    var resumedAt: Long? = null
    val events =
      runner
        .runLive("u", "s", model.queue)
        .onEach {
          if (it.hasText("resumed") && resumedAt == null) resumedAt = testScheduler.currentTime
        }
        .toList()
    return ResumedRun(runner, model, events, resumedAt)
  }

  /** Asserts that nothing after [notice] is read and the reconnect waits only the first backoff. */
  private suspend fun TestScope.assertStopsReadingAtOnce(notice: LlmResponse) {
    val run =
      runThenResume(
        listOf(
          listOf(handleUpdate("h-1"), text("speaking"), notice, text("cut off"), turnComplete())
        )
      )

    assertWithMessage("nothing after the go-away should be read")
      .that(run.events.none { it.hasText("cut off") })
      .isTrue()
    assertThat(run.resumedAt).isAtMost(250L)
  }

  /** The model's speech as one audio chunk. */
  private fun audio() =
    LlmResponse(
      content =
        modelMessage(
          Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1)))
        )
    )

  private fun Event.hasText(value: String) = content?.parts?.singleOrNull()?.text == value

  companion object {
    // Scripted sentinels the connection turns into failures or a stall, for tests to use.
    private val DROP_MARKER = LlmResponse(errorMessage = "__scripted_transport_drop__")
    private val SERVER_ERROR_MARKER = LlmResponse(errorMessage = "__scripted_server_error__")
    private val SEND_FAILURE_MARKER = LlmResponse(errorMessage = "__scripted_send_failure__")
    private val AUTH_FAILURE_MARKER = LlmResponse(errorMessage = "__scripted_auth_failure__")
    private val SEND_DROP_MARKER = LlmResponse(errorMessage = "__scripted_send_drop__")
    private val SEND_CANCEL_MARKER = LlmResponse(errorMessage = "__scripted_send_cancel__")
    private val STALL_MARKER = LlmResponse(errorMessage = "__scripted_stall__")

    /** The update the server sends while the model answers or a call runs: no handle to resume. */
    private val NOT_RESUMABLE =
      LlmResponse(
        liveSessionResumptionUpdate = LiveServerSessionResumptionUpdate(resumable = false)
      )

    private val RESUMING =
      RunConfig(live = LiveRunConfig(sessionResumption = SessionResumptionConfig()))
  }
}
