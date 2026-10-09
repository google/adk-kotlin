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

import com.google.adk.kt.VERSION
import com.google.adk.kt.types.HttpOptions
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.optionals.getOrNull
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

/** The terminal marker of a Chat Completions SSE stream. */
private const val DONE_SENTINEL = "[DONE]"

/** Bounds a request that never gets a response, while leaving room for slow reasoning models. */
private val REQUEST_TIMEOUT = 10.minutes

/** Retries after the first attempt, as in the OpenAI SDKs. */
private const val MAX_RETRIES = 2

/** One client for every model, so they share its connection pool and threads, as ADK Java does. */
private val sharedHttpClient: HttpClient by lazy { HttpClient.newHttpClient() }

/** Usage-tracking `user-agent` shared across ADK SDKs. */
private val TRACKING_USER_AGENT = "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"

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
 * [ChatCompletionsClient] backed by the JDK [HttpClient]; it streams SSE without any extra
 * dependency. Cancelling the caller aborts an in-flight request in both modes. [headersProvider] is
 * called before each attempt, so it can hand out a refreshed token; tests pass [retryDelay].
 */
internal class JdkChatCompletionsClient(
  private val headersProvider: () -> Map<String, String>,
  httpOptions: HttpOptions? = null,
  private val httpClient: HttpClient = sharedHttpClient,
  private val retryDelay: (retry: Int, retryAfter: Duration?) -> Duration = ::defaultRetryDelay,
) : ChatCompletionsClient {

  private val endpoint =
    URI.create("${(httpOptions?.baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')}/chat/completions")
  private val fixedHeaders = httpOptions?.headers.orEmpty()
  // Zero means no timeout, as in ADK Java, and so does infinite, on which the JDK client overflows.
  private val timeout =
    (httpOptions?.timeout ?: REQUEST_TIMEOUT).takeIf { it.isPositive() && it.isFinite() }

  init {
    require(httpOptions?.apiVersion == null) {
      "Chat Completions does not use HttpOptions.apiVersion; put the version in baseUrl."
    }
    require(httpOptions?.timeout?.isNegative() != true) {
      "HttpOptions.timeout must not be negative."
    }
    // Fails here, not on the first request, for a header the JDK client does not allow.
    with(HttpRequest.newBuilder(endpoint)) {
      fixedHeaders.forEach { (name, value) -> setHeaderHidingValue(name, value) }
    }
  }

  override suspend fun create(request: ChatCompletionRequest): ChatCompletionResponse {
    val response =
      withRetries(request) { httpRequest ->
        // The JDK timeout ends once headers arrive, so it alone would not bound a stalled body.
        val attempt =
          withTimeoutOrNull(timeout ?: Duration.INFINITE) { send(httpRequest) }
            ?: throw HttpTimeoutException("Chat Completions request timed out")
        if (attempt.statusCode() !in 200..299) {
          throw StatusException(attempt.statusCode(), attempt.headers(), attempt.body())
        }
        attempt
      }
    val decoded = decode(response.body(), "response body")
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
   * Sends [request], aborting the exchange when the caller is cancelled, which awaiting the future
   * would not do.
   */
  private suspend fun send(request: HttpRequest): HttpResponse<String> {
    val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { future.cancel(true) }
      future.whenComplete { response, error ->
        if (error == null) {
          continuation.resume(checkNotNull(response))
        } else {
          continuation.resumeWithException(error.unwrapped())
        }
      }
    }
  }

  /**
   * Runs [attempt] on [request], built afresh each time, retrying a connection failure or a
   * retryable status as the OpenAI SDKs do, unless [canRetry] says the attempt has made progress.
   */
  private suspend fun <T> withRetries(
    request: ChatCompletionRequest,
    canRetry: () -> Boolean = { true },
    attempt: suspend (HttpRequest) -> T,
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
   * Streams the `data:` payloads of a server-sent-events response. The client's own threads push
   * lines into an unbounded buffer, so they never block, and cancelling the collector cancels the
   * subscription.
   */
  private fun serverSentData(request: HttpRequest): Flow<String> =
    callbackFlow {
        val subscription = AtomicReference<Subscription?>()
        val lines =
          object : Subscriber<String> {
            override fun onSubscribe(s: Subscription) {
              subscription.set(s)
              s.request(Long.MAX_VALUE)
            }

            override fun onNext(line: String) {
              val trimmed = line.trim()
              if (!trimmed.startsWith("data:")) return
              val payload = trimmed.removePrefix("data:").trim()
              // Never block: HTTP/2 holds a lock while delivering.
              if (payload.isNotEmpty() && trySend(payload).isFailure) subscription.get()?.cancel()
            }

            override fun onError(throwable: Throwable) {
              close(throwable)
            }

            override fun onComplete() {
              close()
            }
          }
        val response =
          httpClient.sendAsync(request) { info ->
            if (info.statusCode() in 200..299) {
              HttpResponse.BodySubscribers.fromLineSubscriber(lines)
            } else {
              // The error body names the provider's error type.
              HttpResponse.BodySubscribers.mapping<String, Void?>(
                HttpResponse.BodySubscribers.ofString(Charsets.UTF_8)
              ) { body ->
                close(StatusException(info.statusCode(), info.headers(), body))
                null
              }
            }
          }
        // A failure before the body, such as a refused connection, arrives only on the future.
        response.whenComplete { _, error -> if (error != null) close(error.unwrapped()) }
        awaitClose {
          subscription.get()?.cancel()
          response.cancel(true)
        }
      }
      .buffer(Channel.UNLIMITED)

  /**
   * Builds the HTTP request. Fixed headers replace provided ones, and either can replace
   * `user-agent`, but `content-type` is always JSON.
   */
  private suspend fun buildRequest(request: ChatCompletionRequest): HttpRequest {
    // The provider may block, for example to refresh a token.
    val provided = runInterruptible(Dispatchers.IO) { headersProvider() }
    val builder =
      HttpRequest.newBuilder(endpoint)
        .setHeader("user-agent", TRACKING_USER_AGENT)
        .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsJson.encodeToString(request)))
    timeout?.let { builder.timeout(it.toJavaDuration()) }
    provided.forEach { (name, value) -> builder.setHeaderHidingValue(name, value) }
    fixedHeaders.forEach { (name, value) -> builder.setHeaderHidingValue(name, value) }
    return builder.setHeader("content-type", "application/json").build()
  }
}

/** Sets a header, keeping its value, which may be a key, out of the JDK's error message. */
@Suppress("UnusedException") // Chaining the cause would leak the header value.
private fun HttpRequest.Builder.setHeaderHidingValue(name: String, value: String) {
  try {
    setHeader(name, value)
  } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid HTTP header: $name")
  }
}

private fun Throwable.unwrapped(): Throwable =
  if (this is CompletionException) cause ?: this else this

/**
 * A response with an error status, carrying the [error] to throw and what decides a retry; it never
 * leaves the client.
 */
private class StatusException(status: Int, headers: HttpHeaders, body: String) : Exception() {
  // As in the OpenAI SDKs, the server's `x-should-retry` hint wins over the status.
  val retryable =
    when (headers.firstValue("x-should-retry").getOrNull()) {
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
internal fun retryAfter(headers: HttpHeaders, now: Instant = Instant.now()): Duration? {
  headers.firstValue("retry-after-ms").getOrNull()?.toNumberOrNull()?.let {
    return it.milliseconds
  }
  val value = headers.firstValue("retry-after").getOrNull() ?: return null
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
