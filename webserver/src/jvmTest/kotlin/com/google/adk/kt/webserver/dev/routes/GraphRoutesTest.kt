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
import com.google.adk.kt.webserver.dev.GraphFixtures.step
import com.google.adk.kt.webserver.dev.GraphFixtures.supportWorkflow
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
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

/** The two endpoints the Dev UI draws an app's graphs from, served by the development module. */
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

  @Test
  fun buildGraphImage_workflowApp_drawsTheRootAndEveryNestedWorkflow() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph_image/support?dark_mode=false")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val graphs = response.json()
    assertThat(graphs.keys).containsExactly("", "support/tech_flow").inOrder()
    val root = graphs.dotSrc("")
    assertThat(root).contains("__START__ -> classify")
    assertThat(root).contains("classify -> tech_flow [label=\"  tech\"]")
    assertThat(root).contains("bgcolor=\"#F8FAFC\"")
    assertThat(graphs.dotSrc("support/tech_flow")).contains("diagnose -> fix")
  }

  @Test
  fun buildGraphImage_nestedWorkflows_prefixesNonRootKeysWithTheRootName() = testApplication {
    // Arrange
    val inner = Workflow(name = "inner", edges = listOf(Edge(Start, step("leaf"))))
    val middle = Workflow(name = "middle", edges = listOf(Edge(Start, inner)))
    val outer = Workflow(name = "outer", edges = listOf(Edge(Start, middle)))
    serve(appLoader = InMemoryAppLoader(App(appName = "outer", rootNode = outer)))

    // Act
    val graphs = client.get("/dev/build_graph_image/outer").json()

    // Assert
    assertThat(graphs.keys).containsExactly("", "outer/middle", "outer/middle/inner").inOrder()
    assertThat(graphs.dotSrc("outer/middle/inner")).contains("__START__ -> leaf")
  }

  @Test
  fun buildGraphImage_darkModeValues_selectDarkOrLightBackground() = testApplication {
    // Arrange
    serve()

    // Act
    val dark = client.get("/dev/build_graph_image/support?dark_mode=true").json()
    val darkYes = client.get("/dev/build_graph_image/support?dark_mode=YES").json()
    val omitted = client.get("/dev/build_graph_image/support").json()
    val lightZero = client.get("/dev/build_graph_image/support?dark_mode=0").json()

    // Assert
    assertThat(dark.dotSrc("")).contains("bgcolor=\"#0F172A\"")
    assertThat(darkYes.dotSrc("")).contains("bgcolor=\"#0F172A\"")
    assertThat(omitted.dotSrc("")).contains("bgcolor=\"#F8FAFC\"")
    assertThat(lightZero.dotSrc("")).contains("bgcolor=\"#F8FAFC\"")
  }

  @Test
  fun buildGraphImage_node_returnsBareDotGraphForTarget() = testApplication {
    // Arrange
    serve()

    // Act
    val nested = client.get("/dev/build_graph_image/support?node=tech_flow").json()
    val agent = client.get("/dev/build_graph_image/support?node=support/billing_agent").json()

    // Assert
    assertThat(nested.keys).containsExactly("dotSrc")
    assertThat(nested["dotSrc"]!!.jsonPrimitive.content).contains("diagnose -> fix")
    assertThat(agent.keys).containsExactly("dotSrc")
    val agentDot = agent["dotSrc"]!!.jsonPrimitive.content
    assertThat(agentDot).contains("billing_agent -> lookup_invoice")
    assertThat(agentDot).doesNotContain("__START__")
  }

  @Test
  fun buildGraphImage_emptyNode_returnsThePathKeyedMap() = testApplication {
    // Arrange
    serve()

    // Act
    val graphs = client.get("/dev/build_graph_image/support?node=").json()

    // Assert
    assertThat(graphs.keys).containsExactly("", "support/tech_flow").inOrder()
  }

  @Test
  fun buildGraphImage_nodeNamingTheRoot_returnsTheRootGraph() = testApplication {
    // Arrange
    serve()

    // Act
    val root = client.get("/dev/build_graph_image/support?node=support").json()

    // Assert
    assertThat(root.keys).containsExactly("dotSrc")
    assertThat(root["dotSrc"]!!.jsonPrimitive.content).contains("__START__ -> classify")
  }

  @Test
  fun buildGraphImage_agentApp_drawsItsTree() = testApplication {
    // Arrange
    serve()

    // Act
    val graphs = client.get("/dev/build_graph_image/assistant").json()

    // Assert
    assertThat(graphs.keys).containsExactly("")
    assertThat(graphs.dotSrc("")).contains("assistant -> helper")
    assertThat(graphs.dotSrc("")).doesNotContain("__END__")
  }

  @Test
  fun buildGraphImage_unknownNode_returnsNotFound() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph_image/support?node=tech_flow/missing")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun buildGraphImage_unknownApp_returnsNotFound() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph_image/missing")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun buildGraphImage_invalidDarkMode_returnsBadRequest() = testApplication {
    // Arrange
    serve()

    // Act
    val response = client.get("/dev/build_graph_image/support?dark_mode=maybe")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
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

  private fun JsonObject.dotSrc(path: String): String =
    this[path]!!.jsonObject["dotSrc"]!!.jsonPrimitive.content
}
