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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for the read cache of a [BufferedSessionService].
 *
 * The cache assumes this service makes every change to the sessions it caches: until an entry
 * expires, a hit misses changes made elsewhere, such as `app:` and `user:` state another instance
 * changed or a session the store deleted. A hit does not wait for buffered writes, so it can show
 * events the store does not hold yet, or never will if their write fails, after which a read goes
 * to the store. The sessions an unfiltered [BufferedSessionService.getSession] returns share their
 * events and state values with the cache, and a hit can differ from what the store returns, such as
 * in event order, event ids or fields the store does not keep.
 *
 * @property ttl How long a session stays cached after it is read from the store, 5 minutes by
 *   default; [Duration.INFINITE] disables expiry.
 * @property maxSessions The most sessions cached at once, 1024 by default; past it, the session
 *   read least recently is dropped.
 */
data class SessionCacheConfig(
  val ttl: Duration = DEFAULT_TTL,
  val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
  init {
    require(ttl > Duration.ZERO) { "ttl must be positive, but was $ttl." }
    require(maxSessions >= 1) { "maxSessions must be at least 1, but was $maxSessions." }
  }

  /**
   * Returns [ttl] in whole milliseconds, or [Long.MAX_VALUE] if it never expires (its getter is
   * mangled for Java).
   */
  fun ttlMillis(): Long = ttl.inWholeMilliseconds

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi fun toBuilder(): Builder = Builder().ttl(ttl).maxSessions(maxSessions)

  /**
   * Fluent builder for [SessionCacheConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var ttl: Duration = DEFAULT_TTL
    private var maxSessions: Int = DEFAULT_MAX_SESSIONS

    // Lets toBuilder keep a sub-millisecond ttl, which the millisecond setter would round to zero.
    internal fun ttl(ttl: Duration): Builder = apply { this.ttl = ttl }

    /**
     * Sets [SessionCacheConfig.ttl] in milliseconds, where [Long.MAX_VALUE] disables expiry; the
     * [Duration] constructor param is mangled for Java.
     */
    fun ttlMillis(ttlMillis: Long): Builder = apply { this.ttl = ttlMillis.milliseconds }

    fun maxSessions(maxSessions: Int): Builder = apply { this.maxSessions = maxSessions }

    fun build(): SessionCacheConfig = SessionCacheConfig(ttl = ttl, maxSessions = maxSessions)
  }

  companion object {
    private val DEFAULT_TTL: Duration = 5.minutes
    private const val DEFAULT_MAX_SESSIONS: Int = 1024

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
