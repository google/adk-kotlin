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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.dto.CreateSessionRequestDto
import com.google.adk.kt.sessions.dto.ListEventsResponseDto
import com.google.adk.kt.sessions.dto.ListSessionsResponseDto
import com.google.adk.kt.sessions.dto.OperationDto
import com.google.adk.kt.sessions.dto.SessionDto
import com.google.adk.kt.sessions.dto.SessionEventDto
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy

/**
 * The reasoning engine a client call targets: the Google Cloud [project] and [location] that host
 * it, plus its numeric [id]. [VertexAiSessionsClient] is a stateless transport and takes this per
 * call; [VertexAiSessionService] passes the single engine it was configured with at construction.
 */
internal data class ReasoningEngineRef(val project: String, val location: String, val id: String)

/**
 * Client for the Vertex AI Session Service REST API.
 *
 * It owns the Vertex-specific concerns (the `aiplatform` host, API version, and
 * `projects/{p}/locations/{l}` path prefix) and delegates authentication and the actual HTTP call
 * to a shared [GoogleApiClient]. Session creation is a long-running operation (LRO):
 * [createSession] polls the returned operation until it is `done` and then fetches the materialized
 * session.
 *
 * Every call is scoped to a [ReasoningEngineRef], so the host and `projects/{p}/locations/{l}`
 * prefix are derived per request rather than fixed at construction.
 *
 * Read methods return a [Result]: a successful `null` payload means "not found" (HTTP 404 or an
 * empty body), while any transport, HTTP (non-404), or decoding error is reported as
 * [Result.failure] with the underlying cause. Errors are never swallowed; it is the caller's choice
 * whether to surface or ignore them.
 *
 * @property apiClient The authenticated transport shared with other Google Cloud clients.
 * @property baseUrlOverride Optional host override for testing (e.g. `http://localhost:8080`). It
 *   must be the host root only; the API version and resource path segments are appended here.
 */
@OptIn(FrameworkInternalApi::class)
internal open class VertexAiSessionsClient(
  private val apiClient: GoogleApiClient,
  private val baseUrlOverride: String? = null,
) {

  private fun host(location: String): String =
    baseUrlOverride?.trimEnd('/')
      ?: if (location == "global") {
        "https://aiplatform.googleapis.com"
      } else {
        "https://$location-aiplatform.googleapis.com"
      }

  /** Full URL for [subPath] under the `projects/{p}/locations/{l}` parent of [engine]. */
  private fun locationUrl(engine: ReasoningEngineRef, subPath: String): String =
    "${host(engine.location)}/$API_VERSION/projects/${engine.project}/locations/${engine.location}/" +
      subPath

  /** Full URL for [subPath] under [engine]'s `reasoningEngines/{id}` resource. */
  private fun engineUrl(engine: ReasoningEngineRef, subPath: String): String =
    locationUrl(engine, "reasoningEngines/${engine.id}/$subPath")

  /**
   * Creates a new session for the given reasoning engine.
   *
   * This is a long-running operation: the method polls the operation returned by the create call
   * until completion and then fetches the materialized session.
   *
   * @param engine The reasoning engine that owns the session.
   * @param userId The ID of the user owning the session.
   * @param state Optional initial state map to inject into the session.
   * @return The created [SessionDto], or a [Result.failure] describing why creation failed.
   */
  open suspend fun createSession(
    engine: ReasoningEngineRef,
    userId: String,
    state: Map<String, Any>?,
  ): Result<SessionDto> {
    val requestBody =
      adkJson.encodeToString(
        CreateSessionRequestDto.serializer(),
        CreateSessionRequestDto(userId = userId, sessionState = state?.let { anyToJsonElement(it) }),
      )
    val createResponse =
      postAndDecode(
          engineUrl(engine, "sessions"),
          requestBody,
          OperationDto.serializer(),
          "createSession",
        )
        .getOrElse {
          return Result.failure(it)
        } ?: return Result.failure(IOException("createSession returned an empty response."))

    // The create call returns an LRO whose `name` encodes both the session id and the operation id,
    // e.g. ".../sessions/{sessionId}/operations/{operationId}".
    val operationName =
      createResponse.name
        ?: return Result.failure(IOException("createSession operation is missing a name."))
    val parts = operationName.split("/")
    if (parts.size < 3) {
      return Result.failure(
        IOException("createSession operation name is malformed: $operationName")
      )
    }
    val sessionId = parts[parts.size - 3]
    val operationId = parts.last()

    pollOperation(engine, operationId).getOrElse {
      return Result.failure(it)
    }
    return getSession(engine, sessionId).mapCatching {
      it ?: throw IOException("Session $sessionId was not found after creation.")
    }
  }

  /** Retrieves an existing session, or a successful `null` if it does not exist (HTTP 404). */
  open suspend fun getSession(engine: ReasoningEngineRef, sessionId: String): Result<SessionDto?> =
    getAndDecode(engineUrl(engine, "sessions/$sessionId"), SessionDto.serializer(), "getSession")

  /** Lists sessions associated with a reasoning engine, filtered by user ID. */
  open suspend fun listSessions(
    engine: ReasoningEngineRef,
    userId: String,
  ): Result<ListSessionsResponseDto?> {
    // Send the user id as a quoted filter literal so its contents can't alter the filter, then URL
    // form escape the whole filter for transport.
    val filter = "user_id=" + quoteFilterLiteral(userId)
    val subPath = "sessions?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8.name())
    return getAndDecode(
      engineUrl(engine, subPath),
      ListSessionsResponseDto.serializer(),
      "listSessions",
    )
  }

  /**
   * Wraps a value in an AIP-160 double-quoted string literal (only `\` and `"` need escaping inside
   * the quotes) so its contents can't alter the surrounding filter expression.
   */
  private fun quoteFilterLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /**
   * Lists the events associated with a session.
   *
   * @param filter Optional server-side filter expression (e.g. `timestamp>="..."`). It is URL form
   *   escaped before being appended to the request.
   */
  open suspend fun listEvents(
    engine: ReasoningEngineRef,
    sessionId: String,
    filter: String? = null,
  ): Result<ListEventsResponseDto?> {
    var subPath = "sessions/$sessionId/events"
    if (filter != null) {
      subPath += "?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8.name())
    }
    return getAndDecode(
      engineUrl(engine, subPath),
      ListEventsResponseDto.serializer(),
      "listEvents",
    )
  }

  /** Deletes an existing session; a missing session (HTTP 404) is treated as a successful no-op. */
  open suspend fun deleteSession(engine: ReasoningEngineRef, sessionId: String): Result<Unit> {
    val url = engineUrl(engine, "sessions/$sessionId")
    val response = delete(url)
    val body = response.bodyAsText()
    return if (response.status.isSuccess() || response.status.value == 404) {
      Result.success(Unit)
    } else {
      Result.failure(httpError("deleteSession", response, url, body))
    }
  }

  /** Appends a new event (e.g. user message or model response) to a session. */
  open suspend fun appendEvent(
    engine: ReasoningEngineRef,
    sessionId: String,
    event: SessionEventDto,
  ): Result<Unit> {
    val body = adkJson.encodeToString(SessionEventDto.serializer(), event)
    val url = engineUrl(engine, "sessions/$sessionId:appendEvent")
    val response = post(url, body)
    val responseBody = response.bodyAsText()
    return if (response.status.isSuccess()) {
      Result.success(Unit)
    } else {
      Result.failure(httpError("appendEvent", response, url, responseBody, request = body))
    }
  }

  private suspend fun get(url: String): HttpResponse = apiClient.execute {
    method = HttpMethod.Get
    url(url)
  }

  private suspend fun post(url: String, body: String): HttpResponse = apiClient.execute {
    method = HttpMethod.Post
    url(url)
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  private suspend fun delete(url: String): HttpResponse = apiClient.execute {
    method = HttpMethod.Delete
    url(url)
  }

  private suspend fun <T> getAndDecode(
    url: String,
    deserializer: DeserializationStrategy<T>,
    opName: String,
  ): Result<T?> = decodeResponse(get(url), deserializer, opName, url)

  private suspend fun <T> postAndDecode(
    url: String,
    body: String,
    deserializer: DeserializationStrategy<T>,
    opName: String,
  ): Result<T?> = decodeResponse(post(url, body), deserializer, opName, url)

  /**
   * Reads and decodes an API response into [T].
   *
   * - `2xx` with a non-empty body -> [Result.success] with the decoded value (or a failure if the
   *   body cannot be decoded).
   * - `2xx` with an empty body, or `404` -> [Result.success] with `null` ("not found").
   * - Any other status -> [Result.failure] carrying the HTTP status and response body.
   */
  private suspend fun <T> decodeResponse(
    response: HttpResponse,
    deserializer: DeserializationStrategy<T>,
    opName: String,
    url: String,
  ): Result<T?> {
    val bodyString = response.bodyAsText()
    if (!response.status.isSuccess()) {
      return if (response.status.value == 404) {
        Result.success(null)
      } else {
        Result.failure(httpError(opName, response, url, bodyString))
      }
    }
    if (bodyString.isEmpty()) return Result.success(null)
    return runCatching { adkJson.decodeFromString(deserializer, bodyString) }
  }

  private fun httpError(
    opName: String,
    response: HttpResponse,
    url: String,
    body: String,
    request: String? = null,
  ): IOException {
    val requestPart = if (request != null) " request=${truncate(request)}" else ""
    return IOException(
      "$opName failed: HTTP ${response.status.value} ${response.status.description} url=$url" +
        "$requestPart response=${truncate(body)}"
    )
  }

  private fun truncate(s: String?, max: Int = 2000): String {
    if (s == null) return "<empty>"
    return if (s.length <= max) s else s.take(max) + "...(${s.length - max} more chars truncated)"
  }

  /**
   * Polls a long-running operation until it reports `done` or the retry budget is exhausted.
   *
   * @return [Result.success] once the operation is done, or a [Result.failure] (a poll error or a
   *   [TimeoutException] if it does not complete within [MAX_RETRY_ATTEMPTS]).
   */
  private suspend fun pollOperation(engine: ReasoningEngineRef, operationId: String): Result<Unit> {
    val url = locationUrl(engine, "operations/$operationId")
    for (attempt in 0 until MAX_RETRY_ATTEMPTS) {
      val op =
        getAndDecode(url, OperationDto.serializer(), "pollOperation").getOrElse {
          return Result.failure(it)
        }
      if (op?.done == true) {
        return Result.success(Unit)
      }
      delay(POLL_INTERVAL_MILLIS)
    }
    return Result.failure(TimeoutException("Operation $operationId did not complete in time."))
  }

  private companion object {
    private const val API_VERSION = "v1beta1"
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val POLL_INTERVAL_MILLIS = 1000L
  }
}
