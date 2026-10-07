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

import com.google.common.truth.Truth.assertThat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.event.Level

/** [requestLoggingPlugin] on a real Netty server, the engine [AdkApiServer] runs. */
@RunWith(JUnit4::class)
class RequestLoggingTest {
  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  @Test
  fun success_logsOneInfoLine() {
    val lines = linesLoggedFor("/ok") { routing { get("/ok") { call.respondText("ok") } } }

    assertThat(lines).containsExactly(Level.INFO to "Status: 200 OK, HTTP method: GET, URI: /ok")
  }

  @Test
  fun serverError_logsOneWarning() {
    val lines =
      linesLoggedFor("/fail") {
        routing { get("/fail") { call.respond(HttpStatusCode.InternalServerError) } }
      }

    assertThat(lines)
      .containsExactly(
        Level.WARN to "Status: 500 Internal Server Error, HTTP method: GET, URI: /fail"
      )
  }

  @Test
  fun resentResponse_logsOneLine() {
    val lines =
      linesLoggedFor("/ok") {
        install(resendAsNotFound)
        routing { get("/ok") { call.respondText("ok") } }
      }

    assertThat(lines)
      .containsExactly(Level.INFO to "Status: 404 Not Found, HTTP method: GET, URI: /ok")
  }

  private fun linesLoggedFor(
    path: String,
    module: Application.() -> Unit,
  ): List<Pair<Level, String>> {
    val lines = Collections.synchronizedList(mutableListOf<Pair<Level, String>>())
    val port = ServerSocket(0).use { it.localPort }
    val server =
      embeddedServer(Netty, port = port, host = LOOPBACK) {
        install(requestLoggingPlugin { level, message -> lines += level to message })
        module()
      }
    server.start(wait = false)
    try {
      val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:$port$path")).build()
      client.send(request, HttpResponse.BodyHandlers.discarding())
    } finally {
      // stop() waits for the call to finish, so every ResponseSent has fired by now.
      server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
    }
    return lines.toList()
  }

  private companion object {
    const val LOOPBACK = "127.0.0.1"
    const val STOP_GRACE_MILLIS = 100L
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}

private val RESENT_KEY = AttributeKey<Unit>("Resent")

private val resendAsNotFound =
  createApplicationPlugin("ResendAsNotFound") {
    on(ResponseBodyReadyForSend) { call, _ ->
      if (RESENT_KEY in call.attributes) return@on
      call.attributes.put(RESENT_KEY, Unit)
      call.respondText("resent", status = HttpStatusCode.NotFound)
    }
  }
