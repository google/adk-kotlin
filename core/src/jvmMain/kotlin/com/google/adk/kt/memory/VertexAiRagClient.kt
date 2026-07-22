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
import com.google.adk.kt.memory.dto.RagFileDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.RetrieveContextsResponseDto
import com.google.adk.kt.memory.dto.UploadRagFileMetadataDto
import com.google.adk.kt.serialization.adkJson
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.DeserializationStrategy

/**
 * Client for the Vertex AI RAG REST API used by [VertexAiRagMemoryService].
 *
 * It owns the Vertex-specific concerns (the `aiplatform` host, API version, and the two request
 * shapes) and delegates authentication and the actual HTTP call to a shared [GoogleApiClient],
 * mirroring `com.google.adk.kt.sessions.VertexAiSessionsClient`.
 *
 * Calls return a [Result]: a successful `null` payload means "not found" (HTTP 404 or an empty
 * body), while any transport, HTTP (non-404), or decoding error is reported as [Result.failure]
 * with the underlying cause. Errors are never swallowed; it is the caller's choice whether to
 * surface or ignore them.
 *
 * @property apiClient The authenticated transport shared with other Google Cloud clients.
 * @property project The Google Cloud project id.
 * @property location The Google Cloud location. The special value `"global"` selects the global,
 *   non-region-prefixed endpoint.
 * @property baseUrlOverride Optional host override for testing (e.g. `http://localhost:8080`). It
 *   must be the host root only; the API version and resource path segments are appended here.
 */
@OptIn(FrameworkInternalApi::class)
internal open class VertexAiRagClient(
  private val apiClient: GoogleApiClient,
  private val project: String,
  private val location: String,
  private val baseUrlOverride: String? = null,
) {

  private val host: String =
    baseUrlOverride?.trimEnd('/')
      ?: if (location == "global") {
        "https://aiplatform.googleapis.com"
      } else {
        "https://$location-aiplatform.googleapis.com"
      }

  /**
   * Retrieves relevant contexts for [request] from the location `projects/{p}/locations/{l}`.
   *
   * @return [Result.success] with the decoded response, or with `null` when the service reports
   *   "not found" (HTTP 404 or an empty body). Any transport, HTTP (non-404), or decode error is a
   *   [Result.failure] carrying the cause.
   */
  open suspend fun retrieveContexts(
    request: RetrieveContextsRequestDto
  ): Result<RetrieveContextsResponseDto?> {
    val body = adkJson.encodeToString(RetrieveContextsRequestDto.serializer(), request)
    val url = "$host/$API_VERSION/projects/$project/locations/$location:retrieveContexts"
    val response = apiClient.execute {
      method = HttpMethod.Post
      url(url)
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    return decodeResponse(
      response,
      RetrieveContextsResponseDto.serializer(),
      "retrieveContexts",
      url,
    )
  }

  /**
   * Uploads [content] as a single RAG file into [corpusName] under the given [displayName].
   *
   * Uses the resumable media-upload endpoint (`/upload/...:upload`) with the `multipart` protocol:
   * a `metadata` JSON part carrying the [displayName] and a `file` part carrying the bytes.
   *
   * @param corpusName The full corpus resource name (`projects/{p}/locations/{l}/ragCorpora/{id}`).
   * @return [Result.success] on a 2xx response, or a [Result.failure] carrying the HTTP error.
   */
  open suspend fun uploadRagFile(
    corpusName: String,
    displayName: String,
    content: String,
  ): Result<Unit> {
    val metadata =
      adkJson.encodeToString(
        UploadRagFileMetadataDto.serializer(),
        UploadRagFileMetadataDto(ragFile = RagFileDto(displayName = displayName)),
      )
    val parts = formData {
      append(
        "metadata",
        metadata,
        Headers.build { append(HttpHeaders.ContentType, "application/json; charset=UTF-8") },
      )
      append(
        "file",
        content.toByteArray(Charsets.UTF_8),
        Headers.build {
          append(HttpHeaders.ContentType, "text/plain")
          append(HttpHeaders.ContentDisposition, "filename=\"$UPLOAD_FILE_NAME\"")
        },
      )
    }
    val url = "$host/upload/$API_VERSION/$corpusName/ragFiles:upload"
    val response = apiClient.execute {
      method = HttpMethod.Post
      url(url)
      header(UPLOAD_PROTOCOL_HEADER, UPLOAD_PROTOCOL_MULTIPART)
      setBody(MultiPartFormDataContent(parts))
    }
    val responseBody = response.bodyAsText()
    return if (response.status.isSuccess()) {
      Result.success(Unit)
    } else {
      Result.failure(httpError("uploadRagFile", response, url, responseBody))
    }
  }

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
  ): IOException =
    IOException(
      "$opName failed: HTTP ${response.status.value} ${response.status.description} url=$url" +
        " response=${truncate(body)}"
    )

  private fun truncate(s: String?, max: Int = 2000): String {
    if (s == null) return "<empty>"
    return if (s.length <= max) s else s.take(max) + "...(${s.length - max} more chars truncated)"
  }

  private companion object {
    private const val API_VERSION = "v1beta1"
    private const val UPLOAD_PROTOCOL_HEADER = "X-Goog-Upload-Protocol"
    private const val UPLOAD_PROTOCOL_MULTIPART = "multipart"
    private const val UPLOAD_FILE_NAME = "memory.txt"
  }
}
