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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the wire rules the agent runtime contract puts on every endpoint: read `snake_case` or
 * `camelCase`, ignore unrecognized keys, and write `camelCase` without null fields.
 */
@RunWith(JUnit4::class)
class WireContractTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = FakeAgentLoader()

  private val snakeCaseRun =
    """
    {
      "app_name": "mock-agent",
      "user_id": "testUser",
      "session_id": "testSession",
      "new_message": {"role": "user", "parts": [{"text": "Hello agent"}]}
    }
    """
      .trimIndent()

  private val camelCaseRun =
    """
    {
      "appName": "mock-agent",
      "userId": "testUser",
      "sessionId": "testSession",
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
    assertThat(snakeCase.bodyAsText()).contains("mocked response from Agent mock-agent")
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
    assertThat(response.bodyAsText()).contains("mocked response from Agent mock-agent")
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
    assertThat(response.bodyAsText()).contains("mocked response from Agent mock-agent")
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
  fun run_response_emitsCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    val body = client.post("/run") { jsonBody(camelCaseRun) }.bodyAsText()

    assertThat(body).contains("\"turnComplete\":true")
    assertThat(keysOf(body)).contains("invocationId")
    assertThat(keysOf(body).filter { it.contains('_') }).isEmpty()
    assertThat(nullValuedKeysOf(body)).isEmpty()
  }

  @Test
  fun runSse_frames_emitCamelCaseWithoutNulls() = testApplication {
    application { adkApiModule(testConfig()) }

    // The stream is encoded outside content negotiation, so it needs its own assertion.
    val body =
      client
        .post("/run_sse") {
          jsonBody(camelCaseRun.replace("\"sessionId\"", "\"streaming\": true, \"sessionId\""))
        }
        .bodyAsText()

    assertThat(body).contains("data: ")
    val frames =
      body.lineSequence().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
    assertThat(frames.toList()).isNotEmpty()
    for (frame in frames) {
      assertThat(keysOf(frame)).contains("turnComplete")
      assertThat(keysOf(frame).filter { it.contains('_') }).isEmpty()
      assertThat(nullValuedKeysOf(frame)).isEmpty()
    }
  }

  @Test
  fun run_malformedBody_isRejected() = testApplication {
    application { adkApiModule(testConfig()) }

    val response = client.post("/run") { jsonBody("{\"app_name\": ") }

    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  /** Returns every object key in [body], at any depth, so a key rule is checked on keys alone. */
  private fun keysOf(body: String): List<String> = buildList {
    fun walk(element: JsonElement) {
      when (element) {
        is JsonObject ->
          element.forEach { (key, value) ->
            add(key)
            walk(value)
          }
        is JsonArray -> element.forEach(::walk)
        else -> {}
      }
    }
    walk(Json.parseToJsonElement(body))
  }

  /** Returns the keys in [body] whose value is JSON `null`, at any depth. */
  private fun nullValuedKeysOf(body: String): List<String> = buildList {
    fun walk(element: JsonElement) {
      when (element) {
        is JsonObject ->
          element.forEach { (key, value) -> if (value is JsonNull) add(key) else walk(value) }
        is JsonArray -> element.forEach(::walk)
        else -> {}
      }
    }
    walk(Json.parseToJsonElement(body))
  }

  /** Blanks the event id and timestamp, which differ between two runs of the same request. */
  private fun withoutVolatileFields(body: String): String =
    body
      .replace(Regex("\"id\":\"[^\"]*\""), "\"id\":\"\"")
      .replace(Regex("\"invocationId\":\"[^\"]*\""), "\"invocationId\":\"\"")
      .replace(Regex("\"timestamp\":[0-9]+"), "\"timestamp\":0")

  private fun testConfig() =
    AdkServerConfig(
      agentLoader = agentLoader,
      sessionService = sessionService,
      artifactService = artifactService,
      apiServerSpanExporter = ApiServerSpanExporter(),
    )
}
