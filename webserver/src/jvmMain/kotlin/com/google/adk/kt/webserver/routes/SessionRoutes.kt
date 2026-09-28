/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.webserver.models.CreateSessionRequest
import com.google.adk.kt.webserver.models.SessionDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal data class SessionRoutesError(val message: String, val code: HttpStatusCode)

internal object SessionRoutesErrors {
  val ERR_MISSING_APP_NAME = SessionRoutesError("Missing appName", HttpStatusCode.BadRequest)
  val ERR_MISSING_USER_ID = SessionRoutesError("Missing userId", HttpStatusCode.BadRequest)
  val ERR_MISSING_SESSION_ID = SessionRoutesError("Missing sessionId", HttpStatusCode.BadRequest)
  val ERR_SESSION_NOT_FOUND = SessionRoutesError("Session not found", HttpStatusCode.NotFound)
  val ERR_SESSION_ALREADY_EXISTS =
    SessionRoutesError("Session already exists", HttpStatusCode.Conflict)
  val ERR_MALFORMED_BODY =
    SessionRoutesError("Malformed session initialization request", HttpStatusCode.BadRequest)
  val ERR_UNPROCESSABLE_BODY =
    SessionRoutesError(
      "Session initialization request does not fit the expected shape",
      HttpStatusCode.UnprocessableEntity,
    )
  val ERR_INVALID_SESSION_ID = SessionRoutesError("Invalid sessionId", HttpStatusCode.BadRequest)
  val ERR_UNSUPPORTED_MEDIA_TYPE =
    SessionRoutesError("Content-Type must be application/json", HttpStatusCode.UnsupportedMediaType)
  val ERR_MIXED_SEED_INVOCATION_IDS =
    SessionRoutesError(
      "Session initialization events must all carry an invocationId or all omit it",
      HttpStatusCode.BadRequest,
    )
}

/** The outcome of creating a session from a request body, so a route can answer either way. */
private sealed class CreateSessionOutcome {
  data class Created(val session: Session) : CreateSessionOutcome()

  data class Failed(val error: SessionRoutesError) : CreateSessionOutcome()
}

/** A session-initialization body that was read, or the error explaining why it was not. */
private sealed class SessionInitBody {
  data class Parsed(val request: CreateSessionRequest, val rawEvents: JsonArray?) :
    SessionInitBody()

  data class Rejected(val error: SessionRoutesError) : SessionInitBody()
}

private fun seedEventError(index: Int, disallowed: String) =
  SessionRoutesError(
    "Session initialization event $index cannot include $disallowed.",
    HttpStatusCode.BadRequest,
  )

/**
 * Reads the optional session-initialization body; a request carrying none - a blank body or a
 * literal `null` - is valid, as it is on the reference server. A non-empty body must be JSON: a
 * missing `Content-Type` is read as JSON (matching the reference), but any other type answers 415,
 * which closes the `text/plain` cross-site seeding vector that skips the CORS preflight. Returns
 * the raw `events` array beside the decoded request so each seed event's `actions` can be judged
 * before the decoder drops unknown keys.
 */
@OptIn(FrameworkInternalApi::class)
private suspend fun ApplicationCall.receiveSessionInitBody(): SessionInitBody {
  val raw =
    try {
      receiveText()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // An undecodable charset throws here, before anything is parsed.
      return SessionInitBody.Rejected(SessionRoutesErrors.ERR_MALFORMED_BODY)
    }
  if (raw.isBlank()) return SessionInitBody.Parsed(CreateSessionRequest(), rawEvents = null)
  // A non-empty body must be JSON. A missing Content-Type (ContentType.Any) is read as JSON,
  // matching the reference; any other type is rejected, closing the text/plain no-preflight vector.
  val contentType = request.contentType()
  if (contentType != ContentType.Any && !contentType.match(ContentType.Application.Json)) {
    return SessionInitBody.Rejected(SessionRoutesErrors.ERR_UNSUPPORTED_MEDIA_TYPE)
  }
  // Decoding by hand forgoes ContentNegotiation's own catch-all, and this graph throws
  // IllegalState and IllegalArgument as well as SerializationException. Causes are dropped rather
  // than reported: their messages quote the payload.
  val tree =
    try {
      adkJson.parseToJsonElement(raw)
    } catch (_: StackOverflowError) {
      // A deeply nested array exhausts the stack here as an Error, not an Exception; still 400.
      return SessionInitBody.Rejected(SessionRoutesErrors.ERR_MALFORMED_BODY)
    } catch (_: Exception) {
      return SessionInitBody.Rejected(SessionRoutesErrors.ERR_MALFORMED_BODY)
    }
  // A literal `null` body means "no body", as it does on the reference server.
  if (tree is JsonNull) return SessionInitBody.Parsed(CreateSessionRequest(), rawEvents = null)
  val request =
    try {
      adkJson.decodeFromJsonElement(CreateSessionRequest.serializer(), tree)
    } catch (_: StackOverflowError) {
      // A stack-exhausting body is unreadable input (400), not a shape mismatch (422).
      return SessionInitBody.Rejected(SessionRoutesErrors.ERR_MALFORMED_BODY)
    } catch (_: Exception) {
      // The body parsed as JSON but does not fit the request shape, so it reached the decoder and
      // was refused: 422, not the 400 that means it never parsed.
      return SessionInitBody.Rejected(SessionRoutesErrors.ERR_UNPROCESSABLE_BODY)
    }
  return SessionInitBody.Parsed(request, (tree as? JsonObject)?.get("events") as? JsonArray)
}

/** Drops null-valued entries, which session state cannot hold. */
private fun Map<String, Any?>?.droppingNullValues(): Map<String, Any>? =
  this?.mapNotNull { (key, value) -> value?.let { key to it } }?.toMap()

/**
 * Returns whether this event names a function the ADK generates itself. Ordinary tool calls and
 * responses are allowed, so a conversation that used tools can be restored.
 */
@OptIn(FrameworkInternalApi::class)
private fun Event.claimsAnAdkFunction(): Boolean =
  (functionCalls().map { it.name } + functionResponses().map { it.name }).any {
    it in FunctionCall.ADK_RESERVED_FUNCTION_NAMES
  }

/**
 * Whether a raw `actions` object carries a value the server would honor. The reference servers
 * write empty `stateDelta`/`artifactDelta` maps on every event, so a `null`, `{}` or `[]` entry
 * does not count - only a real value does. Judged on the raw JSON because an unknown or
 * not-yet-aliased key would otherwise decode to the default and pass unnoticed.
 */
private fun JsonObject?.hasHonoredAction(): Boolean =
  this != null &&
    any { (_, value) ->
      !(value is JsonNull ||
        (value is JsonObject && value.isEmpty()) ||
        (value is JsonArray && value.isEmpty()))
    }

/**
 * Returns the error for the first seed event the runtime will not accept from a client, checked in
 * the reference server's field order. Ordinary tool calls are allowed so a conversation that used
 * tools can be restored; `actions`, long-running tool ids and ADK protocol functions are not.
 */
private fun firstSeedEventError(events: List<Event>?, rawEvents: JsonArray?): SessionRoutesError? {
  events?.forEachIndexed { index, event ->
    val rawActions = (rawEvents?.getOrNull(index) as? JsonObject)?.get("actions") as? JsonObject
    val disallowed =
      when {
        event.longRunningToolIds.isNotEmpty() -> "long-running tool IDs"
        rawActions.hasHonoredAction() -> "event actions"
        event.claimsAnAdkFunction() -> "ADK protocol function calls"
        else -> return@forEachIndexed
      }
    return seedEventError(index, disallowed)
  }
  return null
}

/**
 * Stamps [seededId] on a seed event that arrives without an `invocationId`. A client may omit it,
 * but some backends (Vertex Agent Engine) reject an empty `invocation_id` on the wire. The caller
 * has already rejected a request that mixes present and absent ids, so this only fires when all
 * omit.
 */
private fun Event.withSeededInvocationId(seededId: String): Event =
  if (invocationId.isNullOrBlank()) copy(invocationId = seededId) else this

/**
 * Creates the session [request] describes under [key] and appends its seed events. Validation runs
 * before creation, so a rejected request leaves no session behind.
 */
private suspend fun SessionService.createSeededSession(
  key: SessionKey,
  request: CreateSessionRequest,
  rawEvents: JsonArray?,
): CreateSessionOutcome {
  firstSeedEventError(request.events, rawEvents)?.let {
    return CreateSessionOutcome.Failed(it)
  }
  request.events?.let { events ->
    // Minting for only some events would split one seed across real and synthetic invocations, so
    // require the events to all carry an invocationId or all omit it.
    val missing = events.count { it.invocationId.isNullOrBlank() }
    if (missing in 1 until events.size) {
      return CreateSessionOutcome.Failed(SessionRoutesErrors.ERR_MIXED_SEED_INVOCATION_IDS)
    }
  }
  val session =
    try {
      createSession(key, request.state.droppingNullValues())
    } catch (e: SessionException) {
      // Only a taken id is a 409; anything else is a genuine failure and stays a 500.
      if (e.message != SessionException.SESSION_ALREADY_EXISTS) throw e
      return CreateSessionOutcome.Failed(SessionRoutesErrors.ERR_SESSION_ALREADY_EXISTS)
    } catch (_: IllegalArgumentException) {
      // A backend rejecting an id it cannot address (a decode failure is an IOException, not this).
      // No StatusPages is installed, so without this the body's own id would answer 500 where the
      // reference server answers 400.
      return CreateSessionOutcome.Failed(SessionRoutesErrors.ERR_INVALID_SESSION_ID)
    }
  // Appending is not atomic with creation, matching the reference server: a failure part-way
  // leaves the session with the events appended so far.
  // The id-less seeds arrived as one request, so they share a single minted invocation id.
  val seededInvocationId = "s-${Uuid.random()}"
  request.events?.forEach { appendEvent(session, it.withSeededInvocationId(seededInvocationId)) }
  return CreateSessionOutcome.Created(session)
}

/**
 * Reads the init body, resolves the session id ([pathSessionId] when the route names one, otherwise
 * the trimmed body id, blank meaning "generate one"), creates the seeded session and responds.
 */
private suspend fun ApplicationCall.createSessionFromBody(
  sessionService: SessionService,
  appName: String,
  userId: String,
  pathSessionId: String?,
) {
  val body =
    when (val received = receiveSessionInitBody()) {
      is SessionInitBody.Parsed -> received
      is SessionInitBody.Rejected -> return respond(received.error.code, received.error.message)
    }
  val sessionId = pathSessionId ?: body.request.sessionId?.trim()?.takeIf { it.isNotEmpty() }
  when (
    val outcome =
      sessionService.createSeededSession(
        SessionKey(appName, userId, sessionId),
        body.request,
        body.rawEvents,
      )
  ) {
    is CreateSessionOutcome.Created -> respond(outcome.session.toDto())
    is CreateSessionOutcome.Failed -> respond(outcome.error.code, outcome.error.message)
  }
}

internal data class SessionParams(val appName: String, val userId: String, val sessionId: String?)

internal sealed class SessionRoutesResult {
  data class Success(val params: SessionParams) : SessionRoutesResult()

  data class Error(val error: SessionRoutesError) : SessionRoutesResult()
}

internal fun extractSessionParams(
  parameters: Parameters,
  requireSessionId: Boolean = false,
): SessionRoutesResult {
  val appName =
    parameters["appName"]
      ?: return SessionRoutesResult.Error(SessionRoutesErrors.ERR_MISSING_APP_NAME)
  val userId =
    parameters["userId"]
      ?: return SessionRoutesResult.Error(SessionRoutesErrors.ERR_MISSING_USER_ID)
  val sessionId = parameters["sessionId"]
  if (requireSessionId && sessionId == null) {
    return SessionRoutesResult.Error(SessionRoutesErrors.ERR_MISSING_SESSION_ID)
  }
  return SessionRoutesResult.Success(SessionParams(appName, userId, sessionId))
}

internal fun Session.toDto() =
  SessionDto(
    id = key.id,
    appName = key.appName,
    userId = key.userId,
    state = state,
    events = events,
    lastUpdateTime = lastUpdateTime.toEpochMilliseconds(),
  )

internal fun Route.sessionRoutes(sessionService: SessionService) {

  route("/apps/{appName}/users/{userId}/sessions") {
    get {
      val result = extractSessionParams(call.parameters)
      val params =
        when (result) {
          is SessionRoutesResult.Success -> result.params
          is SessionRoutesResult.Error -> {
            return@get call.respond(result.error.code, result.error.message)
          }
        }
      val appName = params.appName
      val userId = params.userId

      val sessionsResponse = sessionService.listSessions(appName, userId)

      call.respond(sessionsResponse.sessions.map { it.toDto() })
    }

    post {
      val result = extractSessionParams(call.parameters)
      val params =
        when (result) {
          is SessionRoutesResult.Success -> result.params
          is SessionRoutesResult.Error -> {
            return@post call.respond(result.error.code, result.error.message)
          }
        }
      call.createSessionFromBody(
        sessionService,
        params.appName,
        params.userId,
        pathSessionId = null,
      )
    }

    route("/{sessionId}") {
      get {
        val result = extractSessionParams(call.parameters, requireSessionId = true)
        val params =
          when (result) {
            is SessionRoutesResult.Success -> result.params
            is SessionRoutesResult.Error -> {
              return@get call.respond(result.error.code, result.error.message)
            }
          }
        val appName = params.appName
        val userId = params.userId
        val sessionId = params.sessionId ?: return@get

        val session = sessionService.getSession(SessionKey(appName, userId, sessionId))
        if (session == null) {
          return@get call.respond(
            SessionRoutesErrors.ERR_SESSION_NOT_FOUND.code,
            SessionRoutesErrors.ERR_SESSION_NOT_FOUND.message,
          )
        }
        call.respond(session.toDto())
      }

      post {
        val result = extractSessionParams(call.parameters, requireSessionId = true)
        val params =
          when (result) {
            is SessionRoutesResult.Success -> result.params
            is SessionRoutesResult.Error -> {
              return@post call.respond(result.error.code, result.error.message)
            }
          }
        val sessionId = params.sessionId ?: return@post
        // The path names the session, so any `sessionId` in the body is ignored.
        call.createSessionFromBody(sessionService, params.appName, params.userId, sessionId)
      }

      delete {
        val result = extractSessionParams(call.parameters, requireSessionId = true)
        val params =
          when (result) {
            is SessionRoutesResult.Success -> result.params
            is SessionRoutesResult.Error -> {
              return@delete call.respond(result.error.code, result.error.message)
            }
          }
        val appName = params.appName
        val userId = params.userId
        val sessionId = params.sessionId ?: return@delete

        sessionService.deleteSession(SessionKey(appName, userId, sessionId))
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
