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
import com.google.adk.kt.types.GenerateContentConfig
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * JVM-only sibling of [GeminiTest] for behaviour that can't be exercised from `commonTest`:
 * materializing a `com.google.auth.oauth2.GoogleCredentials`, and asserting tracking headers on
 * real requests via a local [MockWebServer] (a real HTTP port the Kotlin GenAI SDK's Ktor client
 * talks to; the SDK exposes no engine/base-URL override for an in-process mock).
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
    mockServer.shutdown()
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
      MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(GENERATE_CONTENT_RESPONSE)
    )

    runBlocking { collectGenerateContent(stream = false) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_streaming_attachesAdkTrackingHeaders() {
    // The streaming endpoint returns server-sent events ("data: <json>" terminated by a blank
    // line).
    mockServer.enqueue(
      MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: $GENERATE_CONTENT_RESPONSE\n\n")
    )

    runBlocking { collectGenerateContent(stream = true) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
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

  private fun assertTrackingHeaders(request: RecordedRequest?) {
    checkNotNull(request) { "Expected the genai SDK to send a request to the mock server." }
    // The genai SDK may append its own label, so assert our value is present rather than equal.
    val expected = "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"
    assertThat(request.getHeader("x-goog-api-client")).contains(expected)
    assertThat(request.getHeader("user-agent")).contains(expected)
  }

  companion object {
    private const val GENERATE_CONTENT_RESPONSE =
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
