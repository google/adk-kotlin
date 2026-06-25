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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for context caching across all agents in an app.
 *
 * This configuration enables and controls context caching behavior for all LLM agents in an app.
 * When this config is present on an app, context caching is enabled for all agents. When absent
 * (`null`), context caching is disabled.
 *
 * Context caching can significantly reduce costs and improve response times by reusing previously
 * processed context across multiple requests.
 *
 * @property maxInvocations Maximum number of invocations to reuse the same cache before refreshing
 *   it. Must be in the range `1..100`. Defaults to 10.
 * @property ttl Time-to-live for the cache. Must be strictly positive. Defaults to 30 minutes.
 * @property minTokens Minimum estimated request tokens required to enable caching. This compares
 *   against the estimated total tokens of the request (system instruction + tools + contents).
 *   Context cache storage may have a cost, so set this higher to avoid caching small requests where
 *   the overhead may exceed the benefits. Must be non-negative. Defaults to 0.
 */
data class ContextCacheConfig(
  val maxInvocations: Int = 10,
  val ttl: Duration = 1800.seconds,
  val minTokens: Int = 0,
) {
  init {
    require(maxInvocations in 1..100) {
      "maxInvocations must be in 1..100, but was $maxInvocations."
    }
    require(ttl.isPositive()) { "ttl must be positive, but was $ttl." }
    require(minTokens >= 0) { "minTokens must be >= 0, but was $minTokens." }
  }

  /** Returns the TTL in the string format used for cache creation, e.g. `"1800s"`. */
  val ttlString: String
    get() = "${ttl.inWholeSeconds}s"
}
