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

package com.google.adk.kt.interop

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.SearchMemoryResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class CompletableFutureMemoryServiceTest {

  private val memory = CompletableFutureMemoryService.of(InMemoryMemoryService())

  @Test
  fun addSessionToMemory_makesTheSessionSearchable() {
    val session = Session(key = SessionKey("app", "user", "session"))
    session.events.add(event("My cat is called Tom"))

    memory.addSessionToMemory(session).get(5, SECONDS)

    assertThat(searchTexts("cat")).containsExactly("My cat is called Tom")
  }

  @Test
  fun addEventsToMemory_makesTheEventsSearchable() {
    memory.addEventsToMemory("app", "user", listOf(event("Tom likes fish"))).get(5, SECONDS)

    assertThat(searchTexts("fish")).containsExactly("Tom likes fish")
  }

  @Test
  fun addMemory_makesTheMemorySearchable() {
    val entry = MemoryEntry(content = Content.fromText(Role.USER, "Tom sleeps all day"))

    memory.addMemory("app", "user", listOf(entry)).get(5, SECONDS)

    assertThat(searchTexts("sleeps")).containsExactly("Tom sleeps all day")
  }

  @Test
  fun addEventsToMemory_forwardsTheSessionIdAndMetadata() {
    val received = mutableListOf<Pair<String?, Map<String, Any?>?>>()
    val recording =
      object : MemoryService by InMemoryMemoryService() {
        override suspend fun addEventsToMemory(
          appName: String,
          userId: String,
          events: List<Event>,
          sessionId: String?,
          customMetadata: Map<String, Any?>?,
        ) {
          received.add(sessionId to customMetadata)
        }
      }

    CompletableFutureMemoryService.of(recording)
      .addEventsToMemory("app", "user", emptyList(), "session", mapOf("source" to "chat"))
      .get(5, SECONDS)

    assertThat(received).containsExactly("session" to mapOf("source" to "chat"))
  }

  @Test
  fun addMemory_forwardsTheMetadata() {
    val received = mutableListOf<Map<String, Any?>?>()
    val recording =
      object : MemoryService by InMemoryMemoryService() {
        override suspend fun addMemory(
          appName: String,
          userId: String,
          memories: List<MemoryEntry>,
          customMetadata: Map<String, Any?>?,
        ) {
          received.add(customMetadata)
        }
      }

    CompletableFutureMemoryService.of(recording)
      .addMemory("app", "user", emptyList(), mapOf("source" to "chat"))
      .get(5, SECONDS)

    assertThat(received).containsExactly(mapOf("source" to "chat"))
  }

  @Test
  fun addEventsToMemory_unsupportedByTheService_failsTheFuture() {
    val sessionsOnly =
      object : MemoryService {
        override suspend fun addSessionToMemory(session: Session) {}

        override suspend fun searchMemory(appName: String, userId: String, query: String) =
          SearchMemoryResponse(memories = emptyList())
      }
    val future =
      CompletableFutureMemoryService.of(sessionsOnly).addEventsToMemory("app", "user", emptyList())

    val thrown = assertFailsWith<ExecutionException> { future.get(5, SECONDS) }

    assertThat(thrown).hasCauseThat().isInstanceOf(UnsupportedOperationException::class.java)
  }

  @Test
  fun close_cancelsCallsInFlightAndLaterCalls(): Unit = runBlocking {
    val hanging = HangingMemoryService()
    val wrapper = CompletableFutureMemoryService.of(hanging)
    val inFlight = wrapper.searchMemory("app", "user", "cat")
    hanging.started.await()

    wrapper.close()
    val later = wrapper.searchMemory("app", "user", "cat")

    withTimeout(5.seconds) { hanging.canceled.await() }
    assertFailsWith<CancellationException> { inFlight.get(5, SECONDS) }
    assertFailsWith<CancellationException> { later.get(5, SECONDS) }
    assertThat(hanging.calls.get()).isEqualTo(1)
  }

  @Test
  fun close_autoCloseableService_closesTheService() {
    var closed = false
    val closeable =
      object : MemoryService by InMemoryMemoryService(), AutoCloseable {
        override fun close() {
          closed = true
        }
      }

    CompletableFutureMemoryService.of(closeable).close()

    assertThat(closed).isTrue()
  }

  private fun event(text: String) =
    Event(author = "user", content = Content.fromText(Role.USER, text))

  private fun searchTexts(query: String): List<String?> =
    memory.searchMemory("app", "user", query).get(5, SECONDS).memories.map {
      it.content.parts.single().text
    }

  /** A memory service whose [searchMemory] suspends until it is canceled. */
  private class HangingMemoryService : MemoryService by InMemoryMemoryService() {
    val calls = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val canceled = CompletableDeferred<Unit>()

    override suspend fun searchMemory(
      appName: String,
      userId: String,
      query: String,
    ): SearchMemoryResponse {
      calls.incrementAndGet()
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        canceled.complete(Unit)
      }
    }
  }
}
