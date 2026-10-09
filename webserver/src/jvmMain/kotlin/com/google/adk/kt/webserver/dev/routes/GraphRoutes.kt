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

package com.google.adk.kt.webserver.dev.routes

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.dev.AgentGraphGenerator
import com.google.adk.kt.webserver.dev.appGraphOf
import com.google.adk.kt.webserver.dev.graphNodeOf
import com.google.adk.kt.webserver.dev.plotGraph
import com.google.adk.kt.webserver.loaders.ServedApps
import com.google.adk.kt.workflow.Node
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable

internal data class GraphRoutesError(val message: String, val code: HttpStatusCode)

internal object GraphRoutesErrors {
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
  val ERR_INVALID_DARK_MODE =
    GraphRoutesError("dark_mode must be true or false", HttpStatusCode.BadRequest)
  val ERR_NODE_NOT_FOUND = GraphRoutesError("Node not found", HttpStatusCode.NotFound)
}

internal data class GraphParams(
  val appName: String,
  val userId: String,
  val sessionId: String,
  val eventId: String,
)

internal sealed class GraphRoutesResult {
  data class Success(val params: GraphParams) : GraphRoutesResult()

  data class Error(val error: GraphRoutesError) : GraphRoutesResult()
}

internal fun extractGraphParams(parameters: Parameters): GraphRoutesResult {
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

internal fun Route.graphRoutes(servedApps: ServedApps, sessionService: SessionService) {
  val graphGenerator = AgentGraphGenerator(servedApps)
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
          servedApps.loadRoot(appName) as? BaseAgent
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

  // The app's structure, which the Dev UI navigates and lays out its node-state view from.
  for (path in BUILD_GRAPH_PATHS) {
    get(path) {
      val appName = call.parameters.getOrFail("appName")
      val root = call.loadRootOrRespond(servedApps, appName) ?: return@get
      call.respond(appGraphOf(appName, root))
    }
  }

  // DOT source for the root and every workflow below it, or a bare DotGraph for a non-empty `node`.
  for (path in BUILD_GRAPH_IMAGE_PATHS) {
    get(path) {
      val appName = call.parameters.getOrFail("appName")
      val darkMode =
        queryBooleanOrNull(call.request.queryParameters["dark_mode"] ?: "false")
          ?: return@get call.respond(
            GraphRoutesErrors.ERR_INVALID_DARK_MODE.code,
            GraphRoutesErrors.ERR_INVALID_DARK_MODE.message,
          )
      val root = call.loadRootOrRespond(servedApps, appName) ?: return@get
      val nodePath = call.request.queryParameters["node"].orEmpty()
      val target =
        graphNodeOf(root).find(nodePath)
          ?: return@get call.respond(
            GraphRoutesErrors.ERR_NODE_NOT_FOUND.code,
            GraphRoutesErrors.ERR_NODE_NOT_FOUND.message,
          )
      if (nodePath.isNotEmpty()) {
        // On-demand drill-in requests expect a single DotGraph object rather than a path-keyed map.
        return@get call.respond(DotGraph(plotGraph(target, darkMode)))
      }
      // A non-workflow root is drawn as its agent tree; other keys start with the root's name.
      val graphs = mapOf("" to target) + (target.workflowsByPath(target.name) - target.name)
      call.respond(graphs.mapValues { (_, node) -> DotGraph(plotGraph(node, darkMode)) })
    }
  }
}

// adk-web 1.0 and later call the `/dev/apps` paths; earlier UIs call the others.
private val BUILD_GRAPH_PATHS =
  listOf("/dev/apps/{appName}/build_graph", "/dev/build_graph/{appName}")

private val BUILD_GRAPH_IMAGE_PATHS =
  listOf("/dev/apps/{appName}/build_graph_image", "/dev/build_graph_image/{appName}")

private val TRUE_QUERY_VALUES = setOf("1", "on", "t", "true", "y", "yes")

private val FALSE_QUERY_VALUES = setOf("0", "off", "f", "false", "n", "no")

/** Parses [value] as a case-insensitive boolean query parameter, or returns null if invalid. */
private fun queryBooleanOrNull(value: String): Boolean? =
  when (value.lowercase()) {
    in TRUE_QUERY_VALUES -> true
    in FALSE_QUERY_VALUES -> false
    else -> null
  }

/**
 * DOT source for one graph, under the `dotSrc` key the Dev UI reads.
 *
 * `GET /dev/apps/{appName}/build_graph_image` (and the older `/dev/build_graph_image/{appName}`)
 * returns a bare [DotGraph] when a non-empty `node` query parameter requests a single subtree, and
 * a path-keyed map of [DotGraph]s when `node` is omitted or empty.
 */
@Serializable internal data class DotGraph(val dotSrc: String)

/** Loads the root node for [appName], or sends an error response and returns null. */
private suspend fun ApplicationCall.loadRootOrRespond(
  servedApps: ServedApps,
  appName: String,
): Node? {
  val root =
    try {
      servedApps.loadRoot(appName)
    } catch (e: Exception) {
      respond(
        GraphRoutesErrors.ERR_AGENT_NOT_LOADED.code,
        GraphRoutesErrors.ERR_AGENT_NOT_LOADED.message,
      )
      return null
    }
  if (root == null) {
    respond(
      GraphRoutesErrors.ERR_AGENT_NOT_FOUND.code,
      GraphRoutesErrors.ERR_AGENT_NOT_FOUND.message,
    )
  }
  return root
}
