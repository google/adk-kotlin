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
package com.google.adk.kt.models

import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

/**
 * Metadata for the context cache associated with LLM responses.
 *
 * This stores cache identification, usage tracking, and lifecycle information for a particular
 * cache instance. It can be in one of two states:
 * 1. **Active cache state:** [cacheName] is set and [expireTime], [invocationsUsed], and
 *    [createdAt] are populated.
 * 2. **Fingerprint-only state:** [cacheName] is `null` and only [fingerprint] and [contentsCount]
 *    are set, used for prefix matching before a cache exists.
 *
 * Token counts (cached and total) are available in [LlmResponse.usageMetadata] and should be
 * accessed from there to avoid duplication.
 *
 * @property fingerprint Hash of the cacheable contents (system instruction + tools + contents).
 *   Always present for prefix matching.
 * @property contentsCount Number of contents. When an active cache exists this is the count of
 *   cached contents; otherwise it is the count of the cacheable content prefix used for
 *   fingerprinting. Must be non-negative.
 * @property cacheName Full resource name of the cached content (e.g.
 *   `projects/123/locations/us-central1/cachedContents/456`). `null` when no active cache exists.
 * @property expireTime Epoch milliseconds when the cache expires. `null` when no active cache
 *   exists.
 * @property invocationsUsed Number of invocations this cache has been used for. `null` when no
 *   active cache exists. Must be non-negative when set.
 * @property createdAt Epoch milliseconds when the cache was created. `null` when no active cache
 *   exists.
 */
@Serializable
data class CacheMetadata(
  val fingerprint: String,
  val contentsCount: Int,
  val cacheName: String? = null,
  val expireTime: Long? = null,
  val invocationsUsed: Int? = null,
  val createdAt: Long? = null,
) {
  init {
    require(contentsCount >= 0) { "contentsCount must be >= 0, but was $contentsCount." }
    invocationsUsed?.let { require(it >= 0) { "invocationsUsed must be >= 0, but was $it." } }
    val activeFlags = listOf(cacheName != null, expireTime != null, invocationsUsed != null)
    require(activeFlags.all { it } || activeFlags.none { it }) {
      "cacheName, expireTime, and invocationsUsed must all be set (active cache) or all be null " +
        "(fingerprint-only state)."
    }
  }

  /** Whether this metadata refers to an active cache, as opposed to a fingerprint-only state. */
  val isActive: Boolean
    get() = cacheName != null

  /**
   * Whether the cache will expire within [EXPIRY_BUFFER]. Always `false` for fingerprint-only
   * metadata (which has no [expireTime]).
   */
  val expireSoon: Boolean
    get() {
      val expiry = expireTime ?: return false
      return Clock.System.now().toEpochMilliseconds() > expiry - EXPIRY_BUFFER.inWholeMilliseconds
    }

  override fun toString(): String {
    val name =
      cacheName
        ?: return "Fingerprint-only: $contentsCount contents, " +
          "fingerprint=${fingerprint.take(8)}..."
    val cacheId = name.substringAfterLast("/")
    val minutesToExpiry = (expireTime!! - Clock.System.now().toEpochMilliseconds()) / 60_000.0
    val roundedMinutes = round(minutesToExpiry * 10) / 10
    return "Cache $cacheId: used $invocationsUsed invocations, cached $contentsCount contents, " +
      "expires in ${roundedMinutes}min"
  }

  private companion object {
    // Buffer applied when checking expiry so a cache nearing expiry is treated as expired.
    val EXPIRY_BUFFER = 2.minutes
  }
}
