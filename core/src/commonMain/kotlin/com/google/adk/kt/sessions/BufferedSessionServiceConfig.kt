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
import com.google.adk.kt.retry.ExponentialBackoff
import com.google.adk.kt.retry.RetryPolicy
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Configuration for a [BufferedSessionService].
 *
 * @property retryPolicy Decides whether and when to retry a write that the wrapped service failed.
 *   The default retries every failure, waiting up to 30 seconds in all; reads and flushes that need
 *   the write wait meanwhile, as do later `app:` and `user:` changes from other sessions. A policy
 *   whose [RetryPolicy.shouldRetry] rejects failures that will not pass stops sooner.
 * @property cacheConfig Turns on the read cache and configures it; `null`, the default, leaves it
 *   off.
 */
data class BufferedSessionServiceConfig
@JvmOverloads
constructor(
  val retryPolicy: RetryPolicy = ExponentialBackoff(),
  val cacheConfig: SessionCacheConfig? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().retryPolicy(retryPolicy).cacheConfig(cacheConfig)

  /**
   * Fluent builder for [BufferedSessionServiceConfig], provided primarily for Java callers. Any
   * property left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var retryPolicy: RetryPolicy = ExponentialBackoff()
    private var cacheConfig: SessionCacheConfig? = null

    fun retryPolicy(retryPolicy: RetryPolicy): Builder = apply { this.retryPolicy = retryPolicy }

    fun cacheConfig(cacheConfig: SessionCacheConfig?): Builder = apply {
      this.cacheConfig = cacheConfig
    }

    fun build(): BufferedSessionServiceConfig =
      BufferedSessionServiceConfig(retryPolicy = retryPolicy, cacheConfig = cacheConfig)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
