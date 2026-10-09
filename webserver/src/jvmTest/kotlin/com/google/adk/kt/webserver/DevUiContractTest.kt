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

import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.request.get
import io.ktor.server.application.plugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Keeps the bundled Development UI and the development server's routes in step. Nothing else checks
 * the calls the bundle makes, so a refresh that moves an endpoint would otherwise surface only as a
 * 404 in a browser; refreshing it means updating [UI_ENDPOINTS] here.
 */
@RunWith(JUnit4::class)
class DevUiContractTest {

  @Test
  fun bundleCalls_matchTheEndpointList() {
    val listed = UI_ENDPOINTS.map { "${it.method} ${canonical(it.template)}" }

    assertWithMessage("API calls in the bundled Dev UI versus UI_ENDPOINTS")
      .that(bundleCalls(readMainBundle()))
      .containsExactlyElementsIn(listed)
  }

  @Test
  fun chunks_makeNoApiCalls() {
    val chunks = loadableChunks()
    val callers = chunks.filterValues { source -> API_MARKERS.any { it in source } }.keys

    assertWithMessage("chunk-*.js scripts the UI can load").that(chunks).isNotEmpty()
    assertWithMessage("Chunks that call the API, which bundleCalls does not read")
      .that(callers)
      .isEmpty()
  }

  @Test
  fun supportedEndpoints_allRoute() = testApplication {
    val routes = mountDevModule()

    val unrouted = UI_ENDPOINTS.filter { it.unsupported == null }.filterNot { isRouted(it, routes) }

    assertWithMessage(
        "The bundled Dev UI calls these, but the development server does not route them"
      )
      .that(unrouted.map { "${it.method} ${it.template}" })
      .isEmpty()
  }

  @Test
  fun unsupportedEndpoints_stayUnrouted() = testApplication {
    val routes = mountDevModule()

    val nowRouted = UI_ENDPOINTS.filter { it.unsupported != null }.filter { isRouted(it, routes) }

    assertWithMessage("These now route, so drop their unsupported reason in UI_ENDPOINTS")
      .that(nowRouted.map { "${it.method} ${it.template}" })
      .isEmpty()
  }

  /** Mounts the development module and returns every route with a handler, for [isRouted]. */
  private suspend fun ApplicationTestBuilder.mountDevModule(): List<String> {
    val routes = mutableListOf<String>()
    application {
      adkDevModule(testConfig())
      routes += plugin(Routing).getAllRoutes().map(Route::toString)
    }
    // testApplication builds the Application lazily, so force it.
    client.get("/health")
    return routes
  }

  /**
   * Whether [routes] has a handler for [endpoint]: its path and method, or for a WebSocket its path
   * and upgrade header. A 501 stub counts, since it is a handler.
   */
  private fun isRouted(endpoint: UiEndpoint, routes: List<String>): Boolean =
    if (endpoint.webSocket) {
      routes.any { it.startsWith("${endpoint.template}/") && "(header:Upgrade = websocket)" in it }
    } else {
      "${endpoint.template}/(method:${endpoint.method})" in routes
    }

  private fun testConfig() =
    AdkServerConfig(
      appLoader = FakeAppLoader(),
      sessionService = FakeSessionService(),
      artifactService = FakeArtifactService(),
    )

  /** The main bundle the server serves; its name carries a content hash, so index.html names it. */
  private fun readMainBundle(): String {
    val scripts =
      MAIN_SCRIPT.findAll(readResource("browser/index.html")).map { it.groupValues[1] }.toList()
    assertWithMessage("main-*.js scripts in the bundled index.html").that(scripts).hasSize(1)
    return readResource("browser/${scripts.single()}")
  }

  /**
   * Every chunk the UI can load, by name: those index.html and the main bundle name, transitively.
   */
  private fun loadableChunks(): Map<String, String> {
    val chunks = mutableMapOf<String, String>()
    val pending = ArrayDeque(chunkNames(readResource("browser/index.html") + readMainBundle()))
    while (pending.isNotEmpty()) {
      val name = pending.removeFirst()
      if (name in chunks) continue
      val source = readResource("browser/$name")
      chunks[name] = source
      pending += chunkNames(source)
    }
    return chunks
  }

  private fun chunkNames(source: String): Set<String> =
    CHUNK_SCRIPT.findAll(source).map { it.value }.toSet()

  private fun readResource(path: String): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "$path is not on the test classpath"
      }
      .bufferedReader()
      .use { it.readText() }

  /**
   * Every API call the main bundle makes, as "METHOD /path" with each interpolation reduced to
   * `{}`. The chunks make none, which [chunks_makeNoApiCalls] checks.
   *
   * The minifier renames locals on every build, so this matches only what survives it: the field
   * the base URL is read from and the path appended to it. A site that matches fewer times than its
   * floor fails, so a bundle that builds URLs differently cannot pass by extracting nothing.
   */
  private fun bundleCalls(source: String): List<String> {
    val calls = mutableSetOf<String>()
    for (site in CALL_SITES) {
      val matches = site.pattern.findAll(source).toList()
      assertWithMessage("matches for the %s call site", site.name)
        .that(matches.size)
        .isAtLeast(site.minMatches)
      for (match in matches) {
        val method =
          site.fixedMethod
            ?: checkNotNull(verbOf(source, match)) {
              "No HTTP method found for the ${site.name} call to ${match.groupValues[2]}"
            }
        calls += "$method ${canonical(match.groupValues[2])}"
      }
    }
    return calls.sorted()
  }

  /** The verb of the call whose URL literal [match] captured: just before the literal, or after. */
  private fun verbOf(source: String, match: MatchResult): String? {
    val literalStart = match.groups[1]!!.range.first
    val before =
      VERB_BEFORE.find(source.substring(maxOf(0, literalStart - VERB_WINDOW_BEFORE), literalStart))
    if (before != null) return before.groupValues[1].uppercase()
    val after =
      source.substring(
        match.range.last + 1,
        minOf(source.length, match.range.last + 1 + VERB_WINDOW_AFTER),
      )
    val verb = VERB_AFTER.find(after) ?: return null
    return verb.groupValues[1].ifEmpty { verb.groupValues[2] }.uppercase()
  }

  private fun canonical(template: String): String =
    template.substringBefore('?').replace(INTERPOLATION, "{}").replace(PLACEHOLDER, "{}")

  /**
   * One request the Dev UI makes. [unsupported] says why the server deliberately does not serve it.
   */
  private data class UiEndpoint(
    val method: String,
    val template: String,
    val unsupported: String? = null,
    val webSocket: Boolean = false,
  )

  private data class CallSite(
    val name: String,
    val pattern: Regex,
    val minMatches: Int,
    val fixedMethod: String? = null,
  )

  private companion object {
    /** Every call the bundled Dev UI (adk-web 1.0.7) makes. */
    val UI_ENDPOINTS =
      listOf(
        UiEndpoint("POST", "/run_sse"),
        UiEndpoint("GET", "/list-apps"),
        UiEndpoint("GET", "/version"),
        UiEndpoint("GET", "/apps/{appName}/users/{userId}/sessions"),
        UiEndpoint("POST", "/apps/{appName}/users/{userId}/sessions"),
        UiEndpoint("GET", "/apps/{appName}/users/{userId}/sessions/{sessionId}"),
        UiEndpoint(
          "PATCH",
          "/apps/{appName}/users/{userId}/sessions/{sessionId}",
          unsupported = "renaming a session or editing its state is not implemented",
        ),
        UiEndpoint("DELETE", "/apps/{appName}/users/{userId}/sessions/{sessionId}"),
        UiEndpoint(
          "GET",
          "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}",
        ),
        UiEndpoint(
          "GET",
          "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/versions/{version}",
          unsupported = "artifact versions are not addressable by path",
        ),
        UiEndpoint(
          "GET",
          "/run_live",
          unsupported = "live runs are not served yet",
          webSocket = true,
        ),
        UiEndpoint("GET", "/config/telemetry"),
        UiEndpoint(
          "POST",
          "/config/telemetry",
          unsupported = "telemetry stays off, so there is no consent to store",
        ),
        UiEndpoint(
          "POST",
          "/agent-identity/finalize",
          unsupported = "Agent Identity credentials are not supported",
        ),
        UiEndpoint("GET", "/dev/apps/{appName}/debug/trace/{eventId}"),
        UiEndpoint("GET", "/dev/apps/{appName}/debug/trace/session/{sessionId}"),
        UiEndpoint(
          "GET",
          "/dev/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph",
          unsupported = "the bundle defines this call but never makes it",
        ),
        UiEndpoint("GET", "/dev/apps/{appName}/build_graph"),
        UiEndpoint("GET", "/dev/apps/{appName}/build_graph_image"),
        UiEndpoint("GET", "/dev/apps/{appName}/builder"),
        UiEndpoint("POST", "/dev/apps/{appName}/builder/save"),
        UiEndpoint("POST", "/dev/apps/{appName}/builder/cancel"),
        UiEndpoint("POST", "/dev/apps/{appName}/eval-sets"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval-sets/{evalSetId}"),
        UiEndpoint("DELETE", "/dev/apps/{appName}/eval-sets/{evalSetId}"),
        UiEndpoint("POST", "/dev/apps/{appName}/eval-sets/{evalSetId}/run"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval_sets"),
        UiEndpoint("POST", "/dev/apps/{appName}/eval_sets/{evalSetId}/add_session"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
        UiEndpoint("PUT", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
        UiEndpoint("DELETE", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval_results"),
        UiEndpoint("GET", "/dev/apps/{appName}/eval_results/{evalResultId}"),
        UiEndpoint("GET", "/dev/apps/{appName}/metrics-info"),
        UiEndpoint("GET", "/dev/apps/{appName}/deploy/defaults"),
        UiEndpoint("POST", "/dev/apps/{appName}/deploy/{target}"),
        UiEndpoint("GET", "/dev/apps/{appName}/tests"),
        UiEndpoint("POST", "/dev/apps/{appName}/tests/rebuild"),
        UiEndpoint("POST", "/dev/apps/{appName}/tests/run"),
        UiEndpoint("GET", "/dev/apps/{appName}/tests/{testName}"),
        UiEndpoint("PUT", "/dev/apps/{appName}/tests/{testName}"),
        UiEndpoint("DELETE", "/dev/apps/{appName}/tests/{testName}"),
      )

    val PLACEHOLDER = Regex("""\{(\w+)}""")

    val MAIN_SCRIPT = Regex("""src=\x22(?:\./)?(main-[\w-]+\.js)\x22""")

    val CHUNK_SCRIPT = Regex("""chunk-[\w-]+\.js""")

    /** What the main bundle builds every API URL from; a chunk that names one calls the API too. */
    val API_MARKERS = listOf("apiServerDomain", "getApiServerBaseUrl", "getWSServerUrl")

    val INTERPOLATION = Regex("""[$]\{[^{}]*}""")

    /** A path inside a URL literal: literal URL characters or `${...}` interpolations. */
    const val URL_CHARS = """(?:[\w\-./?=&]|[$]\{[^{}`]*})*"""

    /** Group 1 is the literal's opening quote, group 2 the path after the base URL. */
    val CALL_SITES =
      listOf(
        CallSite(
          "apiServerDomain interpolated",
          Regex("""(`)[$]\{[\w$.]+\.apiServerDomain}($URL_CHARS)`"""),
          minMatches = 5,
        ),
        CallSite(
          "apiServerDomain concatenated with a template",
          Regex("""\.apiServerDomain\s*\+\s*(`)($URL_CHARS)`"""),
          minMatches = 25,
        ),
        CallSite(
          "apiServerDomain concatenated with a string",
          Regex("""\.apiServerDomain\s*\+\s*(\x22)([\w\-./?=&]*)\x22"""),
          minMatches = 3,
        ),
        CallSite(
          "base URL held in a local",
          Regex("""getApiServerBaseUrl\(\)[;,][^`]{0,120}(`)[$]\{\w+}($URL_CHARS)`"""),
          minMatches = 3,
        ),
        CallSite(
          "WebSocket URL",
          Regex("""(`)[^`]{0,40}[$]\{[\w$.]+\.getWSServerUrl\(\)}($URL_CHARS)`"""),
          minMatches = 1,
          fixedMethod = "GET",
        ),
      )

    val VERB_BEFORE = Regex("""\.http\.(get|post|put|patch|delete)\(\s*$""")

    val VERB_AFTER = Regex("""\.http\.(get|post|put|patch|delete)\(|method:\s*\x22([A-Z]+)\x22""")

    const val VERB_WINDOW_BEFORE = 40

    /** Wide enough for the assignment and return between a URL and its call, not the next call. */
    const val VERB_WINDOW_AFTER = 260
  }
}
