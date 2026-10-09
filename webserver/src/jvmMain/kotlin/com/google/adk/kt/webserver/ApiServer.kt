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

@file:OptIn(ExperimentalAppInfoFeature::class)

package com.google.adk.kt.webserver

import com.google.adk.kt.VERSION
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.adk.kt.webserver.routes.appInfoRoutes
import com.google.adk.kt.webserver.routes.appRoutes
import com.google.adk.kt.webserver.routes.artifactRoutes
import com.google.adk.kt.webserver.routes.isWebUiEnabled
import com.google.adk.kt.webserver.routes.runRoutes
import com.google.adk.kt.webserver.routes.sessionRoutes
import com.google.adk.kt.webserver.routes.staticRoutes
import com.google.adk.kt.webserver.telemetry.OpenTelemetryConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private const val STOP_GRACE_MILLIS = 1000L
private const val STOP_TIMEOUT_MILLIS = 5000L

/**
 * Short enough that the engine's stop (1 s grace, 5 s timeout by default) and then this flush
 * typically fit Cloud Run's 10 s between SIGTERM and SIGKILL.
 */
private val STOP_FLUSH_TIMEOUT = 3.seconds

private val logger = LoggerFactory.getLogger(AdkApiServer::class.java)

/**
 * The Development UI stays unmounted unless [AdkServerConfig.webUiEnabled] or the
 * `adk.web.ui.enabled` property asks for it.
 *
 * [AdkDevServer][com.google.adk.kt.webserver.dev.AdkDevServer] widens the surface with the
 * development-only endpoints. [start] and [stop] are safe to call from different threads; a [stop]
 * arriving while [start] is still binding aborts it, and a failed [start] leaves the engine
 * recorded, so call [stop] before retrying.
 */
open class AdkApiServer(protected val config: AdkServerConfig) {
  private val lifecycleLock = Any()
  // A stop callback, not the engine: embeddedServer returns a different type in Ktor 2 and 3.
  private var stopServer: (() -> Unit)? = null

  /**
   * Installs this server's endpoint surface.
   *
   * An override replaces this body, so it must install everything the server serves. Call
   * `super.configure(application)` to add routes on top, or install a module that already contains
   * [adkApiModule] - as [AdkDevServer][com.google.adk.kt.webserver.dev.AdkDevServer] does with
   * [adkDevModule][com.google.adk.kt.webserver.dev.adkDevModule] - but not both, which fails at
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
        if (stopServer != null) return
        val server =
          embeddedServer(Netty, port = config.port, host = config.host) { configure(this) }
        stopServer = { server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
        server
      }
    logger.info("{} starting on {}:{}", this::class.simpleName, config.host, config.port)
    engine.start(wait = wait)
  }

  /**
   * Stops the engine, which waits for [adkApiModule] to flush [AdkServerConfig.sessionService]: up
   * to 3 seconds more for a flush that honors cancellation.
   */
  fun stop() {
    synchronized(lifecycleLock) {
      stopServer?.invoke()
      stopServer = null
    }
    logger.info("{} stopped", this::class.simpleName)
  }
}

private val REQUEST_LOGGED_KEY = AttributeKey<Unit>("AdkRequestLogged")

/**
 * Logs one line per call to [log], at WARN for a 5xx status and INFO otherwise.
 *
 * Replaces Ktor's CallLogging, whose package differs between Ktor 2 and 3.
 */
internal fun requestLoggingPlugin(log: (Level, String) -> Unit) =
  createApplicationPlugin("AdkRequestLogging") {
    on(ResponseSent) { call ->
      // ResponseSent fires per send; StatusPages and the engine's error fallback can send again.
      if (REQUEST_LOGGED_KEY in call.attributes) return@on
      call.attributes.put(REQUEST_LOGGED_KEY, Unit)
      val status = call.response.status()
      val level = if (status != null && status.value >= 500) Level.WARN else Level.INFO
      log(
        level,
        "Status: $status, HTTP method: ${call.request.httpMethod.value}, URI: ${call.request.uri}",
      )
    }
  }

private val requestLogging = requestLoggingPlugin { level, message ->
  if (level == Level.WARN) logger.warn(message) else logger.info(message)
}

/**
 * Installs the ADK agent runtime (health, version, app discovery, sessions, artifacts, the run
 * endpoints, and app-info when [AdkServerConfig.includeAppInfo] or the `adk.app.info.enabled`
 * property asks for it) and flushes [AdkServerConfig.sessionService] when the application stops,
 * waiting up to 3 seconds for a flush that honors cancellation.
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
  install(requestLogging)
  install(ContentNegotiation) { json(adkJson) }

  flushSessionServiceOnStop(config.sessionService)

  val otelConfig = OpenTelemetryConfig(config.apiServerSpanExporter)
  val sdkTracerProvider = otelConfig.sdkTracerProvider()
  val unused = otelConfig.openTelemetrySdk(sdkTracerProvider)

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

  val camelCase = resolveCamelCase(config)
  val appInfoEnabled = resolveAppInfoEnabled(config)
  routing {
    get("/health") { call.respond(mapOf("status" to "ok")) }
    if (!camelCase) {
      logger.warn(
        "Responses use the mixed spelling: /version emits `language_version` and the " +
          "development trace endpoints emit `span_id`. This default will change in a future " +
          "release; set camelCaseEnforced or {}=true to migrate now.",
        CAMEL_CASE_ENFORCED_PROPERTY,
      )
    }
    get("/version") {
      call.respond(
        VersionInfo.of(
          version = VERSION,
          language = "kotlin",
          languageVersion = System.getProperty("java.version", "unknown"),
          camelCase = camelCase,
        )
      )
    }
    appRoutes(config.servedApps)
    artifactRoutes(config.artifactService)
    runRoutes(config.servedApps, config.sessionService, config.artifactService, config.plugins)
    sessionRoutes(config.sessionService)
    if (appInfoEnabled) {
      appInfoRoutes(config.servedApps)
    }
    if (webUiEnabled) {
      staticRoutes(this@adkApiModule)
    }
  }
}

/**
 * Flushes [sessionService] when this application stops, giving up after [STOP_FLUSH_TIMEOUT] if the
 * flush honors cancellation. Ktor raises [ApplicationStopping] on `stop()` and from its JVM
 * shutdown hook, after the engine's shutdown grace period, so the flush also persists what calls
 * cut off by the stop had buffered.
 */
private fun Application.flushSessionServiceOnStop(sessionService: SessionService) {
  lateinit var subscription: DisposableHandle
  subscription =
    monitor.subscribe(ApplicationStopping) {
      // Unsubscribes, since a dev-mode reload reruns this module on the same monitor.
      subscription.dispose()
      try {
        runBlocking {
          withTimeoutOrNull(STOP_FLUSH_TIMEOUT) { sessionService.flush() }
            ?: logger.warn("Timed out flushing buffered session writes on stop")
        }
      } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        // Type only: the message can carry session content, which a log must not.
        logger.warn("Failed to flush buffered session writes on stop: {}", e.javaClass.name)
      }
    }
}

private fun Application.resolveWebUi(config: AdkServerConfig): Boolean =
  isWebUiEnabled(default = config.webUiEnabled ?: false)

/** Property that switches responses to the enforced camelCase spelling. */
internal const val CAMEL_CASE_ENFORCED_PROPERTY = "adk.wire.camelcase.enforced"

/**
 * Whether responses use the enforced camelCase spelling: the property first, then the Ktor config,
 * then [AdkServerConfig], else the pre-enforced spelling so the Development UI keeps working.
 *
 * The property deliberately beats an explicit setting, as it does for the Development UI: when this
 * default moves, a deployment whose code pins the wrong value needs a lever that does not require a
 * rebuild. Moving the default is a one-line change here.
 */
internal fun Application.resolveCamelCase(config: AdkServerConfig): Boolean =
  settingOrNull(CAMEL_CASE_ENFORCED_PROPERTY, System.getProperty(CAMEL_CASE_ENFORCED_PROPERTY))
    ?: settingOrNull(
      CAMEL_CASE_ENFORCED_PROPERTY,
      environment.config.propertyOrNull(CAMEL_CASE_ENFORCED_PROPERTY)?.getString(),
    )
    ?: config.camelCaseEnforced
    ?: false

/** Parses one configured value of [property]; null when absent or not a boolean, warning then. */
private fun settingOrNull(property: String, raw: String?): Boolean? {
  if (raw == null) return null
  return raw.trim().lowercase().toBooleanStrictOrNull().also {
    if (it == null) {
      logger.warn("Ignoring a non-boolean {}: \"{}\"", property, raw.trim())
    }
  }
}

/** Property that mounts `/apps/{appName}/app-info`. */
internal const val APP_INFO_ENABLED_PROPERTY = "adk.app.info.enabled"

/**
 * Whether `/apps/{appName}/app-info` is mounted: the property first, then the Ktor config, then
 * [AdkServerConfig], else off.
 *
 * The property beats an explicit setting, as it does for the Development UI and the camelCase
 * spelling: the endpoint reports every agent's instruction and tools, so a deployment that needs it
 * off needs a lever that does not require a rebuild.
 */
internal fun Application.resolveAppInfoEnabled(config: AdkServerConfig): Boolean =
  settingOrNull(APP_INFO_ENABLED_PROPERTY, System.getProperty(APP_INFO_ENABLED_PROPERTY))
    ?: settingOrNull(
      APP_INFO_ENABLED_PROPERTY,
      environment.config.propertyOrNull(APP_INFO_ENABLED_PROPERTY)?.getString(),
    )
    ?: config.includeAppInfo
    ?: false
