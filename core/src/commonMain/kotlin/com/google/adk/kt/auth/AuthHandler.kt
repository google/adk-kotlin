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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.sessions.State

/** Builds the sign-in requests ADK sends the app and reads the credentials the app sends back. */
internal class AuthHandler(private val config: AuthConfig) {

  /**
   * Returns the sign-in request for the config: for OAuth2 and OpenID Connect, its exchanged
   * credential carries the authorization URL and `state`. The request travels through the app and
   * into the session, so it drops the API key, the HTTP password, token and headers, and the OAuth2
   * client secret.
   *
   * @throws IllegalArgumentException if an OAuth2 or OpenID Connect config has no authorization URL
   *   yet and has no OAuth2 raw credential or can't produce the URL, as [withNewAuthUri] describes.
   */
  fun generateAuthRequest(): AuthConfig {
    if (config.authScheme.type !in AUTHORIZATION_URL_SCHEME_TYPES) {
      return config.withoutConfiguredSecrets()
    }
    val credential = existingAuthorizationCredential() ?: newAuthorizationCredential()
    return config.copy(exchangedAuthCredential = credential).withoutConfiguredSecrets()
  }

  /**
   * Returns the credential stored in [state] for the config, under its `temp:` key first, or null
   * if there is none. A malformed value is skipped with a warning. When the config has an OAuth2
   * raw credential, its client ID and secret replace the stored ones, which came back through the
   * app.
   */
  fun getAuthResponse(state: Map<String, Any>): AuthCredential? =
    listOf(State.TEMP_PREFIX + config.credentialKey, config.credentialKey)
      .firstNotNullOfOrNull { key -> storedCredential(state[key]) }
      ?.withConfiguredClient(config.rawAuthCredential)

  /**
   * Returns the exchanged or the raw credential, in that order, if it already has an authorization
   * URL.
   */
  private fun existingAuthorizationCredential(): AuthCredential.OAuth2? =
    listOf(config.exchangedAuthCredential, config.rawAuthCredential)
      .filterIsInstance<AuthCredential.OAuth2>()
      .firstOrNull { !it.authUri.isNullOrEmpty() }

  /**
   * Returns the raw credential with a new authorization URL; a public client gets PKCE S256, as in
   * ADK Python.
   */
  private fun newAuthorizationCredential(): AuthCredential.OAuth2 {
    val raw =
      requireNotNull(config.rawAuthCredential as? AuthCredential.OAuth2) {
        "An OAuth2 or OpenID Connect sign-in needs an OAuth2 raw credential."
      }
    val credential = raw.withNewAuthUri(config.authScheme)
    if (raw.isPublicClient) {
      logger.warn { "OAuth2 client secret is not set; treating the client as public." }
    }
    return credential
  }

  /** Returns [value] as a credential, or null if there is none or it is malformed. */
  private fun storedCredential(value: Any?): AuthCredential? {
    if (value is AuthCredential) return value
    val credential =
      when {
        value is Map<*, *> -> decodeCredentialOrNull(value)
        value is String && value.isNotEmpty() -> credentialFromString(value)
        else -> return null
      }
    // The key isn't logged: a derived key can reveal a weak secret.
    if (credential == null) logger.warn { "Ignoring a malformed auth credential stored in state." }
    return credential
  }

  /**
   * Returns [value] as the key or token of the config's scheme, or null for HTTP basic, which needs
   * a username and password.
   */
  private fun credentialFromString(value: String): AuthCredential? =
    when (val authScheme = config.authScheme) {
      is AuthScheme.ApiKey -> AuthCredential.ApiKey(Secret(value))
      is AuthScheme.Http ->
        if (authScheme.scheme.equals("basic", ignoreCase = true)) {
          null
        } else {
          AuthCredential.Http(
            scheme = authScheme.scheme,
            credentials = AuthCredential.Http.Credentials(token = Secret(value)),
          )
        }
      is AuthScheme.OAuth2,
      is AuthScheme.OpenIdConnect,
      is AuthScheme.OpenIdConnectUrl -> AuthCredential.OAuth2(accessToken = Secret(value))
    }

  companion object {
    // The scheme types ADK Python signs in to through an authorization URL.
    private val AUTHORIZATION_URL_SCHEME_TYPES = setOf("oauth2", "openIdConnect")

    private val logger = LoggerFactory.getLogger(AuthHandler::class)
  }
}

/** Returns this config without the secrets the agent configured, as ADK Python sends it. */
private fun AuthConfig.withoutConfiguredSecrets(): AuthConfig =
  copy(
    rawAuthCredential = rawAuthCredential?.withoutConfiguredSecrets(),
    exchangedAuthCredential = exchangedAuthCredential?.withoutConfiguredSecrets(),
  )

private fun AuthCredential.withoutConfiguredSecrets(): AuthCredential =
  when (this) {
    is AuthCredential.ApiKey -> copy(apiKey = null)
    is AuthCredential.Http ->
      copy(credentials = credentials.copy(password = null, token = null), additionalHeaders = null)
    is AuthCredential.OAuth2 -> copy(clientSecret = null)
    // ADK Python keeps the service account key in the request too.
    is AuthCredential.ServiceAccount -> this
  }

/** Returns [value] decoded as credential JSON, or null if it isn't valid. */
@OptIn(FrameworkInternalApi::class)
private fun decodeCredentialOrNull(value: Map<*, *>): AuthCredential? =
  try {
    adkJson.decodeFromJsonElement(AuthCredentialSerializer, anyToJsonElement(value))
  } catch (e: IllegalArgumentException) {
    // SerializationException extends IllegalArgumentException, so this catches both.
    null
  }

/**
 * Returns this credential with [raw]'s OAuth2 client ID and secret when both are OAuth2, so the app
 * can't choose the client.
 */
private fun AuthCredential.withConfiguredClient(raw: AuthCredential?): AuthCredential =
  if (this is AuthCredential.OAuth2 && raw is AuthCredential.OAuth2) {
    copy(clientId = raw.clientId, clientSecret = raw.clientSecret)
  } else {
    this
  }
