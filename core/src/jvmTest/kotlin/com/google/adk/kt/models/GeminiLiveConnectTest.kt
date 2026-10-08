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

package com.google.adk.kt.models

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.SpeechConfig
import com.google.adk.kt.types.Tool
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.kotlin.types.LiveConnectConfig as GenAiLiveConnectConfig
import com.google.genai.kotlin.types.LiveServerMessage as SdkLiveServerMessage
import com.google.genai.kotlin.types.LiveServerSetupComplete as SdkLiveServerSetupComplete
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** `connect` returns once the server confirms setup, so every fake session sends it first. */
private val SETUP_COMPLETE = SdkLiveServerMessage(setupComplete = SdkLiveServerSetupComplete())

/**
 * Tests what `Gemini.connect` does itself: the config and model it connects with, where the session
 * is read, closing a session its cancelled caller never received, and the order of `closeSession`'s
 * two steps.
 */
class GeminiLiveConnectTest {

  /** Captures the config a connect was made with, and returns a session that just parks. */
  private class RecordingLive : Gemini.GeminiLive {
    var connectedWith: GenAiLiveConnectConfig? = null
    var connectedModel: String? = null

    override suspend fun connect(model: String, config: GenAiLiveConnectConfig): LiveSessionHandle {
      connectedModel = model
      connectedWith = config
      return ParkingHandle()
    }
  }

  private class ParkingHandle : LiveSessionHandle {
    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      emit(SETUP_COMPLETE)
      awaitCancellation()
    }

    override suspend fun sendClientContent(
      turns: List<com.google.genai.kotlin.types.Content>,
      turnComplete: Boolean,
    ) {}

    override suspend fun sendRealtimeInput(
      audio: com.google.genai.kotlin.types.Blob?,
      video: com.google.genai.kotlin.types.Blob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: com.google.genai.kotlin.types.ActivityStart?,
      activityEnd: com.google.genai.kotlin.types.ActivityEnd?,
    ) {}

    override suspend fun sendToolResponse(
      functionResponses: List<com.google.genai.kotlin.types.FunctionResponse>
    ) {}

    override suspend fun closeSession() {}
  }

  /**
   * Records the order of shutdown, and models the SDK turning a cancelled read into an API error.
   *
   * The SDK catches `Exception` around its read loop, and when the close reason is not a normal one
   * it turns what it caught into an API exception - so a cancellation arriving before the session
   * has been asked to close comes back as a connection error rather than as a clean shutdown. That
   * is why the two steps of `closeSession` are ordered as they are.
   */
  private class ShutdownOrderHandle : LiveSessionHandle {
    /**
     * Ordered shutdown events. A channel rather than a list because the pump writes these from
     * [Dispatchers.Default] while the test reads them, and a channel makes that safe by
     * construction instead of by a lock nobody would remember to take.
     */
    val events = Channel<String>(Channel.UNLIMITED)

    /** Completes once the pump is genuinely parked inside [receive]; see the test's gate. */
    val parked = CompletableDeferred<Unit>()

    /**
     * The pump's own verdict on what it woke up to: whether the session had already been ended when
     * its read was cancelled.
     *
     * This is the ground truth the ordering is about. Arrival order in [events] only approximates
     * it, and approximates it by way of which thread won a write.
     */
    val pumpVerdict = CompletableDeferred<String>()

    private val sessionClosed = CompletableDeferred<Unit>()

    /** Set on ENTRY to closeSession, before the deferred is completed, to attribute a verdict. */
    @Volatile var closeSessionEntered = false

    override fun receive(): Flow<SdkLiveServerMessage> = flow {
      emit(SETUP_COMPLETE)
      try {
        suspendCancellableCoroutine<Nothing> { continuation ->
          // Signalled from INSIDE the suspension, so the pump really is parked.
          parked.complete(Unit)
        }
      } catch (e: Exception) {
        if (!sessionClosed.isCompleted) {
          val unused = events.trySend("cancelled-before-close")
          pumpVerdict.complete("cancelled-before-close(entered=$closeSessionEntered)")
          throw IllegalStateException("ConnectionClosed: abnormal close reason")
        }
        val unused = events.trySend("cancelled-after-close")
        pumpVerdict.complete("cancelled-after-close")
        throw e
      }
    }

    override suspend fun sendClientContent(
      turns: List<com.google.genai.kotlin.types.Content>,
      turnComplete: Boolean,
    ) {}

    override suspend fun sendRealtimeInput(
      audio: com.google.genai.kotlin.types.Blob?,
      video: com.google.genai.kotlin.types.Blob?,
      audioStreamEnd: Boolean?,
      text: String?,
      activityStart: com.google.genai.kotlin.types.ActivityStart?,
      activityEnd: com.google.genai.kotlin.types.ActivityEnd?,
    ) {}

    override suspend fun sendToolResponse(
      functionResponses: List<com.google.genai.kotlin.types.FunctionResponse>
    ) {}

    override suspend fun closeSession() {
      closeSessionEntered = true
      val unused = events.trySend("closeSession")
      sessionClosed.complete(Unit)
    }
  }

  /**
   * Opens the way ktor does: in its own scope, so a caller cancelled while opening loses the
   * session unless connect closes it.
   */
  private class DetachedOpenLive(private val scope: CoroutineScope) : Gemini.GeminiLive {
    val handshake = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val delivered = CompletableDeferred<Unit>()
    val closedBy = CompletableDeferred<String>()
    // Delegates the rest, so a change to the handle's signatures stays inside ParkingHandle.
    val session: LiveSessionHandle =
      object : LiveSessionHandle by ParkingHandle() {
        override suspend fun closeSession() {
          closedBy.complete("closeSession")
        }

        override fun cancelSession() {
          closedBy.complete("cancelSession")
        }
      }

    override suspend fun connect(model: String, config: GenAiLiveConnectConfig): LiveSessionHandle {
      val opened = CompletableDeferred<LiveSessionHandle>()
      scope.launch {
        handshake.await()
        opened.complete(session)
        delivered.complete(Unit)
      }
      entered.complete(Unit)
      return opened.await()
    }
  }

  private val scope = CoroutineScope(Dispatchers.Default)

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  /** Counts [closeSession] calls; delegates the rest to [ParkingHandle]. */
  private class RecordingSessionHandle : LiveSessionHandle by ParkingHandle() {
    var closeCount = 0
      private set

    override suspend fun closeSession() {
      closeCount++
    }
  }

  /**
   * A [Gemini] whose live opener returns [handle], so [Gemini.connect] can be exercised offline.
   */
  private fun geminiReturning(handle: LiveSessionHandle): Gemini =
    gemini().apply {
      live =
        object : Gemini.GeminiLive {
          override suspend fun connect(
            model: String,
            config: GenAiLiveConnectConfig,
          ): LiveSessionHandle = handle
        }
    }

  @Test
  fun connect_returnsOnlyAfterSetupComplete() = runBlocking {
    val setupGate = CompletableDeferred<Unit>()
    val handle =
      object : LiveSessionHandle by RecordingSessionHandle() {
        override fun receive(): Flow<SdkLiveServerMessage> = flow {
          setupGate.await()
          emit(SETUP_COMPLETE)
          awaitCancellation()
        }
      }
    val connecting = async { geminiReturning(handle).connect(LlmRequest()) }

    // Before setup-complete, connect must not return.
    assertNull(withTimeoutOrNull(200.milliseconds) { connecting.await() })

    setupGate.complete(Unit)
    val connection = withTimeout(5.seconds) { connecting.await() }
    connection.closeSession()
  }

  @Test
  fun connect_receiveFailsBeforeSetup_throwsAndClosesTheSession() = runBlocking {
    val recorder = RecordingSessionHandle()
    val handle =
      object : LiveSessionHandle by recorder {
        override fun receive(): Flow<SdkLiveServerMessage> = flow {
          throw IllegalStateException("socket dropped before setup")
        }
      }

    assertFailsWith<IllegalStateException> {
      withTimeout(5.seconds) { geminiReturning(handle).connect(LlmRequest()) }
    }
    assertTrue(recorder.closeCount >= 1)
  }

  @Test
  fun connect_cancelledDuringSetupWait_closesTheSession() = runBlocking {
    val recorder = RecordingSessionHandle()
    val handle =
      object : LiveSessionHandle by recorder {
        // Never emits setup-complete, so connect stays in the setup wait.
        override fun receive(): Flow<SdkLiveServerMessage> = flow { awaitCancellation() }
      }
    val connecting = async { geminiReturning(handle).connect(LlmRequest()) }

    // Suspended in the setup wait.
    assertNull(withTimeoutOrNull(200.milliseconds) { connecting.await() })

    connecting.cancelAndJoin()
    assertTrue(recorder.closeCount >= 1)
  }

  @Test
  fun connect_explicitVadSignalOnGeminiApiClient_isLeftToTheSdk() = runBlocking {
    // apiKey construction leaves client.enterprise false; the SDK, not connect, rejects it.
    val recorder = RecordingLive()
    val request = LlmRequest(liveConnectConfig = LiveConnectConfig(explicitVadSignal = true))

    gemini().apply { live = recorder }.connect(request).closeSession()

    assertEquals(true, assertNotNull(recorder.connectedWith).explicitVadSignal)
  }

  @Test
  fun connect_transparentResumptionOnGeminiApiClient_isLeftToTheSdk() = runBlocking {
    // apiKey construction leaves client.enterprise false; the SDK, not connect, rejects it.
    val recorder = RecordingLive()
    val request =
      LlmRequest(
        liveConnectConfig =
          LiveConnectConfig(sessionResumption = SessionResumptionConfig(transparent = true))
      )

    gemini().apply { live = recorder }.connect(request).closeSession()

    assertEquals(true, assertNotNull(recorder.connectedWith).sessionResumption?.transparent)
  }

  @Test
  fun connect_resumingOnVertexWithTransparentUnset_asksForTransparentResumption() = runBlocking {
    val recorder = RecordingLive()

    vertexGemini()
      .apply { live = recorder }
      .connect(resumeRequest(transparent = null))
      .closeSession()

    assertEquals(true, assertNotNull(recorder.connectedWith).sessionResumption?.transparent)
  }

  @Test
  fun connect_resumingOnVertexWithTransparentFalse_keepsTheCallersChoice() = runBlocking {
    val recorder = RecordingLive()

    vertexGemini()
      .apply { live = recorder }
      .connect(resumeRequest(transparent = false))
      .closeSession()

    assertEquals(false, assertNotNull(recorder.connectedWith).sessionResumption?.transparent)
  }

  @Test
  fun connect_resumingOnGeminiApiClient_leavesTransparentUnset() = runBlocking {
    val recorder = RecordingLive()

    gemini().apply { live = recorder }.connect(resumeRequest(transparent = null)).closeSession()

    assertEquals(null, assertNotNull(recorder.connectedWith).sessionResumption?.transparent)
  }

  @Test
  fun connect_onVertexWithoutAHandle_leavesTransparentUnset() = runBlocking {
    val recorder = RecordingLive()
    val request =
      LlmRequest(
        liveConnectConfig = LiveConnectConfig(sessionResumption = SessionResumptionConfig())
      )

    vertexGemini().apply { live = recorder }.connect(request).closeSession()

    assertEquals(null, assertNotNull(recorder.connectedWith).sessionResumption?.transparent)
  }

  private fun resumeRequest(transparent: Boolean?) =
    LlmRequest(
      liveConnectConfig =
        LiveConnectConfig(
          sessionResumption =
            SessionResumptionConfig(handle = "resume-handle", transparent = transparent)
        )
    )

  private fun vertexGemini() =
    Gemini(
      name = "gemini-live-test",
      vertexCredentials =
        VertexCredentials(
          project = "test-project",
          location = "us-central1",
          credentials =
            GoogleCredentials.newBuilder()
              .setAccessToken(
                AccessToken(
                  "fake-token",
                  Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()),
                )
              )
              .build(),
        ),
    )

  // An explicit key, never null: null makes the SDK fall back to environment variables.
  private fun gemini() = Gemini(name = "gemini-live-test", apiKey = "fake-key-for-test")

  private fun requestWithAgentConfig() =
    LlmRequest(
      config =
        GenerateContentConfig(
          systemInstruction = Content(role = Role.USER, parts = listOf(Part(text = "be brief"))),
          tools =
            listOf(
              Tool(
                functionDeclarations =
                  listOf(FunctionDeclaration(name = "get_weather", description = "the weather"))
              )
            ),
        )
    )

  @Test
  fun connect_modelOnTheRequest_isTheOneConnectedTo() = runBlocking {
    val recorder = RecordingLive()
    val gemini = gemini().apply { live = recorder }

    gemini.connect(LlmRequest()).closeSession()
    assertEquals("gemini-live-test", recorder.connectedModel)

    val other = Gemini(name = "other-live-model", apiKey = "fake-key-for-test")
    gemini.connect(LlmRequest(model = other)).closeSession()
    assertEquals("other-live-model", recorder.connectedModel)
  }

  @Test
  fun connect_readsTheSessionOnTheCollectorsDispatcher() {
    val readOn = CompletableDeferred<String>()
    val live =
      object : Gemini.GeminiLive {
        override suspend fun connect(
          model: String,
          config: GenAiLiveConnectConfig,
        ): LiveSessionHandle =
          object : LiveSessionHandle by ParkingHandle() {
            override fun receive(): Flow<SdkLiveServerMessage> = flow {
              // Coroutine debug mode appends " @coroutine#N" to the thread name.
              readOn.complete(Thread.currentThread().name.substringBefore(" @"))
              emit(SETUP_COMPLETE)
              awaitCancellation()
            }
          }
      }
    val collector = Executors.newSingleThreadExecutor { Thread(it, "collector") }
    try {
      runBlocking(collector.asCoroutineDispatcher()) {
        val connection = gemini().apply { this.live = live }.connect(requestWithAgentConfig())
        try {
          assertEquals("collector", withTimeoutOrNull(5_000) { readOn.await() })
        } finally {
          withContext(NonCancellable) { connection.closeSession() }
        }
      }
    } finally {
      collector.shutdown()
    }
  }

  @Test
  fun connect_callerCancelledWhileTheSocketOpens_closesTheSessionOnceItOpens(): Unit = runBlocking {
    val live = DetachedOpenLive(scope)
    val gemini = gemini().apply { this.live = live }
    val caller = launch {
      val unused = gemini.connect(LlmRequest())
    }
    live.entered.await()

    caller.cancelAndJoin()
    live.handshake.complete(Unit)

    assertNotNull(
      withTimeoutOrNull(10_000) { live.closedBy.await() },
      "the session that opened after its caller was cancelled was never closed",
    )
  }

  @Test
  fun connect_sessionDeliveredAsTheCallerIsCancelled_stillClosesIt(): Unit = runBlocking {
    val live = DetachedOpenLive(scope)
    val gemini = gemini().apply { this.live = live }
    // Paused, so the caller's connect runs only when this test advances it.
    val callerDispatcher = StandardTestDispatcher()
    val caller =
      launch(callerDispatcher) {
        val unused = gemini.connect(LlmRequest())
      }
    // Advance connect up to where it parks awaiting the session, then leave it paused.
    callerDispatcher.scheduler.advanceUntilIdle()
    live.entered.await()

    live.handshake.complete(Unit)
    // Waits until the session has been handed over.
    live.delivered.await()
    // Still paused, so connect cannot take the session before the cancel lands.
    caller.cancel()
    callerDispatcher.scheduler.advanceUntilIdle()
    caller.join()

    assertNotNull(
      withTimeoutOrNull(10_000) { live.closedBy.await() },
      "the session delivered as its caller was cancelled was never closed",
    )
  }

  @Test
  fun connect_openFailsWithCancellationWhileCallerActive_throwsIllegalState(): Unit = runBlocking {
    // The open's own cancellation must not cancel an active caller; connect wraps it instead.
    val live =
      object : Gemini.GeminiLive {
        override suspend fun connect(
          model: String,
          config: GenAiLiveConnectConfig,
        ): LiveSessionHandle = throw CancellationException("the open's own job was cancelled")
      }

    val failure =
      assertFailsWith<IllegalStateException> {
        gemini().apply { this.live = live }.connect(LlmRequest())
      }

    // CancellationException is an IllegalStateException on JVM, so rule out the raw rethrow.
    assertTrue(failure !is CancellationException, "the open's cancellation was rethrown as is")
    assertEquals("The live session failed to open.", failure.message)
  }

  @Test
  fun connect_callerCancelledButTheOpenCompletedBeforeAwait_closesTheSession(): Unit = runBlocking {
    // An inline dispatcher completes the open before await(), which then skips the cancel check.
    val inline =
      object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
      }
    val closed = CompletableDeferred<String>()
    val handle =
      object : LiveSessionHandle by ParkingHandle() {
        override suspend fun closeSession() {
          closed.complete("closeSession")
        }

        override fun cancelSession() {
          closed.complete("cancelSession")
        }
      }
    val caller = AtomicReference<Job>()
    val live =
      object : Gemini.GeminiLive {
        override suspend fun connect(
          model: String,
          config: GenAiLiveConnectConfig,
        ): LiveSessionHandle {
          caller.get().cancel()
          return handle
        }
      }
    val connect =
      async(inline, start = CoroutineStart.LAZY) {
        gemini().apply { this.live = live }.connect(LlmRequest())
      }
    caller.set(connect)

    withTimeoutOrNull(10_000) { connect.join() } ?: error("connect never returned")

    assertFailsWith<CancellationException> { connect.await() }
    assertEquals(
      "closeSession",
      withTimeoutOrNull(10_000) { closed.await() },
      "the session opened for a cancelled caller was not closed gracefully",
    )
  }

  @Test
  fun connect_notCancelled_returnsTheSessionUnclosed(): Unit = runBlocking {
    val live = DetachedOpenLive(scope).apply { handshake.complete(Unit) }

    val connection = gemini().apply { this.live = live }.connect(LlmRequest())

    try {
      assertNull(
        withTimeoutOrNull(200) { live.closedBy.await() },
        "the session handed to a caller that was not cancelled was closed",
      )
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun connect_carriesTheAgentsToolsAndInstructionToTheSession() = runBlocking {
    // Pins the call to the config assembly: deleting it leaves the assembly's own tests green.
    val recorder = RecordingLive()
    val gemini = gemini().apply { live = recorder }

    gemini.connect(requestWithAgentConfig()).closeSession()

    val config = recorder.connectedWith
    assertNotNull(config, "connect never reached the session")
    assertEquals(
      listOf("get_weather"),
      config.tools?.flatMap { it.functionDeclarations.orEmpty().map { fn -> fn.name } },
      "the agent's tools did not reach the connect",
    )
    val instruction = config.systemInstruction
    assertNotNull(instruction, "the agent's instruction did not reach the connect")
    assertTrue(
      instruction.parts.orEmpty().any { it.text == "be brief" },
      "the instruction reached the connect but without its text: $instruction",
    )
  }

  @Test
  fun connect_modelSpeechConfig_overridesTheLiveConfigs() = runBlocking {
    // As in ADK Python, the model's own speech config wins over the run config's.
    val recorder = RecordingLive()
    val gemini =
      Gemini(
          name = "gemini-live-test",
          apiKey = "fake-key-for-test",
          speechConfig = SpeechConfig(languageCode = "de-DE"),
        )
        .apply { live = recorder }
    val request =
      LlmRequest(
        liveConnectConfig = LiveConnectConfig(speechConfig = SpeechConfig(languageCode = "fr-FR"))
      )

    gemini.connect(request).closeSession()

    assertEquals("de-DE", recorder.connectedWith?.speechConfig?.languageCode)
  }

  @Test
  fun connect_noModelSpeechConfig_keepsTheLiveConfigs() = runBlocking {
    val recorder = RecordingLive()
    val request =
      LlmRequest(
        liveConnectConfig = LiveConnectConfig(speechConfig = SpeechConfig(languageCode = "fr-FR"))
      )

    gemini().apply { live = recorder }.connect(request).closeSession()

    assertEquals("fr-FR", recorder.connectedWith?.speechConfig?.languageCode)
  }

  @Test
  fun closeSession_endsTheSessionBeforeStoppingTheReader() = runBlocking {
    // Cancelling the reader first parks and reports a clean shutdown as an error.
    val handle = ShutdownOrderHandle()
    val recorder =
      object : Gemini.GeminiLive {
        override suspend fun connect(
          model: String,
          config: GenAiLiveConnectConfig,
        ): LiveSessionHandle = handle
      }
    val connection = gemini().apply { live = recorder }.connect(LlmRequest())

    val collector = scope.launch { runCatching { connection.receive().collect {} } }
    // The pump must be PARKED in receive(): the only state where the orderings differ.
    withTimeoutOrNull(5_000) { handle.parked.await() }
      ?: error("the pump never parked in receive(), so this test could not distinguish the orders")

    connection.closeSession()
    collector.cancel()

    // The pump's verdict, not the order two threads happened to append to a log.
    assertEquals(
      "cancelled-after-close",
      withTimeoutOrNull(5_000) { handle.pumpVerdict.await() },
      "the reader was cancelled before the session ended, so the SDK saw an abnormal close reason",
    )
  }
}
