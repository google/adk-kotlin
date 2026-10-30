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

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

/** Sends [body] as JSON, the shape every route test posts in. */
internal fun HttpRequestBuilder.jsonBody(body: String) {
  contentType(ContentType.Application.Json)
  setBody(body)
}

/** Sends [body] with a non-JSON content type, for asserting the 415 path. */
internal fun HttpRequestBuilder.textBody(body: String) {
  contentType(ContentType.Text.Plain)
  setBody(body)
}

/**
 * A websocket client naming [host], as every real HTTP/1.1 client names its host, and a page at
 * [origin] if given; `testApplication` sends no `Host` of its own.
 */
internal fun ApplicationTestBuilder.liveClient(
  host: String = "localhost",
  origin: String? = null,
): HttpClient = createClient {
  install(ClientWebSockets)
  defaultRequest {
    header("Host", host)
    if (origin != null) header("Origin", origin)
  }
}

/**
 * Attempts a live websocket upgrade to [appName] and returns whether the server accepted the
 * upgrade; a hang fails the test.
 */
internal suspend fun ApplicationTestBuilder.liveHandshakeSucceeds(
  appName: String,
  host: String = "localhost",
  origin: String? = null,
): Boolean =
  liveClient(host, origin).use { client ->
    try {
      withTimeout(HANDSHAKE_TIMEOUT) {
        client.webSocket("/run_live?app_name=$appName&user_id=u&session_id=s") {}
      }
      true
    } catch (e: CancellationException) {
      // A hang or a cancelled test, not a refusal.
      throw e
    } catch (e: IllegalStateException) {
      // How the test engine reports a refused upgrade; the engine does not expose the status.
      false
    }
  }

private val HANDSHAKE_TIMEOUT = 10.seconds
