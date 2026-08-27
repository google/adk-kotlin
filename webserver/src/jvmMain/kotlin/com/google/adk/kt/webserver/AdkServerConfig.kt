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
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter

/**
 * What an [AdkApiServer] or [AdkDevServer] needs to serve a set of agents over HTTP.
 *
 * @property captureMessageContent When true the server records prompt and response content into
 *   telemetry spans so the Dev UI trace view can display it. This may capture PII, so it defaults
 *   to false; enable it only for local development.
 * @property webUiEnabled Whether to mount the Development UI; null leaves the choice to the server
 *   variant, off for [AdkApiServer] and on for [AdkDevServer]. The `adk.web.ui.enabled` property
 *   overrides it either way.
 */
data class AdkServerConfig(
  val agentLoader: AgentLoader,
  val sessionService: SessionService,
  val artifactService: ArtifactService,
  val port: Int = DEFAULT_PORT,
  val apiServerSpanExporter: ApiServerSpanExporter = ApiServerSpanExporter(),
  val captureMessageContent: Boolean = false,
  val plugins: List<Plugin> = emptyList(),
  val webUiEnabled: Boolean? = null,
) {
  companion object {
    /** Port both server variants listen on unless told otherwise. */
    const val DEFAULT_PORT: Int = 8080
  }
}
