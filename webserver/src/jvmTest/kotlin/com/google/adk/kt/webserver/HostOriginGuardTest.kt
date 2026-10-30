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

import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HostOriginGuardTest {

  @Test
  fun hostOriginRefusal_hostCheckOffOrLoopbackHost_isServed() {
    val foreign = hostOnly("evil.example:8000")
    // `*`, an unknown bind or a non-loopback bind leaves the Host check off.
    assertThat(refusalFor(foreign, allowedHosts = setOf("*"))).isNull()
    assertThat(refusalFor(foreign, bindHost = null)).isNull()
    assertThat(refusalFor(foreign, bindHost = "0.0.0.0")).isNull()
    // Browsers always send Host, so a request without one is not a rebinding vector.
    assertThat(refusalFor(headers())).isNull()
    // Any loopback name or address is this machine, on any port, and a listed host is admitted.
    assertThat(refusalFor(hostOnly("127.0.0.2:8000"))).isNull()
    assertThat(refusalFor(hostOnly("[::1]:8000"))).isNull()
    assertThat(refusalFor(hostOnly("LOCALHOST."))).isNull()
    assertThat(refusalFor(foreign, allowedHosts = setOf("evil.example"))).isNull()
  }

  @Test
  fun hostOriginRefusal_foreignOrSmuggledHostOnLoopbackBind_isRefused() {
    // Host is a singleton header: several of them, or a list in one, is smuggling.
    assertThat(refusalFor(headers("Host" to "localhost", "Host" to "localhost")))
      .isEqualTo(HOST_NOT_ALLOWED)
    assertThat(refusalFor(hostOnly("localhost, evil.example"))).isEqualTo(HOST_NOT_ALLOWED)
    // An unlisted host is foreign, even when a loopback name is listed.
    assertThat(refusalFor(hostOnly("evil.example:8000"))).isEqualTo(HOST_NOT_ALLOWED)
    assertThat(refusalFor(hostOnly("evil.example"), allowedHosts = setOf("localhost")))
      .isEqualTo(HOST_NOT_ALLOWED)
    // A malformed port keeps the value whole, and a dot-led IPv6-looking host is not loopback.
    assertThat(refusalFor(hostOnly("127.0.0.1:1.evil.example"))).isEqualTo(HOST_NOT_ALLOWED)
    assertThat(refusalFor(hostOnly(".::1"))).isEqualTo(HOST_NOT_ALLOWED)
  }

  @Test
  fun hostOriginRefusal_listedOrSameOriginPage_isServed() {
    // A listed host passes, and listing a loopback name admits a local page on another port.
    assertThat(refusalFor(page("https://app.example"), allowedHosts = setOf("app.example")))
      .isNull()
    assertThat(refusalFor(page("http://localhost:3000"), allowedHosts = setOf("localhost")))
      .isNull()
    // Otherwise the request's own origin passes, on loopback and off it.
    assertThat(refusalFor(page("http://localhost:8000"))).isNull()
    assertThat(refusalFor(page("http://[::1]:8000", host = "[::1]:8000"))).isNull()
    val lan = page("http://lan.example:8000", host = "lan.example:8000")
    assertThat(refusalFor(lan, bindHost = "0.0.0.0")).isNull()
    // An unknown bind falls back to the address the request arrived on.
    val foreign = page("http://evil.example:8000", host = "evil.example:8000")
    assertThat(refusalFor(foreign, bindHost = null, socketHost = "10.0.0.5")).isNull()
    // No Origin is a non-browser client, which this check does not judge.
    assertThat(refusalFor(hostOnly("localhost:8000"))).isNull()
  }

  @Test
  fun hostOriginRefusal_pageFromAnotherOrigin_isRefused() {
    assertThat(refusalFor(page("https://evil.example"), allowedHosts = setOf("app.example")))
      .isEqualTo(ORIGIN_NOT_ALLOWED)
    // A loopback page on another port is not the request's own origin.
    assertThat(refusalFor(page("http://localhost:4200"))).isEqualTo(ORIGIN_NOT_ALLOWED)
    // Nothing listed on loopback: the page must be loopback too, whatever the forwarded host says.
    val rebound =
      headers(
        "Host" to "localhost:8000",
        "Origin" to "http://evil.example:8000",
        "X-Forwarded-Host" to "evil.example:8000",
      )
    assertThat(refusalFor(rebound)).isEqualTo(ORIGIN_NOT_ALLOWED)
    val crossLan = page("https://evil.example", host = "lan.example:8000")
    assertThat(refusalFor(crossLan, bindHost = "0.0.0.0")).isEqualTo(ORIGIN_NOT_ALLOWED)
    val foreign = page("http://evil.example:8000", host = "evil.example:8000")
    assertThat(refusalFor(foreign, bindHost = null, socketHost = "127.0.0.1"))
      .isEqualTo(ORIGIN_NOT_ALLOWED)
    // An opaque "null" origin has no host.
    assertThat(refusalFor(page("null"))).isEqualTo(ORIGIN_NOT_ALLOWED)
    // A URL parser that kept only 127.0.0.1 or app.example as the host would admit these.
    assertThat(
        refusalFor(page("http://127.0.0.1:1.evil.example"), allowedHosts = setOf("127.0.0.1"))
      )
      .isEqualTo(ORIGIN_NOT_ALLOWED)
    assertThat(
        refusalFor(page("https://[app.example]evil.example"), allowedHosts = setOf("app.example"))
      )
      .isEqualTo(ORIGIN_NOT_ALLOWED)
  }

  @Test
  fun requestOriginOf_prefersForwardedThenXForwardedHeadersThenHost() {
    assertThat(requestOriginOf(hostOnly("localhost:8000"), "http"))
      .isEqualTo("http://localhost:8000")
    // A websocket scheme becomes the one its page's Origin uses.
    assertThat(requestOriginOf(hostOnly("localhost:8000"), "wss"))
      .isEqualTo("https://localhost:8000")
    val proxied =
      headers("Host" to "h", "X-Forwarded-Host" to "app.example, p", "X-Forwarded-Proto" to "https")
    assertThat(requestOriginOf(proxied, "http")).isEqualTo("https://app.example")
    val forwarded =
      headers("Host" to "h", "Forwarded" to "for=1.2.3.4;proto=https;host=\"a.example\"")
    assertThat(requestOriginOf(forwarded, "http")).isEqualTo("https://a.example")
    // Forwarded counts only when it names both proto and host.
    assertThat(requestOriginOf(headers("Host" to "h", "Forwarded" to "host=a.example"), "http"))
      .isEqualTo("http://h")
    assertThat(requestOriginOf(headers(), "http")).isNull()
  }

  @Test
  fun foreignPage_isRefusedBeforeTheRouteRuns() = testApplication {
    val sessions = InMemorySessionService()
    application { adkApiModule(testConfig(sessions)) }

    val refused = client.post("/apps/mock-agent/users/u/sessions") { from("https://evil.example") }
    val countAfterRefusal = sessionCount(sessions)
    // The control: the same request without a foreign page creates the session.
    val served = client.post("/apps/mock-agent/users/u/sessions") { hostHeader() }

    assertThat(refused.status).isEqualTo(HttpStatusCode.Forbidden)
    assertThat(refused.bodyAsText()).isEqualTo("Forbidden: origin not allowed")
    assertThat(countAfterRefusal).isEqualTo(0)
    assertThat(served.status).isEqualTo(HttpStatusCode.OK)
    assertThat(sessionCount(sessions)).isEqualTo(1)
  }

  @Test
  fun loopbackBind_foreignHost_isRefusedOnEveryRoute() = testApplication {
    application { onLoopback { adkApiModule(testConfig().copy(webUiEnabled = true)) } }

    val gets = listOf("/health", "/list-apps", "/apps/mock-agent/users/u/sessions", "/dev-ui/")
    val statuses =
      gets.associateWith { client.get(it) { hostHeader("evil.example") }.status } +
        listOf("/run", "/run_sse").associateWith {
          client.post(it) { hostHeader("evil.example") }.status
        }

    assertThat(statuses.filterValues { it != HttpStatusCode.Forbidden }).isEmpty()
  }

  @Test
  fun loopbackBind_sameOriginPage_isServed() = testApplication {
    application { onLoopback { adkApiModule(testConfig()) } }

    // The Development UI calls the server from the server's own origin.
    val response = client.get("/list-apps") { from("http://localhost:8000") }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun embeddedModule_foreignHost_isServed() = testApplication {
    // The embedding engine owns the bind, which this module cannot see, so Host goes unchecked.
    application { adkApiModule(testConfig()) }

    val response = client.get("/list-apps") { hostHeader("lan.example:8000") }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun embeddingAppsOwnRoutes_stayUnguarded() = testApplication {
    application {
      onLoopback { adkApiModule(testConfig()) }
      routing { get("/mine") { call.respondText("mine") } }
    }

    val own = client.get("/mine") { hostHeader("evil.example") }

    assertThat(own.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun allowedHosts_fromTheConfig_matchInAnyCaseAndOnAnyPort() = testApplication {
    val config = testConfig().copy(allowedHosts = setOf("App.Example:8443"))
    application { onLoopback { adkApiModule(config) } }

    val listed = client.get("/list-apps") { from("https://app.example", host = "app.example") }

    assertThat(listed.status).isEqualTo(HttpStatusCode.OK)
  }

  @Test
  fun devModule_loopbackBind_guardsTheDevelopmentRoutes() = testApplication {
    val config = testConfig().copy(allowedHosts = setOf("app.example"))
    application { onLoopback { adkDevModule(config) } }

    val refused = client.get("/apps/mock-agent/eval_sets") { hostHeader("evil.example") }
    val listed = client.get("/apps/mock-agent/eval_sets") { hostHeader("app.example") }

    assertThat(refused.status).isEqualTo(HttpStatusCode.Forbidden)
    assertThat(refused.bodyAsText()).isEqualTo("Forbidden: host not allowed")
    assertThat(listed.status).isNotEqualTo(HttpStatusCode.Forbidden)
  }

  /** Runs [install] after recording a loopback bind, as [AdkApiServer] does for its engine. */
  private fun Application.onLoopback(install: Application.() -> Unit) {
    attributes.put(BIND_HOST_KEY, "127.0.0.1")
    install()
  }

  /** Names [host], as every real HTTP/1.1 client does; `testApplication` sends no `Host` itself. */
  private fun HttpRequestBuilder.hostHeader(host: String = "localhost:8000") {
    header(HttpHeaders.Host, host)
  }

  /** Sends this request as a browser page at [origin] would, to [host]. */
  private fun HttpRequestBuilder.from(origin: String, host: String = "localhost:8000") {
    hostHeader(host)
    header(HttpHeaders.Origin, origin)
  }

  private suspend fun sessionCount(sessions: InMemorySessionService): Int =
    sessions.listSessions("mock-agent", "u").sessions.size

  private fun testConfig(sessions: InMemorySessionService = InMemorySessionService()) =
    AdkServerConfig(
      appLoader = FakeAppLoader(),
      sessionService = sessions,
      artifactService = FakeArtifactService(),
    )

  private fun headers(vararg pairs: Pair<String, String>): Headers = Headers.build {
    for ((name, value) in pairs) append(name, value)
  }

  private fun hostOnly(host: String): Headers = headers("Host" to host)

  private fun page(origin: String, host: String = "localhost:8000"): Headers =
    headers("Host" to host, "Origin" to origin)

  /** [hostOriginRefusal] for a request carrying [headers], on a loopback bind unless overridden. */
  private fun refusalFor(
    headers: Headers,
    bindHost: String? = "127.0.0.1",
    socketHost: String? = null,
    allowedHosts: Set<String> = emptySet(),
  ): String? =
    hostOriginRefusal(
      headers,
      scheme = "http",
      bindHost = bindHost,
      socketHost = socketHost,
      allowedHosts = allowedHosts,
    )
}
