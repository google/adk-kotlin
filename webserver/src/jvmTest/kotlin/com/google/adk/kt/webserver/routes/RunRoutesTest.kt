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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.adkApiModule
import com.google.common.truth.Truth.assertThat
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Serves the run routes from Netty instead of the test engine. Netty handles each call on a single
 * event-loop thread that a blocking tool would hold.
 */
@RunWith(JUnit4::class)
class RunRoutesTest {
  private val bothArrived = CountDownLatch(2)
  private val metTheOtherCall = CopyOnWriteArrayList<Boolean>()
  private val model = TwoParallelCallsModel()
  private val sessions = ReadFailingSessionService()
  private lateinit var server: EmbeddedServer<*, *>
  private var port = 0

  @Before
  fun startServer() {
    val agent = LlmAgent(name = "agent", model = model, tools = listOf(WaitForOtherCallTool()))
    server =
      embeddedServer(Netty, port = EPHEMERAL_PORT, host = LOOPBACK) {
        adkApiModule(
          AdkServerConfig.inMemory(App(appName = APP_NAME, rootAgent = agent))
            .copy(sessionService = sessions)
        )
      }
    server.start(wait = false)
    port = runBlocking { server.engine.resolvedConnectors().first().port }
  }

  @After
  fun stopServer() {
    server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
  }

  @Test
  fun run_parallelBlockingToolCalls_runConcurrently() {
    assertThat(send(post(SESSION_PATH, body = null)).statusCode()).isEqualTo(200)

    val response = send(post("/run", RUN_BODY))

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(metTheOtherCall).containsExactly(true, true)
  }

  @Test
  fun run_unknownSession_answersNotFoundWithoutCallingTheModel() {
    val response = send(post("/run", RUN_BODY))

    assertThat(response.statusCode()).isEqualTo(404)
    assertThat(response.body()).isEqualTo("Session not found")
    assertThat(model.calls.get()).isEqualTo(0)
    assertThat(send(get(SESSION_PATH)).statusCode()).isEqualTo(404)
  }

  @Test
  fun runSse_unknownSession_answersNotFoundWithoutCallingTheModel() {
    val response = send(post("/run_sse", RUN_BODY))

    assertThat(response.statusCode()).isEqualTo(404)
    assertThat(response.body()).isEqualTo("Session not found")
    assertThat(model.calls.get()).isEqualTo(0)
    assertThat(send(get(SESSION_PATH)).statusCode()).isEqualTo(404)
  }

  @Test
  fun run_withoutSessionId_runsInNewSession() {
    val response = send(post("/run", RUN_BODY_WITHOUT_SESSION))

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(model.calls.get()).isEqualTo(2)
  }

  @Test
  fun runSse_withoutSessionId_runsInNewSession() {
    val response = send(post("/run_sse", RUN_BODY_WITHOUT_SESSION))

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(model.calls.get()).isEqualTo(2)
  }

  @Test
  fun run_sessionStoreFails_answersFixedErrorWithoutCallingTheModel() {
    assertSessionStoreFailureAnswersFixedError("/run")
  }

  @Test
  fun runSse_sessionStoreFails_answersFixedErrorWithoutCallingTheModel() {
    assertSessionStoreFailureAnswersFixedError("/run_sse")
  }

  private fun assertSessionStoreFailureAnswersFixedError(path: String) {
    sessions.failReads = true

    val (response, logs) = capturingLogs { send(post(path, RUN_BODY)) }

    assertThat(response.statusCode()).isEqualTo(500)
    assertThat(response.body()).isEqualTo("Session could not be loaded")
    assertThat(model.calls.get()).isEqualTo(0)
    assertThat(logs).contains(StoreFailure::class.simpleName)
    assertThat(logs).doesNotContain(STORE_DETAILS)
  }

  /**
   * Runs [block] and returns its result with what was logged meanwhile, under either binding: Blaze
   * logs through `java.util.logging`, Gradle through slf4j-simple to stderr.
   */
  private fun <T> capturingLogs(block: () -> T): Pair<T, String> {
    val logs = StringBuffer()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          logs.append(SimpleFormatter().formatMessage(record))
          record.thrown?.let { logs.append(' ').append(it) }
          logs.append('\n')
        }

        override fun flush() {}

        override fun close() {}
      }
    val stderr = ByteArrayOutputStream()
    val originalStderr = System.err
    val rootLogger = Logger.getLogger("")
    rootLogger.addHandler(handler)
    System.setErr(PrintStream(stderr, true))
    val result =
      try {
        block()
      } finally {
        System.setErr(originalStderr)
        rootLogger.removeHandler(handler)
      }
    return result to "$logs$stderr"
  }

  private fun post(path: String, body: String?): HttpRequest =
    HttpRequest.newBuilder(URI("http://$LOOPBACK:$port$path"))
      .header("Content-Type", "application/json")
      .POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
      .build()

  private fun get(path: String): HttpRequest =
    HttpRequest.newBuilder(URI("http://$LOOPBACK:$port$path")).GET().build()

  private fun send(request: HttpRequest): HttpResponse<String> =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

  /** Keeps sessions in memory, but fails every read once [failReads] is set. */
  private class ReadFailingSessionService(
    private val delegate: SessionService = InMemorySessionService()
  ) : SessionService by delegate {
    @Volatile var failReads = false

    override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? =
      if (failReads) throw StoreFailure() else delegate.getSession(key, config)
  }

  /** A store failure whose message carries details a client or log must not see. */
  private class StoreFailure : IllegalStateException(STORE_DETAILS)

  /** Blocks, as a synchronous tool does, until the other parallel call arrives too. */
  private inner class WaitForOtherCallTool :
    BaseTool(TOOL_NAME, "Waits for the other parallel call.") {
    override fun declaration() = FunctionDeclaration(name = name, description = description)

    @Suppress("SuspendBlocks") // Blocks on purpose to stand in for a synchronous tool.
    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
      bothArrived.countDown()
      metTheOtherCall += bothArrived.await(WAIT_MILLIS, MILLISECONDS)
      return mapOf("status" to "ok")
    }
  }

  /** Calls the tool twice in one turn, then answers, counting its requests in [calls]. */
  private class TwoParallelCallsModel : Model {
    override val name = "scripted"
    val calls = AtomicInteger()

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      flowOf(if (calls.getAndIncrement() == 0) TWO_CALLS else ANSWER)
  }

  private companion object {
    /** Port 0 binds an ephemeral port, which the engine reports once it is up. */
    const val EPHEMERAL_PORT = 0
    const val LOOPBACK = "127.0.0.1"
    const val APP_NAME = "app"
    const val SESSION_PATH = "/apps/$APP_NAME/users/u/sessions/s"
    const val TOOL_NAME = "wait_for_other"
    const val RUN_BODY =
      """{"appName":"$APP_NAME","userId":"u","sessionId":"s","newMessage":{"role":"user","parts":[{"text":"go"}]}}"""
    const val RUN_BODY_WITHOUT_SESSION =
      """{"appName":"$APP_NAME","userId":"u","newMessage":{"role":"user","parts":[{"text":"go"}]}}"""
    const val STORE_DETAILS = "jdbc://db.internal:5432 password=hunter2"
    const val WAIT_MILLIS = 5_000L
    const val STOP_GRACE_MILLIS = 500L
    const val STOP_TIMEOUT_MILLIS = 2000L

    val TWO_CALLS =
      LlmResponse(
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = TOOL_NAME, args = emptyMap(), id = "c1")),
                Part(functionCall = FunctionCall(name = TOOL_NAME, args = emptyMap(), id = "c2")),
              ),
          )
      )
    val ANSWER =
      LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "done"))))
  }
}
