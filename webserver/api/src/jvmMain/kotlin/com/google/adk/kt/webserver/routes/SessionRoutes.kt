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

import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.models.SessionDto
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

data class SessionRoutesError(val message: String, val code: HttpStatusCode)

object SessionRoutesErrors {
  val ERR_MISSING_APP_NAME = SessionRoutesError("Missing appName", HttpStatusCode.BadRequest)
  val ERR_MISSING_USER_ID = SessionRoutesError("Missing userId", HttpStatusCode.BadRequest)
  val ERR_MISSING_SESSION_ID = SessionRoutesError("Missing sessionId", HttpStatusCode.BadRequest)
  val ERR_SESSION_NOT_FOUND = SessionRoutesError("Session not found", HttpStatusCode.NotFound)
}

data class SessionParams(val appName: String, val userId: String, val sessionId: String?)

sealed class SessionRoutesResult {
  data class Success(val params: SessionParams) : SessionRoutesResult()

  data class Error(val error: SessionRoutesError) : SessionRoutesResult()
}

fun extractSessionParams(
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

fun Session.toDto() =
  SessionDto(
    id = key.id,
    appName = key.appName,
    userId = key.userId,
    state = state,
    events = events,
    lastUpdateTime = lastUpdateTime.toEpochMilliseconds(),
  )

fun Route.sessionRoutes(sessionService: SessionService) {

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
      val appName = params.appName
      val userId = params.userId

      val session = sessionService.createSession(SessionKey(appName, userId, id = null))
      call.respond(session.toDto())
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
        val appName = params.appName
        val userId = params.userId
        val sessionId = params.sessionId ?: return@post

        val session = sessionService.createSession(SessionKey(appName, userId, sessionId))
        call.respond(session.toDto())
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
