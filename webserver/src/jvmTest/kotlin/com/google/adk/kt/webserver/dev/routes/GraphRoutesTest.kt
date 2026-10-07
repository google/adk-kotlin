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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.webserver.dev.routes

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.FakeArtifactService
import com.google.adk.kt.webserver.FakeSessionService
import com.google.adk.kt.webserver.dev.GraphFixtures.assistantAgent
import com.google.adk.kt.webserver.dev.GraphFixtures.supportWorkflow
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** The endpoint the Dev UI reads an app's structure from, served by the development module. */
@RunWith(JUnit4::class)
class GraphRoutesTest {

  @Test
  fun buildGraph_workflowApp_describesItsGraph() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph/support")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val root = response.json()["root_agent"]!!.jsonObject
    assertThat(root["name"]!!.jsonPrimitive.content).isEqualTo("support")
    val graph = root["graph"]!!.jsonObject
    assertThat(graph["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
      .containsExactly(
        "__START__",
        "classify",
        "billing_agent",
        "tech_flow",
        "fallback",
        "summarize",
      )
      .inOrder()
    val firstEdge = graph["edges"]!!.jsonArray.first().jsonObject
    assertThat(firstEdge["from_node"]!!.jsonObject["name"]!!.jsonPrimitive.content)
      .isEqualTo("__START__")
    assertThat(firstEdge["to_node"]!!.jsonObject["name"]!!.jsonPrimitive.content)
      .isEqualTo("classify")
  }

  @Test
  fun buildGraph_camelCaseEnforced_keepsSnakeCaseKeys() = testApplication {
    // Arrange
    serve(camelCaseEnforced = true)

    // Act
    val body = client.get("/dev/build_graph/assistant").bodyAsText()

    // Assert
    assertThat(body).contains("\"root_agent\"")
    assertThat(body).contains("\"sub_agents\"")
    assertThat(body).doesNotContain("rootAgent")
  }

  @Test
  fun buildGraph_unknownApp_returnsNotFound() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph/missing")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun buildGraph_loaderFailure_returnsServerError() = testApplication {
    // Arrange
    serve(
      object : AppLoader {
        override fun listApps() = listOf("broken")

        override fun loadApp(appName: String): App = error("cannot build the app")
      }
    )

    // Act
    val response = client.get("/dev/build_graph/broken")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
  }

  private fun ApplicationTestBuilder.serve(
    appLoader: AppLoader =
      InMemoryAppLoader(
        App(appName = "support", rootNode = supportWorkflow()),
        App(appName = "assistant", rootAgent = assistantAgent()),
      ),
    camelCaseEnforced: Boolean? = null,
  ) {
    application {
      adkDevModule(
        AdkServerConfig(
          sessionService = FakeSessionService(),
          artifactService = FakeArtifactService(),
          apiServerSpanExporter = ApiServerSpanExporter(),
          webUiEnabled = false,
          camelCaseEnforced = camelCaseEnforced,
          appLoader = appLoader,
        )
      )
    }
  }

  private suspend fun HttpResponse.json(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject
}
