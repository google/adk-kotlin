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
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.pluginOrNull
import io.ktor.server.application.pluginRegistry
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.RoutingResolveResult
import io.ktor.server.routing.RoutingRoot
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

private const val REGEX_PREFIX = "regex:"
private const val CORS_PLUGIN_NAME = "CORS"
private const val ALLOWED_CORS_METHODS = "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"

private val FORWARDING_HEADERS =
  listOf(
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedFor,
    HttpHeaders.XForwardedHost,
    HttpHeaders.XForwardedProto,
    "X-Real-IP",
  )

private data class AllowedOriginsConfig(val literalOrigins: Set<String>, val originRegex: Regex?)

private data class OriginEndpoint(val scheme: String, val host: String, val port: Int)

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
      if (
        !allowRemoteRequests &&
          !isLocalClient(call.request.local.remoteAddress, call.request.headers)
      ) {
        call.respondText(
          text =
            "Forbidden: remote requests are disabled. Keep the server on loopback or explicitly " +
              "opt in via AdkServerConfig.allowRemoteRequests behind your own authentication.",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
      }
    }
  }

/**
 * Enforces DNS-rebinding (`Host`) and browser `Origin` checks:
 * 1. Rejects non-loopback or smuggled `Host` headers on loopback binds unless permitted by
 *    [allowOrigins].
 * 2. Enforces same-origin (honoring `Forwarded`, `X-Forwarded-Proto`, and `X-Forwarded-Host` behind
 *    reverse proxies) and evaluates [allowOrigins] entries (literal origins, `"*"`, and
 *    `"regex:<pattern>"`), while deferring to Ktor's `CORS` plugin if the embedding application
 *    installed one at the application or route level.
 */
internal fun originCheckPlugin(allowOrigins: List<String>, bindHost: String?) =
  createApplicationPlugin("AdkOriginCheck") {
    val parsedOrigins = parseAllowedOrigins(allowOrigins)
    val allowedRequestHosts = parseAllowedRequestHosts(parsedOrigins.literalOrigins)
    val loopbackBind = bindHost != null && isLoopbackAddress(bindHost)

    onCallValidators { call ->
      if (isDnsRebindingRequest(call.request.headers, loopbackBind, allowedRequestHosts)) {
        call.respondText(
          text = "Forbidden: host not allowed",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
        return@onCallValidators
      }

      val originValues = call.request.headers.getAll(HttpHeaders.Origin)
      if (originValues.isNullOrEmpty()) return@onCallValidators

      val singleOrigin =
        originValues.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() && ',' !in it }
      val originEndpoint = singleOrigin?.let(::parseNormalizedOrigin)
      if (singleOrigin == null || originEndpoint == null) {
        call.respondText(
          text = "Forbidden: origin not allowed",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
        return@onCallValidators
      }

      if (allowOrigins.isNotEmpty() && isOriginMatchedByConfig(singleOrigin, parsedOrigins)) {
        call.applyConfiguredCorsHeaders(
          origin = singleOrigin,
          wildcard = "*" in parsedOrigins.literalOrigins,
        )
        if (call.isCorsPreflight()) {
          call.respond(HttpStatusCode.OK)
        }
        return@onCallValidators
      }

      if (call.hasCorsPlugin()) return@onCallValidators

      if (
        (loopbackBind && !isLoopbackAddress(originEndpoint.host)) ||
          originEndpoint != parseRequestOrigin(call)
      ) {
        call.respondText(
          text = "Forbidden: origin not allowed",
          contentType = ContentType.Text.Plain,
          status = HttpStatusCode.Forbidden,
        )
        return@onCallValidators
      }

      if (call.isCorsPreflight()) {
        call.applyConfiguredCorsHeaders(origin = singleOrigin, wildcard = false)
        call.respond(HttpStatusCode.OK)
      }
    }
  }

/**
 * Returns `true` if [remoteAddress] is a loopback peer and [headers] carry no proxy/tunnel
 * forwarding headers.
 */
internal fun isLocalClient(remoteAddress: String, headers: Headers): Boolean {
  if (FORWARDING_HEADERS.any { headers.contains(it) }) {
    return false
  }
  return isLoopbackAddress(remoteAddress)
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

/** Extracts the normalized bare hostname from an RFC 6454 origin URL, rejecting userinfo. */
internal fun parseOriginHost(origin: String): String? = parseNormalizedOrigin(origin)?.host

internal fun isDnsRebindingRequest(
  headers: Headers,
  loopbackBind: Boolean,
  allowedRequestHosts: Set<String>?,
): Boolean {
  if (allowedRequestHosts == null || !loopbackBind) return false

  val hostValues = headers.getAll(HttpHeaders.Host)?.map(String::trim).orEmpty()
  if (hostValues.isEmpty()) return false
  if (hostValues.size > 1 || ',' in hostValues[0]) return true
  if (isLoopbackAddress(hostValues[0])) return false

  return stripPort(hostValues[0]).lowercase().trimEnd('.') !in allowedRequestHosts
}

private fun parseNormalizedOrigin(origin: String): OriginEndpoint? {
  if ('%' in origin) return null
  val uri =
    try {
      URI(origin)
    } catch (_: URISyntaxException) {
      return null
    }
  val scheme = uri.scheme?.let(::normalizeOriginScheme) ?: return null
  if (uri.rawUserInfo != null) return null
  if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
  if (uri.rawQuery != null || uri.rawFragment != null) return null
  val rawAuthority = uri.rawAuthority?.takeIf(String::isNotEmpty) ?: return null
  val (rawHost, explicitPort) = parseAuthorityHostAndPort(rawAuthority) ?: return null
  val host = rawHost.lowercase().trimEnd('.').takeIf(String::isNotEmpty) ?: return null
  return OriginEndpoint(
    scheme = scheme,
    host = host,
    port = explicitPort ?: defaultPortForScheme(scheme),
  )
}

private fun parseRequestOrigin(call: ApplicationCall): OriginEndpoint? {
  var forwardedProto: String? = null
  var forwardedHost: String? = null
  val forwarded = call.request.headers[HttpHeaders.Forwarded]?.substringBefore(',')?.trim()
  if (!forwarded.isNullOrEmpty()) {
    for (part in forwarded.split(';')) {
      val eq = part.indexOf('=')
      if (eq <= 0) continue
      val key = part.substring(0, eq).trim()
      val value = part.substring(eq + 1).trim().removeSurrounding("\"").takeIf(String::isNotEmpty)
      if (value == null) continue
      if (forwardedProto == null && key.equals("proto", ignoreCase = true)) {
        forwardedProto = value
      } else if (forwardedHost == null && key.equals("host", ignoreCase = true)) {
        forwardedHost = value
      }
    }
  }

  val rawScheme =
    forwardedProto
      ?: call.request.headers[HttpHeaders.XForwardedProto]?.substringBefore(',')?.trim()
      ?: call.request.local.scheme
  val scheme = normalizeOriginScheme(rawScheme) ?: return null

  val rawAuthority =
    (forwardedHost
        ?: call.request.headers[HttpHeaders.XForwardedHost]?.substringBefore(',')?.trim()
        ?: call.request.headers[HttpHeaders.Host]?.trim())
      ?.takeIf { it.isNotEmpty() && ',' !in it } ?: return null
  val (rawHost, explicitPort) = parseAuthorityHostAndPort(rawAuthority) ?: return null
  val host = rawHost.lowercase().trimEnd('.').takeIf(String::isNotEmpty) ?: return null
  return OriginEndpoint(
    scheme = scheme,
    host = host,
    port = explicitPort ?: defaultPortForScheme(scheme),
  )
}

private fun normalizeOriginScheme(rawScheme: String): String? =
  when (val lower = rawScheme.trim().lowercase()) {
    "" -> null
    "ws" -> "http"
    "wss" -> "https"
    else -> lower
  }

private fun defaultPortForScheme(scheme: String): Int =
  when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> -1
  }

private fun ApplicationCall.isCorsPreflight(): Boolean =
  request.httpMethod == HttpMethod.Options &&
    request.headers[HttpHeaders.AccessControlRequestMethod] != null

private fun ApplicationCall.applyConfiguredCorsHeaders(origin: String, wildcard: Boolean) {
  if (response.headers[HttpHeaders.AccessControlAllowOrigin] == null) {
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, if (wildcard) "*" else origin)
  }
  if (!wildcard && response.headers[HttpHeaders.AccessControlAllowCredentials] == null) {
    response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
  }
  if (!wildcard && response.headers[HttpHeaders.Vary] == null) {
    response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
  }
  if (isCorsPreflight()) {
    if (response.headers[HttpHeaders.AccessControlAllowMethods] == null) {
      response.headers.append(HttpHeaders.AccessControlAllowMethods, ALLOWED_CORS_METHODS)
    }
    val requestedHeaders = request.headers[HttpHeaders.AccessControlRequestHeaders]?.trim()
    if (
      !requestedHeaders.isNullOrEmpty() &&
        response.headers[HttpHeaders.AccessControlAllowHeaders] == null
    ) {
      response.headers.append(HttpHeaders.AccessControlAllowHeaders, requestedHeaders)
    }
  }
}

private suspend fun PipelineCall.hasCorsPlugin(): Boolean {
  if (application.pluginRegistry.allKeys.any { it.name == CORS_PLUGIN_NAME }) return true
  val routingRoot = application.pluginOrNull(RoutingRoot) ?: return false
  if (!routingRoot.anyRouteHasCors()) return false
  val resolved = RoutingResolveContext(routingRoot, this, emptyList()).resolve()
  var node: RoutingNode? = (resolved as? RoutingResolveResult.Success)?.route
  while (node != null) {
    if (node.pluginRegistry.allKeys.any { it.name == CORS_PLUGIN_NAME }) return true
    node = node.parent
  }
  return false
}

private fun RoutingNode.anyRouteHasCors(): Boolean =
  pluginRegistry.allKeys.any { it.name == CORS_PLUGIN_NAME } ||
    children.any(RoutingNode::anyRouteHasCors)

/**
 * Returns [host] without a trailing `:port`, or unchanged when malformed so callers never read
 * `"127.0.0.1:8080.evil.com"` as loopback.
 */
private fun stripPort(host: String): String = parseHostAndPort(host)?.first ?: host

private fun parseAuthorityHostAndPort(authority: String): Pair<String, Int?>? {
  if (!authority.startsWith('[') && authority.count { it == ':' } > 1) return null
  return parseHostAndPort(authority)
}

private fun parseHostAndPort(host: String): Pair<String, Int?>? {
  val bare: String
  val suffix: String
  if (host.startsWith('[')) {
    val closeBracket = host.indexOf(']')
    if (closeBracket < 0) return null
    bare = host.substring(1, closeBracket)
    suffix = host.substring(closeBracket + 1)
  } else if (host.count { it == ':' } == 1) {
    val colon = host.indexOf(':')
    bare = host.substring(0, colon)
    suffix = host.substring(colon)
  } else {
    return host.takeIf(String::isNotEmpty)?.let { it to null }
  }
  val validBare = bare.takeIf(String::isNotEmpty) ?: return null
  if (suffix.isEmpty()) return validBare to null
  if (!suffix.startsWith(':')) return null
  val portText = suffix.drop(1)
  if (!portText.isAsciiDigits()) return null
  val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
  return validBare to port
}

private fun normalizeOriginLiteral(origin: String): String =
  if (origin == "*") "*" else origin.trim().trimEnd('/').lowercase()

private fun parseAllowedOrigins(allowOrigins: List<String>): AllowedOriginsConfig {
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
      literals.add(normalizeOriginLiteral(trimmed))
    }
  }
  val combined =
    if (regexPatterns.isEmpty()) null else Regex(regexPatterns.joinToString(separator = "|"))
  return AllowedOriginsConfig(literals, combined)
}

/** Hosts accepted by the DNS-rebinding check besides loopback, or `null` when `"*"` allows all. */
private fun parseAllowedRequestHosts(allowedLiteralOrigins: Set<String>): Set<String>? {
  if ("*" in allowedLiteralOrigins) return null
  return allowedLiteralOrigins.mapNotNull(::parseOriginHost).toSet()
}

private fun isOriginMatchedByConfig(origin: String, config: AllowedOriginsConfig): Boolean {
  if ("*" in config.literalOrigins || normalizeOriginLiteral(origin) in config.literalOrigins) {
    return true
  }
  return config.originRegex?.matchEntire(origin.trim().trimEnd('/')) != null
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
