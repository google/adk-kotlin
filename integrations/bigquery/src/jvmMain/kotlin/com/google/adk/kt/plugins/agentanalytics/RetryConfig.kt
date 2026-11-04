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

package com.google.adk.kt.plugins.agentanalytics

import com.google.adk.kt.annotations.AdkJavaInteropApi
import java.time.Duration
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * How [BigQueryAgentAnalyticsPlugin] retries a batch of rows that BigQuery failed to write.
 *
 * Each wait is the current delay times a random factor between 1 and 2, capped at [maxDelay]. A
 * retry after an unclear failure such as `DEADLINE_EXCEEDED` can write rows more than once, with
 * the same `event_id`; a timed-out append is not retried, since the client may still write it.
 *
 * @property maxRetries Retries after the first attempt, not negative; `0` turns retrying off.
 * @property initialDelay Delay before the first retry, without the random factor; not negative.
 * @property multiplier Factor the delay grows by after each retry; finite and at least 1.
 * @property maxDelay Ceiling on any single wait; at least [initialDelay].
 */
data class RetryConfig
@JvmOverloads
constructor(
  val maxRetries: Int = 3,
  val initialDelay: Duration = Duration.ofSeconds(1),
  val multiplier: Double = 2.0,
  val maxDelay: Duration = Duration.ofSeconds(10),
) {

  init {
    require(maxRetries >= 0) { "maxRetries must be >= 0, got $maxRetries." }
    require(!initialDelay.isNegative) { "initialDelay must not be negative, got $initialDelay." }
    require(multiplier.isFinite() && multiplier >= 1.0) {
      "multiplier must be finite and >= 1, got $multiplier."
    }
    require(maxDelay >= initialDelay) {
      "maxDelay must be >= initialDelay ($initialDelay), got $maxDelay."
    }
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .maxRetries(maxRetries)
      .initialDelay(initialDelay)
      .multiplier(multiplier)
      .maxDelay(maxDelay)

  /**
   * Fluent builder for [RetryConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var maxRetries: Int = 3
    private var initialDelay: Duration = Duration.ofSeconds(1)
    private var multiplier: Double = 2.0
    private var maxDelay: Duration = Duration.ofSeconds(10)

    fun maxRetries(maxRetries: Int): Builder = apply { this.maxRetries = maxRetries }

    fun initialDelay(initialDelay: Duration): Builder = apply { this.initialDelay = initialDelay }

    fun multiplier(multiplier: Double): Builder = apply { this.multiplier = multiplier }

    fun maxDelay(maxDelay: Duration): Builder = apply { this.maxDelay = maxDelay }

    fun build(): RetryConfig =
      RetryConfig(
        maxRetries = maxRetries,
        initialDelay = initialDelay,
        multiplier = multiplier,
        maxDelay = maxDelay,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
