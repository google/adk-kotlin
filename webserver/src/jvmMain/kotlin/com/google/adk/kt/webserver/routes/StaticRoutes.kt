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

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.lang.invoke.MethodHandles
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

/** Property that decides whether the Development UI is served at all. */
internal const val WEB_UI_ENABLED_PROPERTY = "adk.web.ui.enabled"

/**
 * Whether to mount the Development UI, from the `adk.web.ui.enabled` system property, else the
 * application config, else [default]. A value that is not a boolean counts as unset, so a mistyped
 * system property cannot mask a setting in the config. Only a host-supplied Ktor config is read;
 * `embeddedServer` supplies none.
 */
internal fun Application.isWebUiEnabled(default: Boolean): Boolean =
  webUiSettingOrNull(System.getProperty(WEB_UI_ENABLED_PROPERTY), "system property")
    ?: webUiSettingOrNull(
      environment.config.propertyOrNull(WEB_UI_ENABLED_PROPERTY)?.getString(),
      "application config",
    )
    ?: default

/** Parses one configured value; null when absent or not a boolean, warning in the latter case. */
private fun webUiSettingOrNull(raw: String?, source: String): Boolean? {
  if (raw == null) return null
  val value = raw.trim()
  return value.lowercase().toBooleanStrictOrNull().also {
    if (it == null) {
      logger.warn(
        "Ignoring a non-boolean {} from the {}: \"{}\"",
        WEB_UI_ENABLED_PROPERTY,
        source,
        value,
      )
    }
  }
}

internal fun Route.staticRoutes(application: Application) {
  var webUiDir =
    System.getProperty("adk.web.ui.dir")
      ?: application.environment.config.propertyOrNull("adk.web.ui.dir")?.getString()

  logger.info("webUiDir from property/config: {}", webUiDir)

  var dir: File? = null

  if (!webUiDir.isNullOrEmpty()) {
    val providedDir = File(webUiDir)
    logger.info("Trying to serve from provided directory: {}", providedDir.absolutePath)
    if (providedDir.exists() && providedDir.isDirectory) {
      dir = providedDir
    } else {
      logger.warn(
        "Provided directory does not exist or is not a directory: {}",
        providedDir.absolutePath,
      )
    }
  }

  if (dir != null) {
    get("/dev-ui") { call.respondRedirect("/dev-ui/") }
    get("/dev-ui/") {
      val indexFile = File(dir, "index.html")
      if (indexFile.exists()) {
        call.respondFile(indexFile)
      } else {
        call.respond(HttpStatusCode.NotFound)
      }
    }
    staticFiles("/dev-ui", dir)
  } else {
    logger.info("Serving embedded static browser assets as fallback.")
    get("/dev-ui") { call.respondRedirect("/dev-ui/") }
    staticResources("/dev-ui", "browser")
  }

  get("/") { call.respondRedirect("/dev-ui") }
}
