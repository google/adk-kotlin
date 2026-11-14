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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.AvatarConfig
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.adkApiModule
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.common.truth.Truth.assertThat
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.websocket.CloseReason
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private val TIMEOUT = 10.seconds

private const val LOOPBACK = "127.0.0.1"

private const val MODEL_APP = "model_app"
private const val IDLE_APP = "idle_app"
private const val ONE_SHOT_APP = "one_shot_app"
private const val FAILING_APP = "failing_app"
private const val CANCEL_APP = "cancel_app"

// Lines the route logs, checked in the captured server log.
private const val REJECTED_REQUEST_LOG = "Rejecting a live request"
private const val TOO_BIG_LOG = "A live client sent a message over"
private const val RUN_FAILED_LOG = "Live run failed"
private const val SESSION_LOAD_FAILED_LOG = "Loading the session for a live run failed"

// A session whose read fails in the store, with a message that must reach neither client nor log.
private const val UNREADABLE_SESSION = "unreadable"
private const val STORE_DETAILS = "jdbc:secret://db.internal/password=hunter2"

private const val AUDIO_FRAME = """{"blob":{"mime_type":"audio/pcm","data":"AQID"}}"""

private val INVALID_REQUEST = CloseReason(CloseReason.Codes.NOT_CONSISTENT, "Invalid live request")

private val SERVER_AVATAR = AvatarConfig(avatarName = "server-avatar")

private val http = HttpClient.newHttpClient()

/** Copies every byte to both [first] and [second]. */
private class TeeOutputStream(private val first: OutputStream, private val second: OutputStream) :
  OutputStream() {
  override fun write(b: Int) {
    first.write(b)
    second.write(b)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    first.write(b, off, len)
    second.write(b, off, len)
  }
}

/** A store failure whose message carries details a client or log must not see. */
private class StoreFailure : IllegalStateException(STORE_DETAILS)

/**
 * Reads from [delegate], except that reading [UNREADABLE_SESSION] fails, as a store that is down.
 */
private class UnreadableSessionService(private val delegate: SessionService) :
  SessionService by delegate {
  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    if (key.id == UNREADABLE_SESSION) throw StoreFailure()
    return delegate.getSession(key, config)
  }
}

/**
 * A JDK websocket client, the text frames it received and the close it got. A send fails only when
 * the server's close did not cut it off.
 */
private class SocketClient(
  private val socket: WebSocket,
  private val frames: LinkedBlockingQueue<String>,
  private val closed: CompletableFuture<CloseReason>,
) {
  fun sendText(text: String, last: Boolean = true) {
    sendUnlessClosed { socket.sendText(text, last) }
  }

  /** Sends [text] [count] times as the fragments of one message. */
  fun sendTextInFragments(text: String, count: Int) {
    repeat(count) { i -> sendText(text, last = i == count - 1) }
  }

  fun sendBinary(data: ByteArray) {
    sendUnlessClosed { socket.sendBinary(ByteBuffer.wrap(data), true) }
  }

  fun abort() {
    socket.abort()
  }

  fun nextFrame(): String? = frames.poll(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)

  /** The next text frame that contains [text], or null if none arrives in time. */
  fun nextFrameContaining(text: String): String? {
    var frame = nextFrame()
    while (frame != null && text !in frame) frame = nextFrame()
    return frame
  }

  fun closeReason(): CloseReason = closed.get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)

  /**
   * Runs [send] and rethrows its failure unless the server's close arrives, which cuts sends off.
   */
  private fun sendUnlessClosed(send: () -> CompletableFuture<WebSocket>) {
    try {
      send().get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (sendFailure: ExecutionException) {
      val closedByServer =
        try {
          closed.get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
          true
        } catch (e: TimeoutException) {
          false
        } catch (e: ExecutionException) {
          false
        }
      if (!closedByServer) throw sendFailure
    }
  }
}

/**
 * `/run_live` on a real Netty socket, driven by JDK clients. The test needs neither Ktor's test
 * host nor Ktor's client.
 */
@RunWith(JUnit4::class)
class LiveRoutesSocketTest {
  private val model =
    DummyLiveModel(
      listOf(
        AwaitSent("audio") { it is RealtimeInput.Audio },
        Respond(LlmResponse(content = modelMessage("heard you"), partial = false)),
      )
    )
  private val runStarted = CompletableFuture<Unit>()
  private val runCancelled = CompletableFuture<Unit>()
  private val sessions = InMemorySessionService()
  private var port = 0
  private var server: EmbeddedServer<*, *>? = null
  private val clients = mutableListOf<SocketClient>()
  private val endedLiveCalls = LinkedBlockingQueue<String>()
  private val serverLog = ByteArrayOutputStream()
  private val stderr = System.err
  // With slf4j bound to java.util.logging, JUL's console handler may hold an older System.err.
  private val julCapture =
    object : Handler() {
      override fun publish(record: LogRecord?) {
        if (record == null) return
        val thrown = record.thrown?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        synchronized(serverLog) { serverLog.write("${record.message}$thrown\n".toByteArray()) }
      }

      override fun flush() {}

      override fun close() {}
    }

  private fun startServer() {
    val server =
      embeddedServer(Netty, port = 0, host = LOOPBACK) {
        adkApiModule(
          AdkServerConfig(
            appLoader =
              InMemoryAppLoader(
                App(appName = MODEL_APP, rootAgent = LlmAgent(name = "model_agent", model = model)),
                App(appName = IDLE_APP, rootAgent = StubLiveAgent("idle_agent")),
                App(
                  appName = ONE_SHOT_APP,
                  rootAgent =
                    StubLiveAgent("one_shot_agent", holdOpen = false) {
                      emit(
                        Event(
                          author = "one_shot_agent",
                          content = Content(role = Role.MODEL, parts = listOf(Part(text = "done"))),
                        )
                      )
                    },
                ),
                App(
                  appName = FAILING_APP,
                  rootAgent =
                    StubLiveAgent("failing_agent") { throw IllegalStateException("Quota exceeded") },
                ),
                App(
                  appName = CANCEL_APP,
                  rootAgent =
                    StubLiveAgent("cancel_agent") {
                      runStarted.complete(Unit)
                      try {
                        awaitCancellation()
                      } finally {
                        runCancelled.complete(Unit)
                      }
                    },
                ),
              ),
            sessionService = UnreadableSessionService(sessions),
            artifactService = InMemoryArtifactService(),
            avatarConfig = SERVER_AVATAR,
          )
        )
        install(
          createApplicationPlugin("LiveCallEnds") {
            // For an upgraded call this fires when the route returns, after all it logs.
            on(ResponseSent) { call ->
              if (call.request.path() == "/run_live") endedLiveCalls.add(call.request.uri)
            }
          }
        )
      }
    server.start(wait = false)
    this.server = server
    // Bound to port 0, so the OS picked a free port; read it back instead of racing a probe.
    port = runBlocking { server.engine.resolvedConnectors().first().port }
  }

  @Before
  fun setUp() {
    System.setErr(PrintStream(TeeOutputStream(stderr, serverLog), true))
    Logger.getLogger("").addHandler(julCapture)
    runBlocking {
      for (app in listOf(MODEL_APP, IDLE_APP, ONE_SHOT_APP, FAILING_APP, CANCEL_APP)) {
        sessions.createSession(SessionKey(app, "u", "s"), null)
      }
    }
    startServer()
  }

  /** Waits for the `/run_live` call to end: the route logs after Ktor sends its close. */
  private fun awaitLiveCallEnd() {
    checkNotNull(endedLiveCalls.poll(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
      "The /run_live call did not end"
    }
  }

  private fun stopServer() {
    server?.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    server = null
  }

  @After
  fun tearDown() {
    try {
      for (client in clients) client.abort()
      stopServer()
    } finally {
      System.setErr(stderr)
      Logger.getLogger("").removeHandler(julCapture)
    }
  }

  /** Opens `/run_live` for [app] and [session], asking for [modalities] when set. */
  private fun connect(
    app: String,
    session: String = "s",
    modalities: String? = null,
  ): SocketClient {
    val frames = LinkedBlockingQueue<String>()
    val closed = CompletableFuture<CloseReason>()
    val text = StringBuilder()
    val listener =
      object : WebSocket.Listener {
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
          text.append(data)
          if (last) {
            frames.add(text.toString())
            text.setLength(0)
          }
          ws.request(1)
          return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
          closed.complete(CloseReason(statusCode.toShort(), reason))
          return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {
          closed.completeExceptionally(error)
        }
      }

    val query = modalities?.let { "&modalities=$it" }.orEmpty()
    val socket =
      http
        .newWebSocketBuilder()
        .buildAsync(
          URI("ws://$LOOPBACK:$port/run_live?app_name=$app&user_id=u&session_id=$session$query"),
          listener,
        )
        .get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return SocketClient(socket, frames, closed).also { clients += it }
  }

  /**
   * Upgrades a raw socket, sends one masked text frame carrying [payload] as is, and returns the
   * server's close, or null if the connection ended without one.
   */
  private fun rawTextFrameClose(payload: List<Byte>): CloseReason? {
    require(payload.size < 126) { "Only short frames are needed here" }
    return Socket(InetAddress.getByName(LOOPBACK), port).use { socket ->
      socket.soTimeout = TIMEOUT.inWholeMilliseconds.toInt()
      val out = socket.getOutputStream()
      out.sendAscii(liveUpgradeRequest(IDLE_APP, host = "$LOOPBACK:$port"))
      val input = DataInputStream(socket.getInputStream())
      check(readAsciiLine(input).startsWith("HTTP/1.1 101 ")) { "The upgrade was refused" }
      while (readAsciiLine(input).isNotEmpty()) {
        // Skips the headers.
      }

      val mask = listOf<Byte>(9, 8, 7, 6)
      out.write(0x81)
      out.write(0x80 or payload.size)
      for (byte in mask) out.write(byte.toInt())
      for ((i, byte) in payload.withIndex()) out.write(byte.toInt() xor mask[i % 4].toInt())
      out.flush()

      readClose(input)
    }
  }

  /** Writes [text] as ASCII and flushes, leaving the stream open for the frames that follow. */
  private fun OutputStream.sendAscii(text: String) {
    writer(Charsets.US_ASCII).run {
      write(text)
      flush()
    }
  }

  /** One CRLF-terminated line of [input], without the line break. */
  private fun readAsciiLine(input: DataInputStream): String =
    buildString {
        while (true) {
          val c = input.read()
          check(c >= 0) { "Closed during the handshake" }
          if (c == '\n'.code) break
          append(c.toChar())
        }
      }
      .removeSuffix("\r")

  /** Reads server frames until a close and returns it, or null if the stream ends first. */
  private fun readClose(input: DataInputStream): CloseReason? {
    while (true) {
      val head = input.read()
      val sizeByte = input.read()
      if (head < 0 || sizeByte < 0) return null
      val size =
        when (val short = sizeByte and 0x7f) {
          126 -> input.readUnsignedShort()
          127 -> input.readLong().toInt()
          else -> short
        }
      val body = ByteArray(size).also { input.readFully(it) }
      if (head and 0x0f == 0x8) {
        if (size < 2) return null
        val code = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
        return CloseReason(code.toShort(), String(body, 2, size - 2, Charsets.UTF_8))
      }
    }
  }

  @Test
  fun runLive_deeplyNestedFrame_closesWithNotConsistent() {
    val client = connect(IDLE_APP)
    val depth = 50_000

    client.sendText("""{"state_delta":{"k":${"[".repeat(depth)}${"]".repeat(depth)}}}""")
    val close = client.closeReason()
    awaitLiveCallEnd()

    assertThat(close).isEqualTo(INVALID_REQUEST)
    assertThat(serverLog.toString()).contains(REJECTED_REQUEST_LOG)
    assertThat(serverLog.toString()).doesNotContain("StackOverflowError")
  }

  @Test
  fun runLive_frameThatIsNotUtf8_closesWithNotConsistent() {
    // Valid JSON but for the 0xff byte, so only the UTF-8 check can refuse the frame.
    val payload =
      """{"state_delta":{"k":"""".map { it.code.toByte() } +
        0xff.toByte() +
        """"}}""".map { it.code.toByte() }

    val close = rawTextFrameClose(payload)
    awaitLiveCallEnd()

    assertThat(close).isEqualTo(INVALID_REQUEST)
    assertThat(serverLog.toString()).contains(REJECTED_REQUEST_LOG)
    assertThat(serverLog.toString()).doesNotContain(RUN_FAILED_LOG)
  }

  @Test
  fun runLive_unknownSession_closesWithProtocolError() {
    assertThat(connect(IDLE_APP, session = "nope").closeReason())
      .isEqualTo(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Session not found"))
  }

  @Test
  fun runLive_unknownApp_closesWithPolicyViolation() {
    assertThat(connect("nope").closeReason())
      .isEqualTo(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Agent not found"))
  }

  @Test
  fun runLive_malformedFrame_closesWithNotConsistent() {
    val client = connect(IDLE_APP)

    client.sendText("not json")

    assertThat(client.closeReason()).isEqualTo(INVALID_REQUEST)
  }

  @Test
  fun runLive_binaryFrame_closesWithCannotAccept() {
    val client = connect(IDLE_APP)

    client.sendBinary(byteArrayOf(1, 2, 3))

    assertThat(client.closeReason())
      .isEqualTo(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Binary frames are not supported"))
  }

  @Test
  fun runLive_frameOverTheSizeCap_closesWithTooBig() {
    val client = connect(IDLE_APP)

    client.sendText("x".repeat(MAX_LIVE_FRAME_BYTES.toInt() + 1))
    val close = client.closeReason()
    awaitLiveCallEnd()

    assertThat(close.code).isEqualTo(CloseReason.Codes.TOO_BIG.code)
    assertThat(serverLog.toString()).contains(TOO_BIG_LOG)
    // Ktor already closed the socket, so the route must not log a failure.
    assertThat(serverLog.toString()).doesNotContain(RUN_FAILED_LOG)
  }

  @Test
  fun runLive_messageFragmentedPastTheSizeCap_closesWithTooBig() {
    val client = connect(IDLE_APP)
    val fragment = "x".repeat(1024 * 1024)
    val fragments = (MAX_LIVE_FRAME_BYTES / fragment.length).toInt() + 1

    client.sendTextInFragments(fragment, fragments)
    val close = client.closeReason()
    awaitLiveCallEnd()

    assertThat(close.code).isEqualTo(CloseReason.Codes.TOO_BIG.code)
    assertThat(serverLog.toString()).contains(TOO_BIG_LOG)
    assertThat(serverLog.toString()).doesNotContain(RUN_FAILED_LOG)
  }

  @Test
  fun runLive_audioFrame_reachesTheModelAndItsReplyComesBack() {
    val client = connect(MODEL_APP)

    client.sendText(AUDIO_FRAME)
    val reply = client.nextFrameContaining("heard you")

    assertThat(reply).contains("heard you")
    val sent = model.connections.single().sent
    val audio = sent.filterIsInstance<RealtimeInput.Audio>().single()
    assertThat(audio.blob.data).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(audio.blob.mimeType).isEqualTo("audio/pcm")
  }

  @Test
  fun runLive_videoSession_rendersAsTheServersAvatar() {
    val client = connect(MODEL_APP, modalities = "VIDEO")

    client.sendText(AUDIO_FRAME)
    assertThat(client.nextFrameContaining("heard you")).isNotNull()

    val liveConfig = model.connectRequests.single().liveConnectConfig
    assertThat(liveConfig.avatarConfig).isEqualTo(SERVER_AVATAR)
  }

  @Test
  fun runLive_runEnds_closesNormally() {
    val client = connect(ONE_SHOT_APP)

    assertThat(client.nextFrame()).contains("done")
    assertThat(client.closeReason().code).isEqualTo(CloseReason.Codes.NORMAL.code)
  }

  @Test
  fun runLive_runFails_closesWithInternalErrorAndItsMessage() {
    assertThat(connect(FAILING_APP).closeReason())
      .isEqualTo(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Quota exceeded"))
  }

  @Test
  fun runLive_clientDisconnects_cancelsTheRun() {
    val client = connect(CANCEL_APP)
    runStarted.get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    client.abort()

    runCancelled.get(TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  }

  @Test
  fun runLive_sessionStoreFails_closesWithAFixedReasonAndLogsOnlyTheType() {
    val closeReason = connect(MODEL_APP, session = UNREADABLE_SESSION).closeReason()
    awaitLiveCallEnd()

    assertThat(closeReason)
      .isEqualTo(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Session could not be loaded"))
    assertThat(model.connectRequests).isEmpty()
    val log = serverLog.toString()
    assertThat(log).contains(SESSION_LOAD_FAILED_LOG)
    assertThat(log).contains(StoreFailure::class.simpleName)
    assertThat(log).doesNotContain(STORE_DETAILS)
    assertThat(log).doesNotContain(RUN_FAILED_LOG)
  }
}
