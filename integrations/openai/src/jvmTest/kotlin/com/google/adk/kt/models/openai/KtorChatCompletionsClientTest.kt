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
package com.google.adk.kt.models.openai

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.TrackingHeaders
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.headersOf
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
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
 * Transport-level tests for [KtorChatCompletionsClient] against a [MockWebServer]. They exercise
 * the real Ktor client: URL, bearer auth, JSON decoding, the error contract, and SSE parsing with
 * the `[DONE]` terminator - the wire behavior a fake cannot cover.
 */
@RunWith(JUnit4::class)
class KtorChatCompletionsClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: KtorChatCompletionsClient
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
    // Tracking headers go only to Google APIs, and HTTP/2 is never offered over cleartext.
    assertThat(recorded.headers["x-goog-api-client"]).isNull()
    assertThat(recorded.headers["user-agent"].orEmpty()).doesNotContain("google-adk/")
    assertThat(recorded.headers["upgrade"]).isNull()
    assertThat(recorded.body?.utf8()).contains("\"model\":\"gpt-4o\"")
  }

  @Test
  fun publicConstructors_sendTheirKeyToTheConfiguredEndpoint() {
    val options = HttpOptions(baseUrl = server.url("/v1").toString())
    val request =
      LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "Hi")))))
    repeat(4) { server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}""")) }
    val models =
      listOf(
        ChatCompletions("gpt-4o", "static-key", options),
        ChatCompletions("gpt-4o", options) { "provided-key" },
        ChatCompletions("gpt-4o", httpOptions = options),
        // A blank key counts as unset.
        ChatCompletions("gpt-4o", " ", options),
      )

    val texts = runBlocking {
      models.map { it.generateContent(request, false).toList().single().content!!.parts[0].text }
    }

    assertThat(texts).containsExactly("ok", "ok", "ok", "ok")
    val first = server.takeRequest()
    assertThat(first.target).isEqualTo("/v1/chat/completions")
    assertThat(first.headers["authorization"]).isEqualTo("Bearer static-key")
    assertThat(server.takeRequest().headers["authorization"]).isEqualTo("Bearer provided-key")
    assertThat(server.takeRequest().headers["authorization"]).isNull()
    assertThat(server.takeRequest().headers["authorization"]).isNull()
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
    // Ktor sends the body's content type, so the body is always JSON.
    assertThat(recorded.headers.values("content-type")).containsExactly("application/json")
  }

  @Test
  fun create_timesOutWhenTheBodyStalls() {
    // Headers arrive at once, so only the deadline on the whole attempt can end it.
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

    assertFailsWith<HttpRequestTimeoutException> { runBlocking { client.create(request()) } }

    // A timed-out attempt is retried like a connection failure.
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun create_timeoutClosesTheConnectionWhileTheResponseStalls() {
    assertTimeoutClosesTheConnection {
      val unused = it.create(request())
    }
  }

  @Test
  fun create_timeoutClosesTheConnectionWhileTheBodyStalls() {
    // Declares a body it never sends.
    val headers = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: 64\r\n\r\n"
    assertTimeoutClosesTheConnection(headers) {
      val unused = it.create(request())
    }
  }

  @Test
  fun createStream_timeoutClosesTheConnectionWhileHeadersAreLate() {
    assertTimeoutClosesTheConnection { it.createStream(request()).toList() }
  }

  @Test
  fun sendsTrackingHeaders_onlyToGoogleApis() {
    val vertex =
      "https://aiplatform.googleapis.com/v1/projects/p/locations/global/endpoints/openapi"

    assertThat(sendsTrackingHeaders(vertex)).isTrue()
    assertThat(sendsTrackingHeaders("https://api.openai.com/v1")).isFalse()
    assertThat(sendsTrackingHeaders("https://googleapis.com.example.org/v1")).isFalse()
  }

  @Test
  @OptIn(FrameworkInternalApi::class)
  fun requestHeaders_addsOnlyTheTrackingTagsCallerHeadersLack() {
    val headers =
      requestHeaders(
        provided = mapOf("User-Agent" to "provided/1.0", "Authorization" to "Bearer t"),
        fixed = mapOf("user-agent" to "my-app/1.0", "x-goog-api-client" to "lib/2 google-adk/0.1"),
        tracked = true,
      )

    assertThat(headers["user-agent"]).isEqualTo("my-app/1.0 ${TrackingHeaders.VALUE}")
    assertThat(headers["x-goog-api-client"])
      .isEqualTo("lib/2 google-adk/0.1 gl-kotlin/${KotlinVersion.CURRENT}")
    assertThat(headers["Authorization"]).isEqualTo("Bearer t")
    assertThat(headers.keys.count { it.equals("user-agent", ignoreCase = true) }).isEqualTo(1)
  }

  @Test
  @OptIn(FrameworkInternalApi::class)
  fun create_extendsTheProvidedUserAgentWithTrackingHeaders() {
    val client =
      KtorChatCompletionsClient(
        headersProvider = { mapOf("User-Agent" to "my-app/1.0") },
        httpOptions = HttpOptions(baseUrl = server.url("/").toString()),
        sendTrackingHeaders = true,
      )
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val unused = runBlocking { client.create(request()) }

    val recorded = server.takeRequest()
    assertThat(recorded.headers.values("user-agent"))
      .containsExactly("my-app/1.0 ${TrackingHeaders.VALUE}")
    assertThat(recorded.headers.values("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE)
  }

  @Test
  @OptIn(FrameworkInternalApi::class)
  fun create_addsOnlyTheAdkTagToAUserAgentThatNamesKotlin() {
    val client =
      KtorChatCompletionsClient(
        headersProvider = { mapOf("User-Agent" to "my-app/1.0 gl-kotlin/1.9.0") },
        httpOptions = HttpOptions(baseUrl = server.url("/").toString()),
        sendTrackingHeaders = true,
      )
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val unused = runBlocking { client.create(request()) }

    val recorded = server.takeRequest()
    assertThat(recorded.headers.values("user-agent"))
      .containsExactly("my-app/1.0 gl-kotlin/1.9.0 ${TrackingHeaders.ADK_TAG}")
    assertThat(recorded.headers.values("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE)
  }

  @Test
  fun create_toAnotherProvider_sendsNoTrackingHeaders() {
    val client =
      KtorChatCompletionsClient(
        headersProvider = { mapOf("User-Agent" to "my-app/1.0") },
        httpOptions = HttpOptions(baseUrl = server.url("/").toString()),
      )
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val unused = runBlocking { client.create(request()) }

    val recorded = server.takeRequest()
    assertThat(recorded.headers.values("user-agent")).containsExactly("my-app/1.0")
    assertThat(recorded.headers.values("x-goog-api-client")).isEmpty()
  }

  @Test
  fun createStream_timesOutWhenHeadersNeverArrive() {
    repeat(3) {
      server.enqueue(
        MockResponse.Builder()
          .addHeader("content-type", "text/event-stream")
          .body("data: [DONE]\n\n")
          .headersDelay(30, TimeUnit.SECONDS)
          .build()
      )
    }
    val client = clientWith(apiKey = { null }, timeout = 200.milliseconds)

    assertFailsWith<HttpRequestTimeoutException> {
      runBlocking { client.createStream(request()).toList() }
    }

    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun createStream_timeoutDoesNotEndAStreamWhoseHeadersArrived() {
    server.enqueue(
      MockResponse.Builder()
        .addHeader("content-type", "text/event-stream")
        .body("data: {\"choices\":[]}\n\ndata: [DONE]\n\n")
        .bodyDelay(3, TimeUnit.SECONDS)
        .build()
    )
    val client = clientWith(apiKey = { null }, timeout = 1.seconds)

    val chunks = runBlocking { client.createStream(request()).toList() }

    assertThat(chunks).hasSize(1)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun zeroOrInfiniteTimeout_sendsRequestsWithoutADeadline() =
    runBlocking<Unit> {
      // Zero means none, as in ADK Java, and so does infinite.
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
  fun create_cancellationClosesTheConnectionWhileTheResponseStalls() {
    assertCancellationClosesTheConnection {
      val unused = it.create(request())
    }
  }

  @Test
  fun create_leavesNoThreadThatKeepsTheJvmAlive() {
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val unused = runBlocking { client.create(request()) }

    // OkHttp's default dispatcher threads would hold the JVM open for a minute after the call.
    val transportThreads =
      Thread.getAllStackTraces().keys.filter {
        it.isAlive && !it.isDaemon && (it.name.startsWith("OkHttp") || it.name.startsWith("ADK"))
      }
    assertThat(transportThreads.map { it.name }).isEmpty()
  }

  @Test
  fun create_runsMoreThanFiveCallsToOneHostAtOnce() {
    // OkHttp's default dispatcher runs at most 5 calls per host and queues the rest.
    val calls = 6
    ServerSocket(0).use { listener ->
      val waiting = CountDownLatch(calls)
      val gaveUp = AtomicBoolean()
      thread(isDaemon = true) {
        while (true) {
          val socket = runCatching { listener.accept() }.getOrNull() ?: break
          thread(isDaemon = true) {
            socket.use {
              readRequest(it.getInputStream())
              waiting.countDown()
              // Answers only once every call is in flight, or gives up after 5 seconds.
              if (!waiting.await(5, TimeUnit.SECONDS)) gaveUp.set(true)
              val body = """{"choices":[]}"""
              val head = "HTTP/1.1 200 OK\r\ncontent-length: ${body.length}\r\n\r\n"
              it.getOutputStream().apply { write((head + body).toByteArray()) }.flush()
            }
          }
        }
      }
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}"),
        )

      runBlocking { List(calls) { async { client.create(request()) } }.awaitAll() }

      assertThat(gaveUp.get()).isFalse()
    }
  }

  @Test
  fun constructor_rejectsUnusableOptions() {
    assertFailsWith<IllegalArgumentException> {
      KtorChatCompletionsClient({ emptyMap() }, HttpOptions(apiVersion = "v1"))
    }
    assertFailsWith<IllegalArgumentException> {
      KtorChatCompletionsClient({ emptyMap() }, HttpOptions(timeout = (-1).seconds))
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
  fun create_doesNotRetryWhenTheRetryHintIsFalse() {
    server.enqueue(MockResponse.Builder().code(503).addHeader("x-should-retry", "false").build())
    // A retry would get this response and succeed.
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val exception =
      assertFailsWith<ChatCompletionsApiException> { runBlocking { client.create(request()) } }

    assertThat(exception.statusCode).isEqualTo(503)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun create_followsTheServersRetryHint() {
    // An unknown hint falls back to the status, and `true` retries a 400.
    server.enqueue(MockResponse.Builder().code(503).addHeader("x-should-retry", "maybe").build())
    server.enqueue(MockResponse.Builder().code(400).addHeader("x-should-retry", "true").build())
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val response = runBlocking { client.create(request()) }

    assertThat(response.choices).isEmpty()
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
  fun createStream_failsWithoutRetryWhenTheBodyIsCutAfterAnEvent() {
    ServerSocket(0).use { listener ->
      val connections = AtomicInteger()
      val firstEventSeen = CompletableFuture<Unit>()
      thread(isDaemon = true) {
        while (true) {
          val socket = runCatching { listener.accept() }.getOrNull() ?: break
          connections.incrementAndGet()
          socket.use {
            readRequest(it.getInputStream())
            // Declares twice the body it sends, then drops the connection.
            val body = "data: {\"choices\":[]}\n\n".repeat(20)
            val head =
              "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\n" +
                "content-length: ${body.length * 2}\r\n\r\n"
            it.getOutputStream().apply { write((head + body).toByteArray()) }.flush()
            runCatching { firstEventSeen.get(5, TimeUnit.SECONDS) }
          }
        }
      }
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}"),
          retryDelay = { _, _ -> Duration.ZERO },
        )
      val received = mutableListOf<ChatCompletionResponse>()

      assertFailsWith<IOException> {
        runBlocking {
          withTimeout(10.seconds) {
            client.createStream(request()).collect {
              received += it
              firstEventSeen.complete(Unit)
            }
          }
        }
      }

      // A retry would repeat output the caller has already seen.
      assertThat(received).isNotEmpty()
      assertThat(connections.get()).isEqualTo(1)
    }
  }

  @Test
  fun createStream_failsWhenTheBodyIsCutWhileEventsAreBuffered() {
    // Declares twice the body it sends, then drops the connection at once.
    val body = "data: {\"choices\":[]}\n\n".repeat(2_000)
    val response =
      "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\n" +
        "content-length: ${body.length * 2}\r\n\r\n$body"
    ServerSocket(0).use { listener ->
      thread(isDaemon = true) {
        while (true) {
          val socket = runCatching { listener.accept() }.getOrNull() ?: break
          socket.use {
            readRequest(it.getInputStream())
            it.getOutputStream().apply { write(response.toByteArray()) }.flush()
          }
        }
      }
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}"),
          retryDelay = { _, _ -> Duration.ZERO },
        )

      // Only some attempts see the cut land inside a line read, between Ktor's checks.
      repeat(200) {
        assertFailsWith<IOException> { runBlocking { client.createStream(request()).collect {} } }
      }
    }
  }

  @Test
  fun createStream_failsWhenTheBodyStopsAfterAnEvent() {
    ServerSocket(0).use { listener ->
      val connections = AtomicInteger()
      thread(isDaemon = true) {
        while (true) {
          val socket = runCatching { listener.accept() }.getOrNull() ?: break
          connections.incrementAndGet()
          socket.use {
            readRequest(it.getInputStream())
            val event = "data: {\"choices\":[]}\n\n"
            val head =
              "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\n" +
                "transfer-encoding: chunked\r\n\r\n"
            val chunk = "${event.length.toString(16)}\r\n$event\r\n"
            it.getOutputStream().apply { write((head + chunk).toByteArray()) }.flush()
            // Sends nothing more until the client closes the connection.
            runCatching {
              val unused = it.getInputStream().readAllBytes()
            }
          }
        }
      }
      // The read limit is never below the timeout, so both are short.
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}", timeout = 1.seconds),
          retryDelay = { _, _ -> Duration.ZERO },
          streamReadTimeout = 1.seconds,
        )
      val received = mutableListOf<ChatCompletionResponse>()

      val error =
        assertFailsWith<IOException> {
          runBlocking {
            withTimeout(10.seconds) { client.createStream(request()).collect { received += it } }
          }
        }

      // Not the headers timer's `HttpRequestTimeoutException`; Ktor 3 wraps the socket timeout.
      val causes = generateSequence<Throwable>(error) { it.cause }
      assertThat(causes.any { it is SocketTimeoutException }).isTrue()
      assertThat(received).hasSize(1)
      // A retry would repeat output the caller has already seen.
      assertThat(connections.get()).isEqualTo(1)
    }
  }

  @Test
  fun retryAfter_readsMillisecondsSecondsAndHttpDates() {
    fun headers(vararg values: Pair<String, String>) =
      headersOf(*values.map { (name, value) -> name to listOf(value) }.toTypedArray())
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
      : keep-alive

      data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}

      event: chunk
      id: 2
      data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

      data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

      data: [DONE]

      """
        .trimIndent()
    server.enqueue(sseResponse(sse))

    val chunks = runBlocking { client.createStream(request()).toList() }

    // The comment and the `event:` and `id:` fields carry no data, so they add no chunk.
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
  fun createStream_acceptsCrAndCrlfLineEndings() {
    server.enqueue(
      sseResponse("data: {\"choices\":[]}\r\rdata: {\"choices\":[]}\r\n\r\ndata: [DONE]\r\r")
    )

    val chunks = runBlocking { client.createStream(request()).toList() }

    assertThat(chunks).hasSize(2)
  }

  @Test
  fun createStream_cancellationClosesTheConnectionWhileTheBodyStalls() {
    // One event arrives, then the body stalls.
    val event = "data: {\"choices\":[]}\n\n"
    val response =
      "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\n\r\n" +
        "${event.length.toString(16)}\r\n$event\r\n"
    val firstEvent = CompletableFuture<Unit>()
    assertCancellationClosesTheConnection(response, ready = firstEvent) {
      it.createStream(request()).collect { firstEvent.complete(Unit) }
    }
  }

  /**
   * Runs [call] with a short timeout against a server that sends [response] to each attempt and
   * then stalls. Checks that every timed-out attempt closes its connection, so retries do not pile
   * up.
   */
  private fun assertTimeoutClosesTheConnection(
    response: String = "",
    call: suspend (KtorChatCompletionsClient) -> Unit,
  ) {
    ServerSocket(0).use { listener ->
      // The first attempt and its two retries.
      val closed = CountDownLatch(3)
      // One at a time, so an attempt the client leaves open blocks the ones after it.
      thread(isDaemon = true) {
        while (true) {
          val socket = runCatching { listener.accept() }.getOrNull() ?: break
          socket.use {
            readRequest(it.getInputStream())
            it.getOutputStream().apply { write(response.toByteArray()) }.flush()
            // Returns, or fails with a reset, only once the client closes the connection.
            runCatching {
              val unused = it.getInputStream().readAllBytes()
            }
          }
          closed.countDown()
        }
      }
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(
            baseUrl = "http://localhost:${listener.localPort}",
            timeout = 200.milliseconds,
          ),
          retryDelay = { _, _ -> Duration.ZERO },
        )

      assertFailsWith<HttpRequestTimeoutException> { runBlocking { call(client) } }

      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
    }
  }

  /**
   * Runs [call] against a server that sends [response] and then stalls. Cancels it once the server
   * has the request and [ready] is complete. Checks that the caller gets control back at once and
   * that the client closes the connection.
   */
  private fun assertCancellationClosesTheConnection(
    response: String = "",
    ready: CompletableFuture<*> = CompletableFuture.completedFuture(Unit),
    call: suspend (KtorChatCompletionsClient) -> Unit,
  ) {
    ServerSocket(0).use { listener ->
      val requestReceived = CompletableFuture<Socket>()
      val closed = CompletableFuture<Unit>()
      // Its own thread, since it may block until the end of the test.
      thread(isDaemon = true) {
        runCatching {
          listener.accept().use { socket ->
            // Waits for the request, so the cancel comes once the exchange has started.
            readRequest(socket.getInputStream())
            socket.getOutputStream().apply { write(response.toByteArray()) }.flush()
            requestReceived.complete(socket)
            // Returns, or fails with a reset, once either side closes the connection.
            runCatching {
              val unused = socket.getInputStream().readAllBytes()
            }
          }
        }
        closed.complete(Unit)
      }
      val client =
        KtorChatCompletionsClient(
          { emptyMap() },
          HttpOptions(baseUrl = "http://localhost:${listener.localPort}"),
        )

      runBlocking {
        val running = launch {
          // Cancelling can fail the call; an earlier failure fails the waits below at once.
          val unusedResult =
            runCatching { call(client) }
              .onFailure {
                requestReceived.completeExceptionally(it)
                ready.completeExceptionally(it)
              }
        }
        val unused = withTimeout(5.seconds) { requestReceived.await() }
        val unusedReady = withTimeout(5.seconds) { ready.await() }
        val elapsed = measureTime { running.cancelAndJoin() }
        assertThat(elapsed.inWholeMilliseconds).isLessThan(5_000)
      }

      try {
        closed.get(5, TimeUnit.SECONDS)
      } finally {
        requestReceived.getNow(null)?.close()
      }
    }
  }

  private fun clientWith(
    apiKey: () -> String?,
    headers: Map<String, String>? = null,
    timeout: Duration? = null,
  ): KtorChatCompletionsClient =
    KtorChatCompletionsClient(
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

  /** Reads a request's headers and body, so closing the socket does not reset the response. */
  private fun readRequest(input: InputStream) {
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
      val byte = input.read()
      if (byte < 0) return
      head.append(byte.toChar())
    }
    val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt()
    repeat(length ?: 0) { input.read() }
  }

  private companion object {
    /** An error body whose message must never reach an exception. */
    const val ERROR_BODY =
      """{"error":{"type":"invalid_request_error","message":"secret prompt text"}}"""
  }
}
