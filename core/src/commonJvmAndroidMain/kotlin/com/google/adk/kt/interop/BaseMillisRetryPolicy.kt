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
import com.google.adk.kt.retry.RetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Java-friendly base for implementing a [RetryPolicy], whose [Duration] return type Java cannot
 * produce: the Java subclass returns the delay in milliseconds instead. Like any [RetryPolicy], it
 * must be thread-safe and return quickly.
 */
@AdkJavaInteropApi
abstract class BaseMillisRetryPolicy : RetryPolicy {

  final override fun retryDelay(failure: Exception, failedAttempt: Int): Duration? =
    retryDelayMillis(failure, failedAttempt)?.milliseconds

  /**
   * Returns the delay in milliseconds before retrying after attempt [failedAttempt] (1-based)
   * failed with [failure], or `null` to stop retrying.
   */
  protected abstract fun retryDelayMillis(failure: Exception, failedAttempt: Int): Long?
}
