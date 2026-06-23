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
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val UNKNOWN_SESSION_ID = "__unknown_session_id__"

/**
 * An in-memory memory service for prototyping purposes only.
 *
 * Uses keyword matching instead of semantic search.
 */
class InMemoryMemoryService : MemoryService {

  private val mutex = Mutex()
  private val sessionEvents = mutableMapOf<UserKey, MutableMap<String, List<Event>>>()

  override suspend fun addSessionToMemory(session: Session) = mutex.withLock {
    val key = UserKey(session.key.appName, session.key.userId)
    val userSessions = sessionEvents.getOrPut(key) { mutableMapOf() }

    val nonEmptyEvents =
      session.events.filter { event ->
        event.content?.parts?.any { part -> !part.text.isNullOrEmpty() } ?: false
      }

    userSessions[session.key.id!!] = nonEmptyEvents
  }

  override suspend fun addEventsToMemory(
    appName: String,
    userId: String,
    events: List<Event>,
    sessionId: String?,
    customMetadata: Map<String, Any?>?,
  ) = mutex.withLock {
    val key = UserKey(appName, userId)
    val userSessions = sessionEvents.getOrPut(key) { mutableMapOf() }
    val scopedSessionId = sessionId ?: UNKNOWN_SESSION_ID

    val eventsToAdd = events.filter { event ->
      event.content?.parts?.any { part -> !part.text.isNullOrEmpty() } ?: false
    }

    val existingEvents = userSessions[scopedSessionId]?.toMutableList() ?: mutableListOf()
    val existingIds = existingEvents.map { it.id }.toMutableSet()

    for (event in eventsToAdd) {
      if (event.id !in existingIds) {
        existingEvents.add(event)
        existingIds.add(event.id)
      }
    }

    userSessions[scopedSessionId] = existingEvents
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): SearchMemoryResponse = mutex.withLock {
    val userSessions =
      sessionEvents[UserKey(appName, userId)] ?: return SearchMemoryResponse(emptyList())

    val wordsInQuery = WORD_PATTERN.findAll(query).map { it.value.lowercase() }.toSet()

    val matchingMemories = mutableListOf<MemoryEntry>()

    for (event in userSessions.values.asSequence().flatten()) {
      val parts = event.content?.parts
      if (parts.isNullOrEmpty()) {
        continue
      }

      val wordsInEvent =
        parts
          .asSequence()
          .mapNotNull { it.text }
          .filter { it.isNotEmpty() }
          .flatMap { WORD_PATTERN.findAll(it) }
          .map { it.value.lowercase() }
          .toSet()

      if (wordsInQuery.none { it in wordsInEvent }) {
        continue
      }

      val memory =
        MemoryEntry(
          content = event.content,
          author = event.author,
          timestamp = formatTimestamp(event.timestamp),
        )

      matchingMemories.add(memory)
    }

    SearchMemoryResponse(memories = matchingMemories)
  }

  private fun formatTimestamp(timestamp: Long): String =
    Instant.fromEpochMilliseconds(timestamp).toString()

  private data class UserKey(val appName: String, val userId: String)

  companion object {
    // Pattern to extract words, matching the Python version.
    private val WORD_PATTERN = Regex("[A-Za-z]+")
  }
}
