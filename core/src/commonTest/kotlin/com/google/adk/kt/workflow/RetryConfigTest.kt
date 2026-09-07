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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Exercises the retry policy directly; the workflow tests cover it through public behavior. */
class RetryConfigTest {

  @Test
  fun zeroOrOneMaxAttemptsMeansNoRetry() {
    val error = NodeExecutionException("RuntimeError", "boom")

    assertFalse(RetryConfig(maxAttempts = 0).shouldRetry(error, attemptCount = 1))
    assertFalse(RetryConfig(maxAttempts = 1).shouldRetry(error, attemptCount = 1))
  }

  @Test
  fun delayForGrowsByBackoffFactorUntilTheCeiling() {
    // Arrange
    val config =
      RetryConfig(
        initialDelay = 1.seconds,
        backoffFactor = 2.0,
        jitter = 0.0,
        maxDelay = 60.seconds,
      )

    // Act, Assert: 1s, 2s, 4s, then held at the ceiling.
    assertEquals(1.seconds, config.delayFor(attemptCount = 1))
    assertEquals(2.seconds, config.delayFor(attemptCount = 2))
    assertEquals(4.seconds, config.delayFor(attemptCount = 3))
    assertEquals(60.seconds, config.delayFor(attemptCount = 10))
  }

  @Test
  fun delayForStaysWithinBoundsWhenJittered() {
    // Arrange
    val ceiling = 30.seconds
    val config =
      RetryConfig(initialDelay = 1.seconds, backoffFactor = 2.0, jitter = 1.0, maxDelay = ceiling)
    val random = Random(seed = 42)

    // Act, Assert: jitter never drives a delay below zero or past the ceiling.
    repeat(100) { i ->
      val delay = config.delayFor(attemptCount = i + 1, random = random)
      assertTrue(delay >= Duration.ZERO, "delay went negative: $delay")
      assertTrue(delay <= ceiling, "delay exceeded the ceiling: $delay")
    }
  }
}
