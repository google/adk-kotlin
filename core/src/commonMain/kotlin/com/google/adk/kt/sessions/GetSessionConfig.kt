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

package com.google.adk.kt.sessions

import kotlin.jvm.JvmStatic
import kotlin.time.Instant

/** Configuration for getting a session. */
data class GetSessionConfig(val numRecentEvents: Int? = null, val afterTimestamp: Instant? = null) {
  /**
   * Fluent builder for [GetSessionConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var numRecentEvents: Int? = null
    private var afterTimestamp: Instant? = null

    fun numRecentEvents(numRecentEvents: Int?): Builder = apply {
      this.numRecentEvents = numRecentEvents
    }

    fun afterTimestamp(afterTimestamp: Instant?): Builder = apply {
      this.afterTimestamp = afterTimestamp
    }

    fun build(): GetSessionConfig =
      GetSessionConfig(numRecentEvents = numRecentEvents, afterTimestamp = afterTimestamp)
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
