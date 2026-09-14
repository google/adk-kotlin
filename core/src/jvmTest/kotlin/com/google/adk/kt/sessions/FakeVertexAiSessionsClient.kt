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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.dto.ListEventsResponseDto
import com.google.adk.kt.sessions.dto.ListSessionsResponseDto
import com.google.adk.kt.sessions.dto.SessionDto
import com.google.adk.kt.sessions.dto.SessionEventDto
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * In-memory stateful fake of [VertexAiSessionsClient] for round-trip tests. It records appended
 * events per session and applies their (already `temp:`-trimmed) state delta to the stored session
 * state, so [getSession] / [listEvents] serve back what was written -- close enough to the Vertex
 * backend to exercise [VertexAiSessionService] round-trips without the network. The injected
 * [GoogleApiClient] carries a dummy access token and is never touched, since every transport method
 * is overridden here.
 */
@OptIn(FrameworkInternalApi::class)
internal class FakeVertexAiSessionsClient :
  VertexAiSessionsClient(
    apiClient = GoogleApiClient(credentials = GoogleCredentials.create(AccessToken("test", null)))
  ) {

  private class Stored(
    val userId: String,
    val state: MutableMap<String, JsonElement> = mutableMapOf(),
    val events: MutableList<SessionEventDto> = mutableListOf(),
  )

  private val sessions = mutableMapOf<String, Stored>()
  private var nextId = 0

  private fun sessionName(engine: ReasoningEngineRef, id: String): String =
    "projects/${engine.project}/locations/${engine.location}/reasoningEngines/${engine.id}/" +
      "sessions/$id"

  private fun dto(engine: ReasoningEngineRef, id: String, stored: Stored): SessionDto =
    SessionDto(
      name = sessionName(engine, id),
      userId = stored.userId,
      sessionState = JsonObject(stored.state.toMap()),
    )

  override suspend fun createSession(
    engine: ReasoningEngineRef,
    userId: String,
    state: Map<String, Any>?,
    ttl: Duration?,
    expireTime: Instant?,
    sessionId: String?,
  ): Result<SessionDto> {
    val id = sessionId ?: "s${nextId++}"
    val stored = Stored(userId = userId)
    (state?.let { anyToJsonElement(it) } as? JsonObject)?.forEach { (k, v) -> stored.state[k] = v }
    sessions[id] = stored
    return Result.success(dto(engine, id, stored))
  }

  override suspend fun getSession(
    engine: ReasoningEngineRef,
    sessionId: String,
  ): Result<SessionDto?> = Result.success(sessions[sessionId]?.let { dto(engine, sessionId, it) })

  override suspend fun listSessions(
    engine: ReasoningEngineRef,
    userId: String,
  ): Result<ListSessionsResponseDto?> =
    Result.success(
      ListSessionsResponseDto(
        sessions =
          sessions
            .filterValues { it.userId == userId }
            .map { (id, stored) -> dto(engine, id, stored) }
      )
    )

  override suspend fun listEvents(
    engine: ReasoningEngineRef,
    sessionId: String,
    filter: String?,
  ): Result<ListEventsResponseDto?> =
    Result.success(
      sessions[sessionId]?.let { ListEventsResponseDto(sessionEvents = it.events.toList()) }
    )

  override suspend fun deleteSession(engine: ReasoningEngineRef, sessionId: String): Result<Unit> {
    sessions.remove(sessionId)
    return Result.success(Unit)
  }

  override suspend fun appendEvent(
    engine: ReasoningEngineRef,
    sessionId: String,
    event: SessionEventDto,
  ): Result<Unit> {
    val stored =
      sessions[sessionId] ?: return Result.failure(IllegalStateException("session not found"))
    stored.events.add(
      event.copy(name = sessionName(engine, sessionId) + "/events/e${stored.events.size}")
    )
    // The delta reaches the client already `temp:`-trimmed by the base appendEvent; apply it to the
    // stored state, mirroring the backend (a JSON null clears a key).
    (event.actions?.stateDelta as? JsonObject)?.forEach { (k, v) ->
      if (v is JsonNull) stored.state.remove(k) else stored.state[k] = v
    }
    return Result.success(Unit)
  }
}
