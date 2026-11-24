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
@file:OptIn(
  ExperimentalLiveApi::class,
  ExperimentalPrewarmingApi::class,
  ExperimentalAtomicApi::class,
)

package com.google.adk.kt.models

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalPrewarmingApi
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Tool
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Bounds a wait whose failure mode is a hang. */
private val GUARD_TIMEOUT = 10.seconds

/** A context for the pool's work that tests can identify. */
private val POOL_CONTEXT = Dispatchers.Default + CoroutineName("pool")

/** A keep-warm window longer than any test. */
private val KEEP_WARM = 30.minutes

/** How long a test waits to be sure something did not happen. */
private val QUIET_PERIOD = 200.milliseconds

/** The message the server sends before ending a session. */
private val GO_AWAY = LiveServerGoAway(timeLeft = 30.seconds)

/** A tool a request can switch a spare to. */
private val SLOTS =
  Tool(functionDeclarations = listOf(FunctionDeclaration("read_slots", "Reads the open slots.")))

/** Runs dispatched work only when [runPending] is called, so a test can hold a coroutine. */
private class HeldDispatcher : CoroutineDispatcher() {
  private val pending = AtomicReference<List<Runnable>>(emptyList())

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    while (true) {
      val current = pending.load()
      if (pending.compareAndSet(current, current + block)) return
    }
  }

  /** Runs all work dispatched so far. */
  fun runPending() = pending.exchange(emptyList()).forEach { it.run() }
}

class PrewarmingLiveModelTest {

  /** A context update a [FakeConnection] took. */
  private data class ContextUpdate(val systemInstruction: Content?, val tools: List<Tool>?) {
    /** Whether this is the pool's no-op test update. */
    val isTest: Boolean
      get() = systemInstruction == null && tools?.isEmpty() == true
  }

  /**
   * A live connection that stays open until the test closes it or sends to it. It records each
   * context update once [updateGate] returns, or fails them with [updateFailure].
   */
  private class FakeConnection(
    val request: LlmRequest,
    val index: Int,
    private val closeGate: suspend (Int) -> Unit = {},
    private val updateFailure: Exception? = null,
    private val updateGate: suspend () -> Unit = {},
    private val endOnUpdate: Boolean = false,
    private val refuseOnUpdate: Boolean = endOnUpdate,
  ) : LiveConnection, ContextUpdateRefusalSignal {
    private val responses = Channel<LlmResponse>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val updates = AtomicReference<List<ContextUpdate>>(emptyList())

    /** Every context update this connection received, oldest first. */
    val allUpdates: List<ContextUpdate>
      get() = updates.load()

    /** The context updates this connection received, excluding the pool's test update. */
    val contextUpdates: List<ContextUpdate>
      get() = updates.load().filterNot { it.isTest }

    /** How many test updates the pool sent to check whether the server accepts context updates. */
    val testUpdates: Int
      get() = updates.load().count { it.isTest }

    override val serverEnding = CompletableDeferred<Unit>()

    override var refusedContextUpdate = false
      private set

    val isClosed: Boolean
      get() = closed.load()

    /** Sends [response], then reports the session's end if it is a go-away. */
    fun serverSends(response: LlmResponse) {
      responses.trySend(response).getOrThrow()
      if (response.goAway != null) serverEnding.complete(Unit)
    }

    fun serverCloses() {
      serverEnding.complete(Unit)
      responses.close()
    }

    override fun receive(): Flow<LlmResponse> = responses.receiveAsFlow()

    override suspend fun sendHistory(history: List<Content>) {}

    override suspend fun sendContent(content: Content, partial: Boolean) {}

    override suspend fun sendRealtime(input: RealtimeInput) {}

    override suspend fun sendContextUpdate(systemInstruction: Content?, tools: List<Tool>?) {
      updateGate()
      updateFailure?.let { throw it }
      while (true) {
        val current = updates.load()
        if (updates.compareAndSet(current, current + ContextUpdate(systemInstruction, tools))) break
      }
      // A model that does not accept context updates ends the session on the first one.
      if (endOnUpdate) {
        refusedContextUpdate = refuseOnUpdate
        serverCloses()
      }
    }

    override suspend fun closeSession() {
      closeGate(index)
      closed.store(true)
      serverEnding.complete(Unit)
      responses.close()
    }
  }

  /**
   * Opens a [FakeConnection] per connect, failing the opens whose index is in [failing] and holding
   * each open until [gate] returns. Opens whose index is in [goAwayOnOpen] return a connection that
   * has already sent a go-away. Each connection fails context updates with [updateFailure] (if set)
   * once [updateGate] returns for its index; a connection whose index is in [endOnUpdate] ends its
   * session on its first update with a refusal, and one in [endWithoutRefusalOnUpdate] ends it
   * without one.
   */
  private class FakeLiveModel(
    private val failing: Set<Int> = emptySet(),
    private val gate: suspend (Int) -> Unit = {},
    private val closeGate: suspend (Int) -> Unit = {},
    private val failIf: (LlmRequest, Int) -> Boolean = { _, index -> index in failing },
    private val goAwayOnOpen: Set<Int> = emptySet(),
    private val updateFailure: Exception? = null,
    private val updateGate: suspend (Int) -> Unit = {},
    private val endOnUpdate: Set<Int> = emptySet(),
    private val endWithoutRefusalOnUpdate: Set<Int> = emptySet(),
  ) : Model {
    private val requested = AtomicReference<List<LlmRequest>>(emptyList())
    private val opened = AtomicReference<List<FakeConnection>>(emptyList())

    override val name = "fake-live-model"

    /** Every connect request, oldest first, including the failed ones. */
    val requests: List<LlmRequest>
      get() = requested.load()

    /** Every connection opened, oldest first. */
    val connections: List<FakeConnection>
      get() = opened.load()

    override suspend fun connect(request: LlmRequest): LiveConnection {
      var index = 0
      while (true) {
        val current = requested.load()
        index = current.size
        if (requested.compareAndSet(current, current + request)) break
      }
      gate(index)
      if (failIf(request, index)) throw IllegalStateException("Open $index failed.")
      val connection =
        FakeConnection(
          request,
          index,
          closeGate,
          updateFailure,
          updateGate = { updateGate(index) },
          endOnUpdate = index in endOnUpdate || index in endWithoutRefusalOnUpdate,
          refuseOnUpdate = index in endOnUpdate,
        )
      if (index in goAwayOnOpen) connection.serverSends(LlmResponse(goAway = GO_AWAY))
      while (true) {
        val current = opened.load()
        if (opened.compareAndSet(current, current + connection)) return connection
      }
    }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      flowOf(LlmResponse(content = modelMessage("generated")))
  }

  /**
   * A live model whose connections cannot report a refused context update and, unless
   * [reportsServerEnd], cannot tell when the server ends their session either.
   */
  private class PlainLiveModel(private val reportsServerEnd: Boolean = false) : Model {
    class Connection(reportsServerEnd: Boolean) : LiveConnection {
      val closed = AtomicBoolean(false)

      override val serverEnding: Job? = if (reportsServerEnd) CompletableDeferred<Unit>() else null

      override suspend fun sendHistory(history: List<Content>) {}

      override suspend fun sendContent(content: Content, partial: Boolean) {}

      override suspend fun sendRealtime(input: RealtimeInput) {}

      override fun receive(): Flow<LlmResponse> = flowOf()

      override suspend fun closeSession() = closed.store(true)
    }

    private val opened = AtomicReference<List<Connection>>(emptyList())

    override val name = "plain-live-model"

    val connections: List<Connection>
      get() = opened.load()

    override suspend fun connect(request: LlmRequest): LiveConnection {
      val connection = Connection(reportsServerEnd)
      while (true) {
        val current = opened.load()
        if (opened.compareAndSet(current, current + connection)) return connection
      }
    }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flowOf()
  }

  /** A request whose [setup] label is what tells the tests' setups apart. */
  private fun request(
    instruction: String = "Greet the caller.",
    history: List<Content> = emptyList(),
    resumptionHandle: String? = null,
    tools: List<Tool>? = null,
    setup: String = "main",
  ) =
    LlmRequest(
      contents = history,
      config =
        GenerateContentConfig(
          systemInstruction = userMessage(instruction),
          tools = tools,
          labels = mapOf("setup" to setup),
        ),
      liveConnectConfig =
        LiveConnectConfig(
          sessionResumption = resumptionHandle?.let { SessionResumptionConfig(handle = it) }
        ),
    )

  /**
   * Creates a pool with no age limit and no update check unless [maxSpareAge] or
   * [updateCheckWindow] is set.
   */
  private fun newPool(
    model: Model,
    spares: Int = 1,
    maxSetups: Int = PrewarmingLiveModel.DEFAULT_MAX_SETUPS,
    keepWarmFor: Duration? = KEEP_WARM,
    minLifetimeForReplacement: Duration? = 1.minutes,
    maxSpareAge: Duration? = null,
    updateCheckWindow: Duration? = null,
  ) =
    PrewarmingLiveModel(
      model,
      spares,
      maxSetups,
      keepWarmFor = keepWarmFor,
      minLifetimeForReplacement = minLifetimeForReplacement,
      maxSpareAge = maxSpareAge,
      updateCheckWindow = updateCheckWindow,
    )

  /** Connects for a test that needs only the spares the connect opens. */
  private suspend fun PrewarmingLiveModel.connectOnce(
    instruction: String = "Greet the caller.",
    history: List<Content> = emptyList(),
    setup: String = "main",
  ) {
    val unused = connect(request(instruction, history, setup = setup))
  }

  /**
   * With two spares per setup, takes the first two spares one connect at a time so their
   * replacements (opens 3 and 4) start one connect apart and open 3 is older.
   */
  private suspend fun PrewarmingLiveModel.replaceFirstSparesOneByOne(model: FakeLiveModel) {
    connectOnce()
    eventually { model.connections.size == 3 }
    connectOnce()
    eventually { model.requests.size == 4 }
    connectOnce()
    eventually { model.requests.size == 5 }
  }

  /** Prewarms the setup labeled [setup], as [connectionsFor] tells setups apart. */
  private fun PrewarmingLiveModel.prewarmSetup(setup: String) =
    prewarm(LiveRunConfig(), GenerateContentConfig(labels = mapOf("setup" to setup)))

  /** Every setting of this pool, in constructor order. */
  private fun PrewarmingLiveModel.settings(): List<Any?> =
    listOf(
      spares,
      maxSetups,
      keepWarmFor,
      minLifetimeForReplacement,
      maxSpareAge,
      updateCheckWindow,
      context,
    )

  /** The connection from open [index]; [FakeLiveModel.connections] is in order of finishing. */
  private fun FakeLiveModel.opened(index: Int) = connections.single { it.index == index }

  private fun FakeLiveModel.connectionsFor(setup: String) = connections.filter {
    it.request.config.labels?.get("setup") == setup
  }

  private suspend fun eventually(condition: () -> Boolean) {
    withTimeout(GUARD_TIMEOUT) { while (!condition()) delay(5.milliseconds) }
  }

  @Test
  fun connect_sameSetupAgain_returnsTheSpareAndOpensAReplacement() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      val first = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 2 }

      val second = prewarming.connect(request("Greet the caller."))

      assertSame(model.connections[0], first)
      assertSame(model.connections[1], second)
      eventually { model.connections.size == 3 }
    }
  }

  @Test
  fun connect_differentSetup_opensItsOwnConnectionAndKeepsTheOtherSpare() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }

      val other = prewarming.connect(request(setup = "other"))

      assertSame(model.connections[2], other)
      assertFalse(model.connections[1].isClosed)
    }
  }

  @Test
  fun connect_differentHistory_takesTheSpareAndOpensSparesWithoutHistory() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.", listOf(userMessage("Hi")))
      eventually { model.connections.size == 2 }

      val second = prewarming.connect(request("Greet the caller.", listOf(userMessage("Bye"))))

      assertSame(model.connections[1], second)
      assertEquals(emptyList(), model.requests[1].contents)
      assertEquals(mapOf("setup" to "main"), model.requests[1].config.labels)
      assertEquals(null, model.requests[1].config.systemInstruction)
    }
  }

  @Test
  fun connect_twoSpares_keepsTwoOpenPerSetup() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 3 }

      prewarming.connectOnce("Greet the caller.")

      eventually { model.connections.size == 4 }
      delay(QUIET_PERIOD)
      assertEquals(4, model.connections.size)
    }
  }

  @Test
  fun spareClosedByServer_isClosedAndReplacedBeforeItCanBeTaken() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2, minLifetimeForReplacement = null).use { prewarming ->
      val first = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 3 }
      // The second connect takes one spare and puts the setup in use while the other is open.
      val second = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 4 }
      val older = model.connections.first { it !== first && it !== second && it.index <= 2 }

      older.serverCloses()
      eventually { model.connections.size == 5 }
      val third = prewarming.connect(request("Greet the caller."))

      assertTrue(older.isClosed)
      assertTrue(third !== older)
    }
  }

  @Test
  fun spareSentGoAway_isClosedAndReplacedBeforeItCanBeTaken() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2, minLifetimeForReplacement = null).use { prewarming ->
      val first = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 3 }
      val second = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 4 }
      val older = model.connections.first { it !== first && it !== second && it.index <= 2 }

      older.serverSends(LlmResponse(goAway = GO_AWAY))
      eventually { model.connections.size == 5 }
      val third = prewarming.connect(request("Greet the caller."))

      assertTrue(older.isClosed)
      assertTrue(third !== older)
    }
  }

  @Test
  fun spareEnded_keepWarmForNull_isClosedButNotReplaced() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, keepWarmFor = null, minLifetimeForReplacement = null).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }

      model.connections[1].serverCloses()
      eventually { model.connections[1].isClosed }
      delay(QUIET_PERIOD)

      assertEquals(2, model.connections.size)
    }
  }

  @Test
  fun spareEndedWhenItsSetupWasNotRequestedRecently_isClosedButNotReplaced() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, keepWarmFor = 50.milliseconds, minLifetimeForReplacement = null).use { prewarming
      ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }
      // Exceeds keepWarmFor so the setup is no longer considered recently requested.
      delay(QUIET_PERIOD)

      model.connections[1].serverCloses()
      eventually { model.connections[1].isClosed }
      delay(QUIET_PERIOD)

      assertEquals(2, model.connections.size)
    }
  }

  @Test
  fun connect_spareSentGoAwayBeforeItWasHandedOver_opensItsOwn() = runBlocking {
    val release = CompletableDeferred<Unit>()
    // The spare (open 1) opens only after it is taken, so only its taker sees the go-away.
    val model =
      FakeLiveModel(gate = { index -> if (index == 1) release.await() }, goAwayOnOpen = setOf(1))
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.requests.size == 2 }

      val second = async { prewarming.connect(request("Greet the caller.")) }
      delay(QUIET_PERIOD)
      release.complete(Unit)

      assertEquals(2, (withTimeout(GUARD_TIMEOUT) { second.await() } as FakeConnection).index)
      eventually { model.connections.single { it.index == 1 }.isClosed }
    }
  }

  @Test
  fun connect_spareSentGoAwayBeforeItWasHandedOver_takesTheNextSpare(): Unit = runBlocking {
    val older = CompletableDeferred<Unit>()
    val newer = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        gate = {
          when (it) {
            3 -> older.await()
            4 -> newer.await()
          }
        },
        goAwayOnOpen = setOf(3),
      )
    newPool(model, spares = 2).use { prewarming ->
      prewarming.replaceFirstSparesOneByOne(model)

      // Neither spare is open, so the connect takes the older one (open 3) and finds it ending.
      val taken =
        async(start = CoroutineStart.UNDISPATCHED) {
          prewarming.connect(request("Greet the caller."))
        }
      delay(QUIET_PERIOD)
      older.complete(Unit)
      eventually { model.connections.any { it.index == 3 && it.isClosed } }
      newer.complete(Unit)

      val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
      assertSame(model.opened(4), connection)
    }
  }

  @Test
  fun spareEndedWhileItsSetupIsInUse_isReplacedWithOneSpare() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, minLifetimeForReplacement = null).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }

      model.connections[1].serverCloses()

      eventually { model.connections.size == 3 }
      assertTrue(model.connections[1].isClosed)
    }
  }

  @Test
  fun connect_spareFailing_takesTheNextSpare(): Unit = runBlocking {
    val older = CompletableDeferred<Unit>()
    val newer = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        failing = setOf(3),
        gate = {
          when (it) {
            3 -> older.await()
            4 -> newer.await()
          }
        },
      )
    newPool(model, spares = 2).use { prewarming ->
      prewarming.replaceFirstSparesOneByOne(model)

      // Neither spare is open, so the connect takes the older one (open 3) and sees it fail.
      val taken =
        async(start = CoroutineStart.UNDISPATCHED) {
          prewarming.connect(request("Greet the caller."))
        }
      delay(QUIET_PERIOD)
      older.complete(Unit)
      delay(QUIET_PERIOD)
      newer.complete(Unit)

      val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
      assertSame(model.opened(4), connection)
    }
  }

  @Test
  fun connect_twoSpares_takesTheNewestSpareFirst() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 3 }
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 4 }
      // Lets the pool see the replacement as open shortly after the model returns it.
      delay(QUIET_PERIOD)

      val third = prewarming.connect(request("Greet the caller."))

      // The replacement the second connect opened is the newest spare.
      assertEquals(3, (third as FakeConnection).index)
    }
  }

  @Test
  fun connect_spareStillOpening_waitsForItInsteadOfOpeningAnother() = runBlocking {
    val release = CompletableDeferred<Unit>()
    val model = FakeLiveModel(gate = { index -> if (index == 1) release.await() })
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.requests.size == 2 }

      val second = async { prewarming.connect(request("Greet the caller.")) }
      delay(QUIET_PERIOD)
      val requestsWhileWaiting = model.requests.size
      release.complete(Unit)

      assertEquals(2, requestsWhileWaiting)
      assertEquals(1, (withTimeout(GUARD_TIMEOUT) { second.await() } as FakeConnection).index)
    }
  }

  @Test
  fun connect_cancelledWhileItsSpareOpens_closesTheSpareOnceItOpens() = runBlocking {
    val release = CompletableDeferred<Unit>()
    // An open that ignores cancellation, so only the pool can close what it returns.
    val model =
      FakeLiveModel(
        gate = { index -> if (index == 1) withContext(NonCancellable) { release.await() } }
      )
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.requests.size == 2 }

      val second = async { prewarming.connect(request("Greet the caller.")) }
      delay(QUIET_PERIOD)
      second.cancel()
      release.complete(Unit)
      second.join()

      eventually { model.connections.any { it.index == 1 && it.isClosed } }
    }
  }

  @Test
  fun connect_resumingRequest_opensDirectlyAndKeepsNoSpares() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      val resumed = prewarming.connect(request("Greet the caller.", resumptionHandle = "handle-1"))
      delay(QUIET_PERIOD)

      assertSame(model.connections.single(), resumed)
    }
  }

  @Test
  fun spareEndedSoonAfterOpening_isClosedButReplacedOnlyByTheNextConnect() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }

      model.connections[1].serverCloses()
      eventually { model.connections[1].isClosed }
      delay(QUIET_PERIOD)
      val connectionsBeforeNextConnect = model.connections.size
      val taken = prewarming.connect(request("Greet the caller."))

      assertEquals(2, connectionsBeforeNextConnect)
      assertSame(model.connections[2], taken)
      eventually { model.connections.size == 4 }
    }
  }

  @Test
  fun spareResponses_allReachWhoeverTakesIt() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }
      val early = LlmResponse(liveSessionId = "early")
      val later = LlmResponse(liveSessionId = "later")

      model.connections[1].serverSends(early)
      val taken = prewarming.connect(request("Greet the caller."))
      model.connections[1].serverSends(later)

      assertEquals(
        listOf(early, later),
        withTimeout(GUARD_TIMEOUT) { taken.receive().take(2).toList() },
      )
    }
  }

  @Test
  fun spareFailedToOpen_isNotRetriedAndConnectOpensItsOwn() = runBlocking {
    val model = FakeLiveModel(failing = setOf(1))
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.requests.size == 2 }
      delay(QUIET_PERIOD)
      val requestsBeforeNextConnect = model.requests.size

      val second = prewarming.connect(request("Greet the caller."))

      assertEquals(2, requestsBeforeNextConnect)
      assertSame(model.connections[1], second)
    }
  }

  @Test
  fun close_closesSparesAndLaterConnectsOpenDirectly() = runBlocking {
    val model = FakeLiveModel()
    val prewarming = newPool(model)
    prewarming.connectOnce("Greet the caller.")
    eventually { model.connections.size == 2 }

    prewarming.close()
    val afterClose = prewarming.connect(request("Greet the caller."))

    assertTrue(model.connections[1].isClosed)
    assertSame(model.connections[2], afterClose)
    delay(QUIET_PERIOD)
    assertEquals(3, model.connections.size)
  }

  @Test
  fun close_spareStillOpening_waitsAndClosesItOnceItOpens() = runBlocking {
    val release = CompletableDeferred<Unit>()
    // An open that ignores cancellation, so only the pool can close what it returns.
    val model =
      FakeLiveModel(
        gate = { index -> if (index == 1) withContext(NonCancellable) { release.await() } }
      )
    val prewarming = newPool(model)
    prewarming.connectOnce("Greet the caller.")
    eventually { model.requests.size == 2 }

    // close() blocks its thread, so run it on another dispatcher.
    val closing = async(Dispatchers.Default) { prewarming.close() }
    delay(QUIET_PERIOD)
    val closedEarly = closing.isCompleted
    release.complete(Unit)
    withTimeout(GUARD_TIMEOUT) { closing.await() }

    assertFalse(closedEarly)
    assertTrue(model.connections.single { it.index == 1 }.isClosed)
  }

  @Test
  fun close_spareClosingAfterTheServerEndedIt_waitsUntilItIsClosed() = runBlocking {
    val closeStarted = CompletableDeferred<Unit>()
    val closeRelease = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        closeGate = {
          if (it == 1) {
            closeStarted.complete(Unit)
            closeRelease.await()
          }
        }
      )
    val prewarming = newPool(model)
    prewarming.connectOnce("Greet the caller.")
    eventually { model.connections.size == 2 }
    // The spare ends and starts closing, but its close holds until released.
    model.connections[1].serverCloses()
    withTimeout(GUARD_TIMEOUT) { closeStarted.await() }

    val closing = async(Dispatchers.Default) { prewarming.close() }
    delay(QUIET_PERIOD)
    val closedEarly = closing.isCompleted
    closeRelease.complete(Unit)
    withTimeout(GUARD_TIMEOUT) { closing.await() }

    assertFalse(closedEarly)
    assertTrue(model.connections[1].isClosed)
  }

  @Test
  fun connect_moreSetupsThanTheLimit_closesSparesOfTheLeastRecentlyRequested() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      for (i in 0 until PrewarmingLiveModel.DEFAULT_MAX_SETUPS) {
        prewarming.connectOnce(setup = "Setup $i")
        eventually { model.connectionsFor("Setup $i").size == 2 }
      }
      // Requesting Setup 0 again leaves Setup 1 as the least recently requested.
      prewarming.connectOnce(setup = "Setup 0")
      eventually { model.connectionsFor("Setup 0").size == 3 }

      prewarming.connectOnce(setup = "Setup ${PrewarmingLiveModel.DEFAULT_MAX_SETUPS}")

      eventually { model.connectionsFor("Setup 1")[1].isClosed }
      assertFalse(model.connectionsFor("Setup 0").last().isClosed)
    }
  }

  @Test
  fun connect_customMaxSetups_closesSparesBeyondIt() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, maxSetups = 2).use { prewarming ->
      prewarming.connectOnce(setup = "Setup 0")
      eventually { model.connectionsFor("Setup 0").size == 2 }
      prewarming.connectOnce(setup = "Setup 1")
      eventually { model.connectionsFor("Setup 1").size == 2 }

      prewarming.connectOnce(setup = "Setup 2")

      eventually { model.connectionsFor("Setup 0")[1].isClosed }
      eventually { model.connectionsFor("Setup 2").size == 2 }
      assertFalse(model.connectionsFor("Setup 1")[1].isClosed)
    }
  }

  @Test
  fun connect_failsAfterTakingTheLastSpare_freesTheSetupsPlace() = runBlocking {
    val release = CompletableDeferred<Unit>()
    // Every open for the failing setup after its first fails, its spare's (1) once released.
    val model =
      FakeLiveModel(
        gate = { if (it == 1) release.await() },
        failIf = { request, index -> request.config.labels?.get("setup") == "Failing" && index > 0 },
      )
    newPool(model).use { prewarming ->
      prewarming.connectOnce(setup = "Failing")
      eventually { model.requests.size == 2 }
      for (i in 0 until PrewarmingLiveModel.DEFAULT_MAX_SETUPS - 1) {
        prewarming.connectOnce(setup = "Setup $i")
        eventually { model.connectionsFor("Setup $i").size == 2 }
      }
      // Takes the failing setup's only spare, making it the most recently requested.
      val failed = async { runCatching { prewarming.connect(request(setup = "Failing")) } }
      delay(QUIET_PERIOD)
      release.complete(Unit)
      assertTrue(withTimeout(GUARD_TIMEOUT) { failed.await() }.isFailure)

      // A forgotten failing setup leaves room, so the next setup evicts nobody's spare.
      prewarming.connectOnce(setup = "Setup last")
      eventually { model.connectionsFor("Setup last").size == 2 }
      delay(QUIET_PERIOD)

      assertTrue(model.connections.none { it.isClosed })
    }
  }

  @Test
  fun spareReplacement_afterItsSetupWasEvicted_isNotOpened() = runBlocking {
    val closeStarted = CompletableDeferred<Unit>()
    val closeRelease = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        closeGate = {
          if (it == 1) {
            closeStarted.complete(Unit)
            closeRelease.await()
          }
        }
      )
    newPool(model, minLifetimeForReplacement = null).use { prewarming ->
      prewarming.connectOnce(setup = "Evicted")
      eventually { model.connections.size == 2 }
      // The spare ends and is due a replacement, but its close holds until released.
      model.connections[1].serverCloses()
      withTimeout(GUARD_TIMEOUT) { closeStarted.await() }
      for (i in 0 until PrewarmingLiveModel.DEFAULT_MAX_SETUPS) {
        prewarming.connectOnce(setup = "Setup $i")
        eventually { model.connectionsFor("Setup $i").size == 2 }
      }

      closeRelease.complete(Unit)
      eventually { model.connections[1].isClosed }
      delay(QUIET_PERIOD)

      assertEquals(2, model.connectionsFor("Evicted").size)
      assertTrue(model.connections.filter { it.index > 1 }.none { it.isClosed })
    }
  }

  @Test
  fun connect_otherInstructionAndTools_takesTheSpareAndSwitchesItToThem() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce("Greet the caller.")
      eventually { model.connections.size == 2 }

      val taken = prewarming.connect(request("Read the slots.", tools = listOf(SLOTS)))

      assertSame(model.connections[1], taken)
      assertEquals(
        listOf(ContextUpdate(userMessage("Read the slots."), listOf(SLOTS))),
        model.connections[1].contextUpdates,
      )
    }
  }

  @Test
  fun connect_noInstructionOrTools_takesTheSpareWithoutAnUpdate() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val taken =
        prewarming.connect(
          LlmRequest(config = GenerateContentConfig(labels = mapOf("setup" to "main")))
        )

      assertSame(model.connections[1], taken)
      assertEquals(emptyList(), model.connections[1].contextUpdates)
    }
  }

  @Test
  fun connect_instructionOnly_switchesOnlyTheInstruction() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val unused = prewarming.connect(request("Read the slots."))

      assertEquals(
        listOf(ContextUpdate(userMessage("Read the slots."), null)),
        model.connections[1].contextUpdates,
      )
    }
  }

  @Test
  fun connect_toolsOnly_switchesOnlyTheTools() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val unused =
        prewarming.connect(
          LlmRequest(
            config = GenerateContentConfig(tools = listOf(SLOTS), labels = mapOf("setup" to "main"))
          )
        )

      assertEquals(listOf(ContextUpdate(null, listOf(SLOTS))), model.connections[1].contextUpdates)
    }
  }

  @Test
  fun connect_spareTakingNoContextUpdates_opensItsOwnAndClosesEverySpare() = runBlocking {
    val model = FakeLiveModel(updateFailure = UnsupportedOperationException("No updates."))
    newPool(model, spares = 2).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 3 }

      val second = prewarming.connect(request("Read the slots."))

      assertSame(model.connections[3], second)
      eventually { model.connections[1].isClosed && model.connections[2].isClosed }
      delay(QUIET_PERIOD)
      assertEquals(4, model.connections.size)
    }
  }

  @Test
  fun close_whileAStoppedPoolClosesItsSpares_waitsForThem() = runBlocking {
    val closeStarted = CompletableDeferred<Unit>()
    val closeRelease = CompletableDeferred<Unit>()
    val closes = AtomicInt(0)
    val model =
      FakeLiveModel(
        updateFailure = UnsupportedOperationException("No updates."),
        closeGate = {
          // The first close is the taken spare; the second is the stopped pool closing the other.
          if (closes.addAndFetch(1) == 2) {
            closeStarted.complete(Unit)
            closeRelease.await()
          }
        },
      )
    val prewarming = newPool(model, spares = 2)
    prewarming.connectOnce()
    eventually { model.connections.size == 3 }
    // Takes the newest spare, which rejects updates, so the pool stops and closes the other spare.
    prewarming.connectOnce("Read the slots.")
    withTimeout(GUARD_TIMEOUT) { closeStarted.await() }

    val closing = async(Dispatchers.Default) { prewarming.close() }
    delay(QUIET_PERIOD)
    val closedEarly = closing.isCompleted
    closeRelease.complete(Unit)
    withTimeout(GUARD_TIMEOUT) { closing.await() }

    assertFalse(closedEarly)
    assertTrue(model.connections[1].isClosed && model.connections[2].isClosed)
  }

  @Test
  fun connect_cancelledDuringContextUpdate_closesTheSpareAndOpensNothing() = runBlocking {
    val updateStarted = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        updateGate = {
          updateStarted.complete(Unit)
          awaitCancellation()
        },
        // A close that suspends, as a real one does, so a cancelled caller could cut it short.
        closeGate = { delay(10.milliseconds) },
      )
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val connecting = async { prewarming.connect(request("Read the slots.")) }
      withTimeout(GUARD_TIMEOUT) { updateStarted.await() }
      connecting.cancelAndJoin()
      delay(QUIET_PERIOD)

      assertTrue(model.connections[1].isClosed)
      assertEquals(2, model.requests.size)
    }
  }

  @Test
  fun connect_contextUpdateCancelledByTheConnection_closesTheSpareAndOpensItsOwn() = runBlocking {
    val model = FakeLiveModel(updateFailure = CancellationException("Socket closed."))
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val second = prewarming.connect(request("Read the slots."))

      assertSame(model.connections[2], second)
      assertTrue(model.connections[1].isClosed)
    }
  }

  @Test
  fun connect_contextUpdateFailing_closesTheSpareAndOpensItsOwn() = runBlocking {
    val model = FakeLiveModel(updateFailure = IllegalStateException("Socket closed."))
    newPool(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val second = prewarming.connect(request("Read the slots."))

      assertSame(model.connections[2], second)
      assertTrue(model.connections[1].isClosed)
      // Only a connection that takes no updates at all stops the pool.
      eventually { model.connections.size == 4 }
    }
  }

  @Test
  fun connect_contextUpdateFailingOnOneOfTwoSpares_takesTheOtherSpare() = runBlocking {
    // Spares open concurrently, so the first update to arrive fails, whichever spare it is.
    val failedOnce = AtomicBoolean(false)
    val model =
      FakeLiveModel(
        updateGate = {
          if (failedOnce.compareAndSet(false, true)) throw IllegalStateException("Socket closed.")
        }
      )
    newPool(model, spares = 2).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 3 }
      delay(QUIET_PERIOD)

      val second = prewarming.connect(request("Read the slots."))
      val requestsAfterConnect = model.requests.size

      val spares = listOf(model.opened(1), model.opened(2))
      assertEquals(1, spares.count { it.isClosed })
      assertSame(spares.single { !it.isClosed }, second)
      assertEquals(
        listOf(ContextUpdate(userMessage("Read the slots."), null)),
        spares.single { !it.isClosed }.contextUpdates,
      )
      // Both attempts used spares: no direct connect opened a fourth connection.
      assertEquals(3, requestsAfterConnect)
    }
  }

  @Test
  fun updateCheck_testsTheFirstSpareBeforeHandingItOverAndNoLaterOne() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, updateCheckWindow = 300.milliseconds).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      // The spare's check may still be running when taken, so connect waits for it.
      val taken = prewarming.connect(request("Read the slots."))
      eventually { model.connections.size == 3 }
      delay(QUIET_PERIOD)

      assertSame(model.connections[1], taken)
      assertEquals(listOf(0, 1, 0), model.connections.map { it.testUpdates })
      assertEquals(listOf(true, false), model.connections[1].allUpdates.map { it.isTest })
      assertEquals(
        listOf(ContextUpdate(userMessage("Read the slots."), null)),
        model.connections[1].contextUpdates,
      )
    }
  }

  @Test
  fun updateCheck_serverEndsTheSpare_closesItKeepsNoSparesAndConnectsDirectly() = runBlocking {
    val model = FakeLiveModel(endOnUpdate = setOf(0))
    newPool(model, updateCheckWindow = GUARD_TIMEOUT).use { prewarming ->
      prewarming.prewarm(LiveRunConfig())
      eventually { model.connections.size == 1 && model.connections[0].isClosed }

      val taken = prewarming.connect(request("Greet the caller."))
      delay(QUIET_PERIOD)

      assertSame(model.connections[1], taken)
      assertFalse(model.connections[1].isClosed)
      assertEquals(emptyList(), model.connections[1].contextUpdates)
      // A pool that keeps no spares opens no further connections after connect.
      assertEquals(2, model.connections.size)
    }
  }

  @Test
  fun updateCheck_spareAlreadyEnding_isNotTestedAndTheNextSpareIs() = runBlocking {
    val model = FakeLiveModel(goAwayOnOpen = setOf(1))
    newPool(model, updateCheckWindow = 50.milliseconds).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 && model.connections[1].isClosed }

      val taken = prewarming.connect(request("Greet the caller."))
      eventually { model.connections.size == 4 && model.connections[3].testUpdates == 1 }
      delay(QUIET_PERIOD)

      assertEquals(0, model.connections[1].testUpdates)
      assertSame(model.connections[2], taken)
      assertFalse(model.connections[3].isClosed)
    }
  }

  @Test
  fun updateCheck_testUpdateFailsWhileTheSpareIsTaken_closesItAndConnectsDirectly() = runBlocking {
    val release = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        updateGate = {
          if (it == 1) {
            release.await()
            throw IllegalStateException("The send failed.")
          }
        }
      )
    newPool(model, updateCheckWindow = GUARD_TIMEOUT).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      // This connect waits for the spare's check, which then fails.
      val taken = async { prewarming.connect(request("Greet the caller.")) }
      delay(QUIET_PERIOD)
      release.complete(Unit)

      val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
      assertSame(model.connections[2], connection)
      assertTrue(model.connections[1].isClosed)
      // A failed send is not a refusal, so the pool still opens a spare after the connect.
      eventually { model.connections.size == 4 }
      assertFalse(model.connections[3].isClosed)
    }
  }

  @Test
  fun updateCheck_serverEndsTheSpareWhileItIsTaken_closesItKeepsNoSparesAndConnectsDirectly() =
    runBlocking {
      val release = CompletableDeferred<Unit>()
      val model =
        FakeLiveModel(endOnUpdate = setOf(1), updateGate = { if (it == 1) release.await() })
      newPool(model, updateCheckWindow = GUARD_TIMEOUT).use { prewarming ->
        prewarming.connectOnce()
        eventually { model.connections.size == 2 }

        // This connect waits for the spare's check, which the server refuses by ending the session.
        val taken = async { prewarming.connect(request("Greet the caller.")) }
        delay(QUIET_PERIOD)
        release.complete(Unit)

        val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
        assertSame(model.connections[2], connection)
        assertTrue(model.connections[1].isClosed)
        delay(QUIET_PERIOD)
        assertEquals(3, model.connections.size)
      }
    }

  @Test
  fun updateCheck_spareEndedAfterAnotherPassedItsCheck_isDroppedAndThePoolKeepsSpares() =
    runBlocking {
      val waiting = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val model =
        FakeLiveModel(
          endOnUpdate = setOf(2),
          updateGate = {
            if (it == 2) {
              waiting.complete(Unit)
              release.await()
            }
          },
        )
      newPool(model, spares = 2, updateCheckWindow = 100.milliseconds).use { prewarming ->
        prewarming.connectOnce()
        eventually { model.connections.size == 3 && model.opened(1).testUpdates == 1 }
        withTimeout(GUARD_TIMEOUT) { waiting.await() }
        // Let the first spare pass its check while the second still waits to send its update.
        delay(1.seconds)
        release.complete(Unit)
        eventually { model.opened(2).isClosed }

        val taken = prewarming.connect(request("Greet the caller."))

        assertSame(model.opened(1), taken)
      }
    }

  @Test
  fun updateCheck_serverEndsTheSpareWithoutARefusal_dropsItAndChecksTheNextSpare() = runBlocking {
    val model = FakeLiveModel(endWithoutRefusalOnUpdate = setOf(1))
    newPool(model, minLifetimeForReplacement = null, updateCheckWindow = 300.milliseconds).use {
      prewarming ->
      prewarming.connectOnce()
      // The first spare ends during its check, but not for refusing it, so it is replaced.
      eventually { model.connections.size == 3 && model.opened(2).testUpdates == 1 }
      delay(QUIET_PERIOD)

      val taken = prewarming.connect(request("Greet the caller."))

      assertTrue(model.opened(1).isClosed)
      assertSame(model.opened(2), taken)
    }
  }

  @Test
  fun updateCheck_connectionThatCannotReportARefusal_isNotTested() = runBlocking {
    // Its connections throw on any context update, so a test update would stop the pool.
    val model = PlainLiveModel(reportsServerEnd = true)
    newPool(model, updateCheckWindow = GUARD_TIMEOUT).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }
      delay(QUIET_PERIOD)

      assertFalse(model.connections[1].closed.load())
    }
  }

  @Test
  fun updateCheck_publicConstructorDefaults_sendNoTestUpdate() = runBlocking {
    val model = FakeLiveModel()
    PrewarmingLiveModel(model).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val taken = prewarming.connect(request("Read the slots."))

      assertSame(model.connections[1], taken)
      assertEquals(0, model.connections[1].testUpdates)
    }
  }

  @Test
  fun close_whileATakenSpareIsUnderCheck_closesItBeforeReturning() = runBlocking {
    val checking = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        updateGate = {
          if (it == 1) {
            checking.complete(Unit)
            awaitCancellation()
          }
        }
      )
    val prewarming = newPool(model, updateCheckWindow = GUARD_TIMEOUT)
    prewarming.connectOnce()
    withTimeout(GUARD_TIMEOUT) { checking.await() }
    // The connect runs only when pumped, so it cannot close the spare on its own.
    val held = HeldDispatcher()
    val taken = async(held) { prewarming.connect(request("Greet the caller.")) }
    repeat(20) {
      held.runPending()
      delay(10.milliseconds)
    }

    withContext(Dispatchers.Default) { prewarming.close() }
    val closedOnReturn = model.connections[1].isClosed
    val pump = launch {
      while (true) {
        held.runPending()
        delay(5.milliseconds)
      }
    }
    // Because the check was cancelled, the connect opens its own connection.
    val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
    pump.cancel()

    assertTrue(closedOnReturn)
    assertSame(model.connections[2], connection)
  }

  @Test
  fun prewarm_opensSparesBeforeAnyConnect() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2).use { prewarming ->
      prewarming.prewarm(LiveRunConfig(), GenerateContentConfig(temperature = 0.5f))

      eventually { model.connections.size == 2 }
      delay(QUIET_PERIOD)
      assertEquals(2, model.connections.size)
      // The agent's sampling settings fold into the live config, as for an agent's own request.
      assertEquals(0.5f, model.requests[0].liveConnectConfig.temperature)
    }
  }

  @Test
  fun prewarm_resumingRunConfig_opensSparesWithoutTheHandle() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.prewarm(LiveRunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1")))

      eventually { model.connections.size == 1 }
      assertEquals(SessionResumptionConfig(), model.requests[0].liveConnectConfig.sessionResumption)
    }
  }

  @Test
  fun connect_requestWithoutModel_takesTheSpareOfTheWrappedModel() = runBlocking {
    val model = FakeLiveModel()
    newPool(model).use { prewarming ->
      prewarming.prewarm(LiveRunConfig())
      eventually { model.connections.size == 1 }

      val taken =
        prewarming.connect(LlmRequest(liveConnectConfig = model.requests[0].liveConnectConfig))

      assertSame(model.connections[0], taken)
    }
  }

  @Test
  fun prewarm_liveAgentsFirstConnect_takesTheSpareAndSwitchesIt() = runBlocking {
    val model = FakeLiveModel()
    val liveRunConfig = LiveRunConfig(responseModalities = listOf(Modality.AUDIO))
    val config = GenerateContentConfig(temperature = 0.5f)
    newPool(model).use { prewarming ->
      prewarming.prewarm(liveRunConfig, config)
      eventually { model.connections.size == 1 }
      val agent =
        LlmAgent(
          name = "greeter",
          model = prewarming,
          instruction = Instruction("Greet the caller."),
          generateContentConfig = config,
        )

      val run = launch {
        val unused =
          InMemoryRunner(agent = agent)
            .runLive("u", "s", LiveRequestQueue(), RunConfig(live = liveRunConfig))
            .toList()
      }
      eventually { model.connections[0].contextUpdates.isNotEmpty() && model.requests.size == 2 }
      run.cancelAndJoin()

      val update = model.connections[0].contextUpdates.single()
      assertTrue(update.systemInstruction?.text().orEmpty().contains("Greet the caller."))
      assertEquals(null, update.tools)
      // The agent connected through the spare: neither open carried its instruction.
      assertTrue(model.requests.all { it.config.systemInstruction == null })
    }
  }

  @Test
  fun generateContent_delegatesToTheWrappedModel() = runBlocking {
    val prewarming = newPool(FakeLiveModel())

    val responses = prewarming.generateContent(request("Greet the caller.")).toList()

    assertEquals(listOf(LlmResponse(content = modelMessage("generated"))), responses)
    assertEquals("fake-live-model", prewarming.name)
  }

  @Test
  fun connect_newerSpareStillOpening_takesTheOpenOne() = runBlocking {
    val release = CompletableDeferred<Unit>()
    val model = FakeLiveModel(gate = { if (it == 3) release.await() })
    newPool(model, spares = 2).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 3 }
      val second = prewarming.connect(request("Greet the caller."))
      eventually { model.requests.size == 4 }

      val taken = withTimeout(GUARD_TIMEOUT) { prewarming.connect(request("Greet the caller.")) }

      release.complete(Unit)
      // Opens 1 and 2 are the first two spares; open 3 replaces the one taken and stays pending.
      assertSame(model.connections.single { it.index in 1..2 && it !== second }, taken)
    }
  }

  @Test
  fun connect_noSpareOpenYet_takesTheOldestStillOpening(): Unit = runBlocking {
    val older = CompletableDeferred<Unit>()
    val newer = CompletableDeferred<Unit>()
    val model =
      FakeLiveModel(
        gate = {
          when (it) {
            3 -> older.await()
            4 -> newer.await()
          }
        }
      )
    newPool(model, spares = 2).use { prewarming ->
      prewarming.replaceFirstSparesOneByOne(model)

      val taken =
        async(start = CoroutineStart.UNDISPATCHED) {
          prewarming.connect(request("Greet the caller."))
        }
      // Lets the connect pick its spare while both spares are still opening.
      delay(QUIET_PERIOD)
      older.complete(Unit)

      val connection = withTimeout(GUARD_TIMEOUT) { taken.await() }
      newer.complete(Unit)
      assertSame(model.opened(3), connection)
    }
  }

  @Test
  fun prewarm_setupAlreadyKept_makesItTheMostRecent() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, spares = 2, maxSetups = 2).use { prewarming ->
      prewarming.prewarmSetup("A")
      eventually { model.connectionsFor("A").size == 2 }
      prewarming.prewarmSetup("B")
      eventually { model.connectionsFor("B").size == 2 }
      // A spare ended this early is not replaced, so prewarming A again opens one.
      model.connectionsFor("A")[0].serverSends(LlmResponse(goAway = GO_AWAY))
      eventually { model.connectionsFor("A")[0].isClosed }

      prewarming.prewarmSetup("A")
      eventually { model.connectionsFor("A").size == 3 }
      prewarming.prewarmSetup("C")

      eventually { model.connectionsFor("B").all { it.isClosed } }
      assertFalse(model.connectionsFor("A")[1].isClosed)
    }
  }

  @Test
  fun updateCheck_nullWindow_sendsNoTestUpdate() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, updateCheckWindow = null).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      val taken = prewarming.connect(request("Greet the caller."))

      assertEquals(0, (taken as FakeConnection).testUpdates)
    }
  }

  @Test
  fun context_opensSparesInIt() = runBlocking {
    val seen = CompletableDeferred<String?>()
    val model =
      FakeLiveModel(gate = { seen.complete(currentCoroutineContext()[CoroutineName]?.name) })
    PrewarmingLiveModel(model, context = POOL_CONTEXT).use { prewarming ->
      prewarming.prewarm(LiveRunConfig())

      assertEquals("pool", withTimeout(GUARD_TIMEOUT) { seen.await() })
    }
  }

  @Test
  fun context_withACanceledJob_ignoresIt() = runBlocking {
    val model = FakeLiveModel()
    val canceled = Job().apply { cancel() }
    PrewarmingLiveModel(model, context = canceled + Dispatchers.Default).use { prewarming ->
      prewarming.prewarm(LiveRunConfig())

      eventually { model.connections.size == 1 }
    }
  }

  @Test
  fun spareReplacementEnded_keepWarmForAfterTheLastConnect_isClosedButNotReplaced() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, keepWarmFor = 1500.milliseconds, minLifetimeForReplacement = null).use {
      prewarming ->
      prewarming.connectOnce()
      val connected = TimeSource.Monotonic.markNow()
      eventually { model.connections.size == 2 }
      // The first spare ends within keepWarmFor of the connect, so a replacement opens.
      delay(1.seconds - connected.elapsedNow())
      model.opened(1).serverCloses()
      eventually { model.connections.size == 3 }
      // The replacement ends after keepWarmFor since the connect, though not since it opened.
      delay(2.seconds - connected.elapsedNow())
      model.opened(2).serverCloses()
      eventually { model.opened(2).isClosed }
      delay(QUIET_PERIOD)

      assertEquals(3, model.connections.size)
    }
  }

  @Test
  fun maxSpareAge_spareReachesIt_isClosedAndReplaced() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, minLifetimeForReplacement = null, maxSpareAge = 300.milliseconds).use {
      prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      eventually { model.opened(1).isClosed && model.connections.size == 3 }
      val taken = prewarming.connect(request("Greet the caller."))

      // The replacement or, if it aged out too, a new connection; never the retired spare.
      assertTrue(taken !== model.opened(1))
    }
  }

  @Test
  fun maxSpareAge_belowMinLifetimeForReplacement_closesTheSpareWithoutReplacingIt() = runBlocking {
    val model = FakeLiveModel()
    newPool(model, minLifetimeForReplacement = 1.minutes, maxSpareAge = 300.milliseconds).use {
      prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }

      eventually { model.opened(1).isClosed }
      delay(QUIET_PERIOD)

      // An age shorter than the minimum lifetime cannot keep the pool reconnecting.
      assertEquals(2, model.connections.size)
    }
  }

  @Test
  fun maxSpareAge_setupNotRequestedWithinKeepWarmFor_closesTheSpareWithoutReplacingIt() =
    runBlocking {
      val model = FakeLiveModel()
      newPool(model, keepWarmFor = 100.milliseconds, maxSpareAge = 300.milliseconds).use {
        prewarming ->
        prewarming.connectOnce()
        eventually { model.connections.size == 2 }

        eventually { model.opened(1).isClosed }
        delay(QUIET_PERIOD)

        assertEquals(2, model.connections.size)
      }
    }

  @Test
  fun serverEnding_null_spareIsKeptUntilItsMaxAge() = runBlocking {
    val model = PlainLiveModel()
    newPool(model, minLifetimeForReplacement = null, maxSpareAge = 1.seconds).use { prewarming ->
      prewarming.connectOnce()
      eventually { model.connections.size == 2 }
      delay(QUIET_PERIOD)
      val openBeforeItsAge = !model.connections[1].closed.load()

      // With no end signal, only age retires the spare.
      eventually { model.connections[1].closed.load() && model.connections.size == 3 }
      assertTrue(openBeforeItsAge)
    }
  }

  @Test
  fun constructor_noSpares_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> { PrewarmingLiveModel(FakeLiveModel(), spares = 0) }
  }

  @Test
  fun constructor_noSetups_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), maxSetups = 0)
    }
  }

  @Test
  fun constructor_zeroKeepWarmFor_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), keepWarmFor = Duration.ZERO)
    }
  }

  @Test
  fun constructor_zeroMinLifetimeForReplacement_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), minLifetimeForReplacement = Duration.ZERO)
    }
  }

  @Test
  fun constructor_zeroMaxSpareAge_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), maxSpareAge = Duration.ZERO)
    }
  }

  @Test
  fun constructor_infiniteUpdateCheckWindow_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), updateCheckWindow = Duration.INFINITE)
    }
  }

  @Test
  fun constructor_zeroUpdateCheckWindow_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel(FakeLiveModel(), updateCheckWindow = Duration.ZERO)
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetProperties_matchConstructorDefaults() {
    val model = FakeLiveModel()
    PrewarmingLiveModel.builder().model(model).build().use { built ->
      PrewarmingLiveModel(model).use { constructed ->
        assertEquals(constructed.settings(), built.settings())
      }
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_setProperties_areApplied() {
    PrewarmingLiveModel.builder()
      .model(FakeLiveModel())
      .spares(2)
      .maxSetups(3)
      .keepWarmForMillis(5_000)
      .minLifetimeForReplacementMillis(6_000)
      .maxSpareAgeMillis(8_000)
      .updateCheckWindowMillis(7_000)
      .context(POOL_CONTEXT)
      .build()
      .use { built ->
        assertEquals(
          listOf(2, 3, 5.seconds, 6.seconds, 8.seconds, 7.seconds, POOL_CONTEXT),
          built.settings(),
        )
      }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_durationSetters_areApplied() {
    PrewarmingLiveModel.builder()
      .model(FakeLiveModel())
      .keepWarmFor(5.seconds)
      .minLifetimeForReplacement(6.seconds)
      .maxSpareAge(8.seconds)
      .updateCheckWindow(7.seconds)
      .build()
      .use { built ->
        assertEquals(
          listOf(
            1,
            PrewarmingLiveModel.DEFAULT_MAX_SETUPS,
            5.seconds,
            6.seconds,
            8.seconds,
            7.seconds,
            Dispatchers.Default,
          ),
          built.settings(),
        )
      }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_negativeKeepWarmForMillis_throwsIllegalArgumentException() {
    assertFailsWith<IllegalArgumentException> {
      PrewarmingLiveModel.builder().model(FakeLiveModel()).keepWarmForMillis(-1).build()
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_nullDurations_turnThemOff() {
    PrewarmingLiveModel.builder()
      .model(FakeLiveModel())
      .keepWarmForMillis(null)
      .minLifetimeForReplacementMillis(null)
      .maxSpareAgeMillis(null)
      .updateCheckWindow(null)
      .build()
      .use { built ->
        assertEquals(
          listOf(
            1,
            PrewarmingLiveModel.DEFAULT_MAX_SETUPS,
            null,
            null,
            null,
            null,
            Dispatchers.Default,
          ),
          built.settings(),
        )
      }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_noModel_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> { PrewarmingLiveModel.builder().build() }
  }
}
