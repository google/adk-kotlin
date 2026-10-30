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
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Candidate
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.Part
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
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
  fun build_nullClientAndVertexCredentials_treatedAsUnset() {
    // The client and vertexCredentials setters are nullable (like apiKey); passing null is the same
    // as never calling them, so the API key wins and a non-enterprise client is built.
    val model =
      Gemini.builder()
        .name("gemini-test")
        .client(null)
        .apiKey("fake-key")
        .vertexCredentials(null)
        .build()

    assertThat(model.name).isEqualTo("gemini-test")
    assertThat(model.client.enterprise).isFalse()
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
  fun generateContent_vertexAi_leavesPartMetadataOutOfRequest() {
    mockServer.enqueue(json(GENERATE_CONTENT_RESPONSE))
    val part = Part(text = "Hello", partMetadata = mapOf("meta" to "data"))

    runBlocking {
      vertexAiClient().use { client ->
        val unused =
          Gemini(client, "gemini-3.1-flash-preview")
            .generateContent(
              LlmRequest(contents = listOf(userMessage(part)), config = GenerateContentConfig()),
              stream = false,
            )
            .toList()
      }
    }

    assertThat(takeRequestBody()).doesNotContain("partMetadata")
  }

  @Test
  fun generateContent_callerSuppliedClient_attachesAdkTrackingHeaders() {
    mockServer.enqueue(json(GENERATE_CONTENT_RESPONSE))

    runBlocking { generateWithCallerClient(stream = false) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_callerSuppliedClientStreaming_attachesAdkTrackingHeaders() {
    mockServer.enqueue(sse(GENERATE_CONTENT_RESPONSE))

    runBlocking { generateWithCallerClient(stream = true) }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_callerSuppliedClientUserAgent_isExtendedWithAdkTag() {
    mockServer.enqueue(json(GENERATE_CONTENT_RESPONSE))

    runBlocking {
      generateWithCallerClient(stream = false, headers = mapOf("user-agent" to "my-app/1.0"))
    }

    val request = mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertTrackingHeaders(request)
    assertThat(request?.headers?.get("user-agent")).endsWith("my-app/1.0 google-adk/$VERSION")
  }

  @Test
  @OptIn(ExperimentalLiveApi::class)
  fun connect_callerSuppliedClient_sendsTrackingHeadersOnTheWire() {
    mockServer.enqueue(MockResponse.Builder().webSocketUpgrade(AnswerLiveSetup).build())

    runBlocking {
      Client(
          apiKey = "fake-key",
          httpOptions = HttpOptions(baseUrl = mockServer.url("/").toString()),
        )
        .use { client -> Gemini(client, "gemini-live-test").connect(LlmRequest()).closeSession() }
    }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  @OptIn(ExperimentalLiveApi::class)
  fun connect_vertexAi_sendsContentWithPartMetadata() {
    mockServer.enqueue(MockResponse.Builder().webSocketUpgrade(AnswerLiveSetup).build())
    val content = userMessage(Part(text = "Hello", partMetadata = mapOf("meta" to "data")))

    // The SDK throws if part metadata reaches its Vertex AI converter.
    runBlocking {
      vertexAiClient().use { client ->
        val connection = Gemini(client, "gemini-live-test").connect(LlmRequest())
        connection.sendContent(content)
        connection.closeSession()
      }
    }
  }

  @Test
  @OptIn(ExperimentalLiveApi::class)
  fun connect_adkBuiltClient_sendsTrackingHeadersOnTheWire() {
    mockServer.enqueue(MockResponse.Builder().webSocketUpgrade(AnswerLiveSetup).build())

    runBlocking {
      Gemini.withBaseUrl(
          name = "gemini-live-test",
          apiKey = "fake-key",
          baseUrl = mockServer.url("/").toString(),
        )
        .connect(LlmRequest())
        .closeSession()
    }

    assertTrackingHeaders(mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  @Test
  fun generateContent_pausedGeneration_isResumedWithSummedUsage() {
    mockServer.enqueue(
      json(
        responseJson(
          "The answer is",
          candidateFields("CONTINUATION", token = "state"),
          usageJson(prompt = 10, candidates = 5, total = 15),
        )
      )
    )
    mockServer.enqueue(
      json(
        responseJson(
          " 42.",
          candidateFields("STOP"),
          usageJson(prompt = 15, candidates = 3, total = 18),
        )
      )
    )

    val response = runBlocking { generateThroughMockServer(stream = false) }.single()

    assertThat(response.content?.parts?.map { it.text })
      .containsExactly("The answer is", " 42.")
      .inOrder()
    assertThat(response.finishReason).isEqualTo(FinishReason.STOP)
    assertUsage(response, prompt = 10 + 15, candidates = 5 + 3, total = 15 + 18)
    val first = takeRequestBody()
    val resumed = takeRequestBody()
    assertThat(first).doesNotContain("continuationToken")
    assertThat(resumed).contains(""""continuationToken":"${base64("state")}"""")
    // The SDK resends the original request, not the output so far.
    assertThat(resumed).doesNotContain("The answer is")
  }

  @Test
  fun generateContent_pausedStream_isResumedWithSummedUsage() {
    mockServer.enqueue(
      sse(
        responseJson("The answer"),
        responseJson(
          " is",
          candidateFields("CONTINUATION", token = "state"),
          usageJson(prompt = 10, candidates = 5, total = 15),
        ),
      )
    )
    mockServer.enqueue(
      sse(
        responseJson(
          " 42.",
          candidateFields("STOP"),
          usageJson(prompt = 15, candidates = 3, total = 18),
        )
      )
    )

    val responses = runBlocking { generateThroughMockServer(stream = true) }

    assertResponse(
      responses.last(),
      expectedText = "The answer is 42.",
      isPartial = false,
      expectedFinishReason = "STOP",
    )
    assertUsage(responses.last(), prompt = 10 + 15, candidates = 5 + 3, total = 15 + 18)
    assertThat(responses.map { it.finishReason }).doesNotContain(FinishReason.CONTINUATION)
    assertThat(mockServer.requestCount).isEqualTo(2)
  }

  @Test
  fun generateContent_streamCollectorThrows_sendsNoFurtherRequest() {
    mockServer.enqueue(
      sse(
        responseJson("The answer", candidateFields(token = "checkpoint")),
        responseJson(" is", candidateFields("CONTINUATION", token = "state")),
      )
    )
    mockServer.enqueue(sse(responseJson(" 42.", candidateFields("STOP"))))

    assertFailsWith<CollectorException> {
      runBlocking { generateContentFlow(stream = true).collect { throw CollectorException() } }
    }

    assertThat(mockServer.requestCount).isEqualTo(1)
  }

  @Test
  fun generateContent_maxTokensWithToken_isNotResumed() {
    mockServer.enqueue(
      json(responseJson("The answer is", candidateFields("MAX_TOKENS", token = "state")))
    )

    val response = runBlocking { generateThroughMockServer(stream = false) }.single()

    assertThat(response.finishReason).isEqualTo(FinishReason.MAX_TOKENS)
    assertThat(mockServer.requestCount).isEqualTo(1)
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
   * Returns a [Gemini.generateContent] flow from the mock server through the test-only
   * [Gemini.withBaseUrl] factory, which applies the production HTTP options (tracking headers and
   * retries).
   */
  private fun generateContentFlow(stream: Boolean): Flow<LlmResponse> =
    Gemini.withBaseUrl(
        name = "gemini-3.1-flash-preview",
        apiKey = "fake-key",
        baseUrl = mockServer.url("/").toString(),
      )
      .generateContent(
        LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
        stream = stream,
      )

  private suspend fun generateThroughMockServer(stream: Boolean): List<LlmResponse> =
    generateContentFlow(stream).toList()

  /** Generates through the public constructor with a client the caller built for the server. */
  private suspend fun generateWithCallerClient(
    stream: Boolean,
    headers: Map<String, String>? = null,
  ) {
    Client(
        apiKey = "fake-key",
        httpOptions = HttpOptions(baseUrl = mockServer.url("/").toString(), headers = headers),
      )
      .use { client ->
        val unused =
          Gemini(client, "gemini-3.1-flash-preview")
            .generateContent(
              LlmRequest(contents = listOf(userMessage("Hello")), config = GenerateContentConfig()),
              stream = stream,
            )
            .toList()
      }
  }

  /** Runs [generateThroughMockServer] for tests that check only the requests it sends. */
  private suspend fun collectGenerateContent(stream: Boolean) {
    val unused = generateThroughMockServer(stream)
  }

  private fun takeRequestBody(): String =
    mockServer.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.body?.utf8().orEmpty()

  private fun json(body: String) =
    MockResponse(headers = Headers.headersOf("Content-Type", "application/json"), body = body)

  /** A server-sent event stream with one `data:` event per chunk. */
  private fun sse(vararg chunks: String) =
    MockResponse(
      headers = Headers.headersOf("Content-Type", "text/event-stream"),
      body = chunks.joinToString("") { "data: $it\n\n" },
    )

  /** A response with one candidate holding [text] and [candidateFields], then [usage]. */
  private fun responseJson(text: String, candidateFields: String = "", usage: String = "") =
    """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}$candidateFields}]$usage}"""

  /** Candidate fields with [finishReason] and [token], each left out when null. */
  private fun candidateFields(finishReason: String? = null, token: String? = null): String =
    finishReason?.let { ""","finishReason":"$it"""" }.orEmpty() +
      token?.let { ""","continuationToken":"${base64(it)}"""" }.orEmpty()

  private fun usageJson(prompt: Int, candidates: Int, total: Int) =
    ""","usageMetadata":{"promptTokenCount":$prompt,"candidatesTokenCount":$candidates,""" +
      """"totalTokenCount":$total}"""

  private fun base64(token: String): String =
    Base64.getEncoder().encodeToString(token.encodeToByteArray())

  private fun assertUsage(response: LlmResponse, prompt: Int, candidates: Int, total: Int) {
    val usage = checkNotNull(response.usageMetadata)
    assertThat(usage.promptTokenCount).isEqualTo(prompt)
    assertThat(usage.candidatesTokenCount).isEqualTo(candidates)
    assertThat(usage.totalTokenCount).isEqualTo(total)
  }

  private fun vertexAiClient() =
    Client(
      project = "test-project",
      location = "us-central1",
      credentials = fakeVertexCredentials().credentials?.toGenaiSdk(),
      enterprise = true,
      httpOptions = HttpOptions(baseUrl = mockServer.url("/").toString()),
    )

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

  /**
   * Asserts both tracking headers as received by the server: each starts with the GenAI SDK label,
   * includes the Kotlin version tag and ADK's tag, and repeats no tag name.
   */
  private fun assertTrackingHeaders(request: RecordedRequest?) {
    checkNotNull(request) { "Expected the genai SDK to send a request to the mock server." }
    for (name in listOf("x-goog-api-client", "user-agent")) {
      val tags = request.headers.values(name).single().split(' ')
      assertThat(tags.first()).startsWith("google-genai-sdk/")
      assertThat(tags).containsAtLeast("gl-kotlin/${KotlinVersion.CURRENT}", "google-adk/$VERSION")
      assertThat(tags.map { it.substringBefore('/') }).containsNoDuplicates()
    }
  }

  /** Answers the Live setup message, which [Gemini.connect] awaits before returning. */
  private object AnswerLiveSetup : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
      webSocket.send("""{"setupComplete":{}}""")
    }

    // Complete the close handshake so the server can shut down after the test.
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, null)
    }
  }

  private class CollectorException : RuntimeException("collector failed")

  companion object {
    private const val GENERATE_CONTENT_RESPONSE =
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
