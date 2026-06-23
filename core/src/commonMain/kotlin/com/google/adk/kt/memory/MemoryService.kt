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

package com.google.adk.kt.memory

import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.Session

/**
 * Base contract for memory services.
 *
 * The service provides functionalities to ingest sessions into memory so that the memory can be
 * used for user queries.
 */
interface MemoryService {

  /**
   * Adds a session to the memory service.
   *
   * A session may be added multiple times during its lifetime.
   *
   * @param session The session to add.
   */
  suspend fun addSessionToMemory(session: Session)

  /**
   * Adds an explicit list of events to the memory service.
   *
   * This is intended for cases where callers want to persist only a subset of events (e.g., the
   * latest turn), rather than re-ingesting the full session.
   *
   * Implementations should treat `events` as an incremental update (delta) and must not assume it
   * represents the full session.
   *
   * @param appName The application name for memory scope.
   * @param userId The user ID for memory scope.
   * @param events The events to add to memory.
   * @param sessionId Optional session ID for memory scope/partitioning.
   * @param customMetadata Optional, portable metadata for memory generation.
   */
  suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String? = null,
    customMetadata: Map<String, Any?>? = null,
  ) {
    throw UnsupportedOperationException(
      "This memory service does not support adding event deltas. " +
        "Call addSessionToMemory(session) to ingest the full session."
    )
  }

  /**
   * Searches for sessions that match the query asynchronously.
   *
   * @param appName The name of the application.
   * @param userId The id of the user.
   * @param query The query to search for.
   * @return A [SearchMemoryResponse] containing the matching memories.
   */
  suspend fun searchMemory(appName: String, userId: String, query: String): SearchMemoryResponse
}
