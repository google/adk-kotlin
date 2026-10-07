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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class BaseMillisRetryPolicyTest {

  @Test
  fun nextRetryDelay_convertsTheMillisecondDelay() {
    val policy =
      object : BaseMillisRetryPolicy() {
        override fun shouldRetry(failure: Exception, failedAttempts: Int): Boolean = true

        override fun nextRetryDelayMillis(failedAttempts: Int): Long = 250L * failedAttempts
      }

    assertThat(policy.nextRetryDelay(2)).isEqualTo(500.milliseconds)
  }
}
