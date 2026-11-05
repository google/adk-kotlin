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

package com.google.adk.kt.webserver.telemetry

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.AdkApiServer
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Spans from a real run reach an OTLP endpoint named by the standard settings.
 *
 * Core resolves its tracer once per JVM, so this class needs a JVM of its own, which the
 * `otlpEndToEndTest` Gradle task gives it.
 */
@RunWith(JUnit4::class)
class OtlpEndToEndTest {

  @Test(timeout = 60_000)
  fun run_withTracesEndpoint_exportsBeforeStopReturns_andAgainAfterARestart() {
    OtlpReceiver().use { receiver ->
      withSystemProperties(
        "otel.exporter.otlp.traces.endpoint" to "${receiver.endpoint}/v1/traces",
        "otel.exporter.otlp.headers" to "x-adk-test=present",
      ) {
        val port = freePort()
        val server = AdkApiServer(config(port))

        // The batch processor would wait 5 s, so only the flush on stop delivers spans this soon.
        runOnce(server, port)
        assertThat(receiver.countReceived(AGENT_SPAN)).isEqualTo(1)

        runOnce(server, port)
        assertThat(receiver.countReceived(AGENT_SPAN)).isEqualTo(2)
      }

      val request = receiver.requests.first()
      assertThat(request.path).isEqualTo("/v1/traces")
      assertThat(request.header("Content-Type")).isEqualTo("application/x-protobuf")
      assertThat(request.header("x-adk-test")).isEqualTo("present")
      assertThat(receiver.received("adk-web-server")).isTrue()
    }
  }

  private fun runOnce(server: AdkApiServer, port: Int) {
    try {
      server.start()
      awaitHealthy(port)
      assertThat(postRun(port)).isEqualTo(HttpURLConnection.HTTP_OK)
    } finally {
      server.stop()
    }
  }

  private fun config(port: Int) =
    AdkServerConfig(
      appLoader = InMemoryAppLoader(App(appName = AGENT, rootAgent = ReplyingAgent())),
      sessionService = InMemorySessionService(),
      artifactService = InMemoryArtifactService(),
      port = port,
    )

  /** Answers every turn with one event, so a run needs no model. */
  private class ReplyingAgent : BaseAgent(name = AGENT, description = "Replies once") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> =
      flowOf(
        Event(
          invocationId = context.invocationId,
          author = AGENT,
          content = Content(role = "model", parts = listOf(Part(text = "hello"))),
          turnComplete = true,
        )
      )
  }

  private fun postRun(port: Int): Int {
    val connection = URI("http://127.0.0.1:$port/run").toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.connectTimeout = TIMEOUT.inWholeMilliseconds.toInt()
    connection.readTimeout = TIMEOUT.inWholeMilliseconds.toInt()
    return try {
      connection.outputStream.use { it.write(RUN_BODY.toByteArray()) }
      connection.responseCode
    } finally {
      connection.disconnect()
    }
  }

  private fun awaitHealthy(port: Int) {
    val deadline = TimeSource.Monotonic.markNow() + STARTUP_TIMEOUT
    while (deadline.hasNotPassedNow()) {
      if (healthStatusOrNull(port) == HttpURLConnection.HTTP_OK) return
      Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
    }
    throw AssertionError("Server did not serve /health in time")
  }

  private fun healthStatusOrNull(port: Int): Int? =
    try {
      val connection =
        URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
      connection.connectTimeout = TIMEOUT.inWholeMilliseconds.toInt()
      connection.readTimeout = TIMEOUT.inWholeMilliseconds.toInt()
      try {
        connection.responseCode
      } finally {
        connection.disconnect()
      }
    } catch (_: IOException) {
      null
    }

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private companion object {
    const val AGENT = "e2e-agent"
    const val AGENT_SPAN = "invoke_agent $AGENT"
    const val RUN_BODY =
      """{"appName":"$AGENT","userId":"u","sessionId":"s","newMessage":""" +
        """{"role":"user","parts":[{"text":"hi"}]}}"""
    val TIMEOUT = 5.seconds
    val POLL_INTERVAL = 50.milliseconds
    val STARTUP_TIMEOUT = 20.seconds
  }
}
