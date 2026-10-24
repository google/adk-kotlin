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

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.response.respondText
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import org.slf4j.LoggerFactory

private val guardLogger = LoggerFactory.getLogger("com.google.adk.kt.webserver.RequestGuards")

/** Property that overrides [AdkServerConfig.allowOrigins] with a comma-separated origin list. */
internal const val ALLOW_ORIGINS_PROPERTY = "adk.allow.origins"

/** Property that overrides [AdkServerConfig.allowRemoteRequests]. */
internal const val ALLOW_REMOTE_REQUESTS_PROPERTY = "adk.allow.remote.requests"

/** Environment variable opting in to non-loopback requests on unauthenticated servers. */
internal const val ALLOW_REMOTE_REQUESTS_ENV = "ADK_ALLOW_REMOTE_REQUESTS"

/** ADK Python's environment variable opting in to remote `/dev` requests. */
internal const val ALLOW_REMOTE_AGENT_BUILDER_ENV = "ADK_ALLOW_REMOTE_AGENT_BUILDER"

private const val REGEX_PREFIX = "regex:"
private const val X_REAL_IP_HEADER = "X-Real-IP"

private val TRUTHY_ENV_VALUES = setOf("1", "true", "yes")

private val FORWARDING_HEADERS =
  listOf(
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedFor,
    HttpHeaders.XForwardedHost,
    HttpHeaders.XForwardedProto,
    X_REAL_IP_HEADER,
  )

/**
 * Configures Ktor's built-in `CORS` plugin (`io.ktor:ktor-server-cors`) to enforce same-origin by
 * default and evaluate [allowOrigins] entries (literal origins, `"*"`, and `"regex:<pattern>"`).
 */
internal fun CORSConfig.configureAdkCors(allowOrigins: List<String>) {
  val (literalOrigins, compiledOriginRegex) = parseAllowedOrigins(allowOrigins)
  allowSameOrigin = true
  allowNonSimpleContentTypes = true
  anyMethod()
  allowHeaders { true }
  if (allowOrigins.isNotEmpty()) {
    allowOrigins { origin ->
      origin != "null" && isOriginMatchedByConfig(origin, literalOrigins, compiledOriginRegex)
    }
  }
}

/**
 * Rejects requests that did not arrive directly from a loopback peer unless [allowRemoteRequests]
 * explicitly permits remote callers.
 *
 * Uses the engine's non-spoofable socket peer address (`call.request.local.remoteAddress`) rather
 * than client-settable forwarding headers, and rejects proxied or tunneled requests whose peer
 * address belongs to the local forwarder rather than the remote caller.
 */
internal fun localClientGuardPlugin(allowRemoteRequests: Boolean) =
  createApplicationPlugin("AdkLocalClientGuard") {
    onCall { call ->
      if (!allowRemoteRequests && !isLocalClient(call)) {
        call.respondText(
          text =
            "Forbidden: remote requests are disabled. Keep the server on loopback or explicitly " +
              "opt in via allowRemoteRequests, $ALLOW_REMOTE_REQUESTS_PROPERTY=true, or " +
              "$ALLOW_REMOTE_REQUESTS_ENV=1 behind your own authentication.",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
      }
    }
  }

/**
 * Companion guard to Ktor's `CORS` plugin that:
 * 1. Rejects DNS-rebinding `Host` headers on loopback binds before `CORS` checks `allowSameOrigin`
 *    (which compares `Origin` against the request's `Host` header).
 * 2. Rejects duplicate or malformed `Origin` headers (e.g. userinfo confusion) that Ktor's `CORS`
 *    plugin treats as `SkipCORS`.
 */
internal fun originCheckPlugin(allowOrigins: List<String>, bindHost: String?) =
  createApplicationPlugin("AdkOriginCheck") {
    val (literalOrigins, _) = parseAllowedOrigins(allowOrigins)
    val allowedRequestHosts = parseAllowedRequestHosts(literalOrigins)

    onCall { call ->
      if (isDnsRebindingRequest(call, bindHost, allowedRequestHosts)) {
        call.respondText(
          text = "Forbidden: host not allowed",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
        return@onCall
      }

      val originValues = call.request.headers.getAll(HttpHeaders.Origin)
      if (!originValues.isNullOrEmpty()) {
        val singleOrigin =
          originValues.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() && ',' !in it }
        if (singleOrigin == null || parseOriginHost(singleOrigin) == null) {
          call.respondText(
            text = "Forbidden: origin not allowed",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.Forbidden,
          )
        }
      }
    }
  }

/**
 * Resolves the effective allowed origins: system property first, then Ktor `ApplicationConfig`,
 * then [AdkServerConfig.allowOrigins].
 */
internal fun Application.resolveAllowOrigins(config: AdkServerConfig): List<String> =
  parseCommaSeparatedProperty(System.getProperty(ALLOW_ORIGINS_PROPERTY))
    ?: parseCommaSeparatedProperty(
      environment.config.propertyOrNull(ALLOW_ORIGINS_PROPERTY)?.getString()
    )
    ?: config.allowOrigins

/**
 * Resolves whether non-loopback callers are accepted: system property first, then Ktor
 * `ApplicationConfig`, then environment variables (`ADK_ALLOW_REMOTE_REQUESTS` /
 * `ADK_ALLOW_REMOTE_AGENT_BUILDER`), then [AdkServerConfig.allowRemoteRequests], else [default].
 */
internal fun Application.resolveAllowRemoteRequests(
  config: AdkServerConfig,
  default: Boolean,
  envLookup: (String) -> String? = System::getenv,
): Boolean =
  booleanSettingOrNull(
    ALLOW_REMOTE_REQUESTS_PROPERTY,
    System.getProperty(ALLOW_REMOTE_REQUESTS_PROPERTY),
  )
    ?: booleanSettingOrNull(
      ALLOW_REMOTE_REQUESTS_PROPERTY,
      environment.config.propertyOrNull(ALLOW_REMOTE_REQUESTS_PROPERTY)?.getString(),
    )
    ?: envFlagOrNull(ALLOW_REMOTE_REQUESTS_ENV, envLookup)
    ?: envFlagOrNull(ALLOW_REMOTE_AGENT_BUILDER_ENV, envLookup)
    ?: config.allowRemoteRequests
    ?: default

/**
 * Returns `true` iff [call] arrived straight from a process on this machine with no proxy or tunnel
 * forwarding headers attached.
 */
internal fun isLocalClient(call: ApplicationCall): Boolean {
  if (FORWARDING_HEADERS.any { call.request.headers.contains(it) }) {
    return false
  }
  return isLoopbackAddress(call.request.local.remoteAddress)
}

/**
 * Returns [host] without a trailing `:port`, or unchanged when malformed so callers never read
 * `"127.0.0.1:8080.evil.com"` as loopback.
 */
internal fun stripPort(host: String): String {
  val bare: String
  val suffix: String
  if (host.startsWith('[')) {
    val closeBracket = host.indexOf(']')
    if (closeBracket < 0) return host
    bare = host.substring(1, closeBracket)
    suffix = host.substring(closeBracket + 1)
  } else if (host.count { it == ':' } == 1) {
    val colon = host.indexOf(':')
    bare = host.substring(0, colon)
    suffix = host.substring(colon)
  } else {
    return host
  }
  if (suffix.isNotEmpty() && !(suffix.startsWith(':') && suffix.drop(1).isAsciiDigits())) {
    return host
  }
  return bare
}

/**
 * Returns `true` if [host] (with or without a port) names a loopback host or IP literal without
 * performing any DNS lookup.
 */
internal fun isLoopbackAddress(host: String): Boolean {
  val bare = stripPort(host).lowercase().trimEnd('.')
  if (bare == "localhost") return true
  if (isLoopbackIpv4Literal(bare)) return true
  if (':' in bare) return isLoopbackIpv6Literal(bare)
  return false
}

private fun parseAllowedOrigins(allowOrigins: List<String>): Pair<Set<String>, Regex?> {
  val literals = mutableSetOf<String>()
  val regexPatterns = mutableListOf<String>()
  for (entry in allowOrigins) {
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) continue
    if (trimmed.startsWith(REGEX_PREFIX)) {
      val pattern = trimmed.removePrefix(REGEX_PREFIX)
      if (pattern.isNotEmpty()) {
        regexPatterns.add("(?:$pattern)")
      }
    } else {
      literals.add(trimmed)
    }
  }
  val combined =
    if (regexPatterns.isEmpty()) null else Regex(regexPatterns.joinToString(separator = "|"))
  return literals to combined
}

/** Hosts accepted by the DNS-rebinding check besides loopback, or `null` when `"*"` allows all. */
private fun parseAllowedRequestHosts(allowedLiteralOrigins: Set<String>): Set<String>? {
  if ("*" in allowedLiteralOrigins) return null
  val hosts = mutableSetOf<String>()
  for (origin in allowedLiteralOrigins) {
    parseOriginHost(origin)?.let(hosts::add)
  }
  return hosts
}

private fun isDnsRebindingRequest(
  call: ApplicationCall,
  bindHost: String?,
  allowedRequestHosts: Set<String>?,
): Boolean {
  if (allowedRequestHosts == null || bindHost == null) return false
  if (!isLoopbackAddress(bindHost)) return false

  val hostValues = call.request.headers.getAll(HttpHeaders.Host)?.map(String::trim).orEmpty()
  if (hostValues.isEmpty()) return false
  if (hostValues.size > 1 || ',' in hostValues[0]) return true
  if (isLoopbackAddress(hostValues[0])) return false

  return stripPort(hostValues[0]).lowercase().trimEnd('.') !in allowedRequestHosts
}

private fun isOriginMatchedByConfig(
  origin: String,
  allowedLiteralOrigins: Set<String>,
  allowedOriginRegex: Regex?,
): Boolean {
  if ("*" in allowedLiteralOrigins || origin in allowedLiteralOrigins) return true
  return allowedOriginRegex?.matchEntire(origin) != null
}

/** Extracts the normalized bare hostname from an RFC 6454 origin URL, rejecting userinfo. */
internal fun parseOriginHost(origin: String): String? {
  val uri =
    try {
      URI(origin)
    } catch (_: URISyntaxException) {
      return null
    }
  if (uri.scheme == null || uri.rawUserInfo != null) return null
  val rawAuthority = uri.rawAuthority?.takeIf(String::isNotEmpty) ?: return null
  return stripPort(rawAuthority).lowercase().trimEnd('.').takeIf(String::isNotEmpty)
}

private fun parseCommaSeparatedProperty(raw: String?): List<String>? {
  if (raw == null || raw.isBlank()) return null
  return raw.split(',').map(String::trim).filter(String::isNotEmpty)
}

private fun booleanSettingOrNull(name: String, raw: String?): Boolean? {
  if (raw == null || raw.isBlank()) return null
  return raw.trim().lowercase().toBooleanStrictOrNull().also {
    if (it == null) {
      guardLogger.warn("Ignoring a non-boolean {}: \"{}\"", name, raw.trim())
    }
  }
}

private fun envFlagOrNull(name: String, envLookup: (String) -> String?): Boolean? {
  val raw = envLookup(name)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
  if (raw in TRUTHY_ENV_VALUES) return true
  if (raw == "0" || raw == "false" || raw == "no") return false
  guardLogger.warn("Ignoring unrecognized boolean environment value for {}: \"{}\"", name, raw)
  return null
}

private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun isLoopbackIpv4Literal(input: String): Boolean {
  val parts = input.split('.')
  if (parts.size != 4) return false
  for (part in parts) {
    if (part.length !in 1..3 || !part.isAsciiDigits()) return false
    if (part.length > 1 && part[0] == '0') return false
    if (part.toInt() > 255) return false
  }
  return parts[0].toInt() == 127
}

private fun isLoopbackIpv6Literal(input: String): Boolean {
  val percent = input.indexOf('%')
  val withoutZone =
    if (percent < 0) {
      input
    } else {
      val zone = input.substring(percent + 1)
      if (zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
        return false
      }
      input.substring(0, percent)
    }
  return try {
    // Bracketed "[...]" forces InetAddress to parse a numeric IPv6 literal without DNS lookup.
    InetAddress.getByName("[$withoutZone]").isLoopbackAddress
  } catch (_: UnknownHostException) {
    false
  }
}
