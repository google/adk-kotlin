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
 * Java-friendly base for implementing a [RetryPolicy]. Java cannot return the [Duration] that
 * [nextRetryDelay] needs, so a Java subclass implements [shouldRetry] and [nextRetryDelayMillis]
 * instead. Like any [RetryPolicy], it must be thread-safe and return quickly.
 */
@AdkJavaInteropApi
abstract class BaseMillisRetryPolicy : RetryPolicy {

  final override fun nextRetryDelay(failedAttempts: Int): Duration =
    nextRetryDelayMillis(failedAttempts).milliseconds

  /** Millisecond counterpart of [nextRetryDelay]. */
  protected abstract fun nextRetryDelayMillis(failedAttempts: Int): Long
}
