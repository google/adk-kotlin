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
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.storedBytes
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Transcription
import com.google.genai.kotlin.GenAiApiException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

  /** One connection's worth of scripted turns, recording what it was sent. */
  private class ScriptedConnection(
    turns: ConnectionScript,
    private val onSendRealtime: suspend () -> Unit = {},
    private val beforeSendDrop: suspend () -> Unit = {},
  ) : LiveConnection {
    val sentHistory = mutableListOf<List<Content>>()
    val sentContent = mutableListOf<Content>()
    val sentRealtime = mutableListOf<RealtimeInput>()

    /**
     * How many times this connection was closed, recorded because a connection nobody closed leaves
     * its pump coroutine running.
     */
    var closeCount = 0
      private set

    /**
     * Set by [SEND_FAILURE_MARKER] in the script: the first send, content or realtime, parks once.
     *
     * Carried in the script so a test opts into a failed write the same way it opts into a drop,
     * and stripped here so the marker never reaches the receive loop.
     */
    private var failNextSend = turns.any { SEND_FAILURE_MARKER in it }

    /** Set by [SEND_DROP_MARKER]: the first content send runs [beforeSendDrop], then drops. */
    private var dropNextSend = turns.any { SEND_DROP_MARKER in it }

    private val dropsAfterACancelledSend = turns.any { SEND_CANCEL_MARKER in it }

    /**
     * Fails the first content send with a `CancellationException` when the script carries a
     * [SEND_CANCEL_MARKER]. The Gen AI SDK's send fails this way once its websocket has closed,
     * while only the receive side reports the close as a 1006 error.
     */
    private var cancelNextSend = dropsAfterACancelledSend

    /**
     * Completes when that send fails. A scripted drop waits for it, so the receive side reports the
     * 1006 only after the send has failed, in the order a real socket produces.
     */
    private val sendCancelled = CompletableDeferred<Unit>()

    /** The scripted turns still to play, with the send markers stripped out. */
    private val remainingTurns =
      turns.map { it - SEND_FAILURE_MARKER - SEND_DROP_MARKER - SEND_CANCEL_MARKER }.toMutableList()

    /** Completed by the first tool answer; until then a turn that called a tool stays open. */
    private val answered = CompletableDeferred<Unit>()

    fun sentTexts(): List<String> = sentContent.mapNotNull { it.parts.singleOrNull()?.text }

    override suspend fun sendHistory(history: List<Content>) {
      sentHistory.add(history)
    }

    override suspend fun sendContent(content: Content, partial: Boolean) {
      sentContent.add(content)
      if (cancelNextSend) {
        cancelNextSend = false
        sendCancelled.complete(Unit)
        throw CancellationException("Failed to send frame")
      }
      if (dropNextSend) {
        dropNextSend = false
        beforeSendDrop()
        // Yields as a real write would, so the other loop runs before the drop surfaces.
        yield()
        throw GenAiApiException(1006, "ConnectionClosed", "socket closed")
      }
      if (failNextSend) {
        failNextSend = false
        // Parks, not throws, so the drop cancels it inside `writeRequest`, past the append.
        awaitCancellation()
      }
      // Only an answer that went through holds the turn's end; a dropped one leaves it open.
      if (content.parts.any { it.functionResponse != null }) answered.complete(Unit)
    }

    override suspend fun sendRealtime(input: RealtimeInput) {
      onSendRealtime()
      sentRealtime.add(input)
      if (failNextSend) {
        failNextSend = false
        awaitCancellation()
      }
    }

    override fun receive(): Flow<LlmResponse> = flow {
      if (remainingTurns.isEmpty()) return@flow
      for (response in remainingTurns.removeAt(0)) {
        // A real transport suspends here; emitting straight through would starve the send loop.
        yield()
        when (response) {
          // 1006 is the abnormal close the SDK reports for a dropped socket; reconnect keys on it.
          DROP_MARKER -> {
            if (dropsAfterACancelledSend) withTimeout(5.seconds) { sendCancelled.await() }
            throw GenAiApiException(1006, "ConnectionClosed", "socket closed")
          }
          // A refusal, not a drop: same exception family, different status, must not be retried.
          AUTH_FAILURE_MARKER -> throw GenAiApiException(401, "UNAUTHENTICATED", "bad key")
          else -> {
            emit(response)
            // Like Gemini 3.x, a turn that called a tool stays open until the answer arrives.
            if (response.content?.parts.orEmpty().any { it.functionCall != null }) {
              withTimeoutOrNull(5.seconds) { answered.await() }
            }
          }
        }
      }
    }

    override suspend fun closeSession() {
      closeCount++
    }
  }

  /** Hands out one connection per connect, so a reconnect is observable. */
  private class ReconnectingLiveModel(
    private val scripts: List<ConnectionScript>,
    private val onSendRealtime: suspend () -> Unit = {},
    private val beforeSendDrop: suspend () -> Unit = {},
  ) : Model {
    override val name = "scripted-live"
    val connections = mutableListOf<ScriptedConnection>()
    val requests = mutableListOf<LlmRequest>()

    /** Reconnects past a cleanly finished script, which only a handle held at the close causes. */
    var trailingConnects = 0
      private set

    /**
     * Whether the script ends on a go-away, transport drop, auth failure, or send drop rather than
     * a clean turn, so reconnects past the end of the script keep failing like an unrecovered
     * outage.
     */
    private val scriptEndsMidOutage: Boolean =
      scripts.lastOrNull()?.lastOrNull()?.lastOrNull().let { last ->
        last != null &&
          (last.goAway != null || last in setOf(DROP_MARKER, AUTH_FAILURE_MARKER, SEND_DROP_MARKER))
      }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {}

    override suspend fun connect(request: LlmRequest): LiveConnection {
      // Clear the handle past a clean script so it ends without counting toward `connections`.
      if (connections.size >= scripts.size && !scriptEndsMidOutage) {
        trailingConnects++
        return ScriptedConnection(
          listOf(
            listOf(
              LlmResponse(
                liveSessionResumptionUpdate =
                  LiveServerSessionResumptionUpdate(newHandle = null, resumable = false)
              )
            )
          )
        )
      }
      requests.add(request)
      // Fail with a drop past a mid-outage script; a go-away would never exhaust the budget.
      val script = scripts.getOrElse(connections.size) { listOf(listOf(DROP_MARKER)) }
      return ScriptedConnection(script, onSendRealtime, beforeSendDrop).also { connections.add(it) }
    }
  }

  private fun goAway() = LlmResponse(goAway = LiveServerGoAway())

  private fun toolCall(id: String) = modelFunctionCallResponse("get_weather", id = id)

  private fun handle(value: String) =
    LlmResponse(liveSessionResumptionUpdate = LiveServerSessionResumptionUpdate(newHandle = value))

  private fun text(value: String) = LlmResponse(content = modelMessage(value))

  private fun turnComplete() = LlmResponse(turnComplete = true)

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
              DummyTool(name = "get_weather", declares = true) { _, _ -> mapOf("tempF" to 72) }
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

  private fun resumptionHandles(model: ReconnectingLiveModel) =
    model.requests.map { it.liveConnectConfig.sessionResumption?.handle }

  /** A connection that hands out its frames and then fails the way a dropped socket does. */
  private fun dropAfter(vararg responses: LlmResponse): ConnectionScript =
    listOf(responses.toList() + DROP_MARKER)

  private fun toolNames(request: LlmRequest) =
    request.config.tools?.flatMap { it.functionDeclarations.orEmpty().map { fn -> fn.name } }

  /**
   * Asserts every connection this run opened was closed, naming the one that was not.
   *
   * Per connection rather than in aggregate: close is idempotent, so a total count can be reached
   * by closing one connection twice while another is never closed at all.
   */
  private fun assertNoConnectionLeaked(model: ReconnectingLiveModel, context: String) {
    assertTrue(model.connections.isNotEmpty(), "$context opened no connection to check")
    for ((index, connection) in model.connections.withIndex()) {
      assertTrue(
        connection.closeCount > 0,
        "$context left connection #${index + 1} of ${model.connections.size} open, so its reader " +
          "coroutine is still running",
      )
    }
  }

  @Test
  fun runLive_transportDrops_reopensAndCarriesOn(): Unit = runTest {
    // A websocket blip mid-conversation must reconnect, not fail the run.
    val (runner, model) =
      runnerFor(
        dropAfter(handle("h-1"), text("before the drop")),
        listOf(listOf(text("after the drop"), turnComplete())),
      )

    val events = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size, "a dropped socket should have been reopened")
    assertEquals(listOf(null, "h-1"), resumptionHandles(model))
    assertEquals(1, model.trailingConnects, "a clean close holding a handle should resume once")
    assertTrue(
      events.any { it.content?.parts?.any { part -> part.text == "after the drop" } == true },
      "the conversation did not continue after the reconnect (${events.size} events)",
    )
  }

  @Test
  fun runLive_transportDropsWithNoHandle_surfacesTheFailure(): Unit = runBlocking {
    // Nothing to resume, and a drop is a failure, so it must reach the caller, not end quietly.
    val (runner, model) = runnerFor(dropAfter(text("before the drop")))

    assertFailsWith<GenAiApiException>("a drop with no handle ended the run silently") {
      runner.runLive("u", "s", LiveRequestQueue()).toList()
    }

    assertEquals(1, model.connections.size, "there was nothing to resume, so nothing to reopen")
  }

  @Test
  fun runLive_authFailure_isNotRetried(): Unit = runBlocking {
    // A refusal is not a drop; retrying it would loop on a backoff forever, silently.
    val (runner, model) = runnerFor(listOf(listOf(handle("h-1"), AUTH_FAILURE_MARKER)))

    val failure =
      assertFailsWith<GenAiApiException>("a rejected request should surface") {
        runner.runLive("u", "s", LiveRequestQueue()).toList()
      }

    assertEquals(401, failure.code)
    assertEquals(1, model.connections.size, "an auth failure must not be retried")
  }

  @Test
  fun runLive_reconnect_stillCarriesTheAgentsPersonaAndTools(): Unit = runTest {
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), text("hello"), goAway())),
        listOf(listOf(text("still here"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.requests.size, "expected an original connect and one reconnect")
    for ((index, request) in model.requests.withIndex()) {
      assertEquals(
        listOf("get_weather"),
        toolNames(request),
        "connect #${index + 1} lost the agent's tools",
      )
      assertTrue(
        request.config.systemInstruction?.parts?.any { it.text?.contains("be brief") == true } ==
          true,
        "connect #${index + 1} lost the agent's instruction",
      )
    }
  }

  @Test
  fun runLive_normalCompletion_leaksNoConnection(): Unit = runBlocking {
    val (runner, model) = runnerFor(listOf(listOf(text("hello"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertNoConnectionLeaked(model, "a run that ended normally")
    assertEquals(0, model.trailingConnects, "a clean close without a handle must not reconnect")
  }

  @Test
  fun runLive_streamFails_leaksNoConnection(): Unit = runBlocking {
    val (runner, model) = runnerFor(listOf(listOf(handle("h-1"), AUTH_FAILURE_MARKER)))

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", LiveRequestQueue()).toList() }

    assertNoConnectionLeaked(model, "a run that failed")
  }

  @Test
  fun runLive_cancelled_leaksNoConnection(): Unit = runBlocking {
    // The exit that skips a plain suspending finally, which is why the close is NonCancellable.
    val (runner, model) = runnerFor(listOf(listOf(text("first"), turnComplete())))
    val queue = LiveRequestQueue()

    val collector = launch { runCatching { runner.runLive("u", "s", queue).toList() } }
    withTimeout(5.seconds) { while (model.connections.isEmpty()) yield() }
    collector.cancelAndJoin()

    assertNoConnectionLeaked(model, "a cancelled run")
  }

  @Test
  fun runLive_transportDrops_leaksNeitherConnection(): Unit = runTest {
    // A drop replaces a live connection, so both the dead one and its replacement must close.
    val (runner, model) =
      runnerFor(
        dropAfter(handle("h-1"), text("before")),
        listOf(listOf(text("after"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size)
    assertNoConnectionLeaked(model, "a run that reconnected after a drop")
  }

  @Test
  fun runLive_dropsThatEachDeliverAFrame_stillExhaustTheBudget(): Unit = runTest {
    // Frames without a completed turn buy no fresh budget, so these drops must still exhaust it.
    val speaksThenDrops = listOf(listOf(handle("h"), text("partial"), DROP_MARKER))
    val (runner, model) = runnerFor(*Array(12) { speaksThenDrops })

    // The drop that exhausted the budget surfaces as itself.
    assertFailsWith<GenAiApiException>("a run whose every connection drops must end") {
      runner.runLive("u", "s", LiveRequestQueue()).toList()
    }

    // The first connection plus the five reconnects the budget allows.
    assertEquals(6, model.connections.size)
  }

  @Test
  fun runLive_dropsThatEachFollowACompletedTurn_neverExhaustTheBudget(): Unit = runTest {
    // A connection that completed a turn was a healthy session, so its drop resets the budget.
    val answersThenDrops =
      listOf(listOf(handle("h"), text("hi"), turnComplete(), text("next"), DROP_MARKER))
    val (runner, model) =
      runnerFor(*Array(7) { answersThenDrops }, listOf(listOf(text("done"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(8, model.connections.size)
  }

  @Test
  fun runLive_completedTurnAfterFailures_restoresTheFullBudget(): Unit = runTest {
    // A completed turn resets the count to zero, so four more failures after it still fit.
    val speaksThenDrops = listOf(listOf(handle("h"), text("hi"), DROP_MARKER))
    val answersThenDrops = listOf(listOf(handle("h"), text("hi"), turnComplete(), DROP_MARKER))
    val (runner, model) =
      runnerFor(
        *Array(5) { speaksThenDrops },
        answersThenDrops,
        *Array(4) { speaksThenDrops },
        listOf(listOf(text("done"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(11, model.connections.size)
  }

  @Test
  fun runLive_goAwaysThatEachFollowACompletedTurn_neverExhaustTheBudget(): Unit = runTest {
    val answersThenGoesAway = listOf(listOf(handle("h"), text("hi"), turnComplete(), goAway()))
    val (runner, model) =
      runnerFor(*Array(7) { answersThenGoesAway }, listOf(listOf(text("done"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(8, model.connections.size)
  }

  @Test
  fun runLive_goAwaysWithoutACompletedTurn_doNotExhaustTheBudget(): Unit = runTest {
    // A go-away never counts against the budget, even without a completed turn, as in ADK Python.
    val speaksThenGoesAway = listOf(listOf(handle("h"), text("partial"), goAway()))
    val (runner, model) =
      runnerFor(*Array(7) { speaksThenGoesAway }, listOf(listOf(text("done"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(8, model.connections.size)
  }

  @Test
  fun runLive_dropAfterTheCallerClosed_isReportedNotReconnected(): Unit = runBlocking {
    val (runner, model) = runnerFor(dropAfter(handle("h-1"), text("bye")))
    val queue = LiveRequestQueue()
    queue.close()

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", queue).toList() }

    assertEquals(1, model.connections.size, "a caller who hung up has nothing to resume")
  }

  @Test
  fun runLive_budgetExhausted_leaksNoConnection(): Unit = runTest {
    // Every attempt opens a connection and the run ends by throwing: the likeliest leak.
    val (runner, model) = runnerFor(listOf(listOf(handle("h-1"), goAway())))

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
          listOf(listOf(handle("h-1"), goAway())),
          listOf(listOf(text("hello"), turnComplete())),
        )
      )
    val runner = runnerFor(model, beforeModelCallbacks = listOf(slowStateSave))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first"))

    val events =
      runner.runLive("u", "s", queue).onEach { if (it.turnComplete) queue.close() }.toList()

    assertTrue(stateSaved, "the callback should have saved state while the go-away cancelled it")
    assertEquals(2, model.connections.size)
    assertTrue(events.any { it.turnComplete }, "the resumed session should complete its turn")
  }

  @Test
  fun runLive_reconnect_closesEveryConnectionItOpens(): Unit = runTest {
    // A go-away replaces the connection, so the old one and its replacement must both close.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size)
    assertNoConnectionLeaked(model, "a reconnecting run")
  }

  @Test
  fun runLive_goAwayWithAHandle_reopensTheSessionFromIt(): Unit = runTest {
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), text("hello"), goAway())),
        listOf(listOf(text("still here"), turnComplete())),
      )
    val queue = LiveRequestQueue()

    val events = runner.runLive("u", "s", queue).toList()

    assertEquals(2, model.connections.size, "the run should have reconnected once")
    assertEquals(listOf(null, "h-1"), resumptionHandles(model))
    assertTrue(
      events.any { it.content?.parts?.any { part -> part.text == "still here" } == true },
      "the resumed connection's turn should reach the caller (${events.size} events)",
    )
  }

  @Test
  fun runLive_whenResuming_doesNotReplayTheHistory(): Unit = runTest {
    // The server holds everything behind the handle; re-sending would duplicate it.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    // History to replay, so the no-replay assertion below cannot pass for the wrong reason.
    val session = runner.sessionService.createSession(SessionKey(runner.appName, "u", "s"))
    val unusedAppend =
      runner.sessionService.appendEvent(
        session,
        Event(author = "user", content = userMessage("earlier turn")),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size)
    assertTrue(
      model.connections[0].sentHistory.isNotEmpty(),
      "the first connection should be seeded with the history, or this proves nothing",
    )
    assertTrue(model.connections[1].sentHistory.isEmpty(), "history must not be replayed")
  }

  @Test
  fun runLive_handleArrivingAfterTurnComplete_isStillUsed(): Unit = runTest {
    // The handle usually arrives on a later collection; stopping at turnComplete would lose it.
    val (runner, model) =
      runnerFor(
        listOf(listOf(text("first"), turnComplete()), listOf(handle("h-late"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(listOf(null, "h-late"), resumptionHandles(model))
  }

  @Test
  fun runLive_goAwayWithNoHandle_continuesInAFreshSession(): Unit = runTest {
    // A go-away with no handle cannot resume, so it continues in a fresh session, as in ADK Python.
    val (runner, model) =
      runnerFor(
        listOf(listOf(text("hello"), turnComplete()), listOf(goAway())),
        listOf(listOf(text("fresh"), turnComplete())),
      )

    val events = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size, "a go-away with no handle continues in a fresh session")
    assertEquals(
      listOf(null, null),
      resumptionHandles(model),
      "the fresh session carries no handle",
    )
    assertEquals(0, model.trailingConnects, "a clean close without a handle must not reconnect")
    assertTrue(events.any { it.content?.parts?.any { p -> p.text == "fresh" } == true })
  }

  @Test
  fun runLive_whenTheSessionKeepsDropping_failsInsteadOfLooping(): Unit = runTest {
    // Every attempt after the go-away drops, so the budget stops the run and surfaces the drop.
    val (runner, model) = runnerFor(listOf(listOf(handle("h-1"), goAway())))
    val queue = LiveRequestQueue()

    val error = assertFailsWith<GenAiApiException> { runner.runLive("u", "s", queue).toList() }

    assertEquals(1006, error.code, "the drop that exhausted the budget should surface")
    assertEquals(6, model.connections.size, "the budget should bound the attempts")
    assertTrue(
      testScheduler.currentTime > 0,
      "the reconnects should have been spaced out, not hot-looped",
    )
  }

  @Test
  fun runLive_deliversWhatWasQueuedDuringTheOutage(): Unit = runTest {
    // The queue is an unlimited channel, so anything still in it outlives the dropped connection.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("queued"))

    val unused = runner.runLive("u", "s", queue).toList()

    val delivered = model.connections.flatMap { it.sentTexts() }
    assertTrue(
      "queued" in delivered,
      "the queued content should still be sent (${delivered.size} texts sent)",
    )
  }

  @Test
  fun runLive_dropDuringSend_doesNotPersistTheUserTurnTwice(): Unit = runTest {
    // The append precedes the write, so without the guard a replay stores the turn twice.
    val (runner, _) =
      runnerFor(
        listOf(listOf(handle("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("only once"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(
      1,
      storedUserTexts(runner).count { it == "only once" },
      "the replayed request must not append the turn twice",
    )
  }

  @Test
  fun runLive_dropDuringRealtimeSend_recordsTheChunkOnce(): Unit = runTest {
    // The replay after the drop re-sends the chunk; it must not add it to the recording again.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()
    queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = byteArrayOf(7))))

    val unused = runner.runLive("u", "s", queue, RunConfig(saveLiveBlob = true)).toList()

    val key = SessionKey(runner.appName, "u", "s")
    val recording =
      runner.sessionService.getSession(key)?.events.orEmpty().firstOrNull { event ->
        event.content?.parts?.singleOrNull()?.fileData?.fileUri?.contains("input_audio") == true
      }
    assertNotNull(recording, "the caller's audio should have been recorded")
    assertContentEquals(byteArrayOf(7), runner.artifactService?.storedBytes(recording, key))
    assertEquals(listOf(1, 1), model.connections.map { it.sentRealtime.size })
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

    assertEquals(listOf(1), deltaEventsAtSend)
  }

  @Test
  fun runLive_dropWhileWritingTheToolAnswer_resendsItOnResume(): Unit = runTest {
    // Re-sends the cut-off tool answer on resume; ADK Python dequeues before writing and loses it.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size)
    val resent =
      model.connections[1].sentContent.flatMap { it.parts }.mapNotNull { it.functionResponse?.id }
    assertEquals(listOf("c1"), resent, "the answer the drop cut off must reach the resumed session")
  }

  @Test
  fun runLive_sendCancelledByADroppedSocket_resendsTheRequestOnceAndFirst(): Unit = runTest {
    // The SDK's send fails with a cancellation after a close; only its receive reports the 1006.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), SEND_CANCEL_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    queue.sendContent(userMessage("second"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(2, model.connections.size)
    assertEquals(
      listOf("hello", "second"),
      model.connections[1].sentTexts(),
      "the cut-off request goes out first, once",
    )
    assertEquals(listOf("hello", "second"), storedUserTexts(runner), "each request is stored once")
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
            listOf(listOf(handle("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
            listOf(listOf(text("resumed"), turnComplete())),
          ),
          // The drop waits for the handle, so the run resumes rather than failing.
          beforeSendDrop = { handleSeen.await() },
        )
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("card 4111"))

      val unused =
        runnerFor(model, beforeModelCallbacks = listOf(redacting))
          .runLive("u", "s", queue)
          .onEach { if (it.liveSessionResumptionUpdate != null) handleSeen.complete(Unit) }
          .toList()

      assertEquals(listOf("card ****"), model.connections[0].sentTexts())
      assertEquals(
        listOf("card ****"),
        model.connections[1].sentTexts(),
        "the resumed session gets the rewrite",
      )
      assertEquals(
        1,
        screenings,
        "a replay sends what the callback returned without running it again",
      )
    }

  @Test
  fun runLive_blockAfterTheServerSentAHandle_restartsAFreshSessionWithHistory(): Unit = runTest {
    // A block restarts the session: the server's handle would resume the output that was blocked.
    val redacted = modelMessage("redacted")
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(
            listOf(handle("h-1"), LlmResponse(outputTranscription = Transcription(text = "secret")))
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

    assertEquals(listOf(null, null), resumptionHandles(model))
    val history = model.connections[1].sentHistory.single()
    assertTrue(history.any { it.parts == redacted.parts }, "the restart's history holds the block")
  }

  @Test
  fun runLive_dropWhileTheSenderWaitsBehindAToolAnswer_recordsTheQueuedTurnOnce(): Unit = runTest {
    // The turn queued behind the dropped answer is delivered on the resumed session, recorded once.
    val queue = LiveRequestQueue()
    val model =
      ReconnectingLiveModel(
        listOf(
          listOf(listOf(handle("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
          listOf(listOf(text("resumed"), turnComplete())),
        ),
        beforeSendDrop = { queue.sendContent(userMessage("queued")) },
      )
    val runner = runnerFor(model)

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(
      1,
      storedUserTexts(runner).count { it == "queued" },
      "the replay must persist the turn the drop cancelled",
    )
    assertEquals(2, model.connections.size)
  }

  @Test
  fun runLive_dropDuringSend_stillDeliversTheRequest(): Unit = runTest {
    // Suppressing the second append must not suppress the re-send; tool answers use this path.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handle("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
        listOf(listOf(text("resumed"), turnComplete())),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("only once"))

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(2, model.connections.size)
    assertEquals(
      listOf("only once"),
      model.connections[1].sentTexts(),
      "the request the drop cut off must reach the resumed session",
    )
  }

  companion object {
    // Scripted sentinels the connection turns into failures, so a test can express one.
    private val DROP_MARKER = LlmResponse(errorMessage = "__scripted_transport_drop__")
    private val SEND_FAILURE_MARKER = LlmResponse(errorMessage = "__scripted_send_failure__")
    private val AUTH_FAILURE_MARKER = LlmResponse(errorMessage = "__scripted_auth_failure__")
    private val SEND_DROP_MARKER = LlmResponse(errorMessage = "__scripted_send_drop__")
    private val SEND_CANCEL_MARKER = LlmResponse(errorMessage = "__scripted_send_cancel__")
  }
}
