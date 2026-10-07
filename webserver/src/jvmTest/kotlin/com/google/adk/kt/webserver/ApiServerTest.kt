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
@file:Suppress("DEPRECATION") // Covers the AgentLoader path kept until 2.0.

package com.google.adk.kt.webserver

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.webserver.loaders.SingleAgentLoader
import com.google.adk.kt.webserver.models.RunResponse
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

class FakeSessionService : SessionService {
  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?) =
    Session(SessionKey(key.appName, key.userId, key.id ?: "gen-id"))

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?) = null

  override suspend fun listSessions(appName: String, userId: String) =
    ListSessionsResponse(emptyList())

  override suspend fun deleteSession(key: SessionKey) {}

  override suspend fun listEvents(key: SessionKey) = ListEventsResponse(emptyList())
}

class FakeArtifactService : ArtifactService {
  override suspend fun saveArtifact(sessionKey: SessionKey, filename: String, artifact: Part) = 0

  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: Part,
  ) = artifact

  override suspend fun loadArtifact(sessionKey: SessionKey, filename: String, version: Int?) = null

  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> = emptyList()

  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {}

  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    emptyList()
}

class FakeAgent(name: String = "mock-agent") : BaseAgent(name = name, description = "Fake Agent") {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    emit(
      Event(
        invocationId = context.invocationId,
        author = "mock-agent",
        content =
          Content(
            role = "model",
            parts = listOf(Part(text = "This is a mocked response from Agent mock-agent")),
          ),
        turnComplete = true,
      )
    )
  }
}

class FakeAgentLoader : AgentLoader {
  override fun listAgents() = listOf("mock-agent")

  override fun loadAgent(agentName: String) = if (agentName == "mock-agent") FakeAgent() else null
}

/** An [AppLoader] serving the agent [newAgent] builds, afresh for each load, as the app [name]. */
class FakeAppLoader(
  private val name: String = "mock-agent",
  private val newAgent: () -> BaseAgent = { FakeAgent() },
) : AppLoader {
  override fun listApps() = listOf(name)

  override fun loadApp(appName: String) =
    if (appName == name) App(appName = name, rootAgent = newAgent()) else null
}

/** A [Plugin] that rewrites every event, so a response body shows whether it was wired in. */
class StampingPlugin : Plugin {
  override val name = "stamping-plugin"

  override suspend fun onEvent(invocationContext: InvocationContext, event: Event) =
    event.copy(
      content = Content(role = "model", parts = listOf(Part(text = "stamped by the plugin")))
    )
}

/**
 * A [Plugin] that records its [tag] in [order] before each run, so a test sees the plugin order.
 */
private class OrderRecordingPlugin(
  private val tag: String,
  private val order: MutableList<String>,
) : Plugin {
  override val name = "order-recording-plugin-$tag"

  override suspend fun beforeRun(
    invocationContext: InvocationContext
  ): CallbackChoice<Unit, Content> {
    order.add(tag)
    return CallbackChoice.Continue(Unit)
  }
}

@RunWith(JUnit4::class)
class ApiServerTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val appLoader = FakeAppLoader()

  @Test
  fun healthCheck_returnsOk() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.get("/health")
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("{\"status\":\"ok\"}")
  }

  @Test
  fun testSerialize_returnsJsonResponse() = testApplication {
    application {
      adkApiModule(testConfig())
      routing {
        get("/api/test-serialize") {
          call.respond(RunResponse(output = "Ok output", sessionId = "test-session"))
        }
      }
    }

    val response = client.get("/api/test-serialize")
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("\"output\":\"Ok output\"")
    assertThat(body).contains("\"sessionId\":\"test-session\"")
  }

  @Test
  fun runRoute_returnsResponse() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"mock-agent\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":false,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello agent\"}]}}"
        )
      }
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    // adkJson has encodeDefaults=false, so default-false partial/interrupted are omitted.
    assertThat(body).contains("\"turnComplete\":true")
    assertThat(body).doesNotContain("\"partial\"")
    assertThat(body).doesNotContain("\"interrupted\"")
  }

  @Test
  fun runRoute_withPlugins_appliesThemToTheRunner() = testApplication {
    application { adkApiModule(testConfig(plugins = listOf(StampingPlugin()))) }

    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"mock-agent\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":false,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello agent\"}]}}"
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("stamped by the plugin")
  }

  @Test
  fun runRoute_workflowRoot_runsTheGraph() = testApplication {
    // Arrange
    application { adkApiModule(testConfig(appLoader = greeterLoader())) }

    // Act
    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"greeter\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}}"
        )
      }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("\"output\":\"hello\"")
    assertThat(body).contains("\"path\":\"greeter@1/greet@1\"")
  }

  @Test
  fun runRoute_workflowRoot_appliesServerPlugins() = testApplication {
    // Arrange
    application {
      adkApiModule(testConfig(plugins = listOf(StampingPlugin()), appLoader = greeterLoader()))
    }

    // Act
    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"greeter\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}}"
        )
      }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("stamped by the plugin")
  }

  @Test
  fun runSseRoute_workflowRoot_streamsGraphEvents() = testApplication {
    // Arrange
    application { adkApiModule(testConfig(appLoader = greeterLoader())) }

    // Act
    val response =
      client.post("/run_sse") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"greeter\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":true,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}}"
        )
      }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("data: ")
    assertThat(body).contains("\"path\":\"greeter@1/greet@1\"")
  }

  @Test
  fun runRoute_unknownApp_returnsNotFound() = testApplication {
    // Arrange
    application { adkApiModule(testConfig(appLoader = greeterLoader())) }

    // Act
    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody("{\"appName\":\"missing\",\"userId\":\"testUser\",\"sessionId\":\"testSession\"}")
      }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
  }

  @Test
  fun runRoute_resumableApp_emitsTheWorkflowCheckpoints() = testApplication {
    // Arrange
    application {
      adkApiModule(
        testConfig(
          appLoader =
            InMemoryAppLoader(
              App(
                appName = "resumable",
                rootNode = greetingWorkflow(),
                resumabilityConfig = ResumabilityConfig(isResumable = true),
              ),
              App(appName = "plain", rootNode = greetingWorkflow()),
            )
        )
      )
    }

    // Act
    val resumable = client.post("/run") { runBody("resumable") }.bodyAsText()
    val plain = client.post("/run") { runBody("plain") }.bodyAsText()

    // Assert
    assertThat(resumable).contains("\"agentState\"")
    assertThat(resumable).contains("\"nodes\"")
    assertThat(resumable).contains("\"output\":\"hello\"")
    assertThat(plain).doesNotContain("\"agentState\"")
    assertThat(plain).contains("\"output\":\"hello\"")
  }

  @Test
  fun runRoute_appWithPlugins_appliesTheAppsPlugins() = testApplication {
    // Arrange
    application {
      adkApiModule(
        testConfig(
          appLoader =
            InMemoryAppLoader(
              App(
                appName = "greeter",
                rootNode = greetingWorkflow(),
                plugins = listOf(StampingPlugin()),
              )
            )
        )
      )
    }

    // Act
    val response = client.post("/run") { runBody("greeter") }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("stamped by the plugin")
  }

  @Test
  fun runRoute_appAndServerPlugins_runsTheAppsFirst() = testApplication {
    // Arrange
    val order = mutableListOf<String>()
    application {
      adkApiModule(
        testConfig(
          plugins = listOf(OrderRecordingPlugin("server", order)),
          appLoader =
            InMemoryAppLoader(
              App(
                appName = "greeter",
                rootNode = greetingWorkflow(),
                plugins = listOf(OrderRecordingPlugin("app", order)),
              )
            ),
        )
      )
    }

    // Act
    val response = client.post("/run") { runBody("greeter") }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(order).containsExactly("app", "server").inOrder()
  }

  @Test
  fun listAppsRoute_appLoader_returnsItsAppNames() = testApplication {
    // Arrange
    application {
      adkApiModule(
        testConfig(
          appLoader =
            InMemoryAppLoader(
              App(appName = "zeta", rootNode = greetingWorkflow()),
              App(appName = "alpha", rootNode = greetingWorkflow()),
            )
        )
      )
    }

    // Act
    val response = client.get("/list-apps")

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("[\"alpha\",\"zeta\"]")
  }

  @Test
  fun runSseRoute_resumableApp_streamsTheWorkflowCheckpoints() = testApplication {
    // Arrange
    application {
      adkApiModule(
        testConfig(
          appLoader =
            InMemoryAppLoader(
              App(
                appName = "resumable",
                rootNode = greetingWorkflow(),
                resumabilityConfig = ResumabilityConfig(isResumable = true),
              )
            )
        )
      )
    }

    // Act
    val response = client.post("/run_sse") { runBody("resumable") }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("data: ")
    assertThat(body).contains("\"agentState\"")
    assertThat(body).contains("\"output\":\"hello\"")
  }

  @Test
  fun runRoute_appNamedDifferentlyFromRequest_keepsTheSessionUnderTheRequestedName() =
    testApplication {
      // Arrange
      val sessions = InMemorySessionService()
      val loader =
        object : AppLoader {
          override fun listApps() = listOf("greeter")

          override fun loadApp(appName: String) =
            App(appName = "internal_name", rootNode = greetingWorkflow()).takeIf {
              appName == "greeter"
            }
        }
      application {
        adkApiModule(
          AdkServerConfig(
            appLoader = loader,
            sessionService = sessions,
            artifactService = artifactService,
            apiServerSpanExporter = ApiServerSpanExporter(),
          )
        )
      }

      // Act
      val response = client.post("/run") { runBody("greeter") }

      // Assert
      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(sessions.getSession(SessionKey("greeter", "testUser", "testSession"))).isNotNull()
      assertThat(sessions.getSession(SessionKey("internal_name", "testUser", "testSession")))
        .isNull()
    }

  @Test
  fun runSseRoute_returnsStream() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/run_sse") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"mock-agent\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":true,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello agent\"}]}}"
        )
      }
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.headers["Content-Type"]).contains("text/event-stream")
    // The SSE stream serializes events with adkJson too, so the shape matches /run.
    val body = response.bodyAsText()
    assertThat(body).contains("data: ")
    assertThat(body).contains("\"turnComplete\":true")
    assertThat(body).doesNotContain("\"partial\"")
    assertThat(body).doesNotContain("\"interrupted\"")
  }

  @Test
  fun runRoute_deprecatedAgentLoaderWithDottedAgentName_runsTheAgent() = testApplication {
    // Arrange
    application {
      adkApiModule(testConfig(agentLoader = SingleAgentLoader(FakeAgent("support.bot"))))
    }

    // Act
    val response = client.post("/run") { runBody("support.bot") }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("mocked response from Agent mock-agent")
  }

  private fun HttpRequestBuilder.runBody(appName: String) {
    contentType(ContentType.Application.Json)
    setBody(
      "{\"appName\":\"$appName\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}}"
    )
  }

  /** Serves [greetingWorkflow] as the app `greeter`. */
  private fun greeterLoader() =
    InMemoryAppLoader(App(appName = "greeter", rootNode = greetingWorkflow()))

  /** A workflow whose only node outputs "hello". */
  private fun greetingWorkflow() =
    Workflow(
      name = "greeter",
      edges =
        listOf(
          Edge(
            Start,
            object : Node {
              override val name = "greet"

              override fun runNode(context: Context, nodeInput: Any?) = flowOf("hello")
            },
          )
        ),
    )

  private fun testConfig(
    plugins: List<Plugin> = emptyList(),
    appLoader: AppLoader = this.appLoader,
    agentLoader: AgentLoader? = null,
  ) =
    AdkServerConfig(
      agentLoader = agentLoader,
      sessionService = sessionService,
      artifactService = artifactService,
      apiServerSpanExporter = ApiServerSpanExporter(),
      plugins = plugins,
      appLoader = appLoader.takeIf { agentLoader == null },
    )
}
