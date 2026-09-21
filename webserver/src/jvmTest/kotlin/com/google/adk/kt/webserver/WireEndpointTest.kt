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
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.SessionDto
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the wire rules the ADK agent runtime puts on every endpoint, against real responses: read
 * `snake_case` or `camelCase`, ignore unrecognized keys, and emit `camelCase` without null fields.
 */
@RunWith(JUnit4::class)
class WireEndpointTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = EchoAgentLoader()

  private val snakeCaseRun =
    """
    {
      "app_name": "echo-agent",
      "user_id": "testUser",
      "session_id": "testSession",
      "invocation_id": "testInvocation",
      "state_delta": {"probe": "probe_value"},
      "new_message": {"role": "user", "parts": [{"text": "Hello agent"}]}
    }
    """
      .trimIndent()

  private val camelCaseRun =
    """
    {
      "appName": "echo-agent",
      "userId": "testUser",
      "sessionId": "testSession",
      "invocationId": "testInvocation",
      "stateDelta": {"probe": "probe_value"},
      "newMessage": {"role": "user", "parts": [{"text": "Hello agent"}]}
    }
    """
      .trimIndent()

  @Test
  fun run_snakeCaseBody_matchesCamelCaseBody() = testApplication {
    application { adkApiModule(testConfig()) }

    val snakeCase = client.post("/run") { jsonBody(snakeCaseRun) }
    val camelCase = client.post("/run") { jsonBody(camelCaseRun) }

    assertThat(snakeCase.status).isEqualTo(HttpStatusCode.OK)
    // The agent echoes what the runner decoded, so a dropped alias on sessionId, stateDelta or
    // invocationId shows up here rather than passing as an ignored key.
    assertThat(snakeCase.bodyAsText()).contains("message=Hello agent")
    assertThat(snakeCase.bodyAsText()).contains("state=probe_value")
    assertThat(snakeCase.bodyAsText()).contains("session=testSession")
    assertThat(snakeCase.bodyAsText()).contains("invocation=testInvocation")
    assertThat(withoutVolatileFields(snakeCase.bodyAsText()))
      .isEqualTo(withoutVolatileFields(camelCase.bodyAsText()))
  }

  @Test
  fun runSse_snakeCaseBody_streamsEvents() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/run_sse") {
        jsonBody(snakeCaseRun.replace("\"session_id\"", "\"streaming\": true, \"session_id\""))
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.headers["Content-Type"]).contains("text/event-stream")
    assertThat(response.bodyAsText()).contains("message=Hello agent")
  }

  @Test
  fun run_unrecognizedKey_isIgnored() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/run") {
        jsonBody(
          snakeCaseRun.replace(
            "\"user_id\"",
            "\"a_field_from_a_newer_client\": {\"x\": 1}, \"user_id\"",
          )
        )
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(response.bodyAsText()).contains("message=Hello agent")
  }

  @Test
  fun artifacts_snakeCasePartBody_isRead() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/apps/a/users/u/sessions/s/artifacts") {
        jsonBody("""{"inline_data":{"mime_type":"image/png","display_name":"chart.png"}}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    // Echoed back by the fake service, so a camelCase body proves the snake_case one was read.
    assertThat(response.bodyAsText()).contains("\"mimeType\":\"image/png\"")
  }

  @Test
  fun run_malformedBody_isRejected() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.post("/run") { jsonBody("{\"app_name\": ") }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }

  @Test
  fun version_byDefault_emitsTheNonEnforcedSpellingOnly() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.get("/version")
    val body = response.bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    // Exactly one spelling: a strict decoder rejects a key it does not model, so emitting both
    // would break any client that models only one of them.
    assertThat((Json.parseToJsonElement(body) as JsonObject).keys)
      .containsExactly("version", "language", LEGACY_VERSION_KEY)
    assertEmissionRule(body, VersionInfo.serializer().descriptor, "VersionInfo", minKeys = 3)
  }

  @Test
  fun version_whenCamelCaseEnforced_emitsTheEnforcedSpellingOnly() = testApplication {
    application { adkApiModule(testConfig().copy(camelCaseEnforced = true)) }

    val response = client.get("/version")
    val body = response.bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat((Json.parseToJsonElement(body) as JsonObject).keys)
      .containsExactly("version", "language", "languageVersion")
    assertEmissionRule(body, VersionInfo.serializer().descriptor, "VersionInfo", minKeys = 3)
  }

  @Test
  fun version_theProperty_overridesAnExplicitSetting() = testApplication {
    // Deliberately beats the config, as it does for the Development UI: when the default moves, a
    // deployment whose code pins the wrong value needs a lever that needs no rebuild.
    withProperty(CAMEL_CASE_ENFORCED_PROPERTY, "true") {
      application { adkApiModule(testConfig().copy(camelCaseEnforced = false)) }

      val body = client.get("/version").bodyAsText()

      assertThat((Json.parseToJsonElement(body) as JsonObject).keys).contains("languageVersion")
      assertThat((Json.parseToJsonElement(body) as JsonObject).keys)
        .doesNotContain(LEGACY_VERSION_KEY)
    }
  }

  @Test
  fun health_emitsOk() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.get("/health")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat((Json.parseToJsonElement(response.bodyAsText()) as JsonObject).keys)
      .containsExactly("status")
  }

  @Test
  fun listApps_emitsAStringArray() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.get("/list-apps")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(Json.parseToJsonElement(response.bodyAsText())).isInstanceOf(JsonArray::class.java)
  }

  @Test
  fun createSession_emitsCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.post("/apps/echo-agent/users/u/sessions")

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertEmissionRule(
      response.bodyAsText(),
      SessionDto.serializer().descriptor,
      "SessionDto",
      minKeys = 4,
    )
  }

  @Test
  fun uploadArtifact_emitsCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    val response =
      client.post("/apps/a/users/u/sessions/s/artifacts") {
        jsonBody("""{"inlineData":{"mimeType":"image/png","displayName":"chart.png"}}""")
      }

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertEmissionRule(response.bodyAsText(), Part.serializer().descriptor, "Part", minKeys = 3)
  }

  @Test
  fun run_emitsCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.post("/run") { jsonBody(camelCaseRun) }
    val body = response.bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(body).contains("\"turnComplete\":true")
    assertThat(body).contains("\"invocationId\"")
    assertEmissionRule(body, EVENT_LIST_DESCRIPTOR, "Event", minKeys = 8)
  }

  @Test
  fun runSse_framesEmitCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    // The stream is encoded outside content negotiation, so it needs its own assertion.
    val response =
      client.post("/run_sse") {
        jsonBody(camelCaseRun.replace("\"sessionId\"", "\"streaming\": true, \"sessionId\""))
      }
    val body = response.bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val frames =
      body
        .lineSequence()
        .filter { it.startsWith(SSE_PREFIX) }
        .map { it.removePrefix(SSE_PREFIX) }
        .toList()
    assertThat(frames).isNotEmpty()
    for (frame in frames) {
      assertThat(frame).contains("\"turnComplete\":true")
      assertEmissionRule(frame, Event.serializer().descriptor, "Event", minKeys = 8)
    }
  }

  @Test
  fun run_callerDataKeepsItsOwnSpelling() = testApplication {
    application { adkApiModule(testConfig(agentLoader = CallerDataAgentLoader())) }

    val response =
      client.post("/run") { jsonBody(camelCaseRun.replace("echo-agent", "mock-agent")) }
    val body = response.bodyAsText()

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    assertThat(body).contains("\"agent_name\":\"other-agent\"")
    assertThat(body).contains("\"report_v2.txt\":1")
    assertThat(body).contains("\"user_tier\":\"gold\"")
    assertThat(body).contains("\"detail\":null")
    assertEmissionRule(body, EVENT_LIST_DESCRIPTOR, "Event", minKeys = 8)
  }

  /** Runs [body] with a system property set, restoring whatever was there before. */
  private inline fun withProperty(name: String, value: String, body: () -> Unit) {
    val previous: String? = System.getProperty(name)
    System.setProperty(name, value)
    try {
      body()
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  /** Blanks the event id and timestamp, which differ between two runs of the same request. */
  private fun withoutVolatileFields(body: String): String =
    body
      .replace(Regex("\"id\":\"[^\"]*\""), "\"id\":\"\"")
      .replace(Regex("\"invocationId\":\"[^\"]*\""), "\"invocationId\":\"\"")
      .replace(Regex("\"timestamp\":[0-9]+"), "\"timestamp\":0")

  private fun testConfig(agentLoader: AgentLoader = this.agentLoader) =
    AdkServerConfig(
      agentLoader = agentLoader,
      sessionService = sessionService,
      artifactService = artifactService,
      apiServerSpanExporter = ApiServerSpanExporter(),
    )

  private companion object {
    const val SSE_PREFIX = "data: "

    val EVENT_LIST_DESCRIPTOR: SerialDescriptor = ListSerializer(Event.serializer()).descriptor
  }
}

/**
 * Reports back what the runner actually decoded from the request body, so a test can tell a field
 * that was read from one whose key was ignored.
 */
private class EchoAgent :
  BaseAgent(name = "echo-agent", description = "Echoes the decoded request") {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    val message = context.userContent?.text().orEmpty()
    val state = context.session.state["probe"]?.toString().orEmpty()
    emit(
      Event(
        invocationId = context.invocationId,
        author = "echo-agent",
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(
                  text =
                    "message=$message;state=$state;session=${context.session.key.id};" +
                      "invocation=${context.invocationId}"
                )
              ),
          ),
        turnComplete = true,
      )
    )
  }
}

private class EchoAgentLoader : AgentLoader {
  override fun listAgents() = listOf("echo-agent")

  override fun loadAgent(agentName: String) = if (agentName == "echo-agent") EchoAgent() else null
}

/** Emits one event whose free-form maps hold the spellings and nulls the rule does not govern. */
private class CallerDataAgent : BaseAgent(name = "mock-agent", description = "Caller data agent") {
  override fun runAsyncImpl(context: InvocationContext): Flow<Event> = flow {
    emit(
      Event(
        invocationId = context.invocationId,
        author = "mock-agent",
        content =
          Content(
            role = "model",
            parts =
              listOf(
                // The built-in transfer tool declares its parameter as `agent_name`.
                Part(
                  functionCall =
                    FunctionCall(
                      name = "transfer_to_agent",
                      args = mapOf("agent_name" to "other-agent"),
                    )
                ),
                Part(
                  functionResponse =
                    FunctionResponse(name = "transfer_to_agent", response = mapOf("detail" to null))
                ),
              ),
          ),
        actions =
          EventActions(
            stateDelta = mutableMapOf<String, Any>("user_tier" to "gold"),
            artifactDelta = mutableMapOf("report_v2.txt" to 1),
          ),
        turnComplete = true,
      )
    )
  }
}

private class CallerDataAgentLoader : AgentLoader {
  override fun listAgents() = listOf("mock-agent")

  override fun loadAgent(agentName: String) =
    if (agentName == "mock-agent") CallerDataAgent() else null
}
