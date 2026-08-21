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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.webserver.AdkWebServer.StatusAwareLogger
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.adk.kt.webserver.routes.appRoutes
import com.google.adk.kt.webserver.routes.artifactRoutes
import com.google.adk.kt.webserver.routes.debugRoutes
import com.google.adk.kt.webserver.routes.evalRoutes
import com.google.adk.kt.webserver.routes.graphRoutes
import com.google.adk.kt.webserver.routes.runRoutes
import com.google.adk.kt.webserver.routes.sessionRoutes
import com.google.adk.kt.webserver.routes.staticRoutes
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
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

/**
 * Embedded Ktor server exposing the ADK dev/web API.
 *
 * [start] and [stop] are safe to call from different threads; a [stop] arriving while [start] is
 * still binding aborts it. A failed [start] leaves the engine recorded, so call [stop] before
 * retrying.
 *
 * @property captureMessageContent When true, the server records prompt/response content into
 *   telemetry spans so the Dev UI trace view can display it. This may capture PII and increase span
 *   size, so it defaults to false; enable it only for local development.
 */
class AdkWebServer(
  private val port: Int = 8080,
  private val sessionService: SessionService,
  private val artifactService: ArtifactService,
  private val agentLoader: AgentLoader,
  private val apiServerSpanExporter: ApiServerSpanExporter,
  private val captureMessageContent: Boolean = false,
  private val plugins: List<Plugin> = emptyList(),
) {
  companion object {
    private val logger = LoggerFactory.getLogger(AdkWebServer::class.java)

    /** The ADK Kotlin version reported by the `/version` endpoint. */
    fun adkVersion(): String = com.google.adk.kt.VERSION
  }

  private val lifecycleLock = Any()
  private var server: ApplicationEngine? = null

  fun start(wait: Boolean = false) {
    // Released before the blocking call below, so stop() can still take it.
    val engine =
      synchronized(lifecycleLock) {
        if (server != null) return
        embeddedServer(Netty, port = port) {
            adkModule(
              sessionService,
              artifactService,
              agentLoader,
              apiServerSpanExporter,
              captureMessageContent,
              plugins,
            )
          }
          .also { server = it }
      }
    logger.info("Ktor server starting on port $port")
    engine.start(wait = wait)
  }

  fun stop() {
    synchronized(lifecycleLock) {
      server?.stop(1000, 5000)
      server = null
    }
    logger.info("Ktor server stopped")
  }

  public class StatusAwareLogger(private val delegate: Logger) : Logger by delegate {
    override fun info(msg: String?) {
      if (msg != null && msg.contains("Status: 5")) {
        delegate.warn(msg)
      } else {
        delegate.info(msg)
      }
    }
  }
}

@OptIn(FrameworkInternalApi::class)
fun Application.adkModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
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

  val otelConfig = OpenTelemetryConfig(apiServerSpanExporter)
  val sdkTracerProvider = otelConfig.sdkTracerProvider()
  otelConfig.openTelemetrySdk(sdkTracerProvider)

  // Message-content capture is controlled by the caller (AdkWebServer(captureMessageContent =
  // ...)).
  // The Dev UI trace view needs it to render prompt/response content, but it records potential PII
  // into spans, so it stays OFF by default in the core library.
  TelemetryConfig.captureMessageContent = captureMessageContent
  if (captureMessageContent) {
    LoggerFactory.getLogger("com.google.adk.kt.webserver.AdkWebServer")
      .warn(
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
          version = AdkWebServer.adkVersion(),
          language = "kotlin",
          languageVersion = System.getProperty("java.version", "unknown"),
        )
      )
    }
    appRoutes(agentLoader)
    artifactRoutes(artifactService)
    debugRoutes(apiServerSpanExporter)
    evalRoutes()
    graphRoutes(agentLoader, sessionService)
    runRoutes(agentLoader, sessionService, artifactService, plugins)
    sessionRoutes(sessionService)
    staticRoutes(this@adkModule)
  }
}
