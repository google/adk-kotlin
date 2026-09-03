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

package com.google.adk.kt.models

import com.google.adk.kt.VERSION
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.Part
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.Client
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * JVM-only sibling of [GeminiTest], which also runs on Android and so cannot construct a GenAI SDK
 * [Client] at all, materialize a `com.google.auth.oauth2.GoogleCredentials`, or assert tracking
 * headers against a local [MockWebServer] (a real HTTP port the SDK's Ktor client talks to; it
 * exposes no engine or base-URL override for an in-process mock).
 */
class GeminiJvmTest {

  private lateinit var mockServer: MockWebServer

  @BeforeTest
  fun startMockServer() {
    mockServer = MockWebServer()
    mockServer.start()
  }

  @AfterTest
  fun stopMockServer() {
    mockServer.close()
  }

  @Test
  fun init_withApiKey_initializesClient() {
    val model = Gemini(name = "gemini-test", apiKey = "fake-key")
    assertThat(model.client.enterprise).isFalse()
  }

  @Test
  fun init_withVertexCredentials_initializesClient() {
    val vertexCredentials =
      VertexCredentials(
        project = "test-project",
        location = "us-central1",
        credentials =
          GoogleCredentials.newBuilder()
            .setAccessToken(
              AccessToken("fake-token", Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli()))
            )
            .build(),
      )
    val model = Gemini(name = "gemini-test", vertexCredentials = vertexCredentials)

    assertThat(model.client.enterprise).isTrue()
  }

  @Test
  fun generateContent_nonStreaming_attachesAdkTrackingHeaders() {
    mockServer.enqueue(
      MockResponse(
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = GENERATE_CONTENT_RESPONSE,
      )
    )

    runBlocking { collectGenerateContent(stream = false) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_streaming_attachesAdkTrackingHeaders() {
    // The streaming endpoint returns server-sent events ("data: <json>" terminated by a blank
    // line).
    mockServer.enqueue(
      MockResponse(
        headers = Headers.headersOf("Content-Type", "text/event-stream"),
        body = "data: $GENERATE_CONTENT_RESPONSE\n\n",
      )
    )

    runBlocking { collectGenerateContent(stream = true) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_streaming_emitsPartialAndFinalResponses() = runTest {
    val client = Client(apiKey = "fake")
    val mockModels = mock<Gemini.GeminiModels>()
    whenever(
        mockModels.generateContentStream(
          eq("gemini-3.1-flash-preview"),
          any<List<Content>>(),
          any<GenerateContentConfig>(),
        )
      )
      .thenReturn(
        flowOf(
          buildResponse("chunk 1 "),
          buildResponse("chunk 2", finishReason = FinishReason.STOP),
        )
      )
    val model = Gemini(client, "gemini-3.1-flash-preview", models = mockModels)

    val responses =
      model
        .generateContent(
          LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
          stream = true,
        )
        .toList()

    // We expect 3 total responses: 2 partial chunks + 1 final aggregated
    assertThat(responses).hasSize(3)
    assertResponse(responses[0], expectedText = "chunk 1 ", isPartial = true)
    assertResponse(responses[1], expectedText = "chunk 2", isPartial = true)
    assertResponse(
      responses[2],
      expectedText = "chunk 1 chunk 2",
      isPartial = false,
      expectedFinishReason = "STOP",
    )
    assertThat(responses[2].errorMessage).isNull()
  }

  @Test
  fun generateContent_nonStreaming_returnsResponse() = runTest {
    val client = Client(apiKey = "fake")
    val mockModels = mock<Gemini.GeminiModels>()
    whenever(
        mockModels.generateContent(
          eq("gemini-3.1-flash-preview"),
          any<List<Content>>(),
          any<GenerateContentConfig>(),
        )
      )
      .thenReturn(buildResponse("full response", finishReason = FinishReason.STOP))
    val model = Gemini(client, "gemini-3.1-flash-preview", models = mockModels)

    val responses =
      model
        .generateContent(
          LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
          stream = false,
        )
        .toList()

    assertThat(responses).hasSize(1)
    assertResponse(
      responses[0],
      expectedText = "full response",
      isPartial = false,
      expectedFinishReason = "STOP",
    )
    assertThat(responses[0].errorMessage).isNull()
  }

  /**
   * Drives a [Gemini.generateContent] flow against the mock server so the GenAI SDK issues exactly
   * one HTTP request. Routes the API-key client at the mock server via the test-only `baseUrl`
   * constructor, which still applies the production tracking headers.
   */
  private suspend fun collectGenerateContent(stream: Boolean) {
    Gemini(
        name = "gemini-3.1-flash-preview",
        apiKey = "fake-key",
        baseUrl = mockServer.url("/").toString(),
      )
      .generateContent(
        LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
        stream = stream,
      )
      .toList()
  }

  private fun buildResponse(
    text: String,
    finishReason: FinishReason? = null,
  ): GenerateContentResponse {
    return GenerateContentResponse(
      candidates =
        listOf(
          Candidate(
            content = Content(role = "model", parts = listOf(Part(text = text))),
            finishReason = finishReason,
          )
        )
    )
  }

  private fun assertResponse(
    response: LlmResponse,
    expectedText: String,
    isPartial: Boolean,
    expectedFinishReason: String? = null,
  ) {
    assertThat(response.partial).isEqualTo(isPartial)
    val actualText = response.content?.parts?.joinToString("") { it.text ?: "" }
    assertThat(actualText).isEqualTo(expectedText)
    if (expectedFinishReason != null) {
      assertThat(response.finishReason?.name).isEqualTo(expectedFinishReason)
    }
  }

  private fun assertTrackingHeaders(request: RecordedRequest?) {
    checkNotNull(request) { "Expected the genai SDK to send a request to the mock server." }
    // The genai SDK may append its own label, so assert our value is present rather than equal.
    val expected = "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"
    assertThat(request.headers.values("x-goog-api-client").firstOrNull()).contains(expected)
    assertThat(request.headers.values("user-agent").firstOrNull()).contains(expected)
  }

  companion object {
    private const val GENERATE_CONTENT_RESPONSE =
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
