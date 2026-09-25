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
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Routing
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Holds the rule on reading a body to every POST route, not only the three that read one today.
 *
 * A route added later with a plain `receive` compiles and passes its own test while quietly
 * bringing back the defects these rules exist to prevent: the wrong status for an empty body, the
 * wrong status for one that parses but does not fit, and the rejected body quoted in a failure the
 * engine logs. Surveying the route tree is what notices.
 */
@RunWith(JUnit4::class)
class RequestBodyCoverageTest {

  @Test
  fun noPostRoute_answersUnsupportedMediaType_toAnEmptyBody() = testApplication {
    val app = startedApplication()
    val paths = app.postRoutePaths()

    val offenders = paths.filter { path ->
      client.post(path) { jsonBody("") }.status == HttpStatusCode.UnsupportedMediaType
    }

    // A survey that stops reaching the routes it exists for would otherwise pass on an empty list.
    assertThat(paths).containsAtLeastElementsIn(BODY_READING_ROUTES)
    assertThat(offenders).isEmpty()
  }

  @Test
  fun noPostRoute_answersBadRequestOrFails_toABodyThatParses() = testApplication {
    val app = startedApplication()
    val paths = app.postRoutePaths()

    assertThat(paths).containsAtLeastElementsIn(BODY_READING_ROUTES)
    val statusByPath = paths.associateWith { client.post(it) { jsonBody("[1, 2, 3]") }.status }

    // A parseable body that fits no type must reach the decoder and be refused as 422.
    for (route in BODY_READING_ROUTES) {
      assertThat(statusByPath[route]).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    // 400 means it never reached the decoder and 5xx that a serializer threw; both are wrong.
    val offenders =
      statusByPath.filterValues { it == HttpStatusCode.BadRequest || it.value >= 500 }.keys
    assertThat(offenders).isEmpty()
  }

  @Test
  fun noPostRoute_quotesTheBody_inAFailureItRaises() = testApplication {
    val raised = CopyOnWriteArrayList<Throwable>()
    val app = startedApplication { raised.add(it) }
    val paths = app.postRoutePaths()

    for (path in paths) {
      client.post(path) { jsonBody("{\"field\": \"$CANARY") }
    }

    val quoted =
      raised
        .flatMap { failure -> generateSequence(failure) { it.cause }.map { it.message.orEmpty() } }
        .filter { it.contains(CANARY) }
    assertThat(paths).containsAtLeastElementsIn(BODY_READING_ROUTES)
    assertThat(quoted).isEmpty()
  }

  /** Every POST route the server mounts, with a value substituted for each path parameter. */
  private fun Application.postRoutePaths(): List<String> =
    plugin(Routing)
      .getAllRoutes()
      .filter { (it.selector as? HttpMethodRouteSelector)?.method == HttpMethod.Post }
      .map { PATH_PARAMETER.replace(it.parent.toString(), PROBE) }

  /** Returns the application, which `testApplication` builds lazily on the first request. */
  private suspend fun ApplicationTestBuilder.startedApplication(
    onFailure: (Throwable) -> Unit = {}
  ): Application {
    lateinit var started: Application
    application {
      intercept(ApplicationCallPipeline.Setup) {
        try {
          proceed()
        } catch (cause: Throwable) {
          onFailure(cause)
          throw cause
        }
      }
      adkApiModule(
        AdkServerConfig(
          agentLoader = FakeAgentLoader(),
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          apiServerSpanExporter = ApiServerSpanExporter(),
        )
      )
      started = this
    }
    client.get("/health")
    return started
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  private companion object {
    const val PROBE = "probe"
    const val CANARY = "do-not-log-this-value"

    val PATH_PARAMETER = Regex("\\{[^}]*}")

    /** The routes that read a body today, so a survey that stops reaching them fails here. */
    val BODY_READING_ROUTES =
      listOf("/run", "/run_sse", "/apps/$PROBE/users/$PROBE/sessions/$PROBE/artifacts")
  }
}
