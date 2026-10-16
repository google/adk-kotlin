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
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.storedBytes
import com.google.adk.kt.testing.turnComplete
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
   * does, keeps a turn that called a tool open until an answer goes through, and counts closes.
   */
  private class ScriptedConnection(private val inner: DummyLiveConnection) : LiveConnection {
    val sentHistory: List<List<Content>>
      get() = inner.sent.filterIsInstance<List<*>>().map { it.filterIsInstance<Content>() }

    val sentContent: List<Content>
      get() = inner.sent.filterIsInstance<ContentInput>().map { it.content }

    val sentRealtime: List<RealtimeInput>
      get() = inner.sent.filterIsInstance<RealtimeInput>()

    /** Whether this connection was closed; one nobody closed leaves its pump coroutine running. */
    var isClosed = false
      private set

    /** Completed by the first tool answer that went through; a dropped one leaves the turn open. */
    private val answered = CompletableDeferred<Unit>()

    fun sentTexts(): List<String> = sentContent.mapNotNull { it.parts.singleOrNull()?.text }

    override suspend fun sendHistory(history: List<Content>) = inner.sendHistory(history)

    override suspend fun sendContent(content: Content, partial: Boolean) {
      inner.sendContent(content, partial)
      if (content.parts.any { it.functionResponse != null }) answered.complete(Unit)
    }

    override suspend fun sendRealtime(input: RealtimeInput) = inner.sendRealtime(input)

    override fun receive(): Flow<LlmResponse> = flow {
      try {
        inner.receive().collect { response ->
          // A real transport suspends here; emitting straight through would starve the send loop.
          yield()
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

  /** Hands out one connection per connect, so a reconnect is observable. */
  private class ReconnectingLiveModel(
    private val scripts: List<ConnectionScript>,
    onSendRealtime: suspend () -> Unit = {},
    beforeSendDrop: suspend () -> Unit = {},
  ) : Model {
    override val name = "scripted-live"

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

    private val faults = scripts.map { SendFaults(it, beforeSendDrop) }

    private val dummy =
      DummyLiveModel.perConnection(
        scripts.map(::stepsOf),
        // Past a clean script, clear the handle so the run ends; past an outage, keep dropping.
        afterScripts =
          if (scriptEndsMidOutage) listOf(Fail(connectionDrop()))
          else
            listOf(
              Respond(
                LlmResponse(
                  liveSessionResumptionUpdate =
                    LiveServerSessionResumptionUpdate(newHandle = null, resumable = false)
                )
              ),
              EndStream,
            ),
        onSend = { index, message ->
          if (scriptEndsMidOutage || index < scripts.size) {
            if (message is RealtimeInput) onSendRealtime()
            faults.getOrNull(index)?.apply(message)
          }
        },
      )

    private val opened = mutableListOf<ScriptedConnection>()

    /** The scripted connections, and past a mid-outage script every later one too. */
    val connections: List<ScriptedConnection>
      get() = if (scriptEndsMidOutage) opened.toList() else opened.take(scripts.size)

    val requests: List<LlmRequest>
      get() = dummy.connectRequests.take(connections.size)

    /** Reconnects past a cleanly finished script, which only a handle held at the close causes. */
    val trailingConnects: Int
      get() = opened.size - connections.size

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {}

    override suspend fun connect(request: LlmRequest): LiveConnection =
      ScriptedConnection(dummy.connect(request) as DummyLiveConnection).also { opened.add(it) }

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
    assertTrue(model.connections.isNotEmpty(), "$context opened no connection to check")
    for ((index, connection) in model.connections.withIndex()) {
      assertTrue(
        connection.isClosed,
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
        dropAfter(handleUpdate("h-1"), text("before the drop")),
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
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), AUTH_FAILURE_MARKER)))

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
        listOf(listOf(handleUpdate("h-1"), text("hello"), goAway())),
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
    assertEquals(6, model.connections.size)
  }

  @Test
  fun runLive_dropsThatEachFollowACompletedTurn_neverExhaustTheBudget(): Unit = runTest {
    // A connection that completed a turn was a healthy session, so its drop resets the budget.
    val answersThenDrops =
      listOf(listOf(handleUpdate("h"), text("hi"), turnComplete(), text("next"), DROP_MARKER))
    val (runner, model) =
      runnerFor(*Array(7) { answersThenDrops }, listOf(listOf(text("done"), turnComplete())))

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(8, model.connections.size)
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

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(11, model.connections.size)
  }

  @Test
  fun runLive_dropAfterTheCallerClosed_isReportedNotReconnected(): Unit = runBlocking {
    val (runner, model) = runnerFor(dropAfter(handleUpdate("h-1"), text("bye")))
    val queue = LiveRequestQueue()
    queue.close()

    assertFailsWith<GenAiApiException> { runner.runLive("u", "s", queue).toList() }

    assertEquals(1, model.connections.size, "a caller who hung up has nothing to resume")
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

    assertTrue(stateSaved, "the callback should have saved state while the go-away cancelled it")
    assertEquals(2, model.connections.size)
    assertTrue(events.any { it.turnComplete }, "the resumed session should complete its turn")
  }

  @Test
  fun runLive_reconnect_closesEveryConnectionItOpens(): Unit = runTest {
    // A go-away replaces the connection, so the old one and its replacement must both close.
    val (runner, model) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), goAway())),
        listOf(listOf(text("resumed"), turnComplete())),
      )

    val unused = runner.runLive("u", "s", LiveRequestQueue()).toList()

    assertEquals(2, model.connections.size)
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
    val queue = LiveRequestQueue()

    val unused = runner.runLive("u", "s", queue).toList()

    assertEquals(listOf(null, "h-late"), resumptionHandles(model))
  }

  @Test
  fun runLive_whenTheSessionKeepsDropping_failsInsteadOfLooping(): Unit = runTest {
    // Every attempt after the go-away drops, so the budget stops the run and surfaces the drop.
    val (runner, model) = runnerFor(listOf(listOf(handleUpdate("h-1"), goAway())))
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
  fun runLive_dropDuringSend_doesNotPersistTheUserTurnTwice(): Unit = runTest {
    // The append precedes the write, so without the guard a replay stores the turn twice.
    val (runner, _) =
      runnerFor(
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
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
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
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
        listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
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
        listOf(listOf(handleUpdate("h-1"), SEND_CANCEL_MARKER, DROP_MARKER)),
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
            listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
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
          listOf(listOf(handleUpdate("h-1"), toolCall("c1"), SEND_DROP_MARKER)),
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
        listOf(listOf(handleUpdate("h-1"), SEND_FAILURE_MARKER, DROP_MARKER)),
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
