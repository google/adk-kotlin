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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a node is retried when it raises. Every property is optional: null means "unset" and falls
 * back to the matching `DEFAULT_` constant when the retry is computed, except [exceptions], which
 * has no default. Unset is kept distinct from a set value rather than resolved at construction, so
 * a policy declared with field presence carries the same meaning here as where it was declared.
 *
 * @property maxAttempts Attempts including the first, so 0 or 1 means no retry.
 * @property initialDelay Delay before the first retry.
 * @property maxDelay Ceiling on any single delay.
 * @property backoffFactor Multiplier applied to the delay after each attempt.
 * @property jitter Randomness factor, not a duration: the delay is spread over `delay * (1 +/-
 *   jitter)`. Zero removes randomness.
 * @property exceptions Type names to retry on, matched against the failure's exact reported name
 *   ([errorTypeName]) with no subclass match. Null retries any failure; an empty list retries none.
 */
@ExperimentalWorkflowApi
data class RetryConfig(
  val maxAttempts: Int? = null,
  val initialDelay: Duration? = null,
  val maxDelay: Duration? = null,
  val backoffFactor: Double? = null,
  val jitter: Double? = null,
  val exceptions: List<String>? = null,
) {

  init {
    require(maxAttempts == null || maxAttempts >= 0) {
      "maxAttempts must not be negative; 0 or 1 means no retry, null uses the default."
    }
    require(initialDelay == null || initialDelay >= Duration.ZERO) {
      "initialDelay must not be negative, or null for the default."
    }
    require(maxDelay == null || maxDelay >= Duration.ZERO) {
      "maxDelay must not be negative, or null for the default."
    }
    require(backoffFactor == null || backoffFactor >= 0.0) {
      "backoffFactor must not be negative, or null for the default."
    }
    require(jitter == null || jitter >= 0.0) {
      "jitter must not be negative, or null for the default."
    }
  }

  /**
   * Returns whether a node that raised [error] on [attemptCount] (1-based, counting the first try)
   * has budget left and raised a type this config retries.
   */
  internal fun shouldRetry(error: Throwable, attemptCount: Int): Boolean {
    if (attemptCount >= (maxAttempts ?: DEFAULT_MAX_ATTEMPTS)) return false
    val retryable = exceptions ?: return true
    return errorTypeName(error) in retryable
  }

  /** Returns how long to wait after the failed [attemptCount] before trying again. */
  internal fun delayFor(attemptCount: Int, random: Random = Random.Default): Duration {
    val ceiling = maxDelay ?: DEFAULT_MAX_DELAY
    val spread = jitter ?: DEFAULT_JITTER
    val backoff = (backoffFactor ?: DEFAULT_BACKOFF_FACTOR).pow(max(0, attemptCount - 1))
    var delay = (initialDelay ?: DEFAULT_INITIAL_DELAY) * backoff

    if (spread > 0.0) {
      // Cap before jittering, not after, so jitter keeps spreading delays near the ceiling.
      delay = minOf(delay, ceiling / (1.0 + spread))
      delay = maxOf(Duration.ZERO, delay * (1.0 + random.nextDouble(-spread, spread)))
    }
    return minOf(delay, ceiling)
  }

  companion object {
    const val DEFAULT_MAX_ATTEMPTS: Int = 5
    const val DEFAULT_BACKOFF_FACTOR: Double = 2.0
    const val DEFAULT_JITTER: Double = 1.0
    val DEFAULT_INITIAL_DELAY: Duration = 1.seconds
    val DEFAULT_MAX_DELAY: Duration = 60.seconds

    /**
     * Returns the name a retry policy matches [error] by. Node failures are compared by simple type
     * name across implementations, and [NodeExecutionException] carries the name of the failure it
     * stands in for rather than its own.
     */
    internal fun errorTypeName(error: Throwable): String =
      (error as? NodeExecutionException)?.typeName ?: error::class.simpleName ?: "Exception"
  }
}
