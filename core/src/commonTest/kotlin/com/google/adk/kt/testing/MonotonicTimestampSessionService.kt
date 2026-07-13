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
package com.google.adk.kt.testing

import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService

/**
 * A [SessionService] that stamps each appended event with a strictly increasing timestamp.
 *
 * [Event.timestamp] defaults to the wall clock, and compaction uses those timestamps to decide
 * which events a summary covers. Under Robolectric (the Android unit-test runtime) the clock is
 * frozen, so every event gets the *same* timestamp and the compaction boundary can no longer
 * separate covered events from retained ones. Assigning monotonic timestamps keeps runner-driven
 * compaction tests deterministic on every platform.
 */
class MonotonicTimestampSessionService(
  private val delegate: SessionService = InMemorySessionService()
) : SessionService by delegate {
  private var nextTimestamp = 1L

  override suspend fun appendEvent(session: Session, event: Event): Event =
    delegate.appendEvent(session, event.copy(timestamp = nextTimestamp++))
}
