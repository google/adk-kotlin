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
package com.google.adk.kt.models.chatcompletions

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.ServerSocket
import java.net.http.HttpHeaders
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Transport-level tests for [JdkChatCompletionsClient] against a [MockWebServer]. They exercise the
 * real JDK HTTP client: URL, bearer auth, JSON decoding, the error contract, and SSE parsing with
 * the `[DONE]` terminator - the wire behavior a fake cannot cover.
 */
@RunWith(JUnit4::class)
class JdkChatCompletionsClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: JdkChatCompletionsClient
  private val retryDelays = mutableListOf<Pair<Int, Duration?>>()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = clientWith(apiKey = { "secret-key" })
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun create_sendsBearerAuthToChatCompletionsEndpointAndParsesResponse() {
    server.enqueue(
      jsonResponse(
        """
        {"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
         "finish_reason":"stop"}],
         "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
        """
          .trimIndent()
      )
    )

    val response = runBlocking { client.create(request()) }

    assertThat(response.choices.single().message!!.contentText()).isEqualTo("hi")
    assertThat(response.choices.single().finishReason).isEqualTo("stop")
    assertThat(response.usage!!.promptTokens).isEqualTo(3)

    val recorded = server.takeRequest()
    assertThat(recorded.method).isEqualTo("POST")
    assertThat(recorded.target).isEqualTo("/chat/completions")
    assertThat(recorded.headers.values("authorization").firstOrNull())
      .isEqualTo("Bearer secret-key")
    assertThat(recorded.headers.values("user-agent").firstOrNull()).contains("google-adk/")
    assertThat(recorded.body?.utf8()).contains("\"model\":\"gpt-4o\"")
  }

  @Test
  fun publicConstructors_sendTheirKeyToTheConfiguredEndpoint() {
    val options = HttpOptions(baseUrl = server.url("/v1").toString())
    val request =
      LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))
    repeat(3) { server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}""")) }
    val models =
      listOf(
        ChatCompletions("gpt-4o", "static-key", options),
        ChatCompletions("gpt-4o", options) { "provided-key" },
        ChatCompletions("gpt-4o", httpOptions = options),
      )

    val texts = runBlocking {
      models.map { it.generateContent(request, false).toList().single().content!!.parts[0].text }
    }

    assertThat(texts).containsExactly("ok", "ok", "ok")
    val first = server.takeRequest()
    assertThat(first.target).isEqualTo("/v1/chat/completions")
    assertThat(first.headers["authorization"]).isEqualTo("Bearer static-key")
    assertThat(server.takeRequest().headers["authorization"]).isEqualTo("Bearer provided-key")
    assertThat(server.takeRequest().headers["authorization"]).isNull()
  }

  @Test
  fun apiKeyOrDefault_readsOpenAiApiKeyOnlyForOpenAisBaseUrl() {
    val env = { name: String -> if (name == "OPENAI_API_KEY") "openai-key" else null }
    val otherServer = HttpOptions(baseUrl = "http://localhost:8080/v1")

    assertThat(apiKeyOrDefault(null, null, env)).isEqualTo("openai-key")
    assertThat(apiKeyOrDefault(null, HttpOptions(timeout = 1.seconds), env)).isEqualTo("openai-key")
    assertThat(apiKeyOrDefault(null, otherServer, env)).isNull()
    assertThat(apiKeyOrDefault("own-key", otherServer, env)).isEqualTo("own-key")
  }

  @Test
  fun withHeadersProvider_sendsProvidedHeadersPerRequestWithoutABearerKey() {
    var calls = 0
    val options =
      HttpOptions(baseUrl = server.url("/").toString(), headers = mapOf("x-team" to "fixed"))
    val model =
      ChatCompletions.withHeadersProvider("gpt-4o", options) {
        mapOf("api-key" to "token-${++calls}", "x-team" to "provided")
      }
    val request =
      LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))
    repeat(2) { server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}""")) }

    runBlocking {
      repeat(2) {
        val unused = model.generateContent(request, false).toList()
      }
    }

    val first = server.takeRequest()
    val second = server.takeRequest()
    assertThat(first.headers["api-key"]).isEqualTo("token-1")
    assertThat(second.headers["api-key"]).isEqualTo("token-2")
    // Fixed headers replace provided ones, and no bearer header is added.
    assertThat(second.headers["x-team"]).isEqualTo("fixed")
    assertThat(first.headers["authorization"]).isNull()
  }

  @Test
  fun create_callsKeyProviderPerRequestAndSendsCustomHeaders() {
    var calls = 0
    val client = clientWith(apiKey = { "token-${++calls}" }, headers = mapOf("x-team" to "adk"))
    repeat(2) { server.enqueue(jsonResponse("""{"choices":[]}""")) }

    runBlocking {
      repeat(2) {
        val unused = client.create(request())
      }
    }

    val first = server.takeRequest()
    val second = server.takeRequest()
    // A refreshed token must reach the next request without rebuilding the model.
    assertThat(first.headers["authorization"]).isEqualTo("Bearer token-1")
    assertThat(second.headers["authorization"]).isEqualTo("Bearer token-2")
    assertThat(second.headers["x-team"]).isEqualTo("adk")
  }

  @Test
  fun create_customHeaderReplacesDefault() {
    // Some gateways take the key in their own header shape.
    val client =
      clientWith(
        apiKey = { "default-key" },
        headers = mapOf("Authorization" to "Token abc", "Content-Type" to "text/plain"),
      )
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val unused = runBlocking { client.create(request()) }

    val recorded = server.takeRequest()
    assertThat(recorded.headers.values("authorization")).containsExactly("Token abc")
    // The body is always JSON.
    assertThat(recorded.headers.values("content-type")).containsExactly("application/json")
  }

  @Test
  fun create_keepsAnInvalidKeyOutOfTheError() {
    // A token read from command output often ends in a newline, which no header may contain.
    val client = clientWith(apiKey = { "secret-token\n" })

    val exception =
      assertFailsWith<IllegalArgumentException> { runBlocking { client.create(request()) } }

    assertThat(exception).hasMessageThat().doesNotContain("secret-token")
    // The JDK's own exception names the value, so it must not be the cause.
    assertThat(exception.cause).isNull()
  }

  @Test
  fun create_timesOutWhenHeadersNeverArrive() {
    server.enqueue(MockResponse.Builder().body("{}").headersDelay(30, TimeUnit.SECONDS).build())
    val client = clientWith(apiKey = { null }, timeout = 200.milliseconds)

    assertFailsWith<HttpTimeoutException> { runBlocking { client.create(request()) } }
  }

  @Test
  fun create_timesOutWhenTheBodyStalls() {
    // Headers arrive at once, so only the deadline on the whole call can end each attempt.
    repeat(3) {
      server.enqueue(
        MockResponse.Builder()
          .addHeader("content-type", "application/json")
          .body("{}")
          .bodyDelay(30, TimeUnit.SECONDS)
          .build()
      )
    }
    val client = clientWith(apiKey = { null }, timeout = 200.milliseconds)

    assertFailsWith<HttpTimeoutException> { runBlocking { client.create(request()) } }

    // A timed-out attempt is retried like a connection failure.
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun zeroOrInfiniteTimeout_sendsRequestsWithoutADeadline() =
    runBlocking<Unit> {
      // Zero means none, as in ADK Java; the JDK client overflows computing an infinite deadline.
      for (timeout in listOf(Duration.ZERO, Duration.INFINITE)) {
        val client = clientWith(apiKey = { null }, timeout = timeout)
        server.enqueue(jsonResponse("""{"choices":[]}"""))
        server.enqueue(
          MockResponse(
            headers = Headers.headersOf("content-type", "text/event-stream"),
            body = "data: [DONE]\n\n",
          )
        )

        val unused = client.create(request())
        val chunks = client.createStream(request()).toList()

        assertThat(chunks).isEmpty()
      }
    }

  @Test
  fun create_cancellationAbortsTheExchangeWhileTheResponseStalls() {
    assertCancellationClosesTheConnection {
      val unused = it.create(request())
    }
  }

  @Test
  fun constructor_rejectsUnusableOptions() {
    assertFailsWith<IllegalArgumentException> {
      JdkChatCompletionsClient({ emptyMap() }, HttpOptions(apiVersion = "v1"))
    }
    assertFailsWith<IllegalArgumentException> {
      JdkChatCompletionsClient({ emptyMap() }, HttpOptions(timeout = (-1).seconds))
    }
    assertFailsWith<IllegalArgumentException> {
      JdkChatCompletionsClient(
        { emptyMap() },
        HttpOptions(headers = mapOf("Host" to "example.com")),
      )
    }
  }

  @Test
  fun create_reportsAClientErrorWithTheProviderErrorTypeAndNoRetry() {
    server.enqueue(MockResponse(code = 400, body = ERROR_BODY))

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception.statusCode).isEqualTo(400)
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("Chat Completions API error: HTTP 400 (invalid_request_error)")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun create_reportsTheStatusOfAGoogleErrorBody() {
    // Vertex AI wraps a Google error, whose code is a number, in an array.
    server.enqueue(
      MockResponse(
        code = 404,
        body = """[{"error":{"code":404,"message":"secret","status":"NOT_FOUND"}}]""",
      )
    )

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("Chat Completions API error: HTTP 404 (NOT_FOUND)")
  }

  @Test
  fun create_retriesConnectionFailuresAndRetryableStatuses() {
    var tokens = 0
    val client = clientWith(apiKey = { "token-${++tokens}" })
    server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
    server.enqueue(MockResponse.Builder().code(503).addHeader("retry-after", "2").build())
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val response = runBlocking { client.create(request()) }

    assertThat(response.choices).isEmpty()
    assertThat(retryDelays).containsExactly(0 to null, 1 to 2.seconds).inOrder()
    // Each attempt asks for a fresh token.
    assertThat(tokens).isEqualTo(3)
  }

  @Test
  fun create_followsTheServersRetryHint() {
    // An unknown hint falls back to the status, so this 503 is retried.
    server.enqueue(MockResponse.Builder().code(503).addHeader("x-should-retry", "maybe").build())
    server.enqueue(MockResponse.Builder().code(400).addHeader("x-should-retry", "true").build())
    server.enqueue(
      MockResponse.Builder().code(503).addHeader("x-should-retry", "false").body(ERROR_BODY).build()
    )

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    // The 400 is retried and the last 503 is not.
    assertThat(exception.statusCode).isEqualTo(503)
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun create_doesNotRetryAFailingHeadersProvider() {
    var calls = 0
    val client =
      clientWith(
        apiKey = {
          calls++
          throw IOException("token refresh failed")
        }
      )

    assertFailsWith<IOException> { runBlocking { client.create(request()) } }

    assertThat(calls).isEqualTo(1)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun create_givesUpAfterTwoRetries() {
    repeat(3) { server.enqueue(MockResponse(code = 500, body = ERROR_BODY)) }

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception.statusCode).isEqualTo(500)
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun create_reportsAMalformedBodyWithoutIt() {
    server.enqueue(jsonResponse("not json with a secret"))

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("Chat Completions API error: malformed response body")
    // The decoder's exception quotes the body, so it must not be the cause.
    assertThat(exception.cause).isNull()
  }

  @Test
  fun create_throwsWhenErrorBodyReturnedWithSuccessStatus() {
    // Some OpenAI-compatible providers report a failure as HTTP 200 with an error body.
    server.enqueue(jsonResponse("""{"error":{"type":"invalid_request_error"},"choices":[]}"""))

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception.statusCode).isNull()
  }

  @Test
  fun createStream_reportsAClientErrorWithTheProviderErrorType() {
    server.enqueue(MockResponse(code = 404, body = ERROR_BODY))

    val exception =
      assertFailsWith<ChatCompletionsApiException> {
        runBlocking { client.createStream(request()).toList() }
      }

    assertThat(exception.statusCode).isEqualTo(404)
    assertThat(exception).hasMessageThat().contains("(invalid_request_error)")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun createStream_retriesBeforeTheFirstEvent() {
    server.enqueue(MockResponse(code = 429, body = ERROR_BODY))
    server.enqueue(sseResponse("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"))

    val chunks = runBlocking { client.createStream(request()).toList() }

    assertThat(chunks).hasSize(1)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun createStream_doesNotRetryAfterTheFirstEvent() {
    // The connection drops after one event; a retry would repeat output already seen.
    server.enqueue(
      MockResponse.Builder()
        .addHeader("content-type", "text/event-stream")
        .body("data: {\"choices\":[]}\n\n" + "data: {\"choices\":[]}\n\n".repeat(200))
        .throttleBody(1_024, 10, TimeUnit.MILLISECONDS)
        .onResponseBody(SocketEffect.ShutdownConnection)
        .build()
    )
    val received = mutableListOf<ChatCompletionResponse>()

    assertFailsWith<IOException> {
      runBlocking { client.createStream(request()).collect { received += it } }
    }

    assertThat(received).isNotEmpty()
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun retryAfter_readsMillisecondsSecondsAndHttpDates() {
    fun headers(vararg values: Pair<String, String>) =
      HttpHeaders.of(values.groupBy({ it.first }, { it.second })) { _, _ -> true }
    val now = Instant.parse("2026-01-01T00:00:00Z")

    assertThat(retryAfter(headers("retry-after-ms" to "1500"), now)).isEqualTo(1500.milliseconds)
    assertThat(retryAfter(headers("retry-after" to "2"), now)).isEqualTo(2.seconds)
    assertThat(retryAfter(headers("retry-after" to "Thu, 01 Jan 2026 00:00:03 GMT"), now))
      .isEqualTo(3.seconds)
    assertThat(retryAfter(headers("retry-after" to "soon"), now)).isNull()
    assertThat(retryAfter(headers("retry-after-ms" to "NaN", "retry-after" to "NaN"), now)).isNull()
    assertThat(retryAfter(headers(), now)).isNull()
  }

  @Test
  fun defaultRetryDelay_honorsAShortRetryAfterAndOtherwiseBacksOff() {
    assertThat(defaultRetryDelay(0, 3.seconds)).isEqualTo(3.seconds)
    // Jittered by up to a quarter below half a second, doubling per retry, capped at 8 seconds.
    val first = defaultRetryDelay(0, 2.minutes)
    assertThat(first).isAtLeast(375.milliseconds)
    assertThat(first).isAtMost(500.milliseconds)
    val capped = defaultRetryDelay(10, null)
    assertThat(capped).isAtLeast(6.seconds)
    assertThat(capped).isAtMost(8.seconds)
  }

  @Test
  fun createStream_parsesSseChunksAndStopsAtDone() {
    val sse =
      """
      data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}

      data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

      data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

      data: [DONE]

      """
        .trimIndent()
    server.enqueue(sseResponse(sse))

    val chunks = runBlocking { client.createStream(request()).toList() }

    assertThat(chunks).hasSize(3)
    assertThat(chunks.first().choices.single().delta!!.contentText()).isEqualTo("Hi")
    assertThat(chunks.last().usage!!.promptTokens).isEqualTo(3)

    val recorded = server.takeRequest()
    val body = recorded.body?.utf8()
    assertThat(body).contains("\"stream\":true")
    assertThat(body).contains("\"include_usage\":true")
    assertThat(recorded.headers["authorization"]).isEqualTo("Bearer secret-key")
    assertThat(recorded.headers["content-type"]).isEqualTo("application/json")
  }

  @Test
  fun createStream_cancellationAbortsTheExchangeWhileTheBodyStalls() {
    // Headers arrive at once, then the body stalls with no line to deliver.
    val headers =
      "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\n\r\n"
    assertCancellationClosesTheConnection(headers) { it.createStream(request()).toList() }
  }

  /**
   * Cancels [call] against a server that sends [response] and then stalls, and checks that the
   * client closes the connection rather than leaving the exchange running.
   */
  private fun assertCancellationClosesTheConnection(
    response: String = "",
    call: suspend (JdkChatCompletionsClient) -> Unit,
  ) {
    ServerSocket(0).use { listener ->
      val closed = CompletableFuture.runAsync {
        listener.accept().use { socket ->
          socket.getOutputStream().apply { write(response.toByteArray()) }.flush()
          // Returns, or fails with a reset, only once the client closes the connection.
          runCatching {
            val unused = socket.getInputStream().readAllBytes()
          }
        }
      }
      val client =
        JdkChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}"),
        )

      assertFailsWith<TimeoutCancellationException> {
        runBlocking { withTimeout(500) { call(client) } }
      }

      closed.get(5, TimeUnit.SECONDS)
    }
  }

  private fun clientWith(
    apiKey: () -> String?,
    headers: Map<String, String>? = null,
    timeout: Duration? = null,
  ): JdkChatCompletionsClient =
    JdkChatCompletionsClient(
      { bearerAuth(apiKey()) },
      HttpOptions(baseUrl = server.url("/").toString(), headers = headers, timeout = timeout),
      retryDelay = { retry, retryAfter ->
        retryDelays += retry to retryAfter
        Duration.ZERO
      },
    )

  private fun request(): ChatCompletionRequest =
    ChatCompletionRequest(model = "gpt-4o", messages = emptyList())

  private fun jsonResponse(body: String): MockResponse =
    MockResponse(headers = Headers.headersOf("content-type", "application/json"), body = body)

  private fun sseResponse(body: String): MockResponse =
    MockResponse(headers = Headers.headersOf("content-type", "text/event-stream"), body = body)

  private companion object {
    /** An error body whose message must never reach an exception. */
    const val ERROR_BODY =
      """{"error":{"type":"invalid_request_error","message":"secret prompt text"}}"""
  }
}
