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
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.plugins.PluginManager
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.RunResponse
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
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

class FakeAgent : BaseAgent(name = "mock-agent", description = "Fake Agent") {
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

class FakeRunner : Runner {
  override val appName = "mock-agent"
  override val agent = FakeAgent()
  override val sessionService = FakeSessionService()
  override val artifactService = FakeArtifactService()
  override val memoryService: MemoryService? = null
  override val pluginManager = PluginManager()
  override val resumabilityConfig = ResumabilityConfig()

  override fun runAsync(
    userId: String,
    sessionId: String,
    invocationId: String?,
    newMessage: Content?,
    stateDelta: Map<String, Any>?,
    runConfig: RunConfig?,
  ): Flow<Event> = flow {
    emit(
      Event(
        invocationId = invocationId ?: "test-invocation",
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

  override fun run(
    userId: String,
    sessionId: String,
    newMessage: Content,
    runConfig: RunConfig?,
  ): Iterator<Event> = emptyList<Event>().iterator()
}

@RunWith(JUnit4::class)
class AdkWebServerTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = FakeAgentLoader()

  @Test
  fun healthCheck_returnsOk() = testApplication {
    application { adkModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter()) }

    val response = client.get("/api/health")
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).isEqualTo("OK")
  }

  @Test
  fun testSerialize_returnsJsonResponse() = testApplication {
    application {
      adkModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter())
      routing {
        get("/api/test-serialize") {
          call.respond(RunResponse(output = "Ok output", sessionId = "test-session"))
        }
      }
    }

    val response = client.get("/api/test-serialize")
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    assertThat(body).contains("\"output\": \"Ok output\"")
    assertThat(body).contains("\"sessionId\": \"test-session\"")
  }

  @Test
  fun runRoute_returnsResponse() = testApplication {
    application { adkModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter()) }

    val response =
      client.post("/run") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"mock-agent\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":false,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello agent\"}]}}"
        )
      }
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val body = response.bodyAsText()
    println("RESPONSE BODY: $body")
    assertThat(body).isNotEmpty()
  }

  @Test
  fun runSseRoute_returnsStream() = testApplication {
    application { adkModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter()) }

    val response =
      client.post("/run_sse") {
        contentType(ContentType.Application.Json)
        setBody(
          "{\"appName\":\"mock-agent\",\"userId\":\"testUser\",\"sessionId\":\"testSession\",\"streaming\":true,\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello agent\"}]}}"
        )
      }
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.headers["Content-Type"]).contains("text/event-stream")
  }
}
