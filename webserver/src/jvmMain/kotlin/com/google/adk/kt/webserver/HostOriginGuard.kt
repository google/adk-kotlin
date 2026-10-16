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

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.net.UnknownHostException

/** The reason sent with the 403 for a refused `Host` header. */
internal const val HOST_NOT_ALLOWED = "host not allowed"

/** The reason sent with the 403 for a refused `Origin` header. */
internal const val ORIGIN_NOT_ALLOWED = "origin not allowed"

/**
 * Returns the reason a request's `Host` or `Origin` header is not allowed, or `null` to serve it.
 *
 * The `Host` check uses [bindHost] (the configured listen address, or `null` if unknown), while the
 * `Origin` check falls back to [socketHost] (the local socket address) when [bindHost] is `null`.
 */
internal fun hostOriginRefusal(
  headers: Headers,
  scheme: String,
  bindHost: String?,
  socketHost: String?,
  allowedHosts: Set<String>,
): String? {
  if ("*" in allowedHosts) return null
  if (isDnsRebinding(headers.getAll(HttpHeaders.Host).orEmpty(), bindHost, allowedHosts)) {
    return HOST_NOT_ALLOWED
  }
  val origin = headers[HttpHeaders.Origin] ?: return null
  val serverHost = bindHost ?: socketHost
  if (!isOriginAllowed(origin, requestOriginOf(headers, scheme), serverHost, allowedHosts)) {
    return ORIGIN_NOT_ALLOWED
  }
  return null
}

/** On a loopback [bindHost], whether [hostValues] name several hosts or an unlisted foreign one. */
private fun isDnsRebinding(
  hostValues: List<String>,
  bindHost: String?,
  allowedHosts: Set<String>,
): Boolean {
  // Other names reach a non-loopback bind legitimately, and an unknown bind is not ours to guess.
  if (bindHost == null || !isLoopbackHost(bindHost)) return false
  // Browsers always send Host, so a request without one is not a rebinding vector.
  if (hostValues.isEmpty()) return false
  // Host is a singleton header, so a list of them is smuggling rather than a client.
  if (hostValues.size > 1 || ',' in hostValues[0]) return true
  val host = hostValues[0].trim()
  return !isLoopbackHost(host) && bareHost(host) !in allowedHosts
}

/**
 * Whether a browser page at [origin] may make this request.
 *
 * The origin's host may be listed in [allowedHosts], or [origin] may match [requestOrigin]. When
 * [allowedHosts] is empty and [serverHost] is loopback, [origin] must also be a loopback host, so a
 * DNS rebinding page, whose `Origin` and `Host` both name the attacker's host, cannot pass.
 */
private fun isOriginAllowed(
  origin: String,
  requestOrigin: String?,
  serverHost: String?,
  allowedHosts: Set<String>,
): Boolean {
  val originHost = originHostOf(origin)
  if (originHost in allowedHosts) return true
  if (allowedHosts.isEmpty() && serverHost != null && isLoopbackHost(serverHost)) {
    if (originHost == null || !isLoopbackHost(originHost)) return false
  }
  return origin == requestOrigin
}

/**
 * This request's own origin: from `Forwarded` when it names both proto and host, otherwise from
 * `X-Forwarded-Host` or `Host` with `X-Forwarded-Proto` or [scheme].
 */
internal fun requestOriginOf(headers: Headers, scheme: String): String? {
  val forwarded = headers.firstValue(HttpHeaders.Forwarded)
  if (forwarded != null) {
    var proto: String? = null
    var host: String? = null
    for (element in forwarded.split(';')) {
      if ('=' !in element) continue
      val value = element.substringAfter('=').trim().removeSurrounding("\"")
      when (element.substringBefore('=').trim().lowercase()) {
        "proto" -> proto = value
        "host" -> host = value
      }
    }
    if (proto != null && host != null) return "${originScheme(proto)}://$host"
  }
  val host =
    headers.firstValue(HttpHeaders.XForwardedHost)
      ?: headers.firstValue(HttpHeaders.Host)
      ?: return null
  val proto = headers.firstValue(HttpHeaders.XForwardedProto) ?: scheme
  return "${originScheme(proto)}://$host"
}

/** The first element of the first [name] header, the only part [requestOriginOf] reads. */
private fun Headers.firstValue(name: String): String? = get(name)?.substringBefore(',')?.trim()

/** Converts [scheme] to its `Origin` header equivalent (`ws` -> `http`, `wss` -> `https`). */
private fun originScheme(scheme: String): String =
  when (scheme) {
    "ws" -> "http"
    "wss" -> "https"
    else -> scheme
  }

/** The lowercased, unbracketed host of an `Origin`, or null for an opaque origin like "null". */
private fun originHostOf(origin: String): String? {
  val authority =
    origin.substringAfter("://", missingDelimiterValue = "").takeWhile { it !in "/?#" }
  // A URL parser rejects an authority with an unmatched bracket.
  if (('[' in authority) != (']' in authority)) return null
  // A malformed port or text after the brackets keeps the authority whole, so it matches nothing.
  return stripPort(authority.substringAfterLast('@')).lowercase().ifEmpty { null }
}

/** Whether [host], with or without a port, names this machine: `localhost` or a loopback IP. */
private fun isLoopbackHost(host: String): Boolean {
  val bare = bareHost(host)
  return bare == "localhost" || isLoopbackIp(bare)
}

/** [host] as compared: without a port, lowercased, and without the root dot a name may carry. */
internal fun bareHost(host: String): String = stripPort(host).lowercase().trimEnd('.')

/** [host] without its port, or whole when the port is malformed, as in `127.0.0.1:1.a.b`. */
private fun stripPort(host: String): String {
  val bare: String
  val suffix: String
  when {
    host.startsWith("[") -> {
      val end = host.indexOf(']')
      if (end < 0) return host
      bare = host.substring(1, end)
      suffix = host.substring(end + 1)
    }
    host.count { it == ':' } == 1 -> {
      bare = host.substringBefore(':')
      suffix = ":${host.substringAfter(':')}"
    }
    // No port, or an unbracketed IPv6 address.
    else -> return host
  }
  val port = suffix.removePrefix(":")
  val validPort = suffix.startsWith(":") && port.isNotEmpty() && port.all { it in '0'..'9' }
  return if (suffix.isEmpty() || validPort) bare else host
}

/** Whether [bare] is a loopback IP literal; a name is never resolved to find out. */
private fun isLoopbackIp(bare: String): Boolean {
  val match = IPV4_LITERAL.matchEntire(bare)
  if (match != null) {
    val octets = match.groupValues.drop(1)
    val valid = octets.all { it.toInt() <= 255 && (it.length == 1 || it[0] != '0') }
    return valid && octets[0] == "127"
  }
  // A leading dot sends InetAddress to the resolver; otherwise these characters only parse.
  if (':' !in bare || bare.startsWith('.') || !bare.all { it in IPV6_LITERAL_CHARS }) return false
  return try {
    InetAddress.getByName(bare).isLoopbackAddress
  } catch (e: UnknownHostException) {
    false
  }
}

private val IPV4_LITERAL = Regex("""([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})""")

private val IPV6_LITERAL_CHARS: Set<Char> = "0123456789abcdefABCDEF:.".toSet()

/** The address [AdkApiServer] binds, recorded on its application so the ADK routes check `Host`. */
internal val BIND_HOST_KEY = AttributeKey<String>("AdkBindHost")

/**
 * Builds the routes in [build] behind a route-scoped guard that responds with 403 when
 * [hostOriginRefusal] rejects a request, on any HTTP method. Scoping the guard to these routes
 * leaves routes the embedding application adds unguarded, since this module installs into that
 * application.
 */
internal fun Route.hostOriginGuarded(allowedHosts: Set<String>, build: Route.() -> Unit) {
  // A selector per call, so a second call gets its own child rather than a duplicate install.
  val guarded = createChild(HostOriginGuardSelector())
  guarded.install(hostOriginGuard) { this.allowedHosts = allowedHosts }
  guarded.build()
}

/** Groups routes without consuming path segments; Ktor's `TransparentRouteSelector` is internal. */
private class HostOriginGuardSelector : RouteSelector() {
  override suspend fun evaluate(
    context: RoutingResolveContext,
    segmentIndex: Int,
  ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

  // Empty, so the routes under it print the same paths as before the guard.
  override fun toString(): String = ""
}

private class HostOriginGuardConfig {
  var allowedHosts: Set<String> = emptySet()
}

private val hostOriginGuard =
  createRouteScopedPlugin(
    name = "AdkHostOriginGuard",
    createConfiguration = ::HostOriginGuardConfig,
  ) {
    // Compared as a request's Host is: without port or brackets, lowercased.
    val allowedHosts = pluginConfig.allowedHosts.map(::bareHost).toSet()
    // Unset in an engine the embedder owns, whose bind is not ours to guess.
    val bindHost = application.attributes.getOrNull(BIND_HOST_KEY)
    onCall { call ->
      val refusal =
        hostOriginRefusal(
          call.request.headers,
          scheme = call.request.local.scheme,
          bindHost = bindHost,
          socketHost = call.request.local.localAddress,
          allowedHosts = allowedHosts,
        )
      if (refusal != null) {
        call.respondText("Forbidden: $refusal", status = HttpStatusCode.Forbidden)
      }
    }
  }
