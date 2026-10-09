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

import com.google.adk.kt.VERSION
import com.google.adk.kt.types.HttpOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dispatcher

private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

/** The terminal marker of a Chat Completions SSE stream. */
private const val DONE_SENTINEL = "[DONE]"

/** Bounds a request that never gets a response, while leaving room for slow reasoning models. */
private val REQUEST_TIMEOUT = 10.minutes

/** Retries after the first attempt, as in the OpenAI SDKs. */
private const val MAX_RETRIES = 2

/** One client for every model, so they share its connection pool and threads, as ADK Java does. */
private val sharedHttpClient: HttpClient by lazy {
  HttpClient(OkHttp) {
    engine {
      config {
        // OkHttp's own dispatcher keeps the JVM alive for a minute and caps calls at 5 per host.
        dispatcher(
          Dispatcher(Executors.newCachedThreadPool(::daemonThread)).apply {
            maxRequests = Int.MAX_VALUE
            maxRequestsPerHost = Int.MAX_VALUE
          }
        )
        // OkHttp's 10-second socket timeouts would end long generations; `timeout` bounds calls.
        connectTimeout(java.time.Duration.ZERO)
        readTimeout(java.time.Duration.ZERO)
        writeTimeout(java.time.Duration.ZERO)
      }
    }
    install(HttpTimeout)
  }
}

private fun daemonThread(task: Runnable): Thread =
  Thread(task, "ADK Chat Completions").apply { isDaemon = true }

/** Usage-tracking value, in the format the other ADK SDKs use, for Google APIs. */
private val TRACKING_HEADER = "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"

/** Headers the client or the connection sets, which a caller must not replace. */
private val RESERVED_HEADERS =
  setOf("connection", "content-length", "expect", "host", "transfer-encoding", "upgrade")

/**
 * Chat Completions transport in wire types, so [ChatCompletions] owns the ADK conversion and tests
 * can replace the HTTP client.
 */
internal interface ChatCompletionsClient {
  /** Sends a non-streaming request and returns the full response. */
  suspend fun create(request: ChatCompletionRequest): ChatCompletionResponse

  /** Sends a streaming request and emits each response chunk as it arrives. */
  fun createStream(request: ChatCompletionRequest): Flow<ChatCompletionResponse>
}

/** The bearer `Authorization` header for [key], or none without a key. */
internal fun bearerAuth(key: String?): Map<String, String> =
  key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

/**
 * [ChatCompletionsClient] backed by a Ktor [HttpClient] on the OkHttp engine, as the Gemini model's
 * client is. Cancelling the caller closes the HTTP exchange, except, on Ktor 2, a stream whose body
 * has stalled. [headersProvider] is called before each attempt, so it can hand out a refreshed
 * token; tests pass [retryDelay].
 */
internal class KtorChatCompletionsClient(
  private val headersProvider: () -> Map<String, String>,
  httpOptions: HttpOptions? = null,
  private val retryDelay: (retry: Int, retryAfter: Duration?) -> Duration = ::defaultRetryDelay,
) : ChatCompletionsClient {

  private val endpoint =
    "${(httpOptions?.baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')}/chat/completions"
  private val fixedHeaders = httpOptions?.headers.orEmpty()
  // Zero means no timeout, as in ADK Java, and so does infinite.
  private val timeout =
    (httpOptions?.timeout ?: REQUEST_TIMEOUT).takeIf { it.isPositive() && it.isFinite() }

  init {
    require(httpOptions?.apiVersion == null) {
      "Chat Completions does not use HttpOptions.apiVersion; put the version in baseUrl."
    }
    require(httpOptions?.timeout?.isNegative() != true) {
      "HttpOptions.timeout must not be negative."
    }
    val uri = URI.create(endpoint)
    require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null) {
      "HttpOptions.baseUrl must be an http or https URL."
    }
    // Fails here, not on the first request.
    fixedHeaders.forEach { (name, value) -> checkHeader(name, value) }
  }

  private val trackingHeaders = trackingHeadersFor(endpoint)

  override suspend fun create(request: ChatCompletionRequest): ChatCompletionResponse {
    val body =
      withRetries(request) { httpRequest ->
        // Bounds the whole attempt and closes it, so a retry does not leave it running.
        timeout?.let { limit ->
          // Ktor rejects zero, which a sub-millisecond timeout would round to.
          val millis = limit.inWholeMilliseconds.coerceAtLeast(1)
          httpRequest.timeout {
            requestTimeoutMillis = millis
            // Ends a stalled body read, which Ktor 2's OkHttp engine does not interrupt on cancel.
            socketTimeoutMillis = millis
          }
        }
        sharedHttpClient.prepareRequest(httpRequest).execute { response ->
          val text = response.bodyAsText()
          if (!response.status.isSuccess()) {
            throw StatusException(response.status.value, response.headers, text)
          }
          text
        }
      }
    val decoded = decode(body, "response body")
    // Some providers report a failure as HTTP 200 with an error body; surface it as an error.
    decoded.error?.let {
      throw ChatCompletionsApiException(
        "Chat Completions API error: ${it.type ?: it.code ?: "error"}"
      )
    }
    return decoded
  }

  override fun createStream(request: ChatCompletionRequest): Flow<ChatCompletionResponse> {
    val streaming = request.copy(stream = true, streamOptions = StreamOptions(includeUsage = true))
    return flow {
        // Once an event has arrived, a retry would repeat output the caller has already seen.
        var started = false
        withRetries(streaming, canRetry = { !started }) { httpRequest ->
          serverSentData(httpRequest).collect {
            started = true
            emit(it)
          }
        }
      }
      .takeWhile { it != DONE_SENTINEL }
      .map { decode(it, "stream event") }
  }

  /**
   * Runs [attempt] on [request], built afresh each time, retrying a connection failure or a
   * retryable status as the OpenAI SDKs do, unless [canRetry] says the attempt has made progress.
   */
  private suspend fun <T> withRetries(
    request: ChatCompletionRequest,
    canRetry: () -> Boolean = { true },
    attempt: suspend (HttpRequestBuilder) -> T,
  ): T {
    var retries = 0
    while (true) {
      // Outside the try, so a failing headers provider is not taken for a connection failure.
      val httpRequest = buildRequest(request)
      try {
        return attempt(httpRequest)
      } catch (e: StatusException) {
        if (!e.retryable || retries == MAX_RETRIES || !canRetry()) throw e.error
        delay(retryDelay(retries++, e.retryAfter))
      } catch (e: IOException) {
        if (retries == MAX_RETRIES || !canRetry()) throw e
        delay(retryDelay(retries++, null))
      }
    }
  }

  /**
   * Streams the `data:` payloads of a server-sent-events response. The timeout covers only the wait
   * for the headers, since a stream can run for minutes; Ktor's request timeout would end it.
   */
  private fun serverSentData(request: HttpRequestBuilder): Flow<String> = flow {
    // A local, so the timer coroutine does not capture `this`, which is not thread-safe.
    val url = endpoint
    coroutineScope {
      val headersTimer = timeout?.let {
        launch {
          delay(it)
          throw HttpRequestTimeoutException(url, it.inWholeMilliseconds)
        }
      }
      sharedHttpClient.prepareRequest(request).execute { response ->
        headersTimer?.cancel()
        if (!response.status.isSuccess()) {
          // The error body names the provider's error type.
          throw StatusException(response.status.value, response.headers, response.bodyAsText())
        }
        val body = response.bodyAsChannel()
        while (true) {
          // Deprecated in Ktor 3 for `readLine`, which Ktor 2 lacks.
          @Suppress("DEPRECATION") val line = body.readUTF8Line()?.trim() ?: break
          if (!line.startsWith("data:")) continue
          val payload = line.removePrefix("data:").trim()
          if (payload.isNotEmpty()) emit(payload)
        }
      }
    }
  }

  /**
   * Builds the HTTP request. Fixed headers replace provided ones, and either can replace the
   * tracking headers, but `content-type` is always JSON.
   */
  private suspend fun buildRequest(request: ChatCompletionRequest): HttpRequestBuilder {
    // The provider may block, for example to refresh a token.
    val provided = runInterruptible(Dispatchers.IO) { headersProvider() }
    provided.forEach { (name, value) -> checkHeader(name, value) }
    return HttpRequestBuilder().apply {
      method = HttpMethod.Post
      url(endpoint)
      trackingHeaders.forEach { (name, value) -> headers[name] = value }
      provided.forEach { (name, value) -> headers[name] = value }
      fixedHeaders.forEach { (name, value) -> headers[name] = value }
      headers.remove(HttpHeaders.ContentType)
      setBody(
        TextContent(chatCompletionsJson.encodeToString(request), ContentType.Application.Json)
      )
    }
  }
}

/**
 * The usage-tracking headers, sent only to Google APIs, such as Vertex AI, as the Claude backend
 * does; other providers get none.
 */
internal fun trackingHeadersFor(url: String): Map<String, String> =
  if (URI.create(url).host?.lowercase()?.endsWith(".googleapis.com") == true) {
    mapOf("user-agent" to TRACKING_HEADER, "x-goog-api-client" to TRACKING_HEADER)
  } else {
    emptyMap()
  }

/**
 * Fails for a header the client cannot send as given, naming only the header, since its value may
 * be a key.
 */
private fun checkHeader(name: String, value: String) {
  require(
    name.isNotEmpty() &&
      name.all { it in '!'..'~' && it !in "\"(),/:;<=>?@[\\]{}" } &&
      name.lowercase() !in RESERVED_HEADERS &&
      value.all { it == '\t' || it in ' '..'~' }
  ) {
    "Invalid HTTP header: $name"
  }
}

/**
 * A response with an error status, carrying the [error] to throw and what decides a retry; it never
 * leaves the client.
 */
private class StatusException(status: Int, headers: Headers, body: String) : Exception() {
  // As in the OpenAI SDKs, the server's `x-should-retry` hint wins over the status.
  val retryable =
    when (headers["x-should-retry"]) {
      "true" -> true
      "false" -> false
      else -> status == 408 || status == 409 || status == 429 || status >= 500
    }
  val retryAfter = retryAfter(headers)
  val error =
    ChatCompletionsApiException(
      "Chat Completions API error: HTTP $status" + errorType(body)?.let { " ($it)" }.orEmpty(),
      statusCode = status,
    )
}

/**
 * The provider's error type, code or status from an error body, never its message, which can hold
 * user content.
 */
private fun errorType(body: String): String? {
  val root = runCatching { chatCompletionsJson.parseToJsonElement(body) }.getOrNull()
  // Google APIs, such as Vertex AI, wrap the error in an array and name it by `status`.
  val wrapper = (root as? JsonArray)?.firstOrNull() ?: root
  val error = (wrapper as? JsonObject)?.get("error") as? JsonObject ?: return null
  return listOf("type", "code", "status").firstNotNullOfOrNull { key ->
    (error[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
  }
}

/**
 * The wait a server asks for in `retry-after-ms`, or in `retry-after` as seconds or an HTTP date.
 */
internal fun retryAfter(headers: Headers, now: Instant = Instant.now()): Duration? {
  headers["retry-after-ms"]?.toNumberOrNull()?.let {
    return it.milliseconds
  }
  val value = headers["retry-after"] ?: return null
  value.toNumberOrNull()?.let {
    return it.seconds
  }
  return runCatching {
      java.time.Duration.between(
          now,
          ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME),
        )
        .toKotlinDuration()
    }
    .getOrNull()
}

// `toDoubleOrNull` accepts "NaN", which no `Duration` can hold.
private fun String.toNumberOrNull(): Double? = toDoubleOrNull()?.takeUnless { it.isNaN() }

/**
 * The delay before retry [retry], counted from 0, as in the OpenAI SDKs: a server's [retryAfter] up
 * to a minute, else a jittered exponential backoff from half a second to eight seconds.
 */
internal fun defaultRetryDelay(retry: Int, retryAfter: Duration?): Duration {
  if (retryAfter != null && retryAfter.isPositive() && retryAfter <= 60.seconds) return retryAfter
  val backoff = (0.5.seconds * 2.0.pow(retry)).coerceAtMost(8.seconds)
  return backoff * (1 - 0.25 * Random.nextDouble())
}

/**
 * Decodes a response payload. A malformed one fails with only its [kind], since the decoder's own
 * message can embed the body, which must never surface in an error.
 */
@Suppress("UnusedException") // Chaining the cause would leak the body.
private fun decode(payload: String, kind: String): ChatCompletionResponse =
  try {
    chatCompletionsJson.decodeFromString<ChatCompletionResponse>(payload)
  } catch (e: SerializationException) {
    throw ChatCompletionsApiException("Chat Completions API error: malformed $kind")
  }
