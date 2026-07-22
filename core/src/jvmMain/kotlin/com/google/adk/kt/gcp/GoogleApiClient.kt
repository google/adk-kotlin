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

package com.google.adk.kt.gcp

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A thin authenticated transport for Google Cloud REST APIs.
 *
 * It owns the ktor [HttpClient] and the OAuth [GoogleCredentials] and injects a fresh bearer token
 * (plus the `x-goog-user-project` quota-project header when the credentials declare one) into every
 * request. It is service-agnostic: callers configure the full request (URL, method, body) through
 * [execute], so a single instance can back any Google Cloud API (e.g. Vertex AI sessions or RAG).
 *
 * @property httpClient The underlying ktor [HttpClient].
 * @property credentials The Google credentials used for authentication.
 */
internal class GoogleApiClient(
  private val httpClient: HttpClient = HttpClient(Java),
  private val credentials: GoogleCredentials = defaultCredentials(),
) {

  /**
   * Executes a request configured by [block] after adding the `Authorization` bearer token and,
   * when present, the `x-goog-user-project` quota-project header. The token refresh (which may
   * block) runs on the IO dispatcher; the request itself is non-blocking.
   */
  suspend fun execute(block: HttpRequestBuilder.() -> Unit): HttpResponse {
    val token =
      withContext(Dispatchers.IO) {
        credentials.refreshIfExpired()
        credentials.accessToken.tokenValue
      }
    val quotaProject = credentials.quotaProjectId
    return httpClient.request {
      header(HttpHeaders.Authorization, "Bearer $token")
      if (quotaProject != null) {
        header(QUOTA_PROJECT_HEADER, quotaProject)
      }
      block()
    }
  }

  companion object {
    private const val QUOTA_PROJECT_HEADER = "x-goog-user-project"

    /** Application-default credentials scoped for Google Cloud Platform. */
    fun defaultCredentials(): GoogleCredentials =
      GoogleCredentials.getApplicationDefault()
        .createScoped("https://www.googleapis.com/auth/cloud-platform")
  }
}
