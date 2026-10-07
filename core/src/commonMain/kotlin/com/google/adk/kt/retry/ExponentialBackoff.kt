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

package com.google.adk.kt.retry

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmStatic
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A [RetryPolicy] that retries every failure with exponentially growing, capped and jittered
 * delays.
 *
 * @property maxAttempts Attempts including the first, so 1 disables retries. Defaults to 5.
 * @property initialDelay Delay before the first retry. Defaults to 1 second.
 * @property maxDelay Upper bound on any single delay. Defaults to 60 seconds.
 * @property backoffFactor Multiplier applied to the delay after each retry. Defaults to 2.0.
 * @property jitter Randomness factor, not a duration: each delay is multiplied by a random factor
 *   in `[1 - jitter, 1 + jitter]`, never going below zero, after being capped low enough that the
 *   result stays within [maxDelay]; 0 removes randomness. Defaults to 1.0.
 */
data class ExponentialBackoff(
  val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
  val maxDelay: Duration = DEFAULT_MAX_DELAY,
  val backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
  val jitter: Double = DEFAULT_JITTER,
) : RetryPolicy {

  init {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, but was $maxAttempts." }
    require(initialDelay.isFinite() && initialDelay >= Duration.ZERO) {
      "initialDelay must be finite and not negative, but was $initialDelay."
    }
    require(maxDelay.isFinite() && maxDelay >= Duration.ZERO) {
      "maxDelay must be finite and not negative, but was $maxDelay."
    }
    require(backoffFactor.isFinite() && backoffFactor >= 0.0) {
      "backoffFactor must be finite and not negative, but was $backoffFactor."
    }
    require(jitter.isFinite() && jitter >= 0.0) {
      "jitter must be finite and not negative, but was $jitter."
    }
  }

  override fun shouldRetry(failure: Exception, failedAttempts: Int): Boolean {
    require(failedAttempts >= 1) { "failedAttempts must be at least 1, but was $failedAttempts." }
    return failedAttempts < maxAttempts
  }

  override fun nextRetryDelay(failedAttempts: Int): Duration {
    require(failedAttempts >= 1) { "failedAttempts must be at least 1, but was $failedAttempts." }
    return exponentialBackoffDelay(
      retryIndex = failedAttempts - 1,
      initialDelay = initialDelay,
      maxDelay = maxDelay,
      backoffFactor = backoffFactor,
      jitter = jitter,
    )
  }

  /** Returns [initialDelay] in whole milliseconds. Java cannot read [initialDelay] (mangled). */
  fun initialDelayMillis(): Long = initialDelay.inWholeMilliseconds

  /** Returns [maxDelay] in whole milliseconds. Java cannot read [maxDelay] (mangled). */
  fun maxDelayMillis(): Long = maxDelay.inWholeMilliseconds

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .maxAttempts(maxAttempts)
      .initialDelay(initialDelay)
      .maxDelay(maxDelay)
      .backoffFactor(backoffFactor)
      .jitter(jitter)

  /**
   * Fluent builder for [ExponentialBackoff], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    private var initialDelay: Duration = DEFAULT_INITIAL_DELAY
    private var maxDelay: Duration = DEFAULT_MAX_DELAY
    private var backoffFactor: Double = DEFAULT_BACKOFF_FACTOR
    private var jitter: Double = DEFAULT_JITTER

    fun maxAttempts(maxAttempts: Int): Builder = apply { this.maxAttempts = maxAttempts }

    // Lets toBuilder copy the delay exactly; initialDelayMillis would truncate it.
    internal fun initialDelay(initialDelay: Duration): Builder = apply {
      this.initialDelay = initialDelay
    }

    /**
     * Sets [ExponentialBackoff.initialDelay] in milliseconds; the [Duration] constructor param is
     * mangled for Java.
     */
    fun initialDelayMillis(initialDelayMillis: Long): Builder = apply {
      this.initialDelay = initialDelayMillis.milliseconds
    }

    // Lets toBuilder copy the delay exactly; maxDelayMillis would truncate it.
    internal fun maxDelay(maxDelay: Duration): Builder = apply { this.maxDelay = maxDelay }

    /**
     * Sets [ExponentialBackoff.maxDelay] in milliseconds; the [Duration] constructor param is
     * mangled for Java.
     */
    fun maxDelayMillis(maxDelayMillis: Long): Builder = apply {
      this.maxDelay = maxDelayMillis.milliseconds
    }

    fun backoffFactor(backoffFactor: Double): Builder = apply { this.backoffFactor = backoffFactor }

    fun jitter(jitter: Double): Builder = apply { this.jitter = jitter }

    fun build(): ExponentialBackoff =
      ExponentialBackoff(
        maxAttempts = maxAttempts,
        initialDelay = initialDelay,
        maxDelay = maxDelay,
        backoffFactor = backoffFactor,
        jitter = jitter,
      )
  }

  companion object {
    // Also the workflow RetryConfig's defaults.
    internal const val DEFAULT_MAX_ATTEMPTS: Int = 5
    internal val DEFAULT_INITIAL_DELAY: Duration = 1.seconds
    internal val DEFAULT_MAX_DELAY: Duration = 60.seconds
    internal const val DEFAULT_BACKOFF_FACTOR: Double = 2.0
    internal const val DEFAULT_JITTER: Double = 1.0

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Returns the delay before retry [retryIndex] (0 for the first retry): [initialDelay] grown by
 * [backoffFactor] per retry, with [jitter] and the [maxDelay] cap applied as [ExponentialBackoff]
 * describes.
 */
internal fun exponentialBackoffDelay(
  retryIndex: Int,
  initialDelay: Duration,
  maxDelay: Duration,
  backoffFactor: Double,
  jitter: Double,
  random: Random = Random.Default,
): Duration {
  // Clamped, as an infinite power times a zero initialDelay would be NaN.
  val delay = initialDelay * backoffFactor.pow(retryIndex).coerceAtMost(Double.MAX_VALUE)
  // Cap before jittering, not after, so jitter keeps spreading delays near the cap.
  val capped = minOf(delay, maxDelay / (1.0 + jitter))
  val spread = if (jitter == 0.0) 0.0 else random.nextDouble(-jitter, jitter)
  return (capped * (1.0 + spread)).coerceIn(Duration.ZERO, maxDelay)
}
