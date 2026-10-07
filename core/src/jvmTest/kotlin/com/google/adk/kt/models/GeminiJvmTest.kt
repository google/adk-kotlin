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
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.SpeechConfig
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.truth.Truth.assertThat
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.HttpOptions
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
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

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_noName_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> { Gemini.builder().build() }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_apiKeyAndVertexCredentials_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> {
      Gemini.builder()
        .name("gemini-test")
        .apiKey("fake-key")
        .vertexCredentials(fakeVertexCredentials())
        .build()
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_clientAndApiKey_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> {
      Gemini.builder()
        .name("gemini-test")
        .client(Client(apiKey = "fake"))
        .apiKey("fake-key")
        .build()
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_clientAndVertexCredentials_throwsIllegalStateException() {
    assertFailsWith<IllegalStateException> {
      Gemini.builder()
        .name("gemini-test")
        .client(Client(apiKey = "fake"))
        .vertexCredentials(fakeVertexCredentials())
        .build()
    }
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_withClient_usesSameClientAndCarriesNameAndSpeechConfig() {
    val client = Client(apiKey = "fake")
    val speechConfig = SpeechConfig(languageCode = "en-US")

    val model =
      Gemini.builder().name("gemini-test").client(client).speechConfig(speechConfig).build()

    assertSame(client, model.client)
    assertThat(model.name).isEqualTo("gemini-test")
    assertSame(speechConfig, model.speechConfig)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_withApiKey_buildsNonEnterpriseClientAndCarriesSpeechConfig() {
    val speechConfig = SpeechConfig(languageCode = "en-US")

    val model =
      Gemini.builder().name("gemini-test").apiKey("fake-key").speechConfig(speechConfig).build()

    assertThat(model.client.enterprise).isFalse()
    assertThat(model.name).isEqualTo("gemini-test")
    assertSame(speechConfig, model.speechConfig)
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_withVertexCredentials_buildsEnterpriseClient() {
    val model =
      Gemini.builder().name("gemini-test").vertexCredentials(fakeVertexCredentials()).build()

    assertThat(model.client.enterprise).isTrue()
    assertThat(model.client.project).isEqualTo("test-project")
    assertThat(model.client.location).isEqualTo("us-central1")
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun build_onlyName_matchesNameOnlyConstructor() {
    // No client, apiKey or vertexCredentials set: the builder must behave exactly like the
    // `Gemini(name)` constructor it delegates to, whatever that does in this environment.
    val constructed = runCatching { Gemini(name = "gemini-test") }
    val built = runCatching { Gemini.builder().name("gemini-test").build() }

    assertThat(built.isSuccess).isEqualTo(constructed.isSuccess)
    if (constructed.isSuccess) {
      assertThat(built.getOrThrow().client.enterprise)
        .isEqualTo(constructed.getOrThrow().client.enterprise)
    } else {
      assertThat(built.exceptionOrNull()).isInstanceOf(constructed.exceptionOrNull()!!.javaClass)
    }
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
  fun generateContent_transientFailure_isRetried() {
    mockServer.enqueue(
      MockResponse(
        code = 503,
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = """{"error":{"code":503,"message":"fake","status":"UNAVAILABLE"}}""",
      )
    )
    mockServer.enqueue(
      MockResponse(
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = GENERATE_CONTENT_RESPONSE,
      )
    )

    runBlocking { collectGenerateContent(stream = false) }

    assertThat(mockServer.requestCount).isEqualTo(2)
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

  @Test
  fun generateContent_quotaExceeded_throwsResourceExhaustedWithMitigation() = runTest {
    val client = Client(apiKey = "fake")
    val mockModels = mock<Gemini.GeminiModels>()
    val quotaError = ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for model")
    whenever(
        mockModels.generateContent(
          eq("gemini-3.1-flash-preview"),
          any<List<Content>>(),
          any<GenerateContentConfig>(),
        )
      )
      .thenAnswer { throw quotaError }
    val model = Gemini(client, "gemini-3.1-flash-preview", models = mockModels)

    val thrown =
      assertFailsWith<GenAiApiException> {
        model
          .generateContent(
            LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
            stream = false,
          )
          .toList()
      }

    // The 429 is remapped to a sibling type carrying the mitigation guidance, with the original
    // error chained as the cause; it is no longer a ClientException.
    assertThat(thrown).isNotInstanceOf(ClientException::class.java)
    assertThat(thrown.message).contains("#error-code-429-resource_exhausted")
    assertThat(thrown.message).contains("Quota exceeded for model")
    assertThat(thrown.cause).isEqualTo(quotaError)
  }

  @Test
  fun generateContent_nonQuotaClientError_propagatesUnchanged() = runTest {
    val client = Client(apiKey = "fake")
    val mockModels = mock<Gemini.GeminiModels>()
    val badRequest = ClientException(400, "INVALID_ARGUMENT", "Bad request")
    whenever(
        mockModels.generateContent(
          eq("gemini-3.1-flash-preview"),
          any<List<Content>>(),
          any<GenerateContentConfig>(),
        )
      )
      .thenAnswer { throw badRequest }
    val model = Gemini(client, "gemini-3.1-flash-preview", models = mockModels)

    val thrown =
      assertFailsWith<ClientException> {
        model
          .generateContent(
            LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
            stream = false,
          )
          .toList()
      }

    assertThat(thrown).isSameInstanceAs(badRequest)
  }

  @Test
  fun genaiCacheClient_createWithServerExpireTime_returnsServerExpiry() = runBlocking {
    mockServer.enqueue(
      MockResponse(
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = """{"name":"cachedContents/abc","expireTime":"2033-05-18T03:33:20.123Z"}""",
      )
    )

    val created = createCache()

    assertThat(created.name).isEqualTo("cachedContents/abc")
    assertThat(created.expireTime?.toEpochMilliseconds()).isEqualTo(2_000_000_000_123L)
  }

  @Test
  fun genaiCacheClient_createWithoutExpireTime_returnsNullExpiry() = runBlocking {
    mockServer.enqueue(
      MockResponse(
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = """{"name":"cachedContents/abc"}""",
      )
    )

    val created = createCache()

    assertThat(created.expireTime).isNull()
  }

  /** Creates a cache through [GenaiCacheClient] with an SDK client routed to the mock server. */
  private suspend fun createCache(): GeminiContextCacheManager.CreatedCache =
    Client(apiKey = "fake-key", httpOptions = HttpOptions(baseUrl = mockServer.url("/").toString()))
      .use { client ->
        GenaiCacheClient(client.caches)
          .create(
            GeminiContextCacheManager.CacheCreateRequest(
              model = "gemini-2.0-flash",
              contents = null,
              systemInstruction = null,
              tools = null,
              toolConfig = null,
              ttl = 30.minutes,
              displayName = "test",
            )
          )
      }

  /**
   * Drives a [Gemini.generateContent] flow against the mock server through the test-only
   * [Gemini.withBaseUrl] factory, which applies the production HTTP options (tracking headers and
   * retries).
   */
  private suspend fun collectGenerateContent(stream: Boolean) {
    Gemini.withBaseUrl(
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

  private fun fakeVertexCredentials(): VertexCredentials =
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

  private fun buildResponse(
    text: String,
    finishReason: FinishReason? = null,
  ): GenerateContentResponse {
    return GenerateContentResponse(
      candidates = listOf(Candidate(content = modelMessage(text), finishReason = finishReason))
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
