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
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.sessions.dto.toAdk
import com.google.adk.kt.sessions.dto.toDto
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

/**
 * A [SessionService] backed by the managed Vertex AI Session Service.
 *
 * This is a Kotlin port of the Java ADK `com.google.adk.sessions.VertexAiSessionService`. It talks
 * to the service through a [VertexAiSessionsClient] over a shared [GoogleApiClient]. The wire
 * format (defined by `session.proto`) is modeled with a small set of `@Serializable` DTOs in the
 * `dto` sub-package; this class only translates between those DTOs and the ADK domain types via the
 * mapper extensions in `com.google.adk.kt.sessions.dto.SessionMappers`.
 *
 * The reasoning engine is fixed at construction from [project], [location], and
 * [reasoningEngineId]. Unlike the Python and Java ADK, the session key's [SessionKey.appName] is
 * never parsed to derive the engine - it is only a label - so the engine must be supplied
 * explicitly here.
 *
 * @property client The [VertexAiSessionsClient] used to talk to the Vertex AI Session API.
 * @property project The Google Cloud project id used to address the API.
 * @property location The Google Cloud location used to address the API.
 * @property reasoningEngineId The numeric id of the reasoning engine to address.
 */
class VertexAiSessionService
internal constructor(
  private val client: VertexAiSessionsClient,
  private val project: String,
  private val location: String,
  private val reasoningEngineId: String,
) : SessionService {

  init {
    require(reasoningEngineId.isNotBlank()) { "reasoningEngineId must not be blank." }
    require(reasoningEngineId.all { it.isDigit() }) {
      "reasoningEngineId must be the numeric reasoning engine id (e.g. \"1234567890\"), not a" +
        " resource name; pass project and location as separate arguments. Got: $reasoningEngineId"
    }
  }

  private val engine = ReasoningEngineRef(project, location, reasoningEngineId)

  /**
   * Creates a service for reasoning engine [reasoningEngineId] under [project] and [location].
   *
   * @param project The Google Cloud project id used to address the API.
   * @param location The Google Cloud location; `"global"` selects the global endpoint.
   * @param reasoningEngineId The numeric id of the reasoning engine to address.
   * @param credentials Credentials for the Vertex AI API; defaults to application-default
   *   credentials scoped for Google Cloud Platform.
   * @param httpClient The underlying ktor [HttpClient].
   */
  constructor(
    project: String,
    location: String,
    reasoningEngineId: String,
    credentials: GoogleCredentials = GoogleApiClient.defaultCredentials(),
    httpClient: HttpClient = HttpClient(Java),
  ) : this(
    VertexAiSessionsClient(GoogleApiClient(httpClient, credentials)),
    project,
    location,
    reasoningEngineId,
  )

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session {
    val sessionDto = client.createSession(engine, key.userId, state).getOrThrow()
    return sessionDto.toAdk(key.appName, key.userId, key.id)
  }

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    val sessionId = requireNotNull(key.id) { "SessionKey.id is required for getSession." }
    validateSessionId(sessionId)
    val sessionDto = client.getSession(engine, sessionId).getOrThrow() ?: return null
    val session = sessionDto.toAdk(key.appName, key.userId, sessionId)

    val eventsResponse =
      client.listEvents(engine, sessionId, afterTimestampFilter(config)).getOrThrow()
    val events = eventsResponse?.sessionEvents?.map { it.toAdk() } ?: emptyList()
    session.events.addAll(filterEvents(events, config))
    return session
  }

  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
    val response = client.listSessions(engine, userId).getOrThrow() ?: return ListSessionsResponse()
    val sessions =
      response.sessions?.map { it.toAdk(appName, userId, fallbackId = null) } ?: emptyList()
    return ListSessionsResponse(sessions)
  }

  override suspend fun deleteSession(key: SessionKey) {
    val sessionId = requireNotNull(key.id) { "SessionKey.id is required for deleteSession." }
    validateSessionId(sessionId)
    client.deleteSession(engine, sessionId).getOrThrow()
  }

  override suspend fun listEvents(key: SessionKey): ListEventsResponse {
    val sessionId = requireNotNull(key.id) { "SessionKey.id is required for listEvents." }
    validateSessionId(sessionId)
    val response = client.listEvents(engine, sessionId).getOrThrow() ?: return ListEventsResponse()
    val events = response.sessionEvents?.map { it.toAdk() } ?: emptyList()
    return ListEventsResponse(events)
  }

  override suspend fun appendEvent(session: Session, event: Event): Event {
    val sessionId = requireNotNull(session.key.id) { "Session.key.id is required for appendEvent." }
    validateSessionId(sessionId)
    val appended = super.appendEvent(session, event)
    client.appendEvent(engine, sessionId, appended.toDto()).getOrThrow()
    return appended
  }

  /**
   * Trims the events to [GetSessionConfig.numRecentEvents] after sorting by timestamp. The
   * [GetSessionConfig.afterTimestamp] filter is applied server-side (see [afterTimestampFilter]) so
   * it is not re-applied here, matching the Java implementation.
   */
  private fun filterEvents(events: List<Event>, config: GetSessionConfig?): List<Event> {
    val sorted = events.sortedBy { it.timestamp }
    val numRecentEvents = config?.numRecentEvents ?: return sorted
    return if (sorted.size > numRecentEvents) {
      sorted.subList(sorted.size - numRecentEvents, sorted.size)
    } else {
      sorted
    }
  }

  companion object {
    /**
     * Allowed session id characters. Matches the Java/Python ADK allowlist and keeps the id within
     * a single URL path segment (no `/`, `?`, `#`, or `..`).
     */
    private val SESSION_ID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

    /** Rejects session ids that could escape the URL path segment. */
    internal fun validateSessionId(sessionId: String) {
      require(SESSION_ID_PATTERN.matches(sessionId)) {
        "Invalid session id: $sessionId. It must match ${SESSION_ID_PATTERN.pattern}."
      }
    }

    /**
     * Builds the inclusive server-side `timestamp>=` filter for [GetSessionConfig.afterTimestamp].
     * The filter is only applied when [GetSessionConfig.numRecentEvents] is not set, matching the
     * precedence in [filterEvents].
     */
    private fun afterTimestampFilter(config: GetSessionConfig?): String? {
      if (config?.numRecentEvents == null && config?.afterTimestamp != null) {
        return "timestamp>=\"${config.afterTimestamp}\""
      }
      return null
    }
  }
}
