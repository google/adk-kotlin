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

import com.google.adk.kt.events.Event
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * A `persist` function for [OrderedEventWriter] tests. It holds the writes of blocked events, waits
 * [persistDelay], then throws an event's failure or records the write in [persisted].
 */
internal class FakeStore {
  @Volatile var persistDelay: Duration = Duration.ZERO

  private val blockedIds = MutableStateFlow(emptySet<String>())
  private val failures = MutableStateFlow(emptyMap<String, Throwable>())
  private val _attemptedIds = MutableStateFlow(emptyList<String>())
  private val _persisted = MutableStateFlow(emptyList<OrderedEventWriter.Write>())
  private val _maxInFlight = MutableStateFlow(0)
  private val inFlight = MutableStateFlow(0)

  /** Event ids of every write passed to [persist], in call order. */
  val attemptedIds: List<String>
    get() = _attemptedIds.value

  val persisted: List<OrderedEventWriter.Write>
    get() = _persisted.value

  val persistedIds: List<String>
    get() = persisted.map { it.event.id }

  /** The most writes [persist] has run at once. */
  val maxInFlight: Int
    get() = _maxInFlight.value

  /** Holds the writes of [events] until they are unblocked. */
  fun block(vararg events: Event) {
    blockedIds.update { it + events.map(Event::id) }
  }

  /** Releases the writes of [events]. */
  fun unblock(vararg events: Event) {
    blockedIds.update { it - events.map(Event::id).toSet() }
  }

  /** Releases every held write. */
  fun unblockAll() {
    blockedIds.value = emptySet()
  }

  /** Makes the writes of [events] throw [cause]. */
  fun fail(vararg events: Event, cause: Throwable = IllegalStateException("store unavailable")) {
    failures.update { it + events.map { event -> event.id to cause } }
  }

  /** Waits until [event]'s write has reached [persist]. */
  suspend fun awaitAttempt(event: Event) {
    _attemptedIds.first { event.id in it }
  }

  suspend fun persist(write: OrderedEventWriter.Write) {
    val id = write.event.id
    val running = inFlight.updateAndGet { it + 1 }
    _maxInFlight.update { maxOf(it, running) }
    try {
      _attemptedIds.update { it + id }
      blockedIds.first { id !in it }
      delay(persistDelay)
      failures.value[id]?.let { throw it }
      _persisted.update { it + write }
    } finally {
      inFlight.update { it - 1 }
    }
  }
}
