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

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.artifacts.InMemoryArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.loaders.SingleAgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter

/**
 * What an [AdkApiServer] or [AdkDevServer] needs to serve a set of agents over HTTP.
 *
 * @property captureMessageContent When true the server records prompt and response content into
 *   telemetry spans so the Dev UI trace view can display it. This may capture PII, so it defaults
 *   to false; enable it only for local development.
 * @property host Interface [AdkApiServer] and [AdkDevServer] bind to; IPv4 loopback by default,
 *   since these endpoints are unauthenticated; nothing listens on ::1. Installing a module into
 *   your own engine binds whatever that engine was given, not this.
 * @property webUiEnabled Whether to mount the Development UI; null leaves the choice to the server
 *   variant, off for [AdkApiServer] and on for [AdkDevServer]. The `adk.web.ui.enabled` property
 *   overrides it either way, so that a deployment which cannot change code can still turn the UI
 *   off; the corollary is that an ambient property beats an explicit setting here.
 */
data class AdkServerConfig(
  val agentLoader: AgentLoader,
  val sessionService: SessionService,
  val artifactService: ArtifactService,
  val port: Int = DEFAULT_PORT,
  val host: String = DEFAULT_HOST,
  val apiServerSpanExporter: ApiServerSpanExporter = ApiServerSpanExporter(),
  val captureMessageContent: Boolean = false,
  val plugins: List<Plugin> = emptyList(),
  val webUiEnabled: Boolean? = null,
) {
  /**
   * Fluent builder for [AdkServerConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var agentLoader: AgentLoader? = null
    private var sessionService: SessionService? = null
    private var artifactService: ArtifactService? = null
    private var port: Int = DEFAULT_PORT
    private var host: String = DEFAULT_HOST
    private var apiServerSpanExporter: ApiServerSpanExporter = ApiServerSpanExporter()
    private var captureMessageContent: Boolean = false
    private var plugins: List<Plugin> = emptyList()
    private var webUiEnabled: Boolean? = null

    fun agentLoader(agentLoader: AgentLoader): Builder = apply { this.agentLoader = agentLoader }

    fun sessionService(sessionService: SessionService): Builder = apply {
      this.sessionService = sessionService
    }

    fun artifactService(artifactService: ArtifactService): Builder = apply {
      this.artifactService = artifactService
    }

    fun port(port: Int): Builder = apply { this.port = port }

    fun host(host: String): Builder = apply { this.host = host }

    fun apiServerSpanExporter(apiServerSpanExporter: ApiServerSpanExporter): Builder = apply {
      this.apiServerSpanExporter = apiServerSpanExporter
    }

    fun captureMessageContent(captureMessageContent: Boolean): Builder = apply {
      this.captureMessageContent = captureMessageContent
    }

    fun plugins(plugins: List<Plugin>): Builder = apply { this.plugins = plugins }

    fun webUiEnabled(webUiEnabled: Boolean?): Builder = apply { this.webUiEnabled = webUiEnabled }

    fun build(): AdkServerConfig =
      AdkServerConfig(
        agentLoader = checkNotNull(agentLoader) { "agentLoader must be set." },
        sessionService = checkNotNull(sessionService) { "sessionService must be set." },
        artifactService = checkNotNull(artifactService) { "artifactService must be set." },
        port = port,
        host = host,
        apiServerSpanExporter = apiServerSpanExporter,
        captureMessageContent = captureMessageContent,
        plugins = plugins,
        webUiEnabled = webUiEnabled,
      )
  }

  companion object {
    /** Port both server variants listen on unless told otherwise. */
    const val DEFAULT_PORT: Int = 8080

    /** Loopback, so an unauthenticated server is not reachable off the machine by default. */
    const val DEFAULT_HOST: String = "127.0.0.1"

    /**
     * Config for serving a single [agent], with session and artifact state held in memory.
     *
     * That state is lost when the process exits, so this suits a local run or a test rather than a
     * deployment. Serve several agents, or persist state, by building [AdkServerConfig] directly.
     */
    @JvmStatic
    @JvmOverloads
    fun inMemory(agent: BaseAgent, port: Int = DEFAULT_PORT): AdkServerConfig =
      AdkServerConfig(
        agentLoader = SingleAgentLoader(agent),
        sessionService = InMemorySessionService(),
        artifactService = InMemoryArtifactService(),
        port = port,
      )

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
