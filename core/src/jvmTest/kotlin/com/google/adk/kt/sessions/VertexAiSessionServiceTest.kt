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
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.sessions.dto.ListEventsResponseDto
import com.google.adk.kt.sessions.dto.ListSessionsResponseDto
import com.google.adk.kt.sessions.dto.SessionDto
import com.google.adk.kt.sessions.dto.SessionEventDto
import com.google.adk.kt.sessions.dto.TimestampDto
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

/**
 * Unit tests for [VertexAiSessionService].
 *
 * These exercise the service-level logic (engine resolution, DTO-to-domain mapping, event
 * filtering/sorting, the append-event write-through and error propagation) against a mocked
 * [VertexAiSessionsClient], so no HTTP transport is involved. The transport itself is covered by
 * `VertexAiSessionsClientTest`.
 */
@RunWith(JUnit4::class)
class VertexAiSessionServiceTest {

  private fun service(client: VertexAiSessionsClient) =
    VertexAiSessionService(
      client,
      project = PROJECT,
      location = LOCATION,
      reasoningEngineId = ENGINE_ID,
    )

  @Test
  fun addressesConfiguredEngineRegardlessOfAppName() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { createSession(any(), any(), anyOrNull()) } doReturn
          Result.success(SessionDto(name = "reasoningEngines/123/sessions/s"))
      }

    // The app name is only a label; the service always addresses the engine set at construction.
    val unused =
      service(client).createSession(SessionKey("any-label", "user", id = null), state = null)

    verifyBlocking(client) { createSession(eq(ENGINE), eq("user"), anyOrNull()) }
  }

  @Test
  fun constructor_blankReasoningEngineId_throws() {
    assertFailsWith<IllegalArgumentException> {
      VertexAiSessionService(
        mock<VertexAiSessionsClient>(),
        project = PROJECT,
        location = LOCATION,
        reasoningEngineId = "",
      )
    }
  }

  @Test
  fun constructor_resourceNameReasoningEngineId_throws() {
    // A full resource name is rejected: reasoningEngineId must be the bare numeric id, with project
    // and location passed separately.
    assertFailsWith<IllegalArgumentException> {
      VertexAiSessionService(
        mock<VertexAiSessionsClient>(),
        project = PROJECT,
        location = LOCATION,
        reasoningEngineId = "projects/p/locations/l/reasoningEngines/123",
      )
    }
  }

  @Test
  fun validateSessionId_rejectsPathEscapingIds() {
    for (bad in listOf("a/b", "..", "a?b", "a#b", "a b", "")) {
      assertFailsWith<IllegalArgumentException> { VertexAiSessionService.validateSessionId(bad) }
    }
  }

  @Test
  fun validateSessionId_acceptsAllowlistedIds() {
    // No exception for ids restricted to [A-Za-z0-9_-].
    VertexAiSessionService.validateSessionId("abc-123_XYZ")
  }

  @Test
  fun getSession_invalidSessionId_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service(mock<VertexAiSessionsClient>()).getSession(SessionKey("123", "user", "bad/id"))
    }
  }

  @Test
  fun appendEvent_invalidSessionId_throws() = runTest {
    val session = Session(SessionKey("123", "user", ".."))

    assertFailsWith<IllegalArgumentException> {
      service(mock<VertexAiSessionsClient>())
        .appendEvent(session, Event(author = "user", timestamp = 1000L))
    }
  }

  @Test
  fun createSession_mapsClientResponse() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { createSession(eq(ENGINE), eq("user"), anyOrNull()) } doReturn
          Result.success(
            SessionDto(
              name = "reasoningEngines/123/sessions/session-1",
              updateTime = "2024-12-12T12:12:12Z",
              sessionState = JsonObject(mapOf("k" to JsonPrimitive("v"))),
            )
          )
      }

    val session =
      service(client).createSession(SessionKey("123", "user", id = null), mapOf("k" to "v"))

    assertThat(session.key.id).isEqualTo("session-1")
    assertThat(session.key.appName).isEqualTo("123")
    assertThat(session.key.userId).isEqualTo("user")
    assertThat(session.state["k"]).isEqualTo("v")
  }

  @Test
  fun createSession_clientFails_propagates() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { createSession(any(), any(), anyOrNull()) } doReturn
          Result.failure(IOException("boom"))
      }

    assertFailsWith<IOException> {
      service(client).createSession(SessionKey("123", "user", id = null), state = null)
    }
  }

  @Test
  fun getSession_notFound_returnsNull() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { getSession(eq(ENGINE), eq("missing")) } doReturn
          Result.success<SessionDto?>(null)
      }

    assertThat(service(client).getSession(SessionKey("123", "user", "missing"))).isNull()
  }

  @Test
  fun getSession_clientFails_propagates() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { getSession(any(), any()) } doReturn Result.failure(IOException("boom"))
      }

    assertFailsWith<IOException> { service(client).getSession(SessionKey("123", "user", "s1")) }
  }

  @Test
  fun getSession_numRecentEvents_returnsMostRecentSorted() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { getSession(eq(ENGINE), eq("s1")) } doReturn
          Result.success(
            SessionDto(
              name = "reasoningEngines/123/sessions/s1",
              updateTime = "2024-12-12T12:00:30Z",
            )
          )
        onBlocking { listEvents(eq(ENGINE), eq("s1"), anyOrNull()) } doReturn
          Result.success(
            ListEventsResponseDto(
              sessionEvents =
                listOf(eventDto("e3", 3000), eventDto("e1", 1000), eventDto("e2", 2000))
            )
          )
      }

    val session =
      service(client)
        .getSession(SessionKey("123", "user", "s1"), GetSessionConfig(numRecentEvents = 2))

    assertThat(session!!.events.map { it.id }).containsExactly("e2", "e3").inOrder()
  }

  @Test
  fun getSession_afterTimestamp_passesInclusiveServerFilter() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { getSession(eq(ENGINE), eq("s1")) } doReturn
          Result.success(SessionDto(name = "reasoningEngines/123/sessions/s1"))
        onBlocking { listEvents(eq(ENGINE), eq("s1"), anyOrNull()) } doReturn
          Result.success(ListEventsResponseDto())
      }
    val threshold = Instant.parse("2024-12-12T12:00:10Z")

    val unused =
      service(client)
        .getSession(SessionKey("123", "user", "s1"), GetSessionConfig(afterTimestamp = threshold))

    verifyBlocking(client) { listEvents(eq(ENGINE), eq("s1"), eq("timestamp>=\"$threshold\"")) }
  }

  @Test
  fun getSession_numRecentEventsAndAfterTimestamp_appliesBothFilters() = runTest {
    val threshold = Instant.parse("2024-12-12T12:00:10Z")
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { getSession(eq(ENGINE), eq("s1")) } doReturn
          Result.success(SessionDto(name = "reasoningEngines/123/sessions/s1"))
        // Server returns events >= threshold; client trims to 2 most recent.
        onBlocking { listEvents(eq(ENGINE), eq("s1"), anyOrNull()) } doReturn
          Result.success(
            ListEventsResponseDto(
              sessionEvents =
                listOf(eventDto("e2", 2000), eventDto("e3", 3000), eventDto("e4", 4000))
            )
          )
      }

    val session =
      service(client)
        .getSession(
          SessionKey("123", "user", "s1"),
          GetSessionConfig(numRecentEvents = 2, afterTimestamp = threshold),
        )

    // afterTimestamp is sent to the server even with numRecentEvents set; both apply.
    verifyBlocking(client) { listEvents(eq(ENGINE), eq("s1"), eq("timestamp>=\"$threshold\"")) }
    assertThat(session!!.events.map { it.id }).containsExactly("e3", "e4").inOrder()
  }

  @Test
  fun getSession_missingId_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service(mock<VertexAiSessionsClient>()).getSession(SessionKey("123", "user", id = null))
    }
  }

  @Test
  fun listSessions_mapsClientResponse() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { listSessions(eq(ENGINE), eq("user")) } doReturn
          Result.success(
            ListSessionsResponseDto(
              sessions =
                listOf(
                  SessionDto(name = "reasoningEngines/123/sessions/1"),
                  SessionDto(name = "reasoningEngines/123/sessions/2"),
                )
            )
          )
      }

    val response = service(client).listSessions("123", "user")

    assertThat(response.sessions.map { it.key.id }).containsExactly("1", "2").inOrder()
  }

  @Test
  fun listSessions_clientReturnsNull_returnsEmpty() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { listSessions(any(), any()) } doReturn
          Result.success<ListSessionsResponseDto?>(null)
      }

    assertThat(service(client).listSessions("123", "user").sessions).isEmpty()
  }

  @Test
  fun listEvents_returnsEventsInServerOrder() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { listEvents(eq(ENGINE), eq("s1"), anyOrNull()) } doReturn
          Result.success(
            ListEventsResponseDto(
              sessionEvents =
                listOf(eventDto("e2", 2000), eventDto("e3", 3000), eventDto("e1", 1000))
            )
          )
      }

    val response = service(client).listEvents(SessionKey("123", "user", "s1"))

    // listEvents does not re-sort; it returns events in the order the server sent them.
    assertThat(response.events.map { it.id }).containsExactly("e2", "e3", "e1").inOrder()
  }

  @Test
  fun deleteSession_delegatesToClient() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { deleteSession(any(), any()) } doReturn Result.success(Unit)
      }

    service(client).deleteSession(SessionKey("123", "user", "s1"))

    verifyBlocking(client) { deleteSession(eq(ENGINE), eq("s1")) }
  }

  @Test
  fun deleteSession_missingId_throws() = runTest {
    assertFailsWith<IllegalArgumentException> {
      service(mock<VertexAiSessionsClient>()).deleteSession(SessionKey("123", "user", id = null))
    }
  }

  @Test
  fun appendEvent_nonPartial_writesThroughAndUpdatesSession() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { appendEvent(any(), any(), any()) } doReturn Result.success(Unit)
      }
    val session = Session(SessionKey("123", "user", "s1"))
    val event =
      Event(
        author = "user",
        timestamp = 1000L,
        content = Content(role = "user", parts = listOf(Part(text = "hi"))),
        actions = EventActions(stateDelta = mutableMapOf<String, Any>("k" to "v")),
      )

    val returned = service(client).appendEvent(session, event)

    assertThat(returned).isEqualTo(event)
    verifyBlocking(client) { appendEvent(eq(ENGINE), eq("s1"), any()) }
    // super.appendEvent keeps the in-memory session in sync.
    assertThat(session.events).contains(event)
    assertThat(session.state["k"]).isEqualTo("v")
  }

  @Test
  fun appendEvent_partial_persistsRemotelyButNotInMemory() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { appendEvent(any(), any(), any()) } doReturn Result.success(Unit)
      }
    val session = Session(SessionKey("123", "user", "s1"))
    val event = Event(author = "user", partial = true, timestamp = 1000L)

    val unused = service(client).appendEvent(session, event)

    // Matching the Java/Python ADK: a partial event is still written to the remote service, but the
    // base implementation does not add it to the in-memory session.
    verifyBlocking(client) { appendEvent(eq(ENGINE), eq("s1"), any()) }
    assertThat(session.events).isEmpty()
  }

  @Test
  fun appendEvent_clientFails_propagates() = runTest {
    val client =
      mock<VertexAiSessionsClient> {
        onBlocking { appendEvent(any(), any(), any()) } doReturn
          Result.failure(IOException("append failed"))
      }
    val session = Session(SessionKey("123", "user", "s1"))

    assertFailsWith<IOException> {
      service(client).appendEvent(session, Event(author = "user", timestamp = 1000L))
    }
  }

  private companion object {
    const val PROJECT = "test-project"
    const val LOCATION = "test-location"
    const val ENGINE_ID = "123"
    val ENGINE = ReasoningEngineRef(PROJECT, LOCATION, ENGINE_ID)

    fun eventDto(id: String, epochMillis: Long): SessionEventDto =
      SessionEventDto(
        name = "reasoningEngines/123/sessions/s1/events/$id",
        author = "agent",
        timestamp = TimestampDto.fromEpochMillis(epochMillis),
      )
  }
}
