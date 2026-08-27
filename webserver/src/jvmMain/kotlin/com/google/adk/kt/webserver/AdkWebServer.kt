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

package com.google.adk.kt.webserver

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import io.ktor.server.application.Application

/**
 * Embedded Ktor server exposing the ADK dev/web API.
 *
 * @param captureMessageContent When true, the server records prompt/response content into telemetry
 *   spans so the Dev UI trace view can display it. This may capture PII and increase span size, so
 *   it defaults to false; enable it only for local development.
 */
@Deprecated(
  "Use AdkDevServer for local development, or AdkApiServer to serve the runtime contract " +
    "headlessly. Both bind loopback, so a container deployment has to set " +
    "AdkServerConfig.host to 0.0.0.0, which this class does for you."
)
class AdkWebServer(
  port: Int = AdkServerConfig.DEFAULT_PORT,
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
  private val delegate =
    AdkDevServer(
      AdkServerConfig(
        agentLoader = agentLoader,
        sessionService = sessionService,
        artifactService = artifactService,
        port = port,
        // Pinned to preserve this class's behaviour; AdkServerConfig now defaults to loopback.
        host = "0.0.0.0",
        apiServerSpanExporter = apiServerSpanExporter,
        captureMessageContent = captureMessageContent,
        plugins = plugins,
      )
    )

  fun start(wait: Boolean = false) {
    delegate.start(wait)
  }

  fun stop() {
    delegate.stop()
  }

  companion object {
    /** The ADK Kotlin version reported by the `/version` endpoint. */
    @Deprecated(
      "Read com.google.adk.kt.VERSION directly.",
      ReplaceWith("VERSION", "com.google.adk.kt.VERSION"),
    )
    fun adkVersion(): String = com.google.adk.kt.VERSION
  }
}

/** Installs the full development surface. Equivalent to [adkDevModule]. */
@Deprecated(
  "Use adkDevModule for the development surface, or adkApiModule for the runtime contract."
)
fun Application.adkModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
  adkDevModule(
    AdkServerConfig(
      agentLoader = agentLoader,
      sessionService = sessionService,
      artifactService = artifactService,
      apiServerSpanExporter = apiServerSpanExporter,
      captureMessageContent = captureMessageContent,
      plugins = plugins,
    )
  )
}
