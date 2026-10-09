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

package com.google.adk.kt.webserver.dev.routes

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class UnimplementedDevRoutesTest {

  @Test
  fun evalRun_answersNotImplementedWithTheNotInstalledMarker() = testApplication {
    mountUnimplementedDevRoutes()

    val response = client.post("/dev/apps/any-app/eval-sets/set-1/run")

    assertThat(response.status).isEqualTo(HttpStatusCode.NotImplemented)
    // The Dev UI matches this phrase in the detail to show its eval-unavailable notice.
    assertThat(response.bodyAsText()).contains("\"detail\":\"Evaluation is not installed")
  }

  @Test
  fun builder_answersNotImplementedWithADetail() = testApplication {
    mountUnimplementedDevRoutes()

    val response = client.get("/dev/apps/any-app/builder")

    assertThat(response.status).isEqualTo(HttpStatusCode.NotImplemented)
    assertThat(response.bodyAsText()).contains("\"detail\":\"The agent builder is not implemented")
  }

  @Test
  fun deploy_answersNotImplementedWithADetail() = testApplication {
    mountUnimplementedDevRoutes()

    val response = client.post("/dev/apps/any-app/deploy/cloud_run")

    assertThat(response.status).isEqualTo(HttpStatusCode.NotImplemented)
    // The Dev UI's deploy dialog shows this detail as its error.
    assertThat(response.bodyAsText())
      .contains("\"detail\":\"Deploying from the Dev UI is not supported")
  }

  private fun ApplicationTestBuilder.mountUnimplementedDevRoutes() {
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { route("/dev/apps/{appName}") { unimplementedDevRoutes() } }
    }
  }
}
