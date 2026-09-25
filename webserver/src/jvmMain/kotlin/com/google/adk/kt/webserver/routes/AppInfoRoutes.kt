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

import com.google.adk.kt.webserver.buildAppInfo
import com.google.adk.kt.webserver.loaders.AgentLoader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Serves one app's agent metadata, mounted only when the server is configured to expose it.
 *
 * The response carries every agent's instruction and tool declarations, which a caller needs to
 * grade a run but a deployment has no reason to publish. Every request walks the agent tree and
 * enumerates its toolsets afresh, so this suits evaluation rather than a hot path.
 */
internal fun Route.appInfoRoutes(agentLoader: AgentLoader) {
  get("/apps/{appName}/app-info") {
    // The route pattern always supplies the segment, so a missing name can only mean no such app.
    val appName = call.parameters["appName"]
    val agent = appName?.let { agentLoader.loadAgent(it) }
    if (agent == null) {
      return@get call.respond(HttpStatusCode.NotFound, "Agent not found")
    }

    call.respond(buildAppInfo(appName, agent))
  }
}
