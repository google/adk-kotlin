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
   * Appends an event to an in-memory session object and updates the session's state based on the
   * event's state delta, if applicable.
   *
   * Note: Implementations of [SessionService] overriding this method should call
   * `super.appendEvent(session, event)` to ensure that the caller's session object is updated and
   * kept in sync.
   *
   * This method primarily modifies the passed [session] object in memory. Persisting these changes
   * typically requires a separate call to an update/save method provided by the specific service
   * implementation, or might happen implicitly depending on the implementation's design.
   *
   * If the event is marked as partial (e.g., `event.partial == true`), it is returned directly
   * without modifying the session state or event list. State delta keys starting with
   * [State.TEMP_PREFIX] are ignored during state updates.
   *
   * @param session The [Session] object to which the event should be appended (will be mutated).
   * @param event The [Event] to append.
   * @return The appended [Event] instance (or the original event if it was partial).
   */
  suspend fun appendEvent(session: Session, event: Event): Event {
    // If the event indicates it's partial or incomplete, don't process it yet.
    if (event.partial == true) {
      return event
    }

    // Apply the event actions state delta to the session state.
    event.actions.stateDelta.let { delta -> session.state.applyDelta(delta) }

    session.events.add(event)
    session.lastUpdateTime = Instant.fromEpochMilliseconds(event.timestamp)
    return event
  }

}

