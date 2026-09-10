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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.collections.concurrentMutableListOf
import com.google.adk.kt.events.Event
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import kotlin.time.Instant

/**
 * A [Session] object that encapsulates the [State] and [Event]s of a session.
 *
 * @property key The composite identifier of the session ([SessionKey.appName], [SessionKey.userId],
 *   [SessionKey.id]).
 * @property state The state of the session.
 * @property events The events of the session, e.g. user input, model response, function
 *   call/response, etc. Defaults to a concurrent list so that appending an event (e.g. from one
 *   parallel branch) while another reads the history does not throw
 *   `ConcurrentModificationException`.
 * @property lastUpdateTime The last update time of the session. Defaults to the Unix epoch.
 */
data class Session(
  val key: SessionKey,
  val state: State = State(),
  val events: MutableList<Event> = concurrentMutableListOf(),
  var lastUpdateTime: Instant = Instant.fromEpochMilliseconds(0),
) {
  /**
   * Fluent builder for [Session], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var key: SessionKey? = null
    private var state: State = State()
    private var events: MutableList<Event> = concurrentMutableListOf()
    private var lastUpdateTime: Instant = Instant.fromEpochMilliseconds(0)

    fun key(key: SessionKey): Builder = apply { this.key = key }

    fun state(state: State): Builder = apply { this.state = state }

    /** Java-friendly alternative to [state]: the map is wrapped in a [State]. */
    fun state(state: Map<String, @JvmSuppressWildcards Any>): Builder = apply {
      this.state = State(state)
    }

    fun events(events: List<Event>): Builder = apply {
      this.events = concurrentMutableListOf<Event>().apply { addAll(events) }
    }

    fun lastUpdateTime(lastUpdateTime: Instant): Builder = apply {
      this.lastUpdateTime = lastUpdateTime
    }

    /** Java-friendly alternative to [lastUpdateTime], as epoch milliseconds. */
    fun lastUpdateTimeEpochMillis(lastUpdateTimeEpochMillis: Long): Builder = apply {
      this.lastUpdateTime = Instant.fromEpochMilliseconds(lastUpdateTimeEpochMillis)
    }

    fun build(): Session =
      Session(
        key = checkNotNull(key) { "Session.Builder requires key to be set." },
        state = state,
        events = events,
        lastUpdateTime = lastUpdateTime,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
