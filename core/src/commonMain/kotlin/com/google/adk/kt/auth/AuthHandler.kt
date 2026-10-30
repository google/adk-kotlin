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
import com.google.adk.kt.logging.LoggerFactory

/** Builds the sign-in requests ADK sends the app, as ADK Python's `AuthHandler` does. */
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
