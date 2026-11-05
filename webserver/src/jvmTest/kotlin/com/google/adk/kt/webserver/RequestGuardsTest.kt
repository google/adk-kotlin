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
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Covers per-request connection-peer, forwarding-header, DNS-rebinding, and `Origin` guards. */
@RunWith(JUnit4::class)
class RequestGuardsTest {
  private val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  @Test
  fun devServer_nonLoopbackSocketPeer_rejectsRunAndRunSseEvenWithSpoofedLoopbackHeaders() {
    val offLoopbackIp = assumeOffLoopbackAddress()
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkDevServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
          host = offLoopbackIp,
        )
      )

    server.start()
    try {
      for (path in listOf("/run", "/run_sse", "/dev/apps/mock-agent/builder")) {
        val method = if (path.endsWith("/builder")) "GET" else "POST"
        val bodyPart =
          if (method == "POST") {
            "Content-Type: application/json\r\nContent-Length: ${RUN_BODY.length}\r\n\r\n$RUN_BODY"
          } else {
            "\r\n"
          }
        val status =
          rawSocketStatus(
            host = offLoopbackIp,
            port = port,
            rawRequest =
              "$method $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: http://127.0.0.1:$port\r\n" +
                "Connection: close\r\n" +
                bodyPart,
          )
        assertThat(status).isEqualTo(HttpStatusCode.Forbidden.value)
      }
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop()
    }
  }

  @Test
  fun embeddedApiModule_defaultConfigOnNonLoopbackEngine_rejectsRemotePeer() {
    val offLoopbackIp = assumeOffLoopbackAddress()
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      embeddedServer(Netty, port = port, host = offLoopbackIp) {
        adkApiModule(
          AdkServerConfig(
            appLoader = FakeAppLoader { CountingAgent(invocations) },
            sessionService = FakeSessionService(),
            artifactService = FakeArtifactService(),
          )
        )
      }

    server.start(wait = false)
    try {
      val request =
        HttpRequest.newBuilder(URI("http://$offLoopbackIp:$port/run"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
          .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      assertThat(response.statusCode()).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop(100, 2_000)
    }
  }

  @Test
  fun apiServer_explicitNonLoopbackHost_acceptsDirectPeerWithoutExtraFlag() {
    val offLoopbackIp = assumeOffLoopbackAddress()
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkApiServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
          host = offLoopbackIp,
        )
      )

    server.start()
    try {
      val request =
        HttpRequest.newBuilder(URI("http://$offLoopbackIp:$port/run"))
          .header("Content-Type", "application/json")
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
  fun devServer_nonLoopbackSocketPeer_allowsWhenExplicitlyOptedIn() {
    val offLoopbackIp = assumeOffLoopbackAddress()
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkDevServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
          host = offLoopbackIp,
          allowRemoteRequests = true,
        )
      )

    server.start()
    try {
      val request =
        HttpRequest.newBuilder(URI("http://$offLoopbackIp:$port/run"))
          .header("Content-Type", "application/json")
          .header("Origin", "http://$offLoopbackIp:$port")
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
  fun devServer_tunneledRequestToLoopback_rejectsEveryForwardingHeader() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkDevServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
        )
      )

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
    val server =
      AdkDevServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
        )
      )

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
      assertThat(duplicateHost).isEqualTo(HttpStatusCode.Forbidden.value)
      assertThat(invocations.get()).isEqualTo(0)
    } finally {
      server.stop()
    }
  }

  @Test
  fun devServer_crossOriginBrowserRequests_areRejectedWhileSameOriginLoopbackSucceeds() {
    val invocations = AtomicInteger(0)
    val port = freePort()
    val server =
      AdkDevServer(
        AdkServerConfig(
          appLoader = FakeAppLoader { CountingAgent(invocations) },
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          port = port,
        )
      )

    server.start()
    try {
      for (untrustedOrigin in
        listOf(
          "https://evil.example",
          "http://localhost@evil.example",
          "null",
          "http://127.0.0.1:$port, https://evil.example",
        )) {
        val rejected =
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port/run"))
            .header("Content-Type", "application/json")
            .header("Origin", untrustedOrigin)
            .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
            .build()
        assertThat(httpClient.send(rejected, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(HttpStatusCode.Forbidden.value)
      }
      assertThat(invocations.get()).isEqualTo(0)

      for (loopbackHost in listOf("127.0.0.1:$port", "localhost:$port")) {
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
      assertThat(invocations.get()).isEqualTo(2)
    } finally {
      server.stop()
    }
  }

  @Test
  fun allowOrigins_literalRegexWildcardAndPropertyPrecedence_behaveAsConfigured() =
    withGuardProperties(allowOrigins = null, allowRemote = null) {
      testApplication {
        application {
          adkApiModule(
            testConfig(
              allowOrigins =
                listOf("https://console.example.com", "regex:https://[a-z0-9-]+\\.preview\\.dev")
            )
          )
        }

        val literalAllowed =
          client.post("/run") {
            header(HttpHeaders.Host, "console.example.com")
            header(HttpHeaders.Origin, "https://console.example.com")
            jsonBody(RUN_BODY)
          }
        val regexAllowed =
          client.post("/run") {
            header(HttpHeaders.Origin, "https://pr-42.preview.dev")
            jsonBody(RUN_BODY)
          }
        val prefixBypassBlocked =
          client.post("/run") {
            header(HttpHeaders.Origin, "https://pr-42.preview.dev.evil.example")
            jsonBody(RUN_BODY)
          }

        assertThat(literalAllowed.status).isEqualTo(HttpStatusCode.OK)
        assertThat(literalAllowed.headers[HttpHeaders.AccessControlAllowOrigin])
          .isEqualTo("https://console.example.com")
        assertThat(regexAllowed.status).isEqualTo(HttpStatusCode.OK)
        assertThat(regexAllowed.headers[HttpHeaders.AccessControlAllowOrigin])
          .isEqualTo("https://pr-42.preview.dev")
        assertThat(prefixBypassBlocked.status).isEqualTo(HttpStatusCode.Forbidden)
      }

      withGuardProperties(allowOrigins = "https://override.example.com", allowRemote = null) {
        testApplication {
          environment { config = MapApplicationConfig(ALLOW_ORIGINS_PROPERTY to "*") }
          application { adkApiModule(testConfig(allowOrigins = listOf("*"))) }

          assertThat(
              client
                .get("/list-apps") { header(HttpHeaders.Origin, "https://override.example.com") }
                .status
            )
            .isEqualTo(HttpStatusCode.OK)
          assertThat(
              client
                .get("/list-apps") { header(HttpHeaders.Origin, "https://other.example.com") }
                .status
            )
            .isEqualTo(HttpStatusCode.Forbidden)
        }
      }
    }

  @Test
  fun resolveAllowRemoteRequests_honorsEnvVarsAndPropertyOverride() =
    withGuardProperties(allowOrigins = null, allowRemote = null) {
      var fromAgentBuilderEnv = false
      var fromGeneralEnv = false
      var propertyBeatsEnvAndConfig = true

      testApplication {
        application {
          fromAgentBuilderEnv =
            resolveAllowRemoteRequests(
              config = testConfig(),
              default = false,
              envLookup = { if (it == ALLOW_REMOTE_AGENT_BUILDER_ENV) "1" else null },
            )
          fromGeneralEnv =
            resolveAllowRemoteRequests(
              config = testConfig(),
              default = false,
              envLookup = { if (it == ALLOW_REMOTE_REQUESTS_ENV) "yes" else null },
            )
        }
        client.get("/health")
      }
      assertThat(fromAgentBuilderEnv).isTrue()
      assertThat(fromGeneralEnv).isTrue()

      withGuardProperties(allowOrigins = null, allowRemote = "false") {
        testApplication {
          application {
            val wideConfig = testConfig(host = "0.0.0.0", allowRemoteRequests = true)
            propertyBeatsEnvAndConfig =
              resolveAllowRemoteRequests(
                config = wideConfig,
                default = true,
                envLookup = { "true" },
              )
          }
          client.get("/health")
        }
      }
      assertThat(propertyBeatsEnvAndConfig).isFalse()
    }

  @Test
  fun devModule_blocksForwardedRequestsByDefaultInTestApplication() =
    withGuardProperties(allowOrigins = null, allowRemote = null) {
      testApplication {
        application { adkDevModule(testConfig()) }

        assertThat(client.get("/list-apps").status).isEqualTo(HttpStatusCode.OK)
        val proxied =
          client.get("/list-apps") { header(HttpHeaders.XForwardedFor, "198.51.100.7") }
        assertThat(proxied.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(proxied.bodyAsText()).contains("Forbidden: remote requests are disabled")
      }
    }

  @Test
  fun loopbackAndAuthorityHelpers_acceptValidLoopbacksAndRejectSmuggledHosts() {
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
    assertThat(parseOriginHost("https://dev_box.internal:8443")).isEqualTo("dev_box.internal")
    assertThat(parseOriginHost("http://localhost@evil.com")).isNull()
    assertThat(parseOriginHost("null")).isNull()
  }

  private fun testConfig(
    host: String = AdkServerConfig.DEFAULT_HOST,
    allowOrigins: List<String> = emptyList(),
    allowRemoteRequests: Boolean? = null,
  ) =
    AdkServerConfig(
      appLoader = FakeAppLoader(),
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
      host = host,
      allowOrigins = allowOrigins,
      allowRemoteRequests = allowRemoteRequests,
    )

  private fun withGuardProperties(allowOrigins: String?, allowRemote: String?, body: () -> Unit) {
    val prevOrigins = System.getProperty(ALLOW_ORIGINS_PROPERTY)
    val prevRemote = System.getProperty(ALLOW_REMOTE_REQUESTS_PROPERTY)
    setOrClearProperty(ALLOW_ORIGINS_PROPERTY, allowOrigins)
    setOrClearProperty(ALLOW_REMOTE_REQUESTS_PROPERTY, allowRemote)
    try {
      body()
    } finally {
      setOrClearProperty(ALLOW_ORIGINS_PROPERTY, prevOrigins)
      setOrClearProperty(ALLOW_REMOTE_REQUESTS_PROPERTY, prevRemote)
    }
  }

  private fun setOrClearProperty(name: String, value: String?) {
    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
  }

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private fun rawSocketStatus(host: String, port: Int, rawRequest: String): Int =
    Socket(host, port).use { socket ->
      socket.soTimeout = 10_000
      socket.outputStream.apply {
        write(rawRequest.toByteArray())
        flush()
      }
      val statusLine = socket.inputStream.bufferedReader().readLine().orEmpty()
      statusLine.split(" ").getOrNull(1)?.toIntOrNull()
        ?: error("No status code in response: \"$statusLine\"")
    }

  private fun assumeOffLoopbackAddress(): String {
    val address =
      NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
    assumeTrue("No off-loopback address on this host", address != null)
    return address!!
  }

  private class CountingAgent(private val invocations: AtomicInteger) :
    BaseAgent(name = "mock-agent", description = "Counting Agent") {
    override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
      invocations.incrementAndGet()
      emit(
        Event(
          invocationId = context.invocationId,
          author = "mock-agent",
          content = Content(role = "model", parts = listOf(Part(text = "executed"))),
          turnComplete = true,
        )
      )
    }
  }

  private companion object {
    const val RUN_BODY =
      """{"appName":"mock-agent","userId":"u","sessionId":"s",""" +
        """"newMessage":{"role":"user","parts":[{"text":"hi"}]}}"""
  }
}
