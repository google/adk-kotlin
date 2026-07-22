/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.sessions

import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.sessions.dto.SessionEventDto
import com.google.adk.kt.sessions.dto.TimestampDto
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Transport-level tests for [VertexAiSessionsClient]. These drive a real [GoogleApiClient] (with a
 * fake, non-expiring credential) against a [MockWebServer], so they exercise URL construction,
 * header injection, JSON (de)serialization, the create-session long-running-operation poll, and the
 * [Result] error contract end to end.
 */
@RunWith(JUnit4::class)
class VertexAiSessionsClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: VertexAiSessionsClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client =
      VertexAiSessionsClient(
        apiClient = GoogleApiClient(credentials = fakeCredentials()),
        baseUrlOverride = server.url("/").toString(),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun createSession_pollsOperationThenFetchesSession() {
    // 1) create returns an LRO whose name encodes the session id and operation id.
    server.enqueue(
      jsonResponse("""{"name":"reasoningEngines/123/sessions/sess-1/operations/op-1"}""")
    )
    // 2) the operation poll reports completion immediately.
    server.enqueue(jsonResponse("""{"name":"operations/op-1","done":true}"""))
    // 3) the materialized session is fetched.
    server.enqueue(
      jsonResponse(
        """{"name":"reasoningEngines/123/sessions/sess-1","updateTime":"2024-12-12T12:12:12Z"}"""
      )
    )

    val session = runBlocking {
      client.createSession(ENGINE, "user", mapOf("k" to "v")).getOrThrow()
    }

    assertThat(session.name).isEqualTo("reasoningEngines/123/sessions/sess-1")

    val createRequest = server.takeRequest()
    assertThat(createRequest.method).isEqualTo("POST")
    assertThat(createRequest.path)
      .isEqualTo(
        "/v1beta1/projects/test-project/locations/test-location/reasoningEngines/123/sessions"
      )
    assertThat(createRequest.getHeader("Authorization")).isEqualTo("Bearer fake-token")
    assertThat(createRequest.getHeader("Content-Type")).contains("application/json")
    val body = createRequest.body.readUtf8()
    assertThat(body).contains("\"userId\":\"user\"")
    assertThat(body).contains("\"k\":\"v\"")

    assertThat(server.takeRequest().path).endsWith("/operations/op-1")
    assertThat(server.takeRequest().path).endsWith("/reasoningEngines/123/sessions/sess-1")
  }

  @Test
  fun createSession_nestedState_serializedAsJsonNotToString() {
    server.enqueue(jsonResponse("""{"name":"reasoningEngines/123/sessions/s/operations/op"}"""))
    server.enqueue(jsonResponse("""{"name":"operations/op","done":true}"""))
    server.enqueue(jsonResponse("""{"name":"reasoningEngines/123/sessions/s"}"""))

    val unused = runBlocking {
      client.createSession(ENGINE, "user", mapOf("nested" to mapOf("a" to listOf(1L, 2L))))
    }

    // Nested Map/List must round-trip as JSON, not be flattened via toString().
    val body = server.takeRequest().body.readUtf8()
    assertThat(body).contains("\"nested\":{\"a\":[1,2]}")
  }

  @Test
  fun getSession_notFound_returnsNullSuccess() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404))

    assertThat(client.getSession(ENGINE, "missing").getOrThrow()).isNull()
  }

  @Test
  fun getSession_serverError_returnsFailure() {
    server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

    val result = runBlocking { client.getSession(ENGINE, "s1") }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun getSession_clientError_returnsFailure() {
    // A non-404 4xx is a real error and must not be masked as a "not found" (null) result.
    server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))

    val result = runBlocking { client.getSession(ENGINE, "s1") }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
  }

  @Test
  fun listSessions_parsesSessionsAndSendsUserFilter() {
    server.enqueue(
      jsonResponse(
        """
        {"sessions":[
          {"name":"reasoningEngines/123/sessions/1"},
          {"name":"reasoningEngines/123/sessions/2"}
        ]}
        """
          .trimIndent()
      )
    )

    val response = runBlocking { client.listSessions(ENGINE, "user").getOrThrow() }

    assertThat(response!!.sessions!!.map { it.name!!.substringAfterLast('/') })
      .containsExactly("1", "2")
      .inOrder()
    // The user id is sent as a quoted filter literal and the whole filter is URL-escaped, so it
    // arrives as `filter=user_id="user"` percent-encoded (= -> %3D, " -> %22).
    assertThat(server.takeRequest().path).contains("filter=user_id%3D%22user%22")
  }

  @Test
  fun listSessions_quotesAndEscapesUserId_preventingFilterInjection() {
    server.enqueue(jsonResponse("""{"sessions":[]}"""))

    val unused = runBlocking { client.listSessions(ENGINE, "\" OR user_id=~\"x").getOrThrow() }

    val path = server.takeRequest().path!!
    // The value stays inside a quoted literal and every special char is percent-encoded, so the
    // injected operator/quotes can't alter the filter.
    assertThat(path).contains("filter=user_id%3D%22")
    assertThat(path).doesNotContain("=~")
    assertThat(path).doesNotContain("\"")
  }

  @Test
  fun listEvents_withFilter_urlEncodesFilter() {
    server.enqueue(jsonResponse("""{"sessionEvents":[]}"""))

    val unused = runBlocking {
      client.listEvents(ENGINE, "s1", "timestamp>=\"2024-12-12T12:00:10Z\"").getOrThrow()
    }

    val path = server.takeRequest().path!!
    // The filter operator and quotes are URL-escaped (>= -> %3E%3D, " -> %22), not sent raw.
    assertThat(path).contains("filter=timestamp%3E%3D%22")
    assertThat(path).doesNotContain("timestamp>=")
  }

  @Test
  fun deleteSession_sendsDeleteToSessionPath() {
    server.enqueue(MockResponse().setResponseCode(200))

    runBlocking { client.deleteSession(ENGINE, "s1").getOrThrow() }

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("DELETE")
    assertThat(request.path).endsWith("/reasoningEngines/123/sessions/s1")
  }

  @Test
  fun appendEvent_success_returnsSuccess() {
    server.enqueue(MockResponse().setResponseCode(200))

    val result = runBlocking {
      client.appendEvent(
        ENGINE,
        "s1",
        SessionEventDto(author = "user", timestamp = TimestampDto.fromEpochMillis(1000)),
      )
    }

    assertThat(result.isSuccess).isTrue()
    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path).endsWith("/reasoningEngines/123/sessions/s1:appendEvent")
  }

  @Test
  fun appendEvent_serverError_returnsFailure() {
    // The client propagates the error rather than swallowing it; whether to ignore a failed append
    // is the caller's choice, not the transport's.
    server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

    val result = runBlocking {
      client.appendEvent(
        ENGINE,
        "s1",
        SessionEventDto(author = "user", timestamp = TimestampDto.fromEpochMillis(1000)),
      )
    }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    assertThat(server.takeRequest().path).endsWith("/reasoningEngines/123/sessions/s1:appendEvent")
  }

  private companion object {
    val ENGINE =
      ReasoningEngineRef(project = "test-project", location = "test-location", id = "123")

    fun jsonResponse(body: String): MockResponse =
      MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    fun fakeCredentials(): GoogleCredentials =
      GoogleCredentials.newBuilder()
        .setAccessToken(
          AccessToken("fake-token", Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()))
        )
        .build()
  }
}
