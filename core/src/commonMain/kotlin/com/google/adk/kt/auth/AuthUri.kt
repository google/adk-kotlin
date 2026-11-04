/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalAuthApi::class)

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.crypto.generateRandomString
import com.google.adk.kt.logging.LoggerFactory
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.formUrlEncode

/** The length of a new OAuth2 `state` value. */
private const val STATE_LENGTH = 30

private object AuthUri

private val logger = LoggerFactory.getLogger(AuthUri::class)

/**
 * Returns this credential with a new authorization URL for [scheme] and its `state`. A public
 * client, or one that sets the `S256` code challenge method, also gets a PKCE challenge for its
 * code verifier, or for a new one.
 *
 * @throws IllegalArgumentException if this credential has no client ID, [scheme] is not an OAuth2
 *   scheme with a flow URL or an OpenID Connect scheme with endpoints, that URL is not an absolute
 *   HTTP or HTTPS URL, the code challenge method is not `S256`, or the code verifier is not ASCII.
 */
@OptIn(SecretAccess::class)
internal fun AuthCredential.OAuth2.withNewAuthUri(scheme: AuthScheme): AuthCredential.OAuth2 {
  require(!clientId.isNullOrEmpty()) { "An authorization URL needs a client ID." }
  val method =
    if (isPublicClient) {
      CodeChallengeMethod.S256
    } else {
      codeChallengeMethod?.ifEmpty { null }?.let(CodeChallengeMethod::named)
    }
  val (endpoint, scopes) = scheme.authorizationEndpointAndScopes()
  val verifier = method?.let { codeVerifier?.reveal()?.ifEmpty { null } ?: newCodeVerifier() }
  val state = generateRandomString(STATE_LENGTH)
  // The request's parameters in a fixed order, without empty values.
  val query =
    listOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "scope" to scopes.joinToString(" "),
        "state" to state,
        "access_type" to "offline",
        "prompt" to (prompt?.ifEmpty { null } ?: "consent"),
        "audience" to audience,
        "nonce" to nonce,
        "code_challenge" to verifier?.let { method.challenge(it) },
        "code_challenge_method" to method?.name,
      )
      .filter { (_, value) -> !value.isNullOrEmpty() }
      .formUrlEncode()
  val authUri = endpoint.withAuthorizationQuery(query)
  // Logged once the URL is built, so a config that fails validation doesn't warn first.
  if (isPublicClient) {
    logger.warn {
      "OAuth2 client secret is not set; using PKCE S256. Set codeChallengeMethod to S256 to " +
        "declare a public client."
    }
  }
  return copy(
    authUri = authUri,
    state = state,
    codeVerifier = verifier?.let(::Secret) ?: codeVerifier,
    codeChallengeMethod = method?.name ?: codeChallengeMethod,
  )
}

/**
 * Whether this is a public client: as in ADK Python, one with neither a client secret nor a code
 * challenge method. A public client signs in with PKCE `S256`.
 */
@OptIn(SecretAccess::class)
internal val AuthCredential.OAuth2.isPublicClient: Boolean
  get() = clientSecret?.reveal().isNullOrEmpty() && codeChallengeMethod.isNullOrEmpty()

/**
 * Returns this authorization endpoint, as written, with [query] after its own query.
 *
 * @throws IllegalArgumentException if this is not an absolute HTTP or HTTPS URL.
 */
private fun String.withAuthorizationQuery(query: String): String {
  val parsed =
    try {
      URLBuilder(this)
    } catch (e: URLParserException) {
      // Not chained: the parser's message repeats the endpoint.
      throw IllegalArgumentException("The authorization endpoint is not a valid URL.")
    }
  require(parsed.protocolOrNull in HTTP_PROTOCOLS && parsed.host.isNotEmpty()) {
    "The authorization endpoint must be an absolute HTTP or HTTPS URL."
  }
  // Spliced, not rebuilt: URLBuilder groups names ignoring case, so it can rename ADK's own.
  val url = trim()
  val beforeFragment = url.substringBefore('#')
  val separator =
    when {
      '?' !in beforeFragment -> "?"
      beforeFragment.endsWith('?') || beforeFragment.endsWith('&') -> ""
      else -> "&"
    }
  return beforeFragment + separator + query + url.substring(beforeFragment.length)
}

private val HTTP_PROTOCOLS = setOf(URLProtocol.HTTP, URLProtocol.HTTPS)

/** Returns the authorization endpoint and scopes of this scheme. */
private fun AuthScheme.authorizationEndpointAndScopes(): Pair<String, List<String>> =
  when (this) {
    is AuthScheme.OpenIdConnect -> authorizationEndpoint to scopes.orEmpty()
    is AuthScheme.OAuth2 -> flows.authorizationEndpointAndScopes()
    is AuthScheme.ApiKey,
    is AuthScheme.Http,
    is AuthScheme.OpenIdConnectUrl ->
      throw IllegalArgumentException(
        "An authorization URL needs an OAuth2 scheme or an OpenID Connect scheme with endpoints."
      )
  }

/**
 * Returns the first non-empty authorization endpoint and the scopes of the first flow present, in
 * this order: implicit, authorization code, client credentials, then password.
 */
private fun AuthScheme.OAuth2.Flows.authorizationEndpointAndScopes(): Pair<String, List<String>> {
  val endpoint =
    implicit?.authorizationUrl?.ifEmpty { null }
      ?: authorizationCode?.authorizationUrl?.ifEmpty { null }
      ?: clientCredentials?.tokenUrl?.ifEmpty { null }
      ?: password?.tokenUrl?.ifEmpty { null }
      ?: throw IllegalArgumentException("No flow of the OAuth2 scheme has an authorization URL.")
  val flow: AuthScheme.OAuth2.Flow? = implicit ?: authorizationCode ?: clientCredentials ?: password
  return endpoint to flow?.scopes?.keys?.toList().orEmpty()
}
