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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.retry.ExponentialBackoff
import com.google.adk.kt.retry.exponentialBackoffDelay
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a node is retried when it raises.
 *
 * @property maxAttempts Attempts including the first, so 0 or 1 means no retry.
 * @property initialDelay Delay before the first retry.
 * @property maxDelay Ceiling on any single delay.
 * @property backoffFactor Multiplier applied to the delay after each attempt.
 * @property jitter Randomness factor, not a duration: the delay is spread over `delay * (1 +/-
 *   jitter)`. Zero removes randomness.
 * @property exceptions Simple class names of failures to retry on, such as "NodeTimeoutException",
 *   matched exactly with no subclass matching. A failure is retried when it matches this list or
 *   [exceptionClasses], and any failure is retried when both are null; a
 *   [WorkflowConfigurationException] is never retried, even when listed.
 * @property exceptionClasses Failure types to retry on, each also matching its subclasses. A
 *   failure is retried when it matches this list or [exceptions], and any failure is retried when
 *   both are null. A [WorkflowConfigurationException] is never retried, even when its class is
 *   listed.
 */
@ExperimentalWorkflowApi
data class RetryConfig(
  val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
  val maxDelay: Duration = DEFAULT_MAX_DELAY,
  val backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
  val jitter: Double = DEFAULT_JITTER,
  val exceptions: List<String>? = null,
  val exceptionClasses: List<KClass<out Exception>>? = null,
) {

  init {
    require(maxAttempts >= 0) { "maxAttempts must not be negative; 0 or 1 means no retry." }
    require(initialDelay >= Duration.ZERO) { "initialDelay must not be negative." }
    require(maxDelay >= Duration.ZERO) { "maxDelay must not be negative." }
    require(backoffFactor.isFinite() && backoffFactor >= 0.0) {
      "backoffFactor must be finite and not negative."
    }
    require(jitter.isFinite() && jitter >= 0.0) { "jitter must be finite and not negative." }
  }

  /**
   * Returns whether a node that raised [error] on [attemptCount] (1-based, counting the first try)
   * has budget left and raised a type this config retries.
   */
  internal fun shouldRetry(error: Throwable, attemptCount: Int): Boolean {
    if (attemptCount >= maxAttempts || error is WorkflowConfigurationException) return false
    if (exceptions == null && exceptionClasses == null) return true
    return errorTypeName(error) in exceptions.orEmpty() ||
      exceptionClasses.orEmpty().any { it.isInstance(error) }
  }

  /** Returns how long to wait after the failed [attemptCount] before trying again. */
  internal fun delayFor(attemptCount: Int, random: Random = Random.Default): Duration =
    exponentialBackoffDelay(
      retryIndex = max(0, attemptCount - 1),
      initialDelay = initialDelay,
      maxDelay = maxDelay,
      backoffFactor = backoffFactor,
      jitter = jitter,
      random = random,
    )

  /** Returns [initialDelay] in whole milliseconds. Java cannot read it (mangled). */
  fun initialDelayMillis(): Long = initialDelay.inWholeMilliseconds

  /** Returns [maxDelay] in whole milliseconds. Java cannot read it (mangled). */
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
      .exceptions(exceptions)
      .exceptionClasses(exceptionClasses)

  /**
   * Fluent builder for [RetryConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    private var initialDelay: Duration = DEFAULT_INITIAL_DELAY
    private var maxDelay: Duration = DEFAULT_MAX_DELAY
    private var backoffFactor: Double = DEFAULT_BACKOFF_FACTOR
    private var jitter: Double = DEFAULT_JITTER
    private var exceptions: List<String>? = null
    private var exceptionClasses: List<KClass<out Exception>>? = null

    fun maxAttempts(maxAttempts: Int): Builder = apply { this.maxAttempts = maxAttempts }

    // Let toBuilder copy the delays exactly; the millisecond setters would truncate them.
    internal fun initialDelay(initialDelay: Duration): Builder = apply {
      this.initialDelay = initialDelay
    }

    internal fun maxDelay(maxDelay: Duration): Builder = apply { this.maxDelay = maxDelay }

    /** Sets [RetryConfig.initialDelay] in milliseconds. */
    fun initialDelayMillis(initialDelayMillis: Long): Builder = apply {
      this.initialDelay = initialDelayMillis.milliseconds
    }

    /** Sets [RetryConfig.maxDelay] in milliseconds. */
    fun maxDelayMillis(maxDelayMillis: Long): Builder = apply {
      this.maxDelay = maxDelayMillis.milliseconds
    }

    fun backoffFactor(backoffFactor: Double): Builder = apply { this.backoffFactor = backoffFactor }

    fun jitter(jitter: Double): Builder = apply { this.jitter = jitter }

    /**
     * Sets [RetryConfig.exceptions], the simple class names of failures to retry on, or unsets it
     * if null. Leaves [RetryConfig.exceptionClasses] unchanged.
     */
    fun exceptions(exceptions: List<String>?): Builder = apply { this.exceptions = exceptions }

    /** Sets [RetryConfig.exceptions] from the given names, like the list overload. */
    fun exceptions(exception: String, vararg moreExceptions: String): Builder =
      exceptions(listOf(exception, *moreExceptions))

    /**
     * Sets [RetryConfig.exceptionClasses], the types of failures to retry on, or unsets it if null.
     * Leaves [RetryConfig.exceptions] unchanged. Java callers convert each class with
     * `JvmClassMappingKt.getKotlinClass`.
     */
    fun exceptionClasses(exceptionClasses: List<KClass<out Exception>>?): Builder = apply {
      // Java callers can pass null elements, which would otherwise throw only when a node fails.
      this.exceptionClasses = exceptionClasses?.requireNoNulls()
    }

    /** Sets [RetryConfig.exceptionClasses] from the given types, like the list overload. */
    fun exceptionClasses(
      exceptionClass: KClass<out Exception>,
      vararg moreExceptionClasses: KClass<out Exception>,
    ): Builder = exceptionClasses(listOf(exceptionClass, *moreExceptionClasses))

    fun build(): RetryConfig =
      RetryConfig(
        maxAttempts = maxAttempts,
        initialDelay = initialDelay,
        maxDelay = maxDelay,
        backoffFactor = backoffFactor,
        jitter = jitter,
        exceptions = exceptions,
        exceptionClasses = exceptionClasses,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    const val DEFAULT_MAX_ATTEMPTS: Int = ExponentialBackoff.DEFAULT_MAX_ATTEMPTS
    const val DEFAULT_BACKOFF_FACTOR: Double = ExponentialBackoff.DEFAULT_BACKOFF_FACTOR
    const val DEFAULT_JITTER: Double = ExponentialBackoff.DEFAULT_JITTER
    val DEFAULT_INITIAL_DELAY: Duration = ExponentialBackoff.DEFAULT_INITIAL_DELAY
    val DEFAULT_MAX_DELAY: Duration = ExponentialBackoff.DEFAULT_MAX_DELAY

    /**
     * Returns the name reported in a node's error event and matched by [RetryConfig.exceptions]:
     * the failure's simple class name, or "Exception" for an anonymous class.
     */
    internal fun errorTypeName(error: Throwable): String = error::class.simpleName ?: "Exception"
  }
}
