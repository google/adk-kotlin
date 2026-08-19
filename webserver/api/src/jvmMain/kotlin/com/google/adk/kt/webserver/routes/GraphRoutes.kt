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

import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.AgentGraphGenerator
import com.google.adk.kt.webserver.loaders.AgentLoader
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

data class GraphRoutesError(val message: String, val code: HttpStatusCode)

object GraphRoutesErrors {
  val ERR_MISSING_APP_NAME = GraphRoutesError("Missing appName", HttpStatusCode.BadRequest)
  val ERR_MISSING_USER_ID = GraphRoutesError("Missing userId", HttpStatusCode.BadRequest)
  val ERR_MISSING_SESSION_ID = GraphRoutesError("Missing sessionId", HttpStatusCode.BadRequest)
  val ERR_MISSING_EVENT_ID = GraphRoutesError("Missing eventId", HttpStatusCode.BadRequest)
  val ERR_AGENT_NOT_FOUND = GraphRoutesError("Agent app not found", HttpStatusCode.NotFound)
  val ERR_SESSION_NOT_FOUND = GraphRoutesError("Session not found", HttpStatusCode.NotFound)
  val ERR_EVENT_NOT_FOUND = GraphRoutesError("Event not found", HttpStatusCode.NotFound)
  val ERR_AGENT_NOT_LOADED =
    GraphRoutesError("Agent app not loaded", HttpStatusCode.InternalServerError)
  val ERR_GRAPH_GENERATION_FAILED =
    GraphRoutesError("Could not generate graph for this event.", HttpStatusCode.InternalServerError)
}

data class GraphParams(
  val appName: String,
  val userId: String,
  val sessionId: String,
  val eventId: String,
)

sealed class GraphRoutesResult {
  data class Success(val params: GraphParams) : GraphRoutesResult()

  data class Error(val error: GraphRoutesError) : GraphRoutesResult()
}

fun extractGraphParams(parameters: Parameters): GraphRoutesResult {
  val appName =
    parameters["appName"] ?: return GraphRoutesResult.Error(GraphRoutesErrors.ERR_MISSING_APP_NAME)
  val userId =
    parameters["userId"] ?: return GraphRoutesResult.Error(GraphRoutesErrors.ERR_MISSING_USER_ID)
  val sessionId =
    parameters["sessionId"]
      ?: return GraphRoutesResult.Error(GraphRoutesErrors.ERR_MISSING_SESSION_ID)
  val eventId =
    parameters["eventId"] ?: return GraphRoutesResult.Error(GraphRoutesErrors.ERR_MISSING_EVENT_ID)
  return GraphRoutesResult.Success(GraphParams(appName, userId, sessionId, eventId))
}

fun Route.graphRoutes(agentLoader: AgentLoader, sessionService: SessionService) {
  val graphGenerator = AgentGraphGenerator(agentLoader)
  route("/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph") {
    get {
      val result = extractGraphParams(call.parameters)
      val params =
        when (result) {
          is GraphRoutesResult.Success -> result.params
          is GraphRoutesResult.Error ->
            return@get call.respond(result.error.code, result.error.message)
        }

      val appName = params.appName
      val userId = params.userId
      val sessionId = params.sessionId
      val eventId = params.eventId

      val agent =
        try {
          agentLoader.loadAgent(appName)
        } catch (e: Exception) {
          return@get call.respond(
            GraphRoutesErrors.ERR_AGENT_NOT_LOADED.code,
            GraphRoutesErrors.ERR_AGENT_NOT_LOADED.message,
          )
        }

      if (agent == null) {
        return@get call.respond(
          GraphRoutesErrors.ERR_AGENT_NOT_FOUND.code,
          GraphRoutesErrors.ERR_AGENT_NOT_FOUND.message,
        )
      }

      val session =
        sessionService.getSession(SessionKey(appName, userId, sessionId))
          ?: return@get call.respond(
            GraphRoutesErrors.ERR_SESSION_NOT_FOUND.code,
            GraphRoutesErrors.ERR_SESSION_NOT_FOUND.message,
          )

      val event =
        session.events.find { it.id == eventId }
          ?: return@get call.respond(
            GraphRoutesErrors.ERR_EVENT_NOT_FOUND.code,
            GraphRoutesErrors.ERR_EVENT_NOT_FOUND.message,
          )

      val highlightPairs = mutableListOf<Pair<String, String>>()
      val eventAuthor = event.author
      val functionCalls = event.functionCalls()
      val functionResponses = event.functionResponses()

      for (fc in functionCalls) {
        if (fc.name.isNotEmpty()) {
          highlightPairs.add(Pair(eventAuthor, fc.name))
        }
      }
      for (fr in functionResponses) {
        if (fr.name.isNotEmpty()) {
          highlightPairs.add(Pair(fr.name, eventAuthor))
        }
      }

      val dotSource = graphGenerator.generateGraph(agent, highlightPairs)

      if (dotSource.isNotEmpty()) {
        call.respond(mapOf("dot" to dotSource))
      } else {
        call.respond(
          GraphRoutesErrors.ERR_GRAPH_GENERATION_FAILED.code,
          GraphRoutesErrors.ERR_GRAPH_GENERATION_FAILED.message,
        )
      }
    }
  }
}
