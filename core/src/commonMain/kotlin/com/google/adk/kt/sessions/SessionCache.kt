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
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Copies of recently read sessions, kept current by the appends made through the service and
 * bounded as [config] says. Not thread-safe: its owner calls it under one lock.
 *
 * @param timeSource Where entries read the time they expire by.
 */
internal class SessionCache(
  private val config: SessionCacheConfig,
  private val timeSource: TimeSource,
) {
  // The session read least recently comes first; a read in progress holds a place too.
  private val entryBySession = LinkedHashMap<SessionKey, Entry>()

  /** Returns a copy of the cached [key] and marks it read most recently, or null if not cached. */
  fun get(key: SessionKey): Session? {
    val entry = entryBySession[key] as? Entry.Cached ?: return null
    entryBySession.remove(key)
    if (entry.isExpired) return null
    entryBySession[key] = entry
    return entry.session.detachedCopy()
  }

  /**
   * Starts a read of [key] from the store for [finishRead]; a change to the session voids it. Call
   * it after [get] misses, since it replaces a cached copy.
   */
  fun startRead(key: SessionKey): Entry.Reading {
    // A read in progress has seen no change since it started, so this one can share it.
    val read = entryBySession[key] as? Entry.Reading ?: Entry.Reading()
    put(key, read)
    return read
  }

  /** Caches [session], the result of [read], unless the session changed since the read started. */
  fun finishRead(key: SessionKey, read: Entry.Reading, session: Session?) {
    if (entryBySession[key] !== read) return
    // A session under another key would escape the changes tracked under this one.
    if (session != null && session.key == key) {
      put(key, Entry.Cached(session.detachedCopy(), expiresAt()))
    } else {
      entryBySession.remove(key)
    }
  }

  /**
   * Applies [event], appended to the session [key], to its cached copy, and drops the other
   * sessions that see the [changed] shared scopes.
   */
  fun append(key: SessionKey, event: Event, changed: Set<SharedStateScope>) {
    when (val entry = entryBySession[key]) {
      is Entry.Cached -> entry.append(event)
      // The read may have missed the event.
      is Entry.Reading -> entryBySession.remove(key)
      null -> {}
    }
    if (changed.isNotEmpty()) entryBySession.keys.removeAll { it != key && it.sees(changed) }
  }

  /**
   * Drops the session [key], whose stored copy changed in a way the cache cannot follow, and the
   * sessions that see one of the [changed] shared scopes.
   */
  fun drop(key: SessionKey, changed: Set<SharedStateScope> = emptySet()) {
    entryBySession.remove(key)
    if (changed.isNotEmpty()) entryBySession.keys.removeAll { it.sees(changed) }
  }

  /** Puts [entry] last; past the limit, drops the expired copies, then the oldest entries. */
  private fun put(key: SessionKey, entry: Entry) {
    entryBySession.remove(key)
    entryBySession[key] = entry
    if (entryBySession.size <= config.maxSessions) return
    entryBySession.values.removeAll { it is Entry.Cached && it.isExpired }
    while (entryBySession.size > config.maxSessions) {
      entryBySession.remove(entryBySession.keys.first())
    }
  }

  private fun expiresAt(): TimeMark? =
    if (config.ttl.isInfinite()) null else timeSource.markNow() + config.ttl

  sealed interface Entry {
    /** A read of the session from the store in progress, told apart by identity. */
    class Reading : Entry

    /** A cached session, and when it expires; `null` means never. */
    class Cached(val session: Session, private val expiresAt: TimeMark?) : Entry {
      val isExpired: Boolean
        get() = expiresAt?.hasPassedNow() == true

      /** Applies [event] as the store would, so a hit sees it before the store has it. */
      fun append(event: Event) {
        session.state.applyDelta(event.actions.stateDelta)
        // A copy of its own: hits hand their events out, and the caller keeps the original.
        session.events.add(event.copy(actions = event.actions.snapshot()))
        session.lastUpdateTime = Instant.fromEpochMilliseconds(event.timestamp)
      }
    }
  }
}

/** Whether this session sees one of the [changed] shared scopes. */
private fun SessionKey.sees(changed: Set<SharedStateScope>): Boolean = changed.any {
  it.isSeenBy(this)
}
