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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalContextCachingFeature
import com.google.adk.kt.types.HttpOptions
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for context caching across all agents in an app.
 *
 * When this config is present on an app, context caching is enabled for all agents. When absent
 * (`null`), context caching is disabled.
 *
 * Context caching can significantly reduce costs and improve response times by reusing previously
 * processed context across multiple requests.
 *
 * Constructing this type requires `@OptIn(ExperimentalContextCachingFeature::class)`.
 *
 * @property cacheIntervals Maximum number of invocations to reuse the same cache before refreshing
 *   it. Must be in the range `1..100`. Defaults to 10.
 * @property ttl Time-to-live for the cache. Must be strictly positive. Defaults to 30 minutes.
 * @property minTokens Minimum prior-request tokens required to enable caching. This gates on the
 *   previous request's actual prompt token count (from the model's usage metadata), not an estimate
 *   of the current request. Gemini enforces a hard 4096-token minimum that always applies, so
 *   values below 4096 have no additional effect. No cache is created on the first request of a
 *   session; caching begins on the second turn once a previous token count is known. Context cache
 *   storage may have a cost, so set this higher to avoid caching small requests where the overhead
 *   may exceed the benefits. Must be non-negative. Defaults to 0.
 * @property createHttpOptions Optional HTTP options for the cache-creation call. Set this to add a
 *   timeout on `CachedContent.create()` calls (e.g. `HttpOptions(timeout = 10.seconds)`). When the
 *   cache creation call exceeds the timeout, it fails and the request proceeds without caching.
 *   `null` uses the model backend's default HTTP options.
 */
data class ContextCacheConfig
@ExperimentalContextCachingFeature
constructor(
  val cacheIntervals: Int = 10,
  val ttl: Duration = 1800.seconds,
  val minTokens: Int = 0,
  val createHttpOptions: HttpOptions? = null,
) {
  init {
    require(cacheIntervals in 1..100) {
      "cacheIntervals must be in 1..100, but was $cacheIntervals."
    }
    require(ttl.isPositive()) { "ttl must be positive, but was $ttl." }
    require(minTokens >= 0) { "minTokens must be >= 0, but was $minTokens." }
  }

  /** Returns the TTL in the string format used for cache creation, e.g. `"1800s"`. */
  val ttlString: String
    get() = "${ttl.inWholeSeconds}s"

  /**
   * Fluent builder for [ContextCacheConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @ExperimentalContextCachingFeature
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var cacheIntervals: Int = 10
    private var ttl: Duration = 1800.seconds
    private var minTokens: Int = 0
    private var createHttpOptions: HttpOptions? = null

    fun cacheIntervals(cacheIntervals: Int): Builder = apply {
      this.cacheIntervals = cacheIntervals
    }

    fun ttl(ttl: Duration): Builder = apply { this.ttl = ttl }

    fun minTokens(minTokens: Int): Builder = apply { this.minTokens = minTokens }

    fun createHttpOptions(createHttpOptions: HttpOptions?): Builder = apply {
      this.createHttpOptions = createHttpOptions
    }

    fun build(): ContextCacheConfig =
      ContextCacheConfig(
        cacheIntervals = cacheIntervals,
        ttl = ttl,
        minTokens = minTokens,
        createHttpOptions = createHttpOptions,
      )
  }

  companion object {
    @ExperimentalContextCachingFeature @JvmStatic fun builder(): Builder = Builder()
  }
}
