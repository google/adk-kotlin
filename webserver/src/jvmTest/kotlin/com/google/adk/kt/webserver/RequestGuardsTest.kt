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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Covers per-request connection-peer, forwarding-header, DNS-rebinding, and `Origin` guards. */
@RunWith(JUnit4::class)
class RequestGuardsTest {
  private val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  @Test
  fun devServer_widenedHostWithoutOptIn_rejectsRemoteRequestsOnRunAndDevEndpoints() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkDevServer(testConfig(invocations, port = port, host = "0.0.0.0"))

    server.start()
    try {
      for (path in listOf("/run", "/run_sse", "/dev/apps/$APP_NAME/builder")) {
        val builder =
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header(HttpHeaders.XForwardedFor, "203.0.113.10")
        val request =
          if (path.endsWith("/builder")) {
            builder.GET().build()
          } else {
            builder
              .header(HttpHeaders.ContentType, "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
              .build()
          }
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(response.body()).contains("Forbidden: remote requests are disabled")
      }
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop()
    }
  }

  @Test
  fun embeddedApiModule_defaultConfig_rejectsRemoteRequestsViaClientGuard() {
    val invocations = AtomicInteger(0)
    withEmbeddedServer {
        adkApiModule(testConfig(invocations, allowOrigins = listOf("https://console.example.com")))
        routing { get("/healthz") { call.respondText("ok") } }
      }
      .use { port ->
        // Loopback Host passes Host guard; allowed Origin tests isCommitted short-circuit.
        val request =
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
            .header(HttpHeaders.ContentType, "application/json")
            .header(HttpHeaders.XForwardedFor, "203.0.113.10")
            .header(HttpHeaders.Origin, "https://console.example.com")
            .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(response.body()).contains("Forbidden: remote requests are disabled")
        assertThat(response.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null))
          .isNull()
        assertThat(invocations.get()).isEqualTo(0)

        // Embedder routes sit outside ADK's route-scoped guards.
        val probeResponse =
          rawSocketResponse(
            port = port,
            rawRequest =
              "GET /healthz HTTP/1.1\r\n" +
                "Host: k8s-probe.internal\r\n" +
                "X-Forwarded-For: 203.0.113.10\r\n" +
                "Origin: https://external.example\r\n" +
                "Connection: close\r\n\r\n",
          )
        assertThat(probeResponse.status).isEqualTo(HttpStatusCode.OK.value)
        assertThat(probeResponse.body).isEqualTo("ok")

        // Null bindHost skips Host check, while Origin check falls back to localAddress.
        val embeddedNonLoopbackHost =
          rawSocketResponse(
            port = port,
            rawRequest =
              "POST /run HTTP/1.1\r\n" +
                "Host: embedded.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${RUN_BODY.length}\r\n" +
                "Connection: close\r\n\r\n" +
                RUN_BODY,
          )
        assertThat(embeddedNonLoopbackHost.status).isEqualTo(HttpStatusCode.OK.value)
        assertThat(invocations.get()).isEqualTo(1)

        val embeddedReboundOrigin =
          rawSocketResponse(
            port = port,
            rawRequest =
              "POST /run HTTP/1.1\r\n" +
                "Host: evil.example:$port\r\n" +
                "Origin: http://evil.example:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${RUN_BODY.length}\r\n" +
                "Connection: close\r\n\r\n" +
                RUN_BODY,
          )
        assertThat(embeddedReboundOrigin.status).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(embeddedReboundOrigin.body).contains("Forbidden: origin not allowed")
        assertThat(invocations.get()).isEqualTo(1)
      }

    // Verify localClientGuardPlugin also skips calls whose response was already committed earlier.
    val preCommitPlugin =
      createRouteScopedPlugin("PreCommitGuard") {
        onCall { call -> call.respondText("pre-committed", status = HttpStatusCode.Accepted) }
      }
    withEmbeddedServer {
        routing {
          install(preCommitPlugin)
          install(localClientGuardPlugin(allowRemoteRequests = false))
          get("/precommitted") { call.respondText("unreachable") }
        }
      }
      .use { port ->
        val response =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/precommitted"))
              .header(HttpHeaders.XForwardedFor, "203.0.113.10")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Accepted.value)
        assertThat(response.body()).isEqualTo("pre-committed")
      }
  }

  @Test
  fun apiServer_defaultLoopbackHostRejectsForwardedRequests_andExplicitNonLoopbackHostAccepts() {
    val defaultInvocations = AtomicInteger(0)
    val defaultPort = freePort()
    val defaultServer = AdkApiServer(testConfig(defaultInvocations, port = defaultPort))

    defaultServer.start()
    try {
      val rejected =
        httpClient.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$defaultPort/run"))
            .header(HttpHeaders.ContentType, "application/json")
            .header(HttpHeaders.XForwardedFor, "203.0.113.10")
            .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
            .build(),
          HttpResponse.BodyHandlers.ofString(),
        )

      assertThat(rejected.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(rejected.body()).contains("Forbidden: remote requests are disabled")

      val foreignOriginLive =
        rawSocketResponse(
          port = defaultPort,
          rawRequest =
            "GET /run_live?app_name=$APP_NAME&user_id=u1&session_id=s1 HTTP/1.1\r\n" +
              "Host: 127.0.0.1:$defaultPort\r\n" +
              "Origin: https://evil.example\r\n" +
              "Upgrade: websocket\r\n" +
              "Connection: Upgrade\r\n" +
              "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
              "Sec-WebSocket-Version: 13\r\n\r\n",
        )
      assertThat(foreignOriginLive.status).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(foreignOriginLive.body).contains("Forbidden: origin not allowed")

      val reboundHostLive =
        rawSocketResponse(
          port = defaultPort,
          rawRequest =
            "GET /run_live?app_name=$APP_NAME&user_id=u1&session_id=s1 HTTP/1.1\r\n" +
              "Host: evil-rebind.example:$defaultPort\r\n" +
              "Upgrade: websocket\r\n" +
              "Connection: Upgrade\r\n" +
              "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
              "Sec-WebSocket-Version: 13\r\n\r\n",
        )
      assertThat(reboundHostLive.status).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(reboundHostLive.body).contains("Forbidden: host not allowed")

      assertThat(defaultInvocations.get()).isEqualTo(0)
    } finally {
      defaultServer.stop()
    }

    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkApiServer(testConfig(invocations, port = port, host = "0.0.0.0"))

    server.start()
    try {
      val request =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
          .header(HttpHeaders.ContentType, "application/json")
          .header(HttpHeaders.XForwardedFor, "203.0.113.10")
          .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
          .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK.value)
      assertThat(invocations.get()).isEqualTo(1)
    } finally {
      server.stop()
    }
  }

  @Test
  fun devServer_explicitAllowRemoteRequestsOptIn_allowsTunneledHostAndForwardedPeer() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkDevServer(
        testConfig(
          invocations,
          port = port,
          allowOrigins = listOf("https://tunnel.example"),
          allowRemoteRequests = true,
        )
      )

    server.start()
    try {
      // Loopback bind still rejects unlisted rebound Host when allowRemoteRequests = true.
      val rebindResponse =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: evil-rebind.example\r\n" +
              "Origin: http://evil-rebind.example\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )
      assertThat(rebindResponse.status).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(rebindResponse.body).contains("Forbidden: host not allowed")
      assertThat(invocations.get()).isEqualTo(0)

      // Tunnel host vouched for by allowOrigins is accepted along with forwarded headers.
      val response =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: tunnel.example\r\n" +
              "Origin: https://tunnel.example\r\n" +
              "X-Forwarded-For: 203.0.113.9\r\n" +
              "X-Forwarded-Proto: https\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )

      assertThat(response.status).isEqualTo(HttpStatusCode.OK.value)
      assertThat(invocations.get()).isEqualTo(1)
    } finally {
      server.stop()
    }
  }

  @Test
  fun apiServer_behindTlsTerminatingProxy_honorsForwardedAndXForwardedProtoForSameOrigin() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkApiServer(testConfig(invocations, port = port, host = "0.0.0.0"))

    server.start()
    try {
      val xForwardedAllowed =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: svc-abc.a.run.app\r\n" +
              "Origin: https://svc-abc.a.run.app\r\n" +
              "X-Forwarded-For: 203.0.113.7\r\n" +
              "X-Forwarded-Proto: https\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )
      val xForwardedSchemeMismatch =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: svc-abc.a.run.app\r\n" +
              "Origin: http://svc-abc.a.run.app\r\n" +
              "X-Forwarded-For: 203.0.113.7\r\n" +
              "X-Forwarded-Proto: https\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )
      val rfcForwardedAllowed =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: internal.local:8080\r\n" +
              "Origin: https://agents.example.com\r\n" +
              "Forwarded: for=203.0.113.7;proto=\"https\";host=\"agents.example.com\"\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )
      val xForwardedHostAllowed =
        rawSocketResponse(
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: internal.local:8080\r\n" +
              "Origin: https://portal.example.com:8443\r\n" +
              "X-Forwarded-Host: portal.example.com:8443, proxy.internal\r\n" +
              "X-Forwarded-Proto: wss, http\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )

      assertThat(xForwardedAllowed.status).isEqualTo(HttpStatusCode.OK.value)
      assertThat(xForwardedSchemeMismatch.status).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(xForwardedSchemeMismatch.body).contains("Forbidden: origin not allowed")
      assertThat(rfcForwardedAllowed.status).isEqualTo(HttpStatusCode.OK.value)
      assertThat(xForwardedHostAllowed.status).isEqualTo(HttpStatusCode.OK.value)
      assertThat(invocations.get()).isEqualTo(3)
    } finally {
      server.stop()
    }
  }

  @Test
  fun devServer_tunneledRequestToLoopback_rejectsEveryForwardingHeader() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkDevServer(testConfig(invocations, port = port))

    server.start()
    try {
      val forwardingHeaders =
        listOf(
          HttpHeaders.Forwarded to "for=203.0.113.42;proto=https",
          HttpHeaders.XForwardedFor to "203.0.113.42",
          HttpHeaders.XForwardedHost to "tunnel.example",
          HttpHeaders.XForwardedProto to "https",
          "X-Real-IP" to "203.0.113.42",
        )
      for ((name, value) in forwardingHeaders) {
        val request =
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
            .header("Content-Type", "application/json")
            .header(name, value)
            .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(response.body()).contains("Forbidden: remote requests are disabled")
      }
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop()
    }
  }

  @Test
  fun devServer_dnsRebindingAndSmuggledHostHeaders_areRejectedOnGetAndPost() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkDevServer(testConfig(invocations, port = port))

    server.start()
    try {
      val rebindGet =
        rawSocketStatus(
          host = "127.0.0.1",
          port = port,
          rawRequest =
            "GET /list-apps HTTP/1.1\r\n" +
              "Host: evil-rebind.example:$port\r\n" +
              "Connection: close\r\n\r\n",
        )
      val rebindPost =
        rawSocketStatus(
          host = "127.0.0.1",
          port = port,
          rawRequest =
            "POST /run HTTP/1.1\r\n" +
              "Host: evil-rebind.example:$port\r\n" +
              "Origin: http://evil-rebind.example:$port\r\n" +
              "Content-Type: application/json\r\n" +
              "Content-Length: ${RUN_BODY.length}\r\n" +
              "Connection: close\r\n\r\n" +
              RUN_BODY,
        )
      val commaSmuggledHost =
        rawSocketStatus(
          host = "127.0.0.1",
          port = port,
          rawRequest =
            "GET /list-apps HTTP/1.1\r\n" +
              "Host: 127.0.0.1:$port, evil.example\r\n" +
              "Connection: close\r\n\r\n",
        )
      val duplicateHost =
        rawSocketStatus(
          host = "127.0.0.1",
          port = port,
          rawRequest =
            "GET /list-apps HTTP/1.1\r\n" +
              "Host: 127.0.0.1:$port\r\n" +
              "Host: evil.example\r\n" +
              "Connection: close\r\n\r\n",
        )

      assertThat(rebindGet).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(rebindPost).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(commaSmuggledHost).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(duplicateHost).isEqualTo(HttpStatusCode.BadRequest.value)
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop()
    }
  }

  @Test
  fun devServer_crossOriginBrowserRequests_areRejectedWhileSameOriginLoopbackAndIpv6Succeed() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server = AdkDevServer(testConfig(invocations, port = port))

    server.start()
    try {
      for (untrustedOrigin in
        listOf(
          "https://evil.example",
          "http://127.0.0.1:${port + 1}",
          "http://localhost@evil.example",
          "http://127.0.0.1:${port}.evil.example",
          "https://evil.example:notaport",
          "https://evil.example?foo=bar",
          "https://evil%2eexample.com",
          "http://[::1",
          "null",
          "http://127.0.0.1:$port, https://evil.example",
        )) {
        val rejected =
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
            .header("Content-Type", "application/json")
            .header("Origin", untrustedOrigin)
            .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
            .build()
        val response = httpClient.send(rejected, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(response.body()).contains("Forbidden: origin not allowed")
      }
      assertThat(invocations.get()).isEqualTo(0)

      for (loopbackHost in listOf("127.0.0.1:$port", "localhost:$port", "[::1]", "[::1]:$port")) {
        val allowed =
          rawSocketStatus(
            host = "127.0.0.1",
            port = port,
            rawRequest =
              "POST /run_sse HTTP/1.1\r\n" +
                "Host: $loopbackHost\r\n" +
                "Origin: http://$loopbackHost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${RUN_BODY.length}\r\n" +
                "Connection: close\r\n\r\n" +
                RUN_BODY,
          )
        assertThat(allowed).isEqualTo(HttpStatusCode.OK.value)
      }
      assertThat(invocations.get()).isEqualTo(4)

      val sameOriginPreflight =
        rawSocketResponse(
          host = "127.0.0.1",
          port = port,
          rawRequest =
            "OPTIONS /run HTTP/1.1\r\n" +
              "Host: 127.0.0.1:$port\r\n" +
              "Origin: http://127.0.0.1:$port\r\n" +
              "Access-Control-Request-Method: POST\r\n" +
              "Access-Control-Request-Headers: content-type\r\n" +
              "Connection: close\r\n\r\n",
        )
      assertThat(sameOriginPreflight.status).isEqualTo(HttpStatusCode.OK.value)
      assertThat(sameOriginPreflight.headers[HttpHeaders.AccessControlAllowOrigin.lowercase()])
        .isEqualTo("http://127.0.0.1:$port")
    } finally {
      server.stop()
    }
  }

  @Test
  fun allowOrigins_literalRegexAndWildcard_setCorsHeadersAndAnswerPreflight() {
    val config =
      testConfig(
        allowOrigins =
          listOf("https://Console.Example.com/", "regex:https://[a-z0-9-]+\\.preview\\.dev"),
        allowRemoteRequests = false,
      )

    withEmbeddedServer { adkApiModule(config) }
      .use { port ->
        val literalAllowed =
          rawSocketResponse(
            port = port,
            rawRequest =
              "POST /run HTTP/1.1\r\n" +
                "Host: console.example.com\r\n" +
                "Origin: https://console.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${RUN_BODY.length}\r\n" +
                "Connection: close\r\n\r\n" +
                RUN_BODY,
          )
        val regexAllowed =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
              .header("Content-Type", "application/json")
              .header("Origin", "https://pr-42.preview.dev")
              .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        val preflightAllowed =
          rawSocketResponse(
            port = port,
            rawRequest =
              "OPTIONS /run HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: https://pr-42.preview.dev\r\n" +
                "Access-Control-Request-Method: POST\r\n" +
                "Access-Control-Request-Headers: content-type, authorization\r\n" +
                "Connection: close\r\n\r\n",
          )
        val prefixBypassBlocked =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
              .header("Content-Type", "application/json")
              .header("Origin", "https://pr-42.preview.dev.evil.example")
              .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )

        assertThat(literalAllowed.status).isEqualTo(HttpStatusCode.OK.value)
        assertThat(literalAllowed.headers[HttpHeaders.AccessControlAllowOrigin.lowercase()])
          .isEqualTo("https://console.example.com")
        assertThat(literalAllowed.headers[HttpHeaders.AccessControlAllowCredentials.lowercase()])
          .isEqualTo("true")
        assertThat(regexAllowed.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(
            regexAllowed.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null)
          )
          .isEqualTo("https://pr-42.preview.dev")
        assertThat(
            regexAllowed
              .headers()
              .firstValue(HttpHeaders.AccessControlAllowCredentials)
              .orElse(null)
          )
          .isEqualTo("true")
        assertThat(preflightAllowed.status).isEqualTo(HttpStatusCode.OK.value)
        assertThat(preflightAllowed.headers[HttpHeaders.AccessControlAllowOrigin.lowercase()])
          .isEqualTo("https://pr-42.preview.dev")
        assertThat(preflightAllowed.headers[HttpHeaders.AccessControlAllowHeaders.lowercase()])
          .isEqualTo("content-type, authorization")
        assertThat(prefixBypassBlocked.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
      }

    withEmbeddedServer {
        rootPath = "/api"
        adkApiModule(config)
      }
      .use { port ->
        val rootPathPreflightAllowed =
          rawSocketResponse(
            port = port,
            rawRequest =
              "OPTIONS /api/run HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: https://console.example.com\r\n" +
                "Access-Control-Request-Method: POST\r\n" +
                "Connection: close\r\n\r\n",
          )
        val rootPathPreflightDisallowed =
          rawSocketResponse(
            port = port,
            rawRequest =
              "OPTIONS /api/run HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: https://evil.example\r\n" +
                "Access-Control-Request-Method: POST\r\n" +
                "Connection: close\r\n\r\n",
          )
        val unprefixedPreflight =
          rawSocketResponse(
            port = port,
            rawRequest =
              "OPTIONS /run HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: https://console.example.com\r\n" +
                "Access-Control-Request-Method: POST\r\n" +
                "Connection: close\r\n\r\n",
          )
        assertThat(rootPathPreflightAllowed.status).isEqualTo(HttpStatusCode.OK.value)
        assertThat(
            rootPathPreflightAllowed.headers[HttpHeaders.AccessControlAllowOrigin.lowercase()]
          )
          .isEqualTo("https://console.example.com")
        assertThat(rootPathPreflightDisallowed.status).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(unprefixedPreflight.status).isEqualTo(HttpStatusCode.NotFound.value)
        assertThat(unprefixedPreflight.headers[HttpHeaders.AccessControlAllowOrigin.lowercase()])
          .isNull()
      }

    withEmbeddedServer { adkApiModule(testConfig(allowOrigins = listOf("*"))) }
      .use { port ->
        val wildcardAllowed =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/list-apps"))
              .header("Origin", "https://any-origin.example.com")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(wildcardAllowed.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(
            wildcardAllowed.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null)
          )
          .isEqualTo("*")
        assertThat(
            wildcardAllowed
              .headers()
              .firstValue(HttpHeaders.AccessControlAllowCredentials)
              .orElse(null)
          )
          .isNull()
      }
  }

  @Test
  fun embeddedApiModule_coexistsWithCallerInstalledCorsAtAppAndRouteLevel() {
    val fakeCorsPlugin =
      createRouteScopedPlugin("CORS") {
        onCall { call ->
          val origin = call.request.headers[HttpHeaders.Origin]
          if (origin != null && (origin.startsWith("http://") || origin.startsWith("https://"))) {
            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
          }
        }
      }

    // Application-level CORS allows HTTP origins while Origin: null still gets 403.
    withEmbeddedServer {
        install(fakeCorsPlugin)
        adkApiModule(testConfig())
      }
      .use { port ->
        val response =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
              .header(HttpHeaders.Origin, "https://allowed.example")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        val nullOriginPost =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/apps/$APP_NAME/users/u1/sessions"))
              .header(HttpHeaders.Origin, "null")
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(response.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null))
          .isEqualTo("*")
        assertThat(nullOriginPost.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(nullOriginPost.body()).contains("Forbidden: origin not allowed")
      }

    // Route-level CORS on a custom route leaves ADK endpoints protected.
    withEmbeddedServer {
        adkApiModule(testConfig())
        routing {
          route("/custom") {
            install(fakeCorsPlugin)
            get { call.respondText("ok") }
          }
        }
      }
      .use { port ->
        val customResponse =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/custom"))
              .header(HttpHeaders.Origin, "https://allowed.example")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        val adkEndpointResponse =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
              .header(HttpHeaders.ContentType, "application/json")
              .header(HttpHeaders.Origin, "https://allowed.example")
              .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(customResponse.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(
            customResponse.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null)
          )
          .isEqualTo("*")
        assertThat(adkEndpointResponse.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
      }

    // Caller installs CORS at application level after adkApiModule.
    withEmbeddedServer {
        adkApiModule(testConfig())
        install(fakeCorsPlugin)
      }
      .use { port ->
        val response =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
              .header(HttpHeaders.Origin, "https://allowed.example")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(response.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null))
          .isEqualTo("*")
      }

    // Caller installs CORS on the routing root wrapping ADK routes.
    withEmbeddedServer {
        routing { install(fakeCorsPlugin) }
        adkApiModule(testConfig())
      }
      .use { port ->
        val response =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
              .header(HttpHeaders.Origin, "https://allowed.example")
              .GET()
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(response.statusCode()).isEqualTo(HttpStatusCode.OK.value)
        assertThat(response.headers().firstValue(HttpHeaders.AccessControlAllowOrigin).orElse(null))
          .isEqualTo("*")
      }
  }

  private fun RoutingNode.leafRoutes(): List<String> =
    if (children.isEmpty()) listOf(toString()) else children.flatMap { it.leafRoutes() }

  @Test
  fun devModule_blocksForwardedRequestsByDefaultAndAllowsWhenOptedIn() {
    val mountedLeafRoutes = mutableSetOf<String>()
    val mountedAllRoutes = mutableSetOf<String>()
    withEmbeddedServer {
        adkDevModule(testConfig())
        mountedLeafRoutes += plugin(RoutingRoot).leafRoutes()
        mountedAllRoutes += plugin(RoutingRoot).getAllRoutes().map { it.toString() }
      }
      .use { port ->
        assertThat(mountedLeafRoutes).contains("/health/(method:GET)")
        assertThat(mountedLeafRoutes).contains("/(method:GET)")
        assertThat(mountedLeafRoutes.filter { "OPTIONS" in it || "AdkRouteSelector" in it })
          .isEmpty()
        assertThat(mountedAllRoutes).contains("/run/(method:POST)")
        val rootUi =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(rootUi.statusCode()).isEqualTo(HttpStatusCode.Found.value)

        val direct =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/list-apps")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(direct.statusCode()).isEqualTo(HttpStatusCode.OK.value)

        val proxied =
          rawSocketResponse(
            port = port,
            rawRequest =
              "GET /list-apps HTTP/1.1\r\n" +
                "Host: tunnel.example\r\n" +
                "X-Forwarded-For: 198.51.100.7\r\n" +
                "Connection: close\r\n\r\n",
          )
        assertThat(proxied.status).isEqualTo(HttpStatusCode.Forbidden.value)
        assertThat(proxied.body).contains("Forbidden: remote requests are disabled")
      }

    withEmbeddedServer {
        adkDevModule(testConfig(allowRemoteRequests = true).copy(webUiEnabled = false))
      }
      .use { port ->
        val rootUiDisabled =
          httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertThat(rootUiDisabled.statusCode()).isEqualTo(HttpStatusCode.NotFound.value)

        val optedIn =
          rawSocketResponse(
            port = port,
            rawRequest =
              "GET /list-apps HTTP/1.1\r\n" +
                "Host: tunnel.example\r\n" +
                "X-Forwarded-For: 198.51.100.7\r\n" +
                "Connection: close\r\n\r\n",
          )
        assertThat(optedIn.status).isEqualTo(HttpStatusCode.OK.value)
      }
  }

  @Test
  fun loopbackAndAuthorityHelpers_acceptValidLoopbacksAndRejectSmuggledHosts() {
    assertThat(isLocalClient("127.0.0.1", Headers.Empty)).isTrue()
    assertThat(isLocalClient("::1", Headers.Empty)).isTrue()
    assertThat(isLocalClient("203.0.113.10", Headers.Empty)).isFalse()
    assertThat(isLocalClient("0.0.0.0", Headers.Empty)).isFalse()
    assertThat(
        isLocalClient(
          "127.0.0.1",
          Headers.build { append(HttpHeaders.XForwardedFor, "203.0.113.10") },
        )
      )
      .isFalse()

    assertThat(
        isDnsRebindingRequest(Headers.Empty, loopbackBind = true, allowedRequestHosts = emptySet())
      )
      .isFalse()
    assertThat(
        isDnsRebindingRequest(
          Headers.build { append(HttpHeaders.Host, "evil.example") },
          loopbackBind = true,
          allowedRequestHosts = emptySet(),
        )
      )
      .isTrue()
    assertThat(
        isDnsRebindingRequest(
          Headers.build { append(HttpHeaders.Host, "evil.example") },
          loopbackBind = false,
          allowedRequestHosts = emptySet(),
        )
      )
      .isFalse()

    for (loopback in
      listOf(
        "localhost",
        "LOCALHOST.",
        "localhost:8080",
        "127.0.0.1",
        "127.255.255.254:9000",
        "::1",
        "[::1]",
        "[::1]:8080",
        "0:0:0:0:0:0:0:1",
        "::ffff:127.0.0.1",
        "[::ffff:127.0.0.1]:8080",
        "::1%lo0",
      )) {
      assertWithMessage(loopback).that(isLoopbackAddress(loopback)).isTrue()
    }

    for (nonLoopback in
      listOf(
        "",
        "unknown",
        "0.0.0.0",
        "::",
        "192.168.1.10",
        "127.0.0.256",
        "127.0.0.1:8080.evil.com",
        "[::1]:8080.evil.com",
        "[::1",
        "localhost.evil.com",
        "0177.0.0.1",
        "127.1",
        "::ffff:192.168.1.5",
        "::1%lo0/evil",
      )) {
      assertWithMessage(nonLoopback).that(isLoopbackAddress(nonLoopback)).isFalse()
    }

    assertThat(parseOriginHost("http://[::1]:4200")).isEqualTo("::1")
    assertThat(parseOriginHost("http://[::1]")).isEqualTo("::1")
    assertThat(parseOriginHost("https://UI.Example.com/")).isEqualTo("ui.example.com")
    assertThat(parseOriginHost("https://dev_box.internal:8443")).isEqualTo("dev_box.internal")
    assertThat(parseOriginHost("https://evil.example/path")).isNull()
    assertThat(parseOriginHost("http://localhost@evil.com")).isNull()
    assertThat(parseOriginHost("http://127.0.0.1:8080.evil.com")).isNull()
    assertThat(parseOriginHost("https://evil.example:notaport")).isNull()
    assertThat(parseOriginHost("https://evil.example:99999")).isNull()
    assertThat(parseOriginHost("https://evil.example?foo=bar")).isNull()
    assertThat(parseOriginHost("https://evil%2eexample.com")).isNull()
    assertThat(parseOriginHost("http://[::1")).isNull()
    assertThat(parseOriginHost("null")).isNull()
    assertThat(
        isDnsRebindingRequest(
          headers =
            Headers.build { appendAll(HttpHeaders.Host, listOf("127.0.0.1:8080", "evil.example")) },
          loopbackBind = true,
          allowedRequestHosts = emptySet(),
        )
      )
      .isTrue()
  }

  private fun testConfig(
    invocations: AtomicInteger = AtomicInteger(0),
    port: Int = AdkServerConfig.DEFAULT_PORT,
    host: String = AdkServerConfig.DEFAULT_HOST,
    allowOrigins: List<String> = emptyList(),
    allowRemoteRequests: Boolean? = null,
  ): AdkServerConfig =
    AdkServerConfig.inMemory(
        App(appName = APP_NAME, rootAgent = CountingAgent(invocations)),
        port = port,
      )
      .copy(host = host, allowOrigins = allowOrigins, allowRemoteRequests = allowRemoteRequests)
      .also { config ->
        runBlocking {
          val unused = config.sessionService.createSession(SessionKey(APP_NAME, "u1", "s1"))
        }
      }

  private class RunningServer(private val server: EmbeddedServer<*, *>, val port: Int) :
    AutoCloseable {
    inline fun <T> use(block: (Int) -> T): T =
      try {
        block(port)
      } finally {
        close()
      }

    override fun close() {
      server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }
  }

  private fun withEmbeddedServer(
    host: String = "127.0.0.1",
    appConfig: MapApplicationConfig = MapApplicationConfig(),
    module: Application.() -> Unit,
  ): RunningServer {
    val environment = applicationEnvironment { config = appConfig }
    val server =
      embeddedServer(
        Netty,
        environment = environment,
        configure = {
          connector {
            this.host = host
            this.port = 0
          }
        },
        module = module,
      )
    server.start(wait = false)
    val resolvedPort = runBlocking { server.engine.resolvedConnectors().single().port }
    return RunningServer(server, resolvedPort)
  }

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private data class RawHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
  )

  private fun rawSocketStatus(host: String = "127.0.0.1", port: Int, rawRequest: String): Int =
    rawSocketResponse(host = host, port = port, rawRequest = rawRequest).status

  private fun rawSocketResponse(
    host: String = "127.0.0.1",
    port: Int,
    rawRequest: String,
  ): RawHttpResponse =
    Socket(host, port).use { socket ->
      socket.soTimeout = 5_000
      socket.getOutputStream().apply {
        write(rawRequest.toByteArray(Charsets.ISO_8859_1))
        flush()
      }
      val input = socket.getInputStream()
      val headerBytes = ByteArrayOutputStream()
      var matched = 0
      val delimiter =
        byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
      while (matched < delimiter.size) {
        val b = input.read()
        if (b == -1) break
        headerBytes.write(b)
        matched =
          if (b.toByte() == delimiter[matched]) {
            matched + 1
          } else if (b.toByte() == delimiter[0]) {
            1
          } else {
            0
          }
      }
      val headerBlock = headerBytes.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
      val lines = headerBlock.split("\r\n")
      val status = lines.first().split(' ')[1].toInt()
      val headers =
        lines
          .drop(1)
          .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) {
              null
            } else {
              line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }
          }
          .toMap()
      val contentLength = headers["content-length"]?.toIntOrNull()
      val body =
        when {
          contentLength != null -> input.readNBytes(contentLength).toString(Charsets.ISO_8859_1)
          status == HttpStatusCode.SwitchingProtocols.value -> {
            val unused = input.read()
            ""
          }
          else -> input.readBytes().toString(Charsets.ISO_8859_1)
        }
      RawHttpResponse(status = status, headers = headers, body = body)
    }

  private class CountingAgent(private val invocations: AtomicInteger = AtomicInteger(0)) :
    BaseAgent(name = "guard_test_agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
      invocations.incrementAndGet()
      emit(
        Event(
          invocationId = context.invocationId,
          author = name,
          content = Content(role = "model", parts = listOf(Part(text = "ok"))),
        )
      )
    }

    @OptIn(ExperimentalLiveApi::class)
    override fun runLiveImpl(context: InvocationContext): Flow<Event> = runAsyncImpl(context)
  }

  private companion object {
    const val APP_NAME = "guard_test_app"
    const val RUN_BODY =
      """{"appName":"guard_test_app","userId":"u1","sessionId":"s1","newMessage":{"role":"user","parts":[{"text":"hi"}]}}"""
  }
}
