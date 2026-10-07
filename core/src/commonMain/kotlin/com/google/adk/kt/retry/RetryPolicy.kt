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

import kotlin.time.Duration

/**
 * Decides whether to retry a failed operation, and how long to wait first.
 *
 * One policy may serve many concurrent operations, so implementations must be thread-safe and
 * should return quickly without blocking. Java implementations extend `BaseMillisRetryPolicy`,
 * since Java cannot return a [Duration].
 */
interface RetryPolicy {
  /**
   * Returns whether to retry once [failedAttempts] attempts (at least 1) have failed, the last with
   * [failure].
   */
  fun shouldRetry(failure: Exception, failedAttempts: Int): Boolean

  /**
   * Returns the delay before the next attempt once [failedAttempts] attempts have failed, and is
   * called only after [shouldRetry] returned `true`. A zero or negative delay retries at once, and
   * [Duration.INFINITE] waits until the retrying coroutine is canceled.
   */
  fun nextRetryDelay(failedAttempts: Int): Duration
}
