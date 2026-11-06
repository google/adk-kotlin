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
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import io.ktor.util.pipeline.PipelinePhase
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

private const val REGEX_PREFIX = "regex:"
private const val ALLOWED_CORS_METHODS = "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"

private val ADK_OPTIONS_PHASE = PipelinePhase("AdkOptionsPreflight")

private val FORWARDING_HEADERS =
  listOf(
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedFor,
    HttpHeaders.XForwardedHost,
    HttpHeaders.XForwardedProto,
    "X-Real-IP",
  )

private data class AllowedOriginsConfig(val literalOrigins: Set<String>, val originRegex: Regex?)

private data class OriginGuardConfig(
  val parsedOrigins: AllowedOriginsConfig,
  val allowedRequestHosts: Set<String>?,
  val loopbackBind: Boolean?,
)

private data class OriginEndpoint(val scheme: String, val host: String, val port: Int)

private data class HostAndPort(val host: String, val port: Int?)

private class AdkRouteSelector : RouteSelector() {
  override suspend fun evaluate(
    context: RoutingResolveContext,
    segmentIndex: Int,
  ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

  override fun toString(): String = ""
}

/**
 * Registers [build] under a transparent child [Route] guarded by [localClientGuardPlugin] and
 * [originCheckPlugin], so embedder-defined routes on the same application are unaffected.
 */
internal fun Route.guardedAdkRoutes(
  allowRemoteRequests: Boolean,
  allowOrigins: List<String>,
  bindHost: String?,
  build: Route.() -> Unit,
) {
  val originConfig = parseOriginGuardConfig(allowOrigins = allowOrigins, bindHost = bindHost)
  val adkRoute =
    createChild(AdkRouteSelector()).apply {
      install(localClientGuardPlugin(allowRemoteRequests))
      install(originCheckPlugin(originConfig))
      build()
    } as RoutingNode
  application.insertPhaseAfter(ApplicationCallPipeline.Call, ADK_OPTIONS_PHASE)
  application.intercept(ADK_OPTIONS_PHASE) {
    val currentCall = context
    if (
      !currentCall.response.isCommitted &&
        currentCall.request.httpMethod == HttpMethod.Options &&
        adkRoute.matchesPath(currentCall)
    ) {
      enforceLocalClientGuard(currentCall, allowRemoteRequests)
      enforceOriginAndHostGuard(currentCall, originConfig)
    }
  }
}

private suspend fun RoutingNode.matchesPath(call: PipelineCall): Boolean {
  val ancestors = generateSequence(parent) { it.parent }.toList().asReversed()
  val rootNode = ancestors.firstOrNull() ?: this
  val context = RoutingResolveContext(rootNode, call, emptyList())
  var segmentIndex = 0
  for (ancestor in ancestors) {
    val evaluation = ancestor.selector.evaluateIgnoringMethod(context, segmentIndex)
    if (evaluation !is RouteSelectorEvaluation.Success) return false
    segmentIndex += evaluation.segmentIncrement
  }
  return matchesSubtree(context, segmentIndex)
}

private suspend fun RoutingNode.matchesSubtree(
  context: RoutingResolveContext,
  segmentIndex: Int,
): Boolean {
  val evaluation = selector.evaluateIgnoringMethod(context, segmentIndex)
  if (evaluation !is RouteSelectorEvaluation.Success) return false
  val nextIndex = segmentIndex + evaluation.segmentIncrement
  if (nextIndex == context.segments.size && hasHandler()) return true
  return children.any { it.matchesSubtree(context, nextIndex) }
}

private suspend fun RouteSelector.evaluateIgnoringMethod(
  context: RoutingResolveContext,
  segmentIndex: Int,
): RouteSelectorEvaluation =
  if (this is HttpMethodRouteSelector) {
    RouteSelectorEvaluation.Transparent
  } else {
    evaluate(context, segmentIndex)
  }

/**
 * Rejects requests that did not arrive directly from a loopback peer unless [allowRemoteRequests]
 * explicitly permits remote callers.
 *
 * Uses the engine's non-spoofable socket peer address (`call.request.local.remoteAddress`) rather
 * than client-settable forwarding headers, and rejects proxied or tunneled requests whose peer
 * address belongs to the local forwarder rather than the remote caller.
 */
internal fun localClientGuardPlugin(allowRemoteRequests: Boolean): RouteScopedPlugin<Unit> =
  createRouteScopedPlugin("AdkLocalClientGuard") {
    onCall { call -> enforceLocalClientGuard(call, allowRemoteRequests) }
  }

private suspend fun enforceLocalClientGuard(call: ApplicationCall, allowRemoteRequests: Boolean) {
  if (call.response.isCommitted) return
  if (
    !allowRemoteRequests && !isLocalClient(call.request.local.remoteAddress, call.request.headers)
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

/** Enforces DNS-rebinding (`Host`) and browser `Origin` checks according to [config]. */
private fun originCheckPlugin(config: OriginGuardConfig): RouteScopedPlugin<Unit> =
  createRouteScopedPlugin("AdkOriginCheck") {
    onCall { call -> enforceOriginAndHostGuard(call, config) }
  }

private suspend fun enforceOriginAndHostGuard(call: ApplicationCall, config: OriginGuardConfig) {
  if (call.response.isCommitted) return
  if (
    isDnsRebindingRequest(
      headers = call.request.headers,
      loopbackBind = config.loopbackBind == true,
      allowedRequestHosts = config.allowedRequestHosts,
    )
  ) {
    call.respondText(
      text = "Forbidden: host not allowed",
      contentType = ContentType.Text.Plain,
      status = HttpStatusCode.Forbidden,
    )
    return
  }

  val originValues = call.request.headers.getAll(HttpHeaders.Origin)
  if (originValues.isNullOrEmpty()) return

  val singleOrigin = originValues.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() && ',' !in it }
  val originEndpoint = singleOrigin?.let(::parseNormalizedOrigin)
  if (singleOrigin == null || originEndpoint == null) {
    call.respondText(
      text = "Forbidden: origin not allowed",
      contentType = ContentType.Text.Plain,
      status = HttpStatusCode.Forbidden,
    )
    return
  }

  if (isOriginMatchedByConfig(singleOrigin, config.parsedOrigins)) {
    call.applyConfiguredCorsHeaders(
      origin = singleOrigin,
      wildcard = "*" in config.parsedOrigins.literalOrigins,
    )
    if (call.isCorsPreflight()) {
      call.respond(HttpStatusCode.OK)
    }
    return
  }

  if (call.response.headers[HttpHeaders.AccessControlAllowOrigin] != null) return

  val loopbackServer = config.loopbackBind ?: isLoopbackAddress(call.request.local.localAddress)
  if (
    (loopbackServer && !isLoopbackAddress(originEndpoint.host)) ||
      originEndpoint != parseRequestOrigin(call)
  ) {
    call.respondText(
      text = "Forbidden: origin not allowed",
      contentType = ContentType.Text.Plain,
      status = HttpStatusCode.Forbidden,
    )
    return
  }

  if (call.isCorsPreflight()) {
    call.applyConfiguredCorsHeaders(origin = singleOrigin, wildcard = false)
    call.respond(HttpStatusCode.OK)
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
internal fun isLoopbackAddress(host: String): Boolean =
  isLoopbackNormalizedHost(stripPort(host).lowercase().trimEnd('.'))

private fun isLoopbackNormalizedHost(bare: String): Boolean {
  if (bare == "localhost") return true
  if (isLoopbackIpv4Literal(bare)) return true
  if (':' in bare) return parseIpv6Literal(bare)?.isLoopbackAddress == true
  return false
}

/** Extracts the normalized bare hostname from an RFC 6454 origin URL, rejecting userinfo. */
internal fun parseOriginHost(origin: String): String? = parseNormalizedOrigin(origin)?.host

/**
 * Returns `true` if [headers] represent a forbidden host or DNS-rebinding request on a loopback
 * bind, or `false` if the request is permitted by [allowedRequestHosts] or loopback checks.
 */
internal fun isDnsRebindingRequest(
  headers: Headers,
  loopbackBind: Boolean,
  allowedRequestHosts: Set<String>?,
): Boolean {
  if (allowedRequestHosts == null || !loopbackBind) return false

  val hostValues = headers.getAll(HttpHeaders.Host)?.map(String::trim).orEmpty()
  if (hostValues.isEmpty()) return false
  if (hostValues.size > 1 || ',' in hostValues[0]) return true
  val bare = stripPort(hostValues[0]).lowercase().trimEnd('.')
  if (isLoopbackNormalizedHost(bare)) return false

  return bare !in allowedRequestHosts
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
  return toOriginEndpoint(scheme = scheme, rawAuthority = rawAuthority)
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
  return toOriginEndpoint(scheme = scheme, rawAuthority = rawAuthority)
}

private fun toOriginEndpoint(scheme: String, rawAuthority: String): OriginEndpoint? {
  val (rawHost, explicitPort) = parseHostAndPort(rawAuthority) ?: return null
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

/**
 * Returns [host] without a trailing `:port`, or unchanged when malformed so callers never read
 * `"127.0.0.1:8080.evil.com"` as loopback.
 */
private fun stripPort(host: String): String = parseHostAndPort(host)?.host ?: host

private fun parseHostAndPort(host: String): HostAndPort? {
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
    return host.takeIf(String::isNotEmpty)?.let { HostAndPort(it, null) }
  }
  val validBare = bare.takeIf(String::isNotEmpty) ?: return null
  if (suffix.isEmpty()) return HostAndPort(validBare, null)
  if (!suffix.startsWith(':')) return null
  val portText = suffix.drop(1)
  if (!portText.isAsciiDigits()) return null
  val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
  return HostAndPort(validBare, port)
}

private fun parseOriginGuardConfig(
  allowOrigins: List<String>,
  bindHost: String?,
): OriginGuardConfig {
  val parsedOrigins = parseAllowedOrigins(allowOrigins)
  return OriginGuardConfig(
    parsedOrigins = parsedOrigins,
    allowedRequestHosts = parseAllowedRequestHosts(parsedOrigins.literalOrigins),
    loopbackBind = bindHost?.let(::isLoopbackAddress),
  )
}

private fun normalizeOriginLiteral(origin: String): String = origin.trim().trimEnd('/').lowercase()

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

private fun parseIpv6Literal(input: String): InetAddress? {
  val percent = input.indexOf('%')
  val withoutZone =
    if (percent < 0) {
      input
    } else {
      val zone = input.substring(percent + 1)
      if (zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
        return null
      }
      input.substring(0, percent)
    }
  return try {
    // Bracketed "[...]" forces InetAddress to parse a numeric IPv6 literal without DNS lookup.
    InetAddress.getByName("[$withoutZone]")
  } catch (_: UnknownHostException) {
    null
  }
}
