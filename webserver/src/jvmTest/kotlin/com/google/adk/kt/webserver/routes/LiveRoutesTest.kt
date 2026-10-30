/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(FrameworkInternalApi::class, ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.webserver.FakeArtifactService
import com.google.adk.kt.webserver.FakeSessionService as SharedFakeSessionService
import com.google.adk.kt.webserver.liveClient
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.AppLoaderApps
import com.google.common.truth.Truth.assertThat
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val PUMP_PATH = "/pump"

private const val AUDIO_FRAME = """{"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""

private const val JPEG_FRAME = """{"blob":{"mime_type":"image/jpeg","data":"AQID"}}"""

private const val CLOSE_FRAME = """{"close":true}"""

private const val INVALID_FRAME = "not json"

private const val KNOWN_SESSION_ID = "s"

private const val LOOPBACK = "127.0.0.1"

private val FLOOD_TEXT = "x".repeat(4096)

/**
 * About ten times the ~1,000 sends a stalled client lets through with the bound, most of them into
 * a 4 MB TCP send buffer; without the bound the count passes it within seconds.
 */
private const val MAX_SENDS_BEFORE_A_STALL = 10_000L

private val COLLECT_TIMEOUT = 10.seconds

/** How long a pump that should still be running is watched for an early return. */
private val STILL_READING = 500.milliseconds

private fun modelText(author: String, text: String) =
  Event(author = author, content = modelMessage(text))

/** Steps for a dummy live model that replies to the first audio the model receives. */
private val REPLY_TO_AUDIO: List<DummyLiveStep> =
  listOf(
    AwaitSent("audio") { it is RealtimeInput.Audio },
    Respond(LlmResponse(content = modelMessage("heard you"), partial = false)),
  )

private class FakeAppLoader(
  val started: CompletableDeferred<Unit> = CompletableDeferred(),
  val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
  val streamStoppedBy: CompletableDeferred<Throwable> = CompletableDeferred(),
  val floodSends: AtomicLong = AtomicLong(),
  val model: DummyLiveModel = DummyLiveModel(REPLY_TO_AUDIO),
) : AppLoader {
  override fun listApps() = listOf("live-agent", "echo-agent", "rich-agent")

  override fun loadApp(appName: String): App? =
    agentFor(appName)?.let { App(appName = appName, rootAgent = it) }

  private fun agentFor(agentName: String): BaseAgent? =
    when (agentName) {
      "live-agent" -> StubLiveAgent(agentName)
      "echo-agent" -> StubLiveAgent(agentName) { emit(modelText(agentName, "pong")) }
      "rich-agent" ->
        StubLiveAgent(agentName) {
          emit(Event(author = agentName, partial = true, content = modelMessage("par")))
          emit(
            Event(
              author = agentName,
              content =
                modelMessage(
                  Part(
                    inlineData =
                      Blob(mimeType = "audio/pcm;rate=24000", data = byteArrayOf(1, 2, 3))
                  )
                ),
            )
          )
          emit(Event(author = agentName, turnComplete = true))
        }
      "one-shot" ->
        StubLiveAgent(agentName, holdOpen = false) { emit(modelText(agentName, "done")) }
      "model-agent" -> LlmAgent(name = agentName, model = model)
      // An Error, not an Exception: the run path must close for both.
      "long-failure" -> StubLiveAgent(agentName) { throw NotImplementedError("x".repeat(200)) }
      // Its run ends only when the close arrives, so the reply proves the close was forwarded.
      "late-agent" ->
        LlmAgent(
          name = "late_agent",
          model = DummyLiveModel(emptyList()),
          afterAgentCallbacks =
            listOf(AfterAgentCallback { CallbackChoice.Break(modelMessage("late reply")) }),
        )
      "cancel-recording" ->
        StubLiveAgent(agentName) {
          started.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            cancelled.complete(Unit)
          }
        }
      // Sends 4 KiB events as fast as the route's sends return, counting each one.
      "flood" ->
        StubLiveAgent(agentName) {
          while (true) {
            emit(modelText(agentName, FLOOD_TEXT))
            floodSends.incrementAndGet()
          }
        }
      // Emits until the run stops, so a client can leave while a send is in flight.
      "streaming" ->
        StubLiveAgent(agentName) {
          try {
            while (true) emit(modelText(agentName, "tick"))
          } catch (e: Throwable) {
            streamStoppedBy.complete(e)
            throw e
          }
        }
      // Stands in for a model client whose constructor throws, e.g. without an API key.
      "unloadable" -> throw IllegalArgumentException("API key must be set")
      // What a top-level `val` agent with a throwing initializer raises on first access.
      "uninitializable" ->
        throw ExceptionInInitializerError(IllegalArgumentException("API key must be set"))
      else -> null
    }
}

/** Knows only the session [KNOWN_SESSION_ID], so a test can name one that does not exist. */
private class FakeSessionService : SessionService by SharedFakeSessionService() {
  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
    if (key.id == KNOWN_SESSION_ID) Session(key) else null
}

/** A session store that fails, as one whose database is down does. */
private class FailingSessionService : SessionService by FakeSessionService() {
  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
    throw IllegalStateException("connection refused")
}

private fun ApplicationTestBuilder.installLiveRoutes(
  loader: AppLoader = FakeAppLoader(),
  sessionService: SessionService = FakeSessionService(),
) {
  application {
    installLiveWebSockets()
    routing { liveRoutes(AppLoaderApps(loader), sessionService, FakeArtifactService()) }
  }
}

/**
 * Serves [pumpLiveRequests] at `/pump` and returns a job that completes once the pump returns.
 *
 * The client returns as soon as it has closed, before the server has read what it sent, so a case
 * joins the job before asserting on the sink.
 */
private fun ApplicationTestBuilder.installPumpRoute(onRequest: (LiveRequest) -> Unit): Job {
  val pumped = Job()
  application {
    install(WebSockets)
    routing {
      webSocket(PUMP_PATH) {
        try {
          pumpLiveRequests(onRequest)
        } finally {
          pumped.complete()
        }
      }
    }
  }
  return pumped
}

/** The close the server sends on [url], bounded because an accepted socket stays open. */
private suspend fun ApplicationTestBuilder.serverCloseOf(
  url: String,
  vararg frames: Frame,
): CloseReason? {
  var reason: CloseReason? = null
  liveClient().use { client ->
    client.webSocket(url) {
      for (frame in frames) send(frame)
      reason = withTimeout(COLLECT_TIMEOUT) { closeReason.await() }
    }
  }
  return reason
}

private suspend fun ApplicationTestBuilder.closeCodeFor(url: String): Short? =
  serverCloseOf(url)?.code

/**
 * The `/run_live` URL for [appName] in the known session, followed by [query], which starts with
 * `&`.
 */
private fun liveRunPath(appName: String, query: String = "") =
  "/run_live?app_name=$appName&user_id=u&session_id=$KNOWN_SESSION_ID$query"

/** The next frame's text, bounded because a run that sends nothing would hang the test. */
private suspend fun DefaultClientWebSocketSession.receiveText(): String =
  withTimeout(COLLECT_TIMEOUT) { (incoming.receive() as Frame.Text).readText() }

/** Upgrades [socket] to a websocket on [path], returning once the server answered 101. */
private fun upgradeToWebSocket(socket: Socket, path: String) {
  socket
    .getOutputStream()
    .write(
      ("GET $path HTTP/1.1\r\nHost: $LOOPBACK\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
          "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
        .toByteArray()
    )
  val head = StringBuilder()
  while (!head.endsWith("\r\n\r\n")) {
    val byte = socket.getInputStream().read()
    check(byte >= 0) { "The server closed during the handshake" }
    head.append(byte.toChar())
  }
  check(head.startsWith("HTTP/1.1 101")) { head.lineSequence().first() }
}

/** [sends] once it has stopped rising for half a second, or as soon as it passes the cap. */
private suspend fun sendCountOnceItStops(sends: AtomicLong): Long =
  withTimeout(COLLECT_TIMEOUT) {
    while (sends.get() == 0L) delay(10.milliseconds)
    var last: Long
    do {
      last = sends.get()
      delay(500.milliseconds)
    } while (sends.get() != last && sends.get() <= MAX_SENDS_BEFORE_A_STALL)
    sends.get()
  }

/** The close [pumpLiveRequests] sends after reading [frames]. */
private suspend fun ApplicationTestBuilder.serverCloseAfter(vararg frames: Frame): CloseReason? =
  serverCloseOf(PUMP_PATH, *frames)

/** How [pumpLiveRequests] ends when Ktor closes the session's `incoming` with [cause]. */
private suspend fun ApplicationTestBuilder.pumpOutcomeWhenIncomingFails(
  cause: Throwable
): Result<Unit> {
  val outcome = CompletableDeferred<Result<Unit>>()
  application {
    install(WebSockets)
    routing {
      webSocket(PUMP_PATH) {
        val failed = Channel<Frame>().apply { close(cause) }
        val session =
          object : DefaultWebSocketServerSession by this {
            override val incoming: ReceiveChannel<Frame> = failed
          }
        outcome.complete(runCatching { session.pumpLiveRequests {} })
      }
    }
  }
  liveClient().use { client ->
    client.webSocket(PUMP_PATH) { withTimeout(COLLECT_TIMEOUT) { outcome.await() } }
  }
  return outcome.await()
}

@RunWith(JUnit4::class)
class LiveRoutesTest {
  @Test
  fun runLive_eventAfterACloseRequest_stillReachesTheClient() = testApplication {
    installLiveRoutes(FakeAppLoader())

    liveClient().use { client ->
      client.webSocket(liveRunPath("late-agent")) {
        send(Frame.Text(CLOSE_FRAME))

        // A close ends only the input; later events from the run still reach the client.
        val text = receiveText()

        assertThat(text).contains("late reply")
      }
    }
  }

  @Test
  fun runLive_clientDisconnects_cancelsTheRun() = testApplication {
    // A real run holds a billed model connection, so a client that goes away must end the run.
    val loader = FakeAppLoader()
    installLiveRoutes(loader)

    liveClient().use { client ->
      client.webSocket(liveRunPath("cancel-recording")) {
        withTimeout(COLLECT_TIMEOUT) { loader.started.await() }
      }
    }

    withTimeout(COLLECT_TIMEOUT) { loader.cancelled.await() }
  }

  @Test
  fun runLive_clientDisconnectsWhileEventsStream_cancelsTheRun() = testApplication {
    // A send in flight when the client leaves must end the run as cancelled, not as a failure.
    val loader = FakeAppLoader()
    installLiveRoutes(loader)

    liveClient().use { client ->
      client.webSocket(liveRunPath("streaming")) {
        withTimeout(COLLECT_TIMEOUT) { incoming.receive() }
      }
    }

    val stoppedBy = withTimeout(COLLECT_TIMEOUT) { loader.streamStoppedBy.await() }
    assertThat(stoppedBy).isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun runLive_invalidParameter_closesWithPolicyViolation() = testApplication {
    installLiveRoutes()

    val code = closeCodeFor("/run_live?app_name=live-agent&user_id=u")

    assertThat(code).isEqualTo(CloseReason.Codes.VIOLATED_POLICY.code)
  }

  @Test
  fun runLive_unknownAgent_closesWithPolicyViolation() = testApplication {
    installLiveRoutes()

    val code = closeCodeFor(liveRunPath("nope"))

    assertThat(code).isEqualTo(CloseReason.Codes.VIOLATED_POLICY.code)
  }

  @Test
  fun runLive_agentFailsToLoad_closesWithInternalErrorAndAFixedReason() = testApplication {
    installLiveRoutes()

    val reason = serverCloseOf(liveRunPath("unloadable"))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.INTERNAL_ERROR.code)
    assertThat(reason?.message).isEqualTo("Agent could not be loaded")
  }

  @Test
  fun runLive_agentInitializerFails_closesWithInternalErrorAndAFixedReason() = testApplication {
    installLiveRoutes()

    val reason = serverCloseOf(liveRunPath("uninitializable"))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.INTERNAL_ERROR.code)
    assertThat(reason?.message).isEqualTo("Agent could not be loaded")
  }

  @Test
  fun runLive_unknownSession_closesWithProtocolError() = testApplication {
    installLiveRoutes()

    val code = closeCodeFor("/run_live?app_name=live-agent&user_id=u&session_id=other")

    assertThat(code).isEqualTo(CloseReason.Codes.PROTOCOL_ERROR.code)
  }

  @Test
  fun runLive_agentEmitsAnEvent_clientReceivesTheEncodedEvent() = testApplication {
    installLiveRoutes()

    liveClient().use { client ->
      client.webSocket(liveRunPath("echo-agent")) {
        val firstFrame = receiveText()

        assertThat(firstFrame).contains("\"text\":\"pong\"")
        assertThat(firstFrame).contains("\"author\":\"echo-agent\"")
      }
    }
  }

  // Real turn shapes, framed as live clients decode them: one JSON object per event.
  @Test
  fun runLive_everyEvent_isFramedOnePerEvent() = testApplication {
    installLiveRoutes()

    liveClient().use { client ->
      client.webSocket(liveRunPath("rich-agent")) {
        val partial = receiveText()
        val audio = receiveText()
        val turnComplete = receiveText()

        assertThat(partial + audio + turnComplete).doesNotContain("\n")
        assertThat(partial).contains("\"partial\":true")
        assertThat(audio).contains("\"mimeType\":\"audio/pcm;rate=24000\"")
        assertThat(audio).contains("\"data\":\"AQID\"")
        // A bare turn boundary, which tells a client the model has finished, is sent too.
        assertThat(turnComplete).contains("\"turnComplete\":true")
      }
    }
  }

  @Test
  fun runLive_runFailsWithALongMessage_closesWithTheMessageCutTo123Bytes() = testApplication {
    installLiveRoutes()

    val reason = serverCloseOf(liveRunPath("long-failure"))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.INTERNAL_ERROR.code)
    assertThat(reason?.message).isEqualTo("x".repeat(123))
  }

  @Test
  fun runLive_sessionServiceFails_closesWithInternalErrorAndAFixedReason() = testApplication {
    installLiveRoutes(sessionService = FailingSessionService())

    val reason = serverCloseOf(liveRunPath("live-agent"))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.INTERNAL_ERROR.code)
    assertThat(reason?.message).isEqualTo("Session could not be loaded")
  }

  @Test
  fun runLive_audioFrame_reachesTheModelAndItsReplyComesBack() = testApplication {
    val loader = FakeAppLoader()
    installLiveRoutes(loader)

    liveClient().use { client ->
      client.webSocket(liveRunPath("model-agent")) {
        send(Frame.Text(AUDIO_FRAME))
        val reply = receiveText()

        assertThat(reply).contains("heard you")
      }
    }

    val sent = loader.model.connections.single().sent
    val audio = sent.filterIsInstance<RealtimeInput.Audio>().single()
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun runLive_queryParameters_reachTheModelsLiveConfig() = testApplication {
    val loader = FakeAppLoader()
    installLiveRoutes(loader)

    liveClient().use { client ->
      client.webSocket(liveRunPath("model-agent", "&modalities=TEXT&proactive_audio=true")) {
        send(Frame.Text(AUDIO_FRAME))
        withTimeout(COLLECT_TIMEOUT) { incoming.receive() }
      }
    }

    val config = loader.model.connectRequests.single().liveConnectConfig
    assertThat(config.responseModalities).containsExactly(Modality.TEXT)
    assertThat(config.proactivity).isEqualTo(ProactivityConfig(proactiveAudio = true))
  }

  @Test
  fun runLive_runEnds_closesNormally() = testApplication {
    installLiveRoutes()

    liveClient().use { client ->
      client.webSocket(liveRunPath("one-shot")) {
        val event = receiveText()
        val reason = withTimeout(COLLECT_TIMEOUT) { closeReason.await() }

        assertThat(event).contains("done")
        assertThat(reason?.code).isEqualTo(CloseReason.Codes.NORMAL.code)
      }
    }
  }

  @Test
  fun installLiveWebSockets_clientStopsReading_suspendsTheRunsSends() {
    val loader = FakeAppLoader()
    val server =
      embeddedServer(Netty, port = 0, host = LOOPBACK) {
        installLiveWebSockets()
        routing { liveRoutes(AppLoaderApps(loader), FakeSessionService(), FakeArtifactService()) }
      }
    server.start(wait = false)
    try {
      Socket().use { socket ->
        // A small receive window, and nothing reads the socket after the handshake.
        socket.receiveBufferSize = 4096
        socket.soTimeout = COLLECT_TIMEOUT.inWholeMilliseconds.toInt()
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        socket.connect(InetSocketAddress(LOOPBACK, port))
        upgradeToWebSocket(socket, liveRunPath("flood"))

        val sends = runBlocking { sendCountOnceItStops(loader.floodSends) }

        assertThat(sends).isAtMost(MAX_SENDS_BEFORE_A_STALL)
      }
    } finally {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
  }

  @Test
  fun closeReasonFor_multibyteMessage_fitsTheCloseFrameWithoutSplittingACharacter() {
    assertThat(closeReasonFor("é".repeat(100))).isEqualTo("é".repeat(61))
    assertThat(closeReasonFor("€".repeat(50))).isEqualTo("€".repeat(41))
    assertThat(closeReasonFor("\uD83D\uDE00".repeat(40))).isEqualTo("\uD83D\uDE00".repeat(30))
  }

  @Test
  fun closeReasonFor_loneSurrogate_isReplacedRatherThanEndingTheReason() {
    assertThat(closeReasonFor("bad \uD800 input")).isEqualTo("bad ? input")
  }

  @Test
  fun pumpLiveRequests_malformedFrame_closesWithNotConsistent() = testApplication {
    installPumpRoute {}

    val code = serverCloseAfter(Frame.Text(INVALID_FRAME))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_invalidUtf8_closesWithNotConsistent() = testApplication {
    installPumpRoute {}
    val bytes = """{"content":{"role":"user","parts":[{"text":"#"}]}}""".encodeToByteArray()
    // 0xFF never occurs in UTF-8; decoded leniently, the frame would pass as valid content.
    bytes[bytes.indexOf('#'.code.toByte())] = 0xFF.toByte()

    val code = serverCloseAfter(Frame.Text(true, bytes))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_contentBreakingContentInputRule_closesWithNotConsistent() = testApplication {
    installPumpRoute {}
    val forgedCall = """{"content":{"role":"user","parts":[{"function_call":{"name":"forged"}}]}}"""

    val code = serverCloseAfter(Frame.Text(forgedCall))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
  }

  @Test
  fun pumpLiveRequests_stateDeltaWithNullValue_isPassedOn() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text("""{"state_delta":{"k":null}}"""))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    // The decoder turns the null into a removal; the pump only passes it on.
    assertThat(received.single().stateDelta.keys).containsExactly("k")
  }

  @Test
  fun pumpLiveRequests_numberArrayBlobData_closesWithNotConsistent() = testApplication {
    installPumpRoute {}

    val reason =
      serverCloseAfter(Frame.Text("""{"blob":{"mime_type":"audio/pcm","data":[1,2,3]}}"""))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.NOT_CONSISTENT.code)
    assertThat(reason?.message).isEqualTo("Invalid live request")
  }

  @Test
  fun pumpLiveRequests_binaryFrame_closesWithCannotAccept() = testApplication {
    installPumpRoute {}

    val reason = serverCloseAfter(Frame.Binary(true, byteArrayOf(1, 2, 3)))

    assertThat(reason?.code).isEqualTo(CloseReason.Codes.CANNOT_ACCEPT.code)
    assertThat(reason?.message).isEqualTo("Binary frames are not supported")
  }

  @Test
  fun pumpLiveRequests_afterClose_keepsReadingUntilTheClientLeaves() = testApplication {
    val closeSeen = CompletableDeferred<Unit>()
    val pumped = installPumpRoute { if (it.close) closeSeen.complete(Unit) }

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text(CLOSE_FRAME))
        withTimeout(COLLECT_TIMEOUT) { closeSeen.await() }

        // Returning here would cancel the run, so the pump must still be reading.
        assertThat(withTimeoutOrNull(STILL_READING) { pumped.join() }).isNull()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }
  }

  @Test
  fun pumpLiveRequests_malformedFrameAfterClose_keepsReading() = testApplication {
    val closeSeen = CompletableDeferred<Unit>()
    val pumped = installPumpRoute { if (it.close) closeSeen.complete(Unit) }

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text(CLOSE_FRAME))
        withTimeout(COLLECT_TIMEOUT) { closeSeen.await() }
        send(Frame.Text(INVALID_FRAME))

        // After a close, a malformed frame is ignored like any other, so the run can finish.
        assertThat(withTimeoutOrNull(STILL_READING) { pumped.join() }).isNull()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }
  }

  @Test
  fun pumpLiveRequests_framesAfterClose_areIgnored() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text(CLOSE_FRAME))
        send(Frame.Text(AUDIO_FRAME))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(1)
    assertThat(received.single().close).isTrue()
  }

  @Test
  fun pumpLiveRequests_binaryFrameAfterClose_closesWithCannotAccept() = testApplication {
    installPumpRoute {}

    val code = serverCloseAfter(Frame.Text(CLOSE_FRAME), Frame.Binary(true, byteArrayOf(1)))?.code

    assertThat(code).isEqualTo(CloseReason.Codes.CANNOT_ACCEPT.code)
  }

  @Test
  fun pumpLiveRequests_audioFrame_reachesTheSink() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text(AUDIO_FRAME))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    val audio = received.first().input as RealtimeInput.Audio
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun pumpLiveRequests_blobWithContent_sendsBothRealtimeFirst() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)
    val blob = """"blob":{"mime_type":"audio/pcm","data":"AQID"}"""
    val content = """"content":{"role":"user","parts":[{"text":"hi"}]}"""

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text("{$blob,$content}"))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(2)
    assertThat(received[0].input).isInstanceOf(RealtimeInput.Audio::class.java)
    assertThat(received[1].input).isNotNull()
    assertThat(received[1].input).isNotInstanceOf(RealtimeInput::class.java)
  }

  @Test
  fun pumpLiveRequests_clientDisconnects_returnsWithoutSending() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client -> client.webSocket(PUMP_PATH) { close() } }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    // A disconnect passes nothing on, not even a close request.
    assertThat(received).isEmpty()
  }

  @Test
  fun pumpLiveRequests_pingTimeout_returnsAsForADisconnect() = testApplication {
    // How Ktor ends `incoming` once the client stops answering pings.
    val outcome = pumpOutcomeWhenIncomingFails(IOException("Ping timeout"))

    assertThat(outcome.exceptionOrNull()).isNull()
  }

  @Test
  fun pumpLiveRequests_frameTooBig_returnsWithoutFailingTheRun() = testApplication {
    // How Ktor ends `incoming` after closing the socket with 1009.
    val outcome = pumpOutcomeWhenIncomingFails(FrameTooBigException(1))

    assertThat(outcome.exceptionOrNull()).isNull()
  }

  @Test
  fun pumpLiveRequests_protocolViolation_returnsWithoutFailingTheRun() = testApplication {
    // How Ktor ends `incoming` after closing the socket with 1002.
    val outcome = pumpOutcomeWhenIncomingFails(ProtocolViolationException("test"))

    assertThat(outcome.exceptionOrNull()).isNull()
  }

  @Test
  fun pumpLiveRequests_severalFrames_keepsOrder() = testApplication {
    val received = mutableListOf<LiveRequest>()
    val pumped = installPumpRoute(received::add)

    liveClient().use { client ->
      client.webSocket(PUMP_PATH) {
        send(Frame.Text(AUDIO_FRAME))
        send(Frame.Text(JPEG_FRAME))
        send(Frame.Text("""{"audio_stream_end":true}"""))
        close()
      }
    }
    withTimeout(COLLECT_TIMEOUT) { pumped.join() }

    assertThat(received).hasSize(3)
    assertThat(received[0].input).isInstanceOf(RealtimeInput.Audio::class.java)
    assertThat(received[1].input).isInstanceOf(RealtimeInput.Video::class.java)
    assertThat(received[2].input).isEqualTo(RealtimeInput.AudioStreamEnd)
  }
}
