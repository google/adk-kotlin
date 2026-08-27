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

import com.google.adk.kt.VERSION
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.webserver.dev.AdkDevServer
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.adk.kt.webserver.routes.appRoutes
import com.google.adk.kt.webserver.routes.artifactRoutes
import com.google.adk.kt.webserver.routes.isWebUiEnabled
import com.google.adk.kt.webserver.routes.runRoutes
import com.google.adk.kt.webserver.routes.sessionRoutes
import com.google.adk.kt.webserver.routes.staticRoutes
import com.google.adk.kt.webserver.telemetry.OpenTelemetryConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private const val STOP_GRACE_MILLIS = 1000L
private const val STOP_TIMEOUT_MILLIS = 5000L

private val logger = LoggerFactory.getLogger(AdkApiServer::class.java)

/**
 * Serves the ADK agent runtime contract, so it can be deployed headlessly; the Development UI stays
 * unmounted unless [AdkServerConfig.webUiEnabled] or the `adk.web.ui.enabled` property asks for it.
 *
 * [AdkDevServer] widens the surface with the development-only endpoints. [start] and [stop] are
 * safe to call from different threads; a [stop] arriving while [start] is still binding aborts it,
 * and a failed [start] leaves the engine recorded, so call [stop] before retrying.
 */
open class AdkApiServer(protected val config: AdkServerConfig) {
  private val lifecycleLock = Any()
  private var server: ApplicationEngine? = null

  /**
   * Installs this server's endpoint surface.
   *
   * An override replaces this body, so it must install everything the server serves. Call
   * `super.configure(application)` to add routes on top, or install a module that already contains
   * [adkApiModule] - as [AdkDevServer] does with [adkDevModule] - but not both, which fails at
   * start with a duplicate-plugin error.
   */
  protected open fun configure(application: Application) {
    val webUiEnabled = application.resolveWebUi(config)
    application.adkApiModule(config, webUiEnabled)
    if (webUiEnabled) {
      logger.warn(
        "Serving the Development UI from the API server; its debug, evaluation and graph views " +
          "will not work. Use AdkDevServer for the full development surface."
      )
    }
  }

  fun start(wait: Boolean = false) {
    // Released before the blocking call below, so stop() can still take it.
    val engine =
      synchronized(lifecycleLock) {
        if (server != null) return
        embeddedServer(Netty, port = config.port, host = config.host) { configure(this) }
          .also { server = it }
      }
    logger.info("{} starting on {}:{}", this::class.simpleName, config.host, config.port)
    engine.start(wait = wait)
  }

  fun stop() {
    synchronized(lifecycleLock) {
      server?.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
      server = null
    }
    logger.info("{} stopped", this::class.simpleName)
  }
}

/** Reports server errors that Ktor's call logging emits at INFO as warnings instead. */
private class StatusAwareLogger(private val delegate: Logger) : Logger by delegate {
  override fun info(msg: String?) {
    if (msg != null && msg.contains("Status: 5")) {
      delegate.warn(msg)
    } else {
      delegate.info(msg)
    }
  }
}

/**
 * Installs the ADK agent runtime contract: health, version, app discovery, sessions, artifacts and
 * the run endpoints.
 *
 * The Development UI stays unmounted unless [AdkServerConfig.webUiEnabled] or the
 * `adk.web.ui.enabled` property asks for it; the development surface is installed separately.
 *
 * Your engine decides the interface: [AdkServerConfig.host] is read by [AdkApiServer], not here.
 */
fun Application.adkApiModule(config: AdkServerConfig) {
  adkApiModule(config, resolveWebUi(config))
}

/** [adkApiModule] with the Development UI decision already made, so it is resolved once. */
@OptIn(FrameworkInternalApi::class)
internal fun Application.adkApiModule(config: AdkServerConfig, webUiEnabled: Boolean) {
  install(CallLogging) {
    level = Level.INFO
    logger = StatusAwareLogger(LoggerFactory.getLogger(CallLogging::class.java))
    format { call ->
      val status = call.response.status()
      val httpMethod = call.request.httpMethod.value
      val uri = call.request.uri
      "Status: $status, HTTP method: $httpMethod, URI: $uri"
    }
  }
  install(ContentNegotiation) { json(adkJson) }

  val otelConfig = OpenTelemetryConfig(config.apiServerSpanExporter)
  val sdkTracerProvider = otelConfig.sdkTracerProvider()
  otelConfig.openTelemetrySdk(sdkTracerProvider)

  // The Dev UI trace view needs message content, but it records potential PII into spans.
  TelemetryConfig.captureMessageContent = config.captureMessageContent
  if (config.captureMessageContent) {
    logger.warn(
      """
      ADK web server enabled telemetry message-content capture: prompt/response content (which
      may contain PII) will be recorded in trace spans. This is intended for local development
      only.
      """
        .trimIndent()
    )
  }

  routing {
    get("/health") { call.respond(mapOf("status" to "ok")) }
    get("/version") {
      call.respond(
        VersionInfo(
          version = VERSION,
          language = "kotlin",
          languageVersion = System.getProperty("java.version", "unknown"),
        )
      )
    }
    appRoutes(config.agentLoader)
    artifactRoutes(config.artifactService)
    runRoutes(config.agentLoader, config.sessionService, config.artifactService, config.plugins)
    sessionRoutes(config.sessionService)
    if (webUiEnabled) {
      staticRoutes(this@adkApiModule)
    }
  }
}

private fun Application.resolveWebUi(config: AdkServerConfig): Boolean =
  isWebUiEnabled(default = config.webUiEnabled ?: false)
