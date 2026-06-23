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
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class InMemoryMemoryServiceTest {

  private val memoryService = InMemoryMemoryService()

  @Test
  fun addSessionToMemory_addsSession() = runTest {
    val session = createSession("app-1", "user-1", "session-1", "Hello world")
    memoryService.addSessionToMemory(session)

    val response = memoryService.searchMemory("app-1", "user-1", "hello")
    assertEquals(1, response.memories.size)
    val memory = response.memories[0]
    assertEquals("Hello world", memory.content.parts[0].text)
  }

  @Test
  fun searchMemory_returnsMatchingMemories() = runTest {
    val session1 = createSession("app-1", "user-1", "session-1", "Hello world")
    val session2 = createSession("app-1", "user-1", "session-2", "Goodbye world")
    val session3 = createSession("app-1", "user-1", "session-3", "Test session")
    memoryService.addSessionToMemory(session1)
    memoryService.addSessionToMemory(session2)
    memoryService.addSessionToMemory(session3)

    val response = memoryService.searchMemory("app-1", "user-1", "hello")
    assertEquals(1, response.memories.size)
    assertEquals("Hello world", response.memories[0].content.parts[0].text)
  }

  @Test
  fun searchMemory_returnsMultipleMatchingMemories() = runTest {
    val session1 = createSession("app-1", "user-1", "session-1", "Hello world")
    val session2 = createSession("app-1", "user-1", "session-2", "Goodbye world")
    val session3 = createSession("app-1", "user-1", "session-3", "Test session")
    memoryService.addSessionToMemory(session1)
    memoryService.addSessionToMemory(session2)
    memoryService.addSessionToMemory(session3)

    val response = memoryService.searchMemory("app-1", "user-1", "world")
    assertEquals(2, response.memories.size)
  }

  @Test
  fun searchMemory_returnsEmptyForNonMatchingQuery() = runTest {
    val session = createSession("app-1", "user-1", "session-1", "Hello world")
    memoryService.addSessionToMemory(session)

    val response = memoryService.searchMemory("app-1", "user-1", "foo")
    assertTrue(response.memories.isEmpty())
  }

  @Test
  fun searchMemory_returnsEmptyForUnknownUser() = runTest {
    val session = createSession("app-1", "user-1", "session-1", "Hello world")
    memoryService.addSessionToMemory(session)

    val response = memoryService.searchMemory("app-1", "user-2", "hello")
    assertTrue(response.memories.isEmpty())
  }

  @Test
  fun addEventsToMemory_withExplicitEvents_addsFilteredEvents() = runTest {
    val event = createEvent("event-1a", "The ADK is a great toolkit.")
    val ignoredEvent = Event(id = "event-1b", author = Role.USER)

    memoryService.addEventsToMemory(
      appName = "app-1",
      userId = "user-1",
      sessionId = "session-1",
      events = listOf(event, ignoredEvent),
    )

    val response = memoryService.searchMemory("app-1", "user-1", "toolkit")
    assertEquals(1, response.memories.size)
    assertEquals("The ADK is a great toolkit.", response.memories[0].content.parts[0].text)
  }

  @Test
  fun addEventsToMemory_withoutSessionId_usesDefaultBucket() = runTest {
    val event = createEvent("event-1a", "The ADK is a great toolkit.")

    memoryService.addEventsToMemory(appName = "app-1", userId = "user-1", events = listOf(event))

    val response = memoryService.searchMemory("app-1", "user-1", "toolkit")
    assertEquals(1, response.memories.size)
    assertEquals("The ADK is a great toolkit.", response.memories[0].content.parts[0].text)
  }

  @Test
  fun addEventsToMemory_appendsWithoutReplacing() = runTest {
    val session = createSession("app-1", "user-1", "session-1", "Hello world")
    memoryService.addSessionToMemory(session)

    val newEvent = createEvent("event-1d", "A new fact.")
    memoryService.addEventsToMemory(
      appName = "app-1",
      userId = "user-1",
      sessionId = "session-1",
      events = listOf(newEvent),
    )

    val response = memoryService.searchMemory("app-1", "user-1", "world fact")
    assertEquals(2, response.memories.size)
  }

  @Test
  fun addEventsToMemory_deduplicatesEventIds() = runTest {
    val session = createSession("app-1", "user-1", "session-1", "Hello world")
    val sessionEvent = session.events[0]
    memoryService.addSessionToMemory(session)

    val duplicateEvent = createEvent(sessionEvent.id, "Updated duplicate text.")
    memoryService.addEventsToMemory(
      appName = "app-1",
      userId = "user-1",
      sessionId = "session-1",
      events = listOf(duplicateEvent),
    )

    val responseUpdated = memoryService.searchMemory("app-1", "user-1", "updated")
    assertTrue(responseUpdated.memories.isEmpty())

    val responseHello = memoryService.searchMemory("app-1", "user-1", "hello")
    assertEquals(1, responseHello.memories.size)
    assertEquals("Hello world", responseHello.memories[0].content.parts[0].text)
  }

  private fun createEvent(id: String, text: String): Event {
    val content = Content(role = null, parts = listOf(Part(text = text)))
    return Event(
      id = id,
      timestamp = Clock.System.now().toEpochMilliseconds(),
      content = content,
      author = Role.USER,
    )
  }

  private fun createSession(
    appName: String,
    userId: String,
    sessionId: String,
    text: String,
  ): Session {
    val content = Content(role = null, parts = listOf(Part(text = text)))
    val event =
      Event(
        timestamp = Clock.System.now().toEpochMilliseconds(),
        content = content,
        author = Role.USER,
      )
    return Session(
      key = SessionKey(appName = appName, userId = userId, id = sessionId),
      state = State(),
      events = mutableListOf(event),
      lastUpdateTime = Clock.System.now(),
    )
  }
}
