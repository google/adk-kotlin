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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffTest {

  private val failure = IllegalStateException("store unavailable")

  @Test
  fun retryDelay_defaults_doubleFromOneSecondUpToAMinute() {
    val fiveAttempts = ExponentialBackoff(jitter = 0.0)
    val tenAttempts = ExponentialBackoff(maxAttempts = 10, jitter = 0.0)

    assertEquals(
      listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, null),
      (1..5).map { fiveAttempts.retryDelay(failure, it) },
    )
    assertEquals(60.seconds, tenAttempts.retryDelay(failure, 8))
  }

  @Test
  fun retryDelay_noJitter_growsByFactorUntilMaxDelay() {
    val policy =
      ExponentialBackoff(
        maxAttempts = 10,
        initialDelay = 100.milliseconds,
        maxDelay = 1.seconds,
        backoffFactor = 3.0,
        jitter = 0.0,
      )

    val delays = (1..5).map { policy.retryDelay(failure, it) }

    assertEquals(
      listOf(100.milliseconds, 300.milliseconds, 900.milliseconds, 1.seconds, 1.seconds),
      delays,
    )
  }

  @Test
  fun retryDelay_attemptReachesMaxAttempts_returnsNull() {
    val policy = ExponentialBackoff(maxAttempts = 3, jitter = 0.0)

    assertEquals(1.seconds, policy.retryDelay(failure, 1))
    assertEquals(2.seconds, policy.retryDelay(failure, 2))
    assertNull(policy.retryDelay(failure, 3))
    assertNull(policy.retryDelay(failure, 4))
  }

  @Test
  fun retryDelay_singleAttempt_neverRetries() {
    assertNull(ExponentialBackoff(maxAttempts = 1).retryDelay(failure, 1))
  }

  @Test
  fun retryDelay_overflowingPower_capsAtMaxDelay() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, maxDelay = 30.seconds, jitter = 0.0)

    assertEquals(30.seconds, policy.retryDelay(failure, 5_000))
  }

  @Test
  fun retryDelay_zeroInitialDelay_staysZeroOnceThePowerOverflows() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, initialDelay = Duration.ZERO, jitter = 0.0)

    assertEquals(Duration.ZERO, policy.retryDelay(failure, 5_000))
  }

  @Test
  fun retryDelay_overflowingPower_stillJitters() {
    val policy =
      ExponentialBackoff(maxAttempts = Int.MAX_VALUE, maxDelay = 30.seconds, jitter = 0.5)

    val delays = List(1_000) { assertNotNull(policy.retryDelay(failure, 5_000)) }

    assertTrue(delays.all { it >= 10.seconds && it <= 30.seconds }, "a delay is out of range")
    assertTrue(delays.distinct().size > 1, "jitter produced a single value")
  }

  @Test
  fun retryDelay_defaultJitter_spreadsFromZeroToTwiceTheDelay() {
    val policy = ExponentialBackoff(maxAttempts = 3)

    val delays = List(1_000) { assertNotNull(policy.retryDelay(failure, 2)) }

    assertTrue(delays.all { it >= Duration.ZERO && it <= 4.seconds }, "a delay is out of range")
    assertTrue(delays.any { it < 1.seconds } && delays.any { it > 3.seconds }, "jitter too narrow")
  }

  @Test
  fun retryDelay_partialJitter_staysWithinJitteredRange() {
    val policy = ExponentialBackoff(maxAttempts = 3, initialDelay = 1.seconds, jitter = 0.25)

    val delays = List(1_000) { assertNotNull(policy.retryDelay(failure, 1)) }

    assertTrue(
      delays.all { it >= 750.milliseconds && it <= 1_250.milliseconds },
      "a delay is out of range",
    )
    assertTrue(delays.distinct().size > 1, "jitter produced a single value")
  }

  @Test
  fun retryDelay_jitterAboveOne_neverGoesBelowZero() {
    val policy = ExponentialBackoff(maxAttempts = 3, initialDelay = 1.seconds, jitter = 2.0)

    val delays = List(1_000) { assertNotNull(policy.retryDelay(failure, 1)) }

    assertTrue(delays.all { it >= Duration.ZERO && it <= 3.seconds }, "a delay is out of range")
    assertTrue(delays.any { it == Duration.ZERO }, "no delay was clamped to zero")
  }

  @Test
  fun retryDelay_jitterNearMaxDelay_spreadsBelowIt() {
    val policy = ExponentialBackoff(maxAttempts = 20, maxDelay = 1.seconds, jitter = 0.5)

    val delays = List(1_000) { assertNotNull(policy.retryDelay(failure, 10)) }

    assertTrue(delays.all { it <= 1.seconds }, "a delay exceeds maxDelay")
    assertTrue(delays.any { it < 500.milliseconds }, "jitter collapsed at the cap")
  }

  @Test
  fun retryDelay_attemptBelowOne_throws() {
    assertFailsWith<IllegalArgumentException> { ExponentialBackoff().retryDelay(failure, 0) }
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
        initialDelay = 1500.microseconds,
        maxDelay = 30.seconds,
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
