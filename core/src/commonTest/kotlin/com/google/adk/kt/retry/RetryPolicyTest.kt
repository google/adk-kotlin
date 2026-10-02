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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

  @Test
  fun retryIf_acceptedFailure_usesTheWrappedPolicy() {
    val policy = ExponentialBackoff(jitter = 0.0).retryIf { it is IllegalStateException }

    assertEquals(2.seconds, policy.retryDelay(IllegalStateException(), failedAttempt = 2))
  }

  @Test
  fun retryIf_rejectedFailure_stopsRetrying() {
    val policy = ExponentialBackoff().retryIf { it is IllegalStateException }

    assertNull(policy.retryDelay(IllegalArgumentException(), failedAttempt = 1))
  }
}
