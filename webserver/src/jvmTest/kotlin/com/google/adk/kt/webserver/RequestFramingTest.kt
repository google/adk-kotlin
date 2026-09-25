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

package com.google.adk.kt.webserver

import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers how a request frames its body, which only a raw socket can state exactly.
 *
 * The test client always sends `Content-Length: 0` for a bodyless request, so it cannot express
 * either shape that decides this rule: no length header at all, and a body framed by a transfer
 * coding instead of a length.
 */
@RunWith(JUnit4::class)
class RequestFramingTest {
  private lateinit var server: ApplicationEngine
  private var port = 0

  @Before
  fun startServer() {
    server =
      embeddedServer(Netty, port = EPHEMERAL_PORT, host = LOOPBACK) {
        adkApiModule(
          AdkServerConfig(
            agentLoader = FakeAgentLoader(),
            sessionService = FakeSessionService(),
            artifactService = FakeArtifactService(),
            apiServerSpanExporter = ApiServerSpanExporter(),
          )
        )
      }
    server.start(wait = false)
    port = runBlocking { server.resolvedConnectors().first().port }
  }

  @After
  fun stopServer() {
    server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
  }

  @Test
  fun post_noLengthAndNoType_isBadRequestOnEveryRoute() {
    val offenders = BODY_READING_ROUTES.filter { path ->
      statusOf("POST $path HTTP/1.1\r\n$BASE_HEADERS\r\n") != HttpStatusCode.BadRequest.value
    }

    assertThat(offenders).isEmpty()
  }

  @Test
  fun run_malformedContentType_isBadRequestNotAServerError() {
    // Parsing this header throws, and Ktor logs its value; the guard must read it, not parse it.
    val status =
      statusOf(
        "POST /run HTTP/1.1\r\n${BASE_HEADERS}Content-Type: foo\r\nContent-Length: 0\r\n\r\n"
      )

    assertThat(status).isEqualTo(HttpStatusCode.BadRequest.value)
  }

  @Test
  fun run_bodyWithNoType_isUnsupportedMediaType() {
    // Content arrived, so its type is the fault; only an absent body is a missing one.
    val status =
      statusOf(
        "POST /run HTTP/1.1\r\n${BASE_HEADERS}Content-Length: ${RUN_BODY.length}\r\n\r\n$RUN_BODY"
      )

    assertThat(status).isEqualTo(HttpStatusCode.UnsupportedMediaType.value)
  }

  @Test
  fun run_chunkedBodyWithNoType_isUnsupportedMediaType() {
    // A transfer coding frames a body without declaring a length, and its value is a list, so
    // matching the header against `chunked` alone drops the body and answers 400 instead.
    val chunked = "${RUN_BODY.length.toString(16)}\r\n$RUN_BODY\r\n0\r\n\r\n"

    val status =
      statusOf(
        "POST /run HTTP/1.1\r\n${BASE_HEADERS}Transfer-Encoding: gzip, chunked\r\n\r\n$chunked"
      )

    assertThat(status).isEqualTo(HttpStatusCode.UnsupportedMediaType.value)
  }

  /** Writes [raw] verbatim and returns the response's status code, so no client adds a header. */
  private fun statusOf(raw: String): Int =
    Socket(LOOPBACK, port).use { socket ->
      socket.soTimeout = SOCKET_TIMEOUT_MILLIS
      socket.outputStream.apply {
        write(raw.toByteArray())
        flush()
      }
      val statusLine = socket.inputStream.bufferedReader().readLine().orEmpty()
      statusLine.split(" ").getOrNull(1)?.toIntOrNull()
        ?: error("No status code in response: \"$statusLine\"")
    }

  private companion object {
    /** Port 0 binds an ephemeral port, which the engine reports once it is up. */
    const val EPHEMERAL_PORT = 0
    const val LOOPBACK = "127.0.0.1"
    const val BASE_HEADERS = "Host: 127.0.0.1\r\nConnection: close\r\n"
    const val RUN_BODY = """{"appName":"a","userId":"u"}"""
    const val SOCKET_TIMEOUT_MILLIS = 15_000
    const val STOP_GRACE_MILLIS = 500L
    const val STOP_TIMEOUT_MILLIS = 2000L
  }
}
