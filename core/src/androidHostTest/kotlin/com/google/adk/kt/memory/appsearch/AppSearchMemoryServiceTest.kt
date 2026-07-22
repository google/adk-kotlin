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

package com.google.adk.kt.memory.appsearch

import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Host-JVM tests for [AppSearchMemoryService] against a [FakeMemoryIndex]. These verify the
 * service's logic (scoping, filtering, dedup, id handling, and entry mapping) without the real
 * AppSearch native engine, which is exercised separately by an instrumented test.
 */
@RunWith(JUnit4::class)
class AppSearchMemoryServiceTest {

  private val index = FakeMemoryIndex()
  private val service = AppSearchMemoryService(index)

  @Test
  fun addSessionToMemory_indexesEventText_returnsMatch() =
    runBlocking<Unit> {
      service.addSessionToMemory(sessionWith("app-1", "user-1", "Hello world"))

      val memories = service.searchMemory("app-1", "user-1", "hello").memories

      assertThat(memories).hasSize(1)
      assertThat(memories[0].content.parts[0].text).isEqualTo("Hello world")
    }

  @Test
  fun addSessionToMemory_emptyTextEvent_isSkipped() =
    runBlocking<Unit> {
      val emptyEvent = Event(id = "e-empty", author = Role.USER)
      val session =
        Session(
          key = SessionKey("app-1", "user-1", "s-1"),
          state = State(),
          events = mutableListOf(emptyEvent),
          lastUpdateTime = Clock.System.now(),
        )

      service.addSessionToMemory(session)

      assertThat(index.records).isEmpty()
    }

  @Test
  fun addSessionToMemory_eventWithContentButNoText_isSkipped() =
    runBlocking<Unit> {
      // Content is present (so the null-content guard doesn't apply) but has no usable text.
      val blankEvent =
        Event(
          id = "e-blank",
          author = Role.USER,
          content = Content(role = null, parts = listOf(Part(text = ""))),
          timestamp = 0L,
        )
      val session =
        Session(
          key = SessionKey("app-1", "user-1", "s-1"),
          state = State(),
          events = mutableListOf(blankEvent),
          lastUpdateTime = Clock.System.now(),
        )

      service.addSessionToMemory(session)

      assertThat(index.records).isEmpty()
    }

  @Test
  fun searchMemory_otherUser_returnsEmpty() =
    runBlocking<Unit> {
      service.addSessionToMemory(sessionWith("app-1", "user-1", "Hello world"))

      assertThat(service.searchMemory("app-1", "user-2", "hello").memories).isEmpty()
    }

  @Test
  fun searchMemory_otherApp_returnsEmpty() =
    runBlocking<Unit> {
      service.addSessionToMemory(sessionWith("app-1", "user-1", "Hello world"))

      assertThat(service.searchMemory("app-2", "user-1", "hello").memories).isEmpty()
    }

  @Test
  fun addEventsToMemory_sameEventIdReingested_overwrites() =
    runBlocking<Unit> {
      service.addEventsToMemory("app-1", "user-1", listOf(eventWith("e-1", "Hello world")))
      service.addEventsToMemory("app-1", "user-1", listOf(eventWith("e-1", "Updated text")))

      assertThat(index.records).hasSize(1)
      assertThat(service.searchMemory("app-1", "user-1", "hello").memories).isEmpty()
      assertThat(service.searchMemory("app-1", "user-1", "updated").memories).hasSize(1)
    }

  @Test
  fun addMemory_nullEntryId_generatesDocIdButReturnsNullId() =
    runBlocking<Unit> {
      service.addMemory(
        "app-1",
        "user-1",
        listOf(MemoryEntry(content = contentOf("A direct memory"))),
      )

      assertThat(index.records).hasSize(1)
      assertThat(index.records[0].id).isNotEmpty()

      val memories = service.searchMemory("app-1", "user-1", "direct").memories
      assertThat(memories).hasSize(1)
      assertThat(memories[0].id).isNull()
    }

  @Test
  fun addMemory_roundTripsAllFields() =
    runBlocking<Unit> {
      val memory =
        MemoryEntry(
          content = contentOf("Remember the Berlin trip"),
          id = "m-1",
          author = "assistant",
          timestamp = "2026-07-14T00:00:00Z",
          customMetadata = mapOf("topic" to "travel", "priority" to 3L),
        )
      service.addMemory("app-1", "user-1", listOf(memory))

      val got = service.searchMemory("app-1", "user-1", "berlin").memories.single()

      assertThat(got.id).isEqualTo("m-1")
      assertThat(got.author).isEqualTo("assistant")
      assertThat(got.timestamp).isEqualTo("2026-07-14T00:00:00Z")
      assertThat(got.content.parts[0].text).isEqualTo("Remember the Berlin trip")
      assertThat(got.customMetadata).containsExactly("topic", "travel", "priority", 3L)
    }

  @Test
  fun addMemory_mergesBatchAndPerEntryMetadata_perEntryWins() =
    runBlocking<Unit> {
      val memory =
        MemoryEntry(
          content = contentOf("Berlin trip"),
          customMetadata = mapOf("a" to "entry", "b" to "entryB"),
        )
      service.addMemory(
        "app-1",
        "user-1",
        listOf(memory),
        customMetadata = mapOf("a" to "batch", "c" to "batchC"),
      )

      val got = service.searchMemory("app-1", "user-1", "berlin").memories.single()

      // Per-entry "a" overrides batch "a"; batch-only "c" is included.
      assertThat(got.customMetadata).containsExactly("a", "entry", "b", "entryB", "c", "batchC")
    }

  @Test
  fun addEventsToMemory_persistsBatchMetadata() =
    runBlocking<Unit> {
      service.addEventsToMemory(
        "app-1",
        "user-1",
        listOf(eventWith("e-1", "Hello world")),
        customMetadata = mapOf("source" to "unit-test"),
      )

      val got = service.searchMemory("app-1", "user-1", "hello").memories.single()

      assertThat(got.customMetadata).containsExactly("source", "unit-test")
    }

  @Test
  fun addMemory_emptyTextMemory_isSkipped() =
    runBlocking<Unit> {
      service.addMemory(
        "app-1",
        "user-1",
        listOf(MemoryEntry(content = Content(parts = emptyList()))),
      )

      assertThat(index.records).isEmpty()
    }

  @Test
  fun addSessionToMemory_eventDerivedMemory_hasNullIdAndEventFields() =
    runBlocking<Unit> {
      val event =
        Event(id = "e-1", author = Role.USER, content = contentOf("Hello world"), timestamp = 0L)
      val session =
        Session(
          key = SessionKey("app-1", "user-1", "s-1"),
          state = State(),
          events = mutableListOf(event),
          lastUpdateTime = Clock.System.now(),
        )
      service.addSessionToMemory(session)

      val got = service.searchMemory("app-1", "user-1", "hello").memories.single()

      assertThat(got.id).isNull()
      assertThat(got.author).isEqualTo(Role.USER)
      assertThat(got.timestamp).isEqualTo("1970-01-01T00:00:00Z")
    }

  @Test
  fun searchMemory_blankQuery_returnsEmpty() =
    runBlocking<Unit> {
      service.addSessionToMemory(sessionWith("app-1", "user-1", "Hello world"))

      assertThat(service.searchMemory("app-1", "user-1", "   ").memories).isEmpty()
    }

  @Test
  fun searchMemory_emptyAppName_throws() =
    runBlocking<Unit> {
      val error = runCatching { service.searchMemory("", "user-1", "hello") }.exceptionOrNull()

      assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

  @Test
  fun searchMemory_appNameContainsNamespaceSeparator_throws() =
    runBlocking<Unit> {
      val error =
        runCatching { service.searchMemory("app\u001Fx", "user-1", "hello") }.exceptionOrNull()

      assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

  @Test
  fun close_closesUnderlyingIndex() =
    runBlocking<Unit> {
      service.close()

      assertThat(index.closed).isTrue()
    }

  private fun contentOf(text: String): Content =
    Content(role = null, parts = listOf(Part(text = text)))

  private fun eventWith(id: String, text: String): Event =
    Event(id = id, author = Role.USER, content = contentOf(text), timestamp = 0L)

  private fun sessionWith(appName: String, userId: String, text: String): Session =
    Session(
      key = SessionKey(appName, userId, "s-1"),
      state = State(),
      events = mutableListOf(Event(author = Role.USER, content = contentOf(text), timestamp = 0L)),
      lastUpdateTime = Clock.System.now(),
    )
}

/**
 * In-memory [MemoryIndex] fake: upserts by `(namespace, id)` and matches records by word overlap.
 */
private class FakeMemoryIndex : MemoryIndex {

  val records = mutableListOf<MemoryRecord>()
  var closed = false

  override suspend fun put(records: List<MemoryRecord>) {
    for (record in records) {
      this.records.removeAll { it.namespace == record.namespace && it.id == record.id }
      this.records.add(record)
    }
  }

  override suspend fun search(namespace: String, query: String): List<MemoryRecord> {
    val tokens = query.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
    // Mimic AppSearch: a query with no terms matches everything in the namespace. This is why
    // AppSearchMemoryService guards blank queries before delegating to the index.
    return records.filter { record ->
      record.namespace == namespace &&
        (tokens.isEmpty() || tokens.any { record.text.lowercase().contains(it) })
    }
  }

  override fun close() {
    closed = true
  }
}
