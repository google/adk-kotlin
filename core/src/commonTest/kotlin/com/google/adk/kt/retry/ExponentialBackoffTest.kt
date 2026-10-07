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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffTest {

  private val failure = IllegalStateException("store unavailable")

  @Test
  fun shouldRetry_defaults_stopsAfterFiveAttempts() {
    val policy = ExponentialBackoff()

    assertEquals(
      listOf(true, true, true, true, false),
      (1..5).map { policy.shouldRetry(failure, it) },
    )
  }

  @Test
  fun shouldRetry_attemptReachesMaxAttempts_returnsFalse() {
    val policy = ExponentialBackoff(maxAttempts = 3)

    assertEquals(listOf(true, true, false, false), (1..4).map { policy.shouldRetry(failure, it) })
  }

  @Test
  fun shouldRetry_singleAttempt_neverRetries() {
    assertFalse(ExponentialBackoff(maxAttempts = 1).shouldRetry(failure, 1))
  }

  @Test
  fun shouldRetry_attemptBelowOne_throws() {
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff().shouldRetry(failure, 0) }
  }

  @Test
  fun nextRetryDelay_defaults_doubleFromOneSecondUpToAMinute() {
    val policy = ExponentialBackoff(maxAttempts = 10, jitter = 0.0)

    assertEquals(
      listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds),
      (1..4).map { policy.nextRetryDelay(it) },
    )
    assertEquals(60.seconds, policy.nextRetryDelay(8))
  }

  @Test
  fun nextRetryDelay_noJitter_growsByFactorUntilMaxDelay() {
    val policy =
      ExponentialBackoff(
        maxAttempts = 10,
        initialDelay = 100.milliseconds,
        maxDelay = 1.seconds,
        backoffFactor = 3.0,
        jitter = 0.0,
      )

    val delays = (1..5).map { policy.nextRetryDelay(it) }

    assertEquals(
      listOf(100.milliseconds, 300.milliseconds, 900.milliseconds, 1.seconds, 1.seconds),
      delays,
    )
  }

  @Test
  fun nextRetryDelay_overflowingPower_capsAtMaxDelay() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, maxDelay = 30.seconds, jitter = 0.0)

    assertEquals(30.seconds, policy.nextRetryDelay(5_000))
  }

  @Test
  fun nextRetryDelay_zeroInitialDelay_staysZeroOnceThePowerOverflows() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, initialDelay = Duration.ZERO, jitter = 0.0)

    assertEquals(Duration.ZERO, policy.nextRetryDelay(5_000))
  }

  @Test
  fun nextRetryDelay_overflowingPower_stillJitters() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, maxDelay = 30.seconds, jitter = 0.5)

    val delays = List(1_000) { policy.nextRetryDelay(5_000) }

    assertEquals(emptyList(), delays.filterNot { it in 10.seconds..30.seconds })
    assertTrue(delays.distinct().size > 1, "jitter produced a single value")
  }

  @Test
  fun nextRetryDelay_defaultJitter_spreadsFromZeroToTwiceTheDelay() {
    val policy = ExponentialBackoff()

    val delays = List(1_000) { policy.nextRetryDelay(2) }

    assertEquals(emptyList(), delays.filterNot { it in Duration.ZERO..4.seconds })
    assertTrue(delays.any { it < 1.seconds } && delays.any { it > 3.seconds }, "jitter too narrow")
  }

  @Test
  fun nextRetryDelay_partialJitter_staysWithinJitteredRange() {
    val policy = ExponentialBackoff(initialDelay = 1.seconds, jitter = 0.25)

    val delays = List(1_000) { policy.nextRetryDelay(1) }

    assertEquals(emptyList(), delays.filterNot { it in 750.milliseconds..1_250.milliseconds })
    assertTrue(delays.distinct().size > 1, "jitter produced a single value")
  }

  @Test
  fun nextRetryDelay_jitterAboveOne_neverGoesBelowZero() {
    val policy = ExponentialBackoff(initialDelay = 1.seconds, jitter = 2.0)

    val delays = List(1_000) { policy.nextRetryDelay(1) }

    assertEquals(emptyList(), delays.filterNot { it in Duration.ZERO..3.seconds })
    assertTrue(delays.any { it == Duration.ZERO }, "no delay was clamped to zero")
  }

  @Test
  fun nextRetryDelay_jitterNearMaxDelay_spreadsBelowIt() {
    val policy = ExponentialBackoff(maxAttempts = 20, maxDelay = 1.seconds, jitter = 0.5)

    val delays = List(1_000) { policy.nextRetryDelay(10) }

    assertEquals(emptyList(), delays.filter { it > 1.seconds })
    assertTrue(delays.any { it < 500.milliseconds }, "jitter collapsed at the cap")
  }

  @Test
  fun nextRetryDelay_attemptBelowOne_throws() {
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff().nextRetryDelay(0) }
  }

  @Test
  fun constructor_invalidArguments_throw() {
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(maxAttempts = 0) }
    assertFailsWith<IllegalArgumentException> {
      ExponentialBackoff(initialDelay = (-1).milliseconds)
    }
    assertFailsWith<IllegalArgumentException> {
      ExponentialBackoff(initialDelay = Duration.INFINITE)
    }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(maxDelay = (-1).milliseconds) }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(maxDelay = Duration.INFINITE) }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(backoffFactor = -1.0) }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(backoffFactor = Double.NaN) }
    assertFailsWith<IllegalArgumentException> {
      ExponentialBackoff(backoffFactor = Double.POSITIVE_INFINITY)
    }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(jitter = -0.1) }
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff(jitter = Double.NaN) }
    assertFailsWith<IllegalArgumentException> {
      ExponentialBackoff(jitter = Double.POSITIVE_INFINITY)
    }
  }

  @Test
  fun initialDelayMillisAndMaxDelayMillis_truncateToWholeMilliseconds() {
    val policy = ExponentialBackoff(initialDelay = 1500.microseconds, maxDelay = 2.seconds)

    assertEquals(1L, policy.initialDelayMillis())
    assertEquals(2_000L, policy.maxDelayMillis())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val policy =
      ExponentialBackoff(
        maxAttempts = 3,
        // Sub-millisecond, so a copy made through the millisecond setters would not match.
        initialDelay = 1500.microseconds,
        maxDelay = 2500.microseconds,
        backoffFactor = 1.5,
        jitter = 0.25,
      )

    assertEquals(policy.copy(), policy.toBuilder().build())
    assertEquals(policy.copy(maxAttempts = 7), policy.toBuilder().maxAttempts(7).build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_unsetProperties_matchConstructorDefaults() {
    assertEquals(ExponentialBackoff(), ExponentialBackoff.builder().build())
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun builder_setProperties_areApplied() {
    val built =
      ExponentialBackoff.builder()
        .maxAttempts(3)
        .initialDelayMillis(200)
        .maxDelayMillis(600)
        .backoffFactor(1.5)
        .jitter(0.5)
        .build()

    assertEquals(
      ExponentialBackoff(
        maxAttempts = 3,
        initialDelay = 200.milliseconds,
        maxDelay = 600.milliseconds,
        backoffFactor = 1.5,
        jitter = 0.5,
      ),
      built,
    )
  }
}
