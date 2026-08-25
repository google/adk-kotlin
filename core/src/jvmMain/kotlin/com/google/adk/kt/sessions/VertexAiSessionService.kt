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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.sessions.dto.toAdk
import com.google.adk.kt.sessions.dto.toDto
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.time.Duration as JavaDuration
import java.time.Instant as JavaInstant
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinDuration
import kotlin.time.toKotlinInstant

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

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): Session =
    createSession(key, state, ttl = null, expireTime = null)

  /**
   * Creates a session that expires, addressing the reasoning engine fixed at construction.
   *
   * At most one of [ttl] and [expireTime] may be set, because the backend models them as a single
   * choice; setting both is rejected. The backend also requires the expiry to be at least 24 hours
   * out, and applies its own default when neither is given.
   *
   * @param key The composite identifier of the session; [SessionKey.appName] is only a label.
   * @param state An optional map representing the initial state of the session.
   * @param ttl How long the session lives, measured from creation. Sub-second precision is dropped.
   * @param expireTime The absolute instant at which the session expires.
   * @return The newly created [Session].
   */
  suspend fun createSession(
    key: SessionKey,
    state: Map<String, Any>? = null,
    ttl: Duration? = null,
    expireTime: Instant? = null,
  ): Session {
    require(ttl == null || expireTime == null) {
      "Cannot specify both ttl and expireTime simultaneously."
    }
    // The wire format is whole seconds, so a sub-second ttl would silently travel as "0s".
    require(ttl == null || ttl.inWholeSeconds > 0) {
      "ttl must be at least one second, but was $ttl."
    }
    val sessionDto = client.createSession(engine, key.userId, state, ttl, expireTime).getOrThrow()
    return sessionDto.toAdk(key.appName, key.userId, key.id)
  }

  /**
   * Creates a session that lives for [ttl], provided primarily for Java callers.
   *
   * Java cannot call [createSession] with a [Duration], whose JVM name is mangled because
   * [Duration] is a value class. A [ttl] under one second, or the backend minimum of 24 hours, is
   * rejected.
   */
  @AdkJavaInteropApi
  suspend fun createSessionWithTtl(
    key: SessionKey,
    state: Map<String, Any>?,
    ttl: JavaDuration,
  ): Session = createSession(key, state, ttl = ttl.toKotlinDuration())

  /**
   * Creates a session expiring at [expireTime], provided primarily for Java callers.
   *
   * It reaches the expiry arm that [createSessionWithTtl] cannot, and the backend requires the
   * instant to be at least 24 hours out.
   */
  @AdkJavaInteropApi
  suspend fun createSessionWithExpireTime(
    key: SessionKey,
    state: Map<String, Any>?,
    expireTime: JavaInstant,
  ): Session = createSession(key, state, expireTime = expireTime.toKotlinInstant())

  override suspend fun getSession(key: SessionKey, config: GetSessionConfig?): Session? {
    val sessionId = requireNotNull(key.id) { "SessionKey.id is required for getSession." }
    validateSessionId(sessionId)
    val sessionDto = client.getSession(engine, sessionId).getOrThrow() ?: return null
    // Deny cross-user reads as not-found so a session's existence isn't revealed.
    if (sessionDto.userId != key.userId) return null
    val session = sessionDto.toAdk(key.appName, key.userId, sessionId)

    val eventsResponse =
      client.listEvents(engine, sessionId, afterTimestampFilter(config)).getOrThrow()
    val events = eventsResponse?.sessionEvents?.map { it.toAdk() } ?: emptyList()
    session.events.addAll(filterEvents(events, config))
    return session
  }

  override suspend fun listSessions(appName: String, userId: String): ListSessionsResponse {
    val response = client.listSessions(engine, userId).getOrThrow() ?: return ListSessionsResponse()
    // Report the backend-provided owner, not the caller's user id.
    val sessions =
      response.sessions?.map { it.toAdk(appName, it.userId ?: userId, fallbackId = null) }
        ?: emptyList()
    return ListSessionsResponse(sessions)
  }

  override suspend fun deleteSession(key: SessionKey) {
    val sessionId = requireNotNull(key.id) { "SessionKey.id is required for deleteSession." }
    validateSessionId(sessionId)
    // Backend delete ignores the user id, so enforce ownership first; a missing session is a no-op.
    val sessionDto = client.getSession(engine, sessionId).getOrThrow() ?: return
    if (sessionDto.userId != key.userId) {
      throw SecurityException("Session $sessionId does not belong to user ${key.userId}.")
    }
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
   * Keeps the last [GetSessionConfig.numRecentEvents] after sorting by timestamp.
   * [GetSessionConfig.afterTimestamp] is filtered server-side (see [afterTimestampFilter]), so both
   * filters compose. Mirrors the Go port.
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

  /**
   * Fluent builder for [VertexAiSessionService], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var project: String? = null
    private var location: String? = null
    private var reasoningEngineId: String? = null
    private var credentials: GoogleCredentials? = null
    private var httpClient: HttpClient? = null

    fun project(project: String): Builder = apply { this.project = project }

    fun location(location: String): Builder = apply { this.location = location }

    fun reasoningEngineId(reasoningEngineId: String): Builder = apply {
      this.reasoningEngineId = reasoningEngineId
    }

    fun credentials(credentials: GoogleCredentials): Builder = apply {
      this.credentials = credentials
    }

    fun httpClient(httpClient: HttpClient): Builder = apply { this.httpClient = httpClient }

    fun build(): VertexAiSessionService =
      VertexAiSessionService(
        project =
          checkNotNull(project) { "VertexAiSessionService.Builder requires project to be set." },
        location =
          checkNotNull(location) { "VertexAiSessionService.Builder requires location to be set." },
        reasoningEngineId =
          checkNotNull(reasoningEngineId) {
            "VertexAiSessionService.Builder requires reasoningEngineId to be set."
          },
        credentials = credentials ?: GoogleApiClient.defaultCredentials(),
        httpClient = httpClient ?: HttpClient(Java),
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

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
     * Inclusive server-side `timestamp>=` filter for [GetSessionConfig.afterTimestamp], or null.
     */
    private fun afterTimestampFilter(config: GetSessionConfig?): String? {
      val afterTimestamp = config?.afterTimestamp ?: return null
      return "timestamp>=\"$afterTimestamp\""
    }
  }
}
