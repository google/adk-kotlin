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

package com.google.adk.kt.memory

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.memory.dto.GenerateMemoriesRequestDto
import com.google.adk.kt.memory.dto.MemoryDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesRequestDto
import com.google.adk.kt.memory.dto.RetrieveMemoriesResponseDto
import com.google.adk.kt.serialization.adkJson
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy

/**
 * Client for the Vertex AI Memory Bank REST API (Agent Engine memories) used by
 * [VertexAiMemoryBankService].
 *
 * It owns the Vertex-specific concerns (the `aiplatform` host, API version, and the
 * reasoning-engine parent path) and delegates authentication and the actual HTTP call to a shared
 * [GoogleApiClient], mirroring `com.google.adk.kt.sessions.VertexAiSessionsClient` and
 * [VertexAiRagClient].
 *
 * Calls return a [Result]: a successful `null` payload means "not found" (HTTP 404 or an empty
 * body), while any transport, HTTP (non-404), or decoding error is reported as [Result.failure]
 * with the underlying cause. Errors are never swallowed; it is the caller's choice whether to
 * surface or ignore them. The write methods return a long-running operation whose completion this
 * client does not await (generation runs server-side).
 *
 * @property apiClient The authenticated transport shared with other Google Cloud clients.
 * @property baseUrlOverride Optional host override for testing (host root only; the API version and
 *   resource path are appended here).
 */
@OptIn(FrameworkInternalApi::class)
internal open class VertexAiMemoryBankClient(
  private val apiClient: GoogleApiClient,
  project: String,
  location: String,
  agentEngineId: String,
  baseUrlOverride: String? = null,
) {

  private val host: String =
    baseUrlOverride?.trimEnd('/')
      ?: if (location == "global") {
        "https://aiplatform.googleapis.com"
      } else {
        "https://$location-aiplatform.googleapis.com"
      }

  private val parent = "projects/$project/locations/$location/reasoningEngines/$agentEngineId"

  /** Generates memories (from events or direct memories). */
  open suspend fun generateMemories(request: GenerateMemoriesRequestDto): Result<Unit> {
    val body = adkJson.encodeToString(GenerateMemoriesRequestDto.serializer(), request)
    return postForUnit(memoriesUrl("memories:generate"), body, "generateMemories")
  }

  /** Creates a single memory directly (no server-side extraction). */
  open suspend fun createMemory(memory: MemoryDto): Result<Unit> {
    // The CreateMemory REST binding uses `body: "memory"`, so the Memory object *is* the body.
    val body = adkJson.encodeToString(MemoryDto.serializer(), memory)
    return postForUnit(memoriesUrl("memories"), body, "createMemory")
  }

  /** Retrieves memories for the scope; a successful `null` means "not found" (HTTP 404). */
  open suspend fun retrieveMemories(
    request: RetrieveMemoriesRequestDto
  ): Result<RetrieveMemoriesResponseDto?> {
    val body = adkJson.encodeToString(RetrieveMemoriesRequestDto.serializer(), request)
    return postAndDecode(
      memoriesUrl("memories:retrieve"),
      body,
      RetrieveMemoriesResponseDto.serializer(),
      "retrieveMemories",
    )
  }

  /** Full URL for [subPath] under the reasoning-engine [parent]. */
  private fun memoriesUrl(subPath: String): String = "$host/$API_VERSION/$parent/$subPath"

  private suspend fun post(url: String, body: String): HttpResponse = apiClient.execute {
    method = HttpMethod.Post
    url(url)
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  /** POSTs [body] and maps a 2xx to [Result.success], any other status to a [Result.failure]. */
  private suspend fun postForUnit(url: String, body: String, opName: String): Result<Unit> {
    val response = post(url, body)
    val responseBody = response.bodyAsText()
    return if (response.status.isSuccess()) {
      Result.success(Unit)
    } else {
      Result.failure(httpError(opName, response, url, responseBody, request = body))
    }
  }

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

  private companion object {
    private const val API_VERSION = "v1beta1"
  }
}
