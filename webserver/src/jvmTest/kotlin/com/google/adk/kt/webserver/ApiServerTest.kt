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
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.loaders.AppLoader
import com.google.adk.kt.webserver.loaders.InMemoryAppLoader
import com.google.adk.kt.webserver.loaders.SingleAgentLoader
import com.google.adk.kt.webserver.models.RunResponse
import com.google.adk.kt.webserver.routes.MAX_LIVE_FRAME_BYTES
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.DuplicatePluginException
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingInterval
import io.ktor.server.websocket.timeout
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Finds only the sessions whose id is in [existingSessionIds], under any app and user. */
class FakeSessionService(private val existingSessionIds: Set<String> = emptySet()) :
  SessionService {
  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?) =
    Session(SessionKey(key.appName, key.userId, key.id ?: "gen-id"))

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?) =
    if (key.id in existingSessionIds) Session(key) else null

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
  private val sessionService = FakeSessionService(existingSessionIds = setOf("testSession"))
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
  fun runSseRoute_endsEachFrameWithLf() = testApplication {
    // Arrange
    application { adkApiModule(testConfig(appLoader = greeterLoader())) }

    // Act
    val body = client.post("/run_sse") { runBody("greeter") }.bodyAsText()

    // Assert: LF framing, as ADK Python and Java write it; a client may split frames on "\n\n".
    assertThat(body).doesNotContain("\r")
    val frames = body.split("\n\n").filter { it.isNotEmpty() }
    assertThat(frames).isNotEmpty()
    for (frame in frames) {
      assertThat(frame).startsWith("data: {")
    }
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
  fun runRoute_unknownSession_returnsNotFoundWithoutRunningTheAgent() = testApplication {
    // Arrange
    val sessions = InMemorySessionService()
    val model = CountingModel()
    application { adkApiModule(countingModelConfig(sessions, model)) }

    // Act
    val response = client.post("/run") { runBody(COUNTED_APP) }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
    assertThat(response.bodyAsText()).isEqualTo("Session not found")
    assertThat(model.calls.get()).isEqualTo(0)
    assertThat(sessions.getSession(SessionKey(COUNTED_APP, "testUser", "testSession"))).isNull()
  }

  @Test
  fun runSseRoute_unknownSession_returnsNotFoundBeforeStreaming() = testApplication {
    // Arrange
    val sessions = InMemorySessionService()
    val model = CountingModel()
    application { adkApiModule(countingModelConfig(sessions, model)) }

    // Act
    val response = client.post("/run_sse") { runBody(COUNTED_APP) }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
    assertThat(response.bodyAsText()).isEqualTo("Session not found")
    assertThat(model.calls.get()).isEqualTo(0)
    assertThat(sessions.getSession(SessionKey(COUNTED_APP, "testUser", "testSession"))).isNull()
  }

  @Test
  fun runRoute_existingSession_runsTheAgent() = testApplication {
    // Arrange
    val sessions = InMemorySessionService()
    val unused = sessions.createSession(SessionKey(COUNTED_APP, "testUser", "testSession"))
    val model = CountingModel()
    application { adkApiModule(countingModelConfig(sessions, model)) }

    // Act
    val response = client.post("/run") { runBody(COUNTED_APP) }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("counted answer")
    assertThat(model.calls.get()).isEqualTo(1)
  }

  @Test
  fun runRoute_noSessionId_runsTheAgentInANewSession() = testApplication {
    // Arrange
    val sessions = InMemorySessionService()
    val model = CountingModel()
    application { adkApiModule(countingModelConfig(sessions, model)) }

    // Act
    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"$COUNTED_APP\",\"userId\":\"testUser\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hi\"}]}}"
        )
      }

    // Assert
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(model.calls.get()).isEqualTo(1)
    assertThat(sessions.listSessions(COUNTED_APP, "testUser").sessions).hasSize(1)
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
      val requestedKey = SessionKey("greeter", "testUser", "testSession")
      val unused = sessions.createSession(requestedKey)
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
      assertThat(checkNotNull(sessions.getSession(requestedKey)).events).isNotEmpty()
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

  @Test
  fun adkApiModule_appInstallsWebSocketsFirst_stillServesRunLive() = testApplication {
    // An app with its own socket routes installs WebSockets first; the module then uses the app's.
    application {
      install(WebSockets)
      adkApiModule(testConfig())
    }

    // The route runs as far as the session check, so it is served.
    val code = liveClient().use { client -> client.closeCodeOf("mock-agent") }
    assertThat(code).isEqualTo(CloseReason.Codes.PROTOCOL_ERROR.code)
  }

  @Test
  fun adkApiModule_appInstallsWebSocketsAfterIt_getsADuplicatePluginError() = testApplication {
    var lateInstallFailure: Throwable? = null
    application {
      adkApiModule(testConfig())
      lateInstallFailure = runCatching { install(WebSockets) }.exceptionOrNull()
    }

    startApplication()

    assertThat(lateInstallFailure).isInstanceOf(DuplicatePluginException::class.java)
  }

  @Test
  fun adkApiModule_installsWebSocketsWithTheLiveLimits() = testApplication {
    var webSockets: WebSockets? = null
    application {
      adkApiModule(testConfig())
      webSockets = plugin(WebSockets)
    }

    startApplication()

    with(checkNotNull(webSockets)) {
      assertThat(pingInterval).isEqualTo(20.seconds)
      assertThat(timeout).isEqualTo(20.seconds)
      assertThat(maxFrameSize).isEqualTo(MAX_LIVE_FRAME_BYTES)
    }
  }

  @Test
  fun runLive_appFromAnAppLoader_resolvesBeforeTheSessionCheck() = testApplication {
    application { adkApiModule(testConfig()) }
    liveClient().use { client ->
      val servedApp = client.closeCodeOf("mock-agent")
      val unknownApp = client.closeCodeOf("nope")

      // A served app gets as far as the session check; an unknown one stops at the app lookup.
      assertThat(servedApp).isEqualTo(CloseReason.Codes.PROTOCOL_ERROR.code)
      assertThat(unknownApp).isEqualTo(CloseReason.Codes.VIOLATED_POLICY.code)
    }
  }

  @Test
  fun runLive_appPlugins_reachTheLiveEvents() = testApplication {
    // Only the app lists the plugin, so the stamp shows app plugins run on live runs.
    assertThat(firstLiveFrame(appPlugins = listOf(StampingPlugin())))
      .contains("stamped by the plugin")
  }

  @Test
  fun runLive_serverPlugins_reachTheLiveEvents() = testApplication {
    assertThat(firstLiveFrame(serverPlugins = listOf(StampingPlugin())))
      .contains("stamped by the plugin")
  }

  /** Serves a live app answering "hi" with these plugins and returns its first frame. */
  @OptIn(ExperimentalLiveApi::class)
  private suspend fun ApplicationTestBuilder.firstLiveFrame(
    appPlugins: List<Plugin> = emptyList(),
    serverPlugins: List<Plugin> = emptyList(),
  ): String {
    val sessions = InMemorySessionService()
    val key = SessionKey("live-app", "u", "s")
    sessions.createSession(key, null)
    val model =
      DummyLiveModel(listOf(Respond(LlmResponse(content = modelMessage("hi"), partial = false))))
    application {
      adkApiModule(
        AdkServerConfig(
          appLoader =
            InMemoryAppLoader(
              App(
                appName = key.appName,
                rootAgent = LlmAgent(name = "live_agent", model = model),
                plugins = appPlugins,
              )
            ),
          sessionService = sessions,
          artifactService = artifactService,
          apiServerSpanExporter = ApiServerSpanExporter(),
          plugins = serverPlugins,
        )
      )
    }
    var frame = ""
    liveClient().use { client ->
      client.webSocket(
        "/run_live?app_name=${key.appName}&user_id=${key.userId}&session_id=${key.id}"
      ) {
        frame = (withTimeout(LIVE_TIMEOUT) { incoming.receive() } as Frame.Text).readText()
      }
    }
    return frame
  }

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

  /** Serves an agent on [model] as the app [COUNTED_APP], keeping its sessions in [sessions]. */
  private fun countingModelConfig(sessions: SessionService, model: Model) =
    AdkServerConfig(
      appLoader =
        InMemoryAppLoader(
          App(appName = COUNTED_APP, rootAgent = LlmAgent(name = "counted", model = model))
        ),
      sessionService = sessions,
      artifactService = artifactService,
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  /** Answers every request with "counted answer" and counts the requests in [calls]. */
  private class CountingModel : Model {
    override val name = "counting"
    val calls = AtomicInteger()

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
      calls.incrementAndGet()
      return flowOf(LlmResponse(content = modelMessage("counted answer")))
    }
  }

  companion object {
    private const val COUNTED_APP = "counted"
    private val LIVE_TIMEOUT = 10.seconds

    /** The close code `/run_live` sends a client that names [appName] and an unknown session. */
    private suspend fun HttpClient.closeCodeOf(appName: String): Short? {
      var code: Short? = null
      webSocket("/run_live?app_name=$appName&user_id=u&session_id=nope") {
        code = withTimeout(LIVE_TIMEOUT) { closeReason.await() }?.code
      }
      return code
    }
  }
}
