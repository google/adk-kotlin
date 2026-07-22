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
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.sessions.Session

/**
 * A dummy implementation of [MemoryService] tailored for testing scenarios.
 *
 * This fixture retains state within its [addedSessions] list whenever [addSessionToMemory] is
 * invoked, allowing you to assert that your classes correctly interface with memory services.
 *
 * Note that [searchMemory] returns an empty list by default. If a test requires specific memory
 * responses, consider overriding or extending this class.
 */
class DummyMemoryService : MemoryService {

  /**
   * Stores all sessions passed to [addSessionToMemory]. You can interrogate this list in your tests
   * to verify memory insertions.
   */
  val addedSessions = mutableListOf<Session>()

  /** Records each [addEventsToMemory] call so tests can assert the forwarded scope and events. */
  val addedEvents = mutableListOf<AddEventsCall>()

  /** Records each [addMemory] call so tests can assert the forwarded scope and memories. */
  val addedMemories = mutableListOf<AddMemoryCall>()

  /** A mutable response to be returned by [searchMemory]. Defaults to an empty response. */
  var searchMemoryResponse: SearchMemoryResponse = SearchMemoryResponse(emptyList())

  override suspend fun addSessionToMemory(session: Session) {
    addedSessions.add(session)
  }

  override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) {
    addedEvents.add(AddEventsCall(appName, userId, events, sessionId, customMetadata))
  }

  override suspend fun addMemory(
    appName: String,
    userId: String,
    memories: List<MemoryEntry>,
    customMetadata: Map<String, Any?>?,
  ) {
    addedMemories.add(AddMemoryCall(appName, userId, memories, customMetadata))
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse {
    return searchMemoryResponse
  }

  /** A captured [addEventsToMemory] invocation. */
  data class AddEventsCall(
    val appName: String,
    val userId: String,
    val events: List<Event>,
    val sessionId: String?,
    val customMetadata: Map<String, Any?>?,
  )

  /** A captured [addMemory] invocation. */
  data class AddMemoryCall(
    val appName: String,
    val userId: String,
    val memories: List<MemoryEntry>,
    val customMetadata: Map<String, Any?>?,
  )
}
