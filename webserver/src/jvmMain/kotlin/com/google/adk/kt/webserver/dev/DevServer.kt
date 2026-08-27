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

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.webserver.AdkApiServer
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.adkApiModule
import com.google.adk.kt.webserver.dev.routes.debugRoutes
import com.google.adk.kt.webserver.dev.routes.evalRoutes
import com.google.adk.kt.webserver.dev.routes.graphRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * The local development server: [AdkApiServer] plus the endpoints the Dev UI drives, and the UI.
 *
 * These endpoints read agent state and run debugging code, so this variant belongs on a development
 * machine; deploy [AdkApiServer] instead.
 */
class AdkDevServer(config: AdkServerConfig) : AdkApiServer(config) {
  override fun configure(application: Application) {
    // Replaces rather than extends the base: the dev module defaults the UI the other way.
    application.adkDevModule(config)
  }
}

/**
 * Installs [adkApiModule] plus the development-only endpoints the Dev UI drives: request traces,
 * evaluation and agent graphs.
 *
 * Installs [adkApiModule] itself, so do not install both. Unlike [adkApiModule] this mounts the
 * Development UI, unless [AdkServerConfig.webUiEnabled] or the `adk.web.ui.enabled` property turns
 * it off.
 */
fun Application.adkDevModule(config: AdkServerConfig) {
  adkApiModule(config.copy(webUiEnabled = config.webUiEnabled ?: true))

  // A second `routing` block merges into the one adkApiModule installed.
  routing {
    debugRoutes(config.apiServerSpanExporter)
    evalRoutes()
    graphRoutes(config.agentLoader, config.sessionService)
  }
}
