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

import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.AgentRunRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString

@OptIn(FrameworkInternalApi::class)
fun Route.runRoutes(
  agentLoader: AgentLoader,
  sessionService: SessionService,
  artifactService: ArtifactService,
) {
  route("/run") {
    post {
      val request = call.receive<AgentRunRequest>()
      val agent = agentLoader.loadAgent(request.appName)
      if (agent == null) {
        return@post call.respond(HttpStatusCode.NotFound, "Agent not found")
      }
      val runner =
        InMemoryRunner(
          agent = agent,
          sessionService = sessionService,
          artifactService = artifactService,
          appName = request.appName,
        )

      val runConfig =
        RunConfig(streamingMode = if (request.streaming) StreamingMode.NONE else StreamingMode.NONE)

      val sessionId = request.sessionId ?: UUID.randomUUID().toString()

      val events =
        runner
          .runAsync(
            request.userId,
            sessionId,
            request.invocationId,
            request.newMessage,
            request.stateDelta,
            runConfig,
          )
          .toList()

      call.respond(events)
    }
  }

  post("/run_sse") {
    val request = call.receive<AgentRunRequest>()
    val agent = agentLoader.loadAgent(request.appName)
    if (agent == null) {
      return@post call.respond(HttpStatusCode.NotFound, "Agent not found")
    }
    val runner =
      InMemoryRunner(
        agent = agent,
        sessionService = sessionService,
        artifactService = artifactService,
        appName = request.appName,
      )

    val runConfig =
      RunConfig(streamingMode = if (request.streaming) StreamingMode.SSE else StreamingMode.NONE)

    val sessionId = request.sessionId ?: UUID.randomUUID().toString()

    // Ktor 2.x has no SSE plugin, so stream manually. Use async respondBytesWriter, not
    // respondTextWriter, whose blocking bridge parks via a coroutines internal gone in 1.11.0.
    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
      runner
        .runAsync(
          request.userId,
          sessionId,
          request.invocationId,
          request.newMessage,
          request.stateDelta,
          runConfig,
        )
        .collect { event ->
          val data = adkJson.encodeToString(event)
          writeStringUtf8("data: $data\n\n")
          flush()
        }
    }
  }
}
