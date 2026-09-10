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

/**
 * Defines the contract for managing [Session]s and their associated [Event]s. Provides methods for
 * creating, retrieving, listing, and deleting sessions, as well as listing and appending events to
 * a session. Implementations of this interface handle the underlying storage and retrieval logic.
 */
interface SessionService {

  /**
   * Creates a new session with the specified parameters.
   *
   * @param key The composite identifier of the session. If [SessionKey.id] is null, the service
   *   generates a unique session id and the returned [Session] will reflect it.
   * @param state An optional map representing the initial state of the session.
   * @return The newly created [Session] instance.
   */
  suspend fun createSession(key: SessionKey, state: Map<String, Any>? = null): Session

  /**
   * Retrieves a specific session, optionally filtering the events included.
   *
   * @param key The composite identifier of the session to retrieve. [SessionKey.id] must not be
   *   null.
   * @param config Optional configuration to filter the events returned within the session.
   * @return The [Session] if found, otherwise null.
   */
  suspend fun getSession(key: SessionKey, config: GetSessionConfig? = null): Session?

  /**
   * Lists sessions associated with a specific application and user.
   *
   * @param appName The name of the application.
   * @param userId The identifier of the user whose sessions are to be listed.
   * @return A [ListSessionsResponse] containing a list of matching sessions.
   */
  suspend fun listSessions(appName: String, userId: String): ListSessionsResponse

  /**
   * Deletes a specific session.
   *
   * @param key The composite identifier of the session to delete. [SessionKey.id] must not be null.
   */
  suspend fun deleteSession(key: SessionKey)

  /**
   * Lists the events within a specific session.
   *
   * @param key The composite identifier of the session whose events are to be listed.
   *   [SessionKey.id] must not be null.
   * @return A [ListEventsResponse] containing a list of events.
   */
  suspend fun listEvents(key: SessionKey): ListEventsResponse

  /**
   * Closes a session.
   *
   * @param session The session object to close.
   */
  suspend fun closeSession(session: Session) {
    // Default implementation does nothing.
  }

  /**
   * Appends [event] to [session], applying its state delta to the in-memory session and recording
   * the event; `temp:` keys are applied to the in-memory state (so later agents in the invocation
   * can read them) but removed from [event] before persistence.
   *
   * Overriding implementations must run this lifecycle before persisting so the stored event has no
   * `temp:` keys: call `super.appendEvent` first then persist the trimmed [event], or call
   * [State.applyTempDelta] and [com.google.adk.kt.events.EventActions.removeTempKeys] before
   * persisting with `super.appendEvent` last (needed when a stale-write check must read the
   * pre-update [Session.lastUpdateTime]).
   *
   * @param session The [Session] to update in place.
   * @param event The [Event] to append; its `temp:` state-delta keys are removed in place.
   * @return The appended [Event], or the original unchanged if it was partial.
   */
  suspend fun appendEvent(session: Session, event: Event): Event {
    // Partial (streaming) events are superseded by the final aggregated event, so skip them.
    if (event.partial) return event

    // `temp:` goes to the live session only; drop it from the event before applying the rest.
    session.state.applyTempDelta(event.actions.stateDelta)
    event.actions.removeTempKeys()
    session.state.applyDelta(event.actions.stateDelta)

    session.events.add(event)
    session.lastUpdateTime = Instant.fromEpochMilliseconds(event.timestamp)
    return event
  }
}
