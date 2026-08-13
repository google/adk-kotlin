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
package com.google.adk.kt.types

import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADK-owned HTTP transport options for calls a [com.google.adk.kt.models.Model] makes to its
 * backend.
 *
 * This is ADK's own type rather than the backend SDK's, so that configuration surfaces such as
 * [com.google.adk.kt.agents.ContextCacheConfig] stay independent of any particular backend.
 * Implementations translate it to whatever their transport expects.
 *
 * @property baseUrl Base URL of the service endpoint. `null` uses the backend's default.
 * @property apiVersion Version of the API to use. `null` uses the backend's default.
 * @property headers Additional HTTP headers to send with the request.
 * @property timeout Request timeout, e.g. `10.seconds`. `null` uses the backend's default.
 */
data class HttpOptions(
  val baseUrl: String? = null,
  val apiVersion: String? = null,
  val headers: Map<String, String>? = null,
  val timeout: Duration? = null,
) {
  /** Returns [timeout] in whole milliseconds, or `null`. Java cannot read [timeout] (mangled). */
  fun timeoutMillis(): Long? = timeout?.inWholeMilliseconds

  /**
   * Fluent builder for [HttpOptions], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var baseUrl: String? = null
    private var apiVersion: String? = null
    private var headers: Map<String, String>? = null
    private var timeout: Duration? = null

    fun baseUrl(baseUrl: String?): Builder = apply { this.baseUrl = baseUrl }

    fun apiVersion(apiVersion: String?): Builder = apply { this.apiVersion = apiVersion }

    fun headers(headers: Map<String, String>?): Builder = apply { this.headers = headers }

    /** Sets [timeout] in milliseconds; the [Duration] constructor param is mangled for Java. */
    fun timeoutMillis(timeoutMillis: Long): Builder = apply {
      this.timeout = timeoutMillis.milliseconds
    }

    fun build(): HttpOptions =
      HttpOptions(baseUrl = baseUrl, apiVersion = apiVersion, headers = headers, timeout = timeout)
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
