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

/**
 * Returns the config that this config's sign-in request carries: for OAuth2 and OpenID Connect, its
 * exchanged credential holds the authorization URL and `state`. The request travels through the app
 * and into the session, so it drops the API key, the HTTP password, token and headers, the OAuth2
 * client secret, and a service account key, with the credential unless that uses Application
 * Default Credentials.
 *
 * @throws IllegalArgumentException if an OAuth2 or OpenID Connect config has no authorization URL
 *   yet and has no OAuth2 raw credential or can't produce the URL, as [withNewAuthUri] describes.
 */
internal fun AuthConfig.signInConfig(): AuthConfig {
  if (!authScheme.usesAuthorizationUrl) return withoutConfiguredSecrets()
  val credential = existingAuthorizationCredential() ?: newAuthorizationCredential()
  return copy(exchangedAuthCredential = credential).withoutConfiguredSecrets()
}

/** Whether the user signs in to this scheme through an authorization URL. */
private val AuthScheme.usesAuthorizationUrl: Boolean
  get() =
    when (this) {
      is AuthScheme.OAuth2,
      is AuthScheme.OpenIdConnect,
      is AuthScheme.OpenIdConnectUrl -> true
      is AuthScheme.ApiKey,
      is AuthScheme.Http -> false
    }

/**
 * Returns the exchanged or the raw credential, in that order, if it already has an authorization
 * URL.
 */
private fun AuthConfig.existingAuthorizationCredential(): AuthCredential.OAuth2? =
  listOf(exchangedAuthCredential, rawAuthCredential)
    .filterIsInstance<AuthCredential.OAuth2>()
    .firstOrNull { !it.authUri.isNullOrEmpty() }

/** Returns the raw credential with a new authorization URL. */
private fun AuthConfig.newAuthorizationCredential(): AuthCredential.OAuth2 {
  val raw =
    requireNotNull(rawAuthCredential as? AuthCredential.OAuth2) {
      "An OAuth2 or OpenID Connect sign-in needs an OAuth2 raw credential."
    }
  return raw.withNewAuthUri(authScheme)
}

/** Returns this config without the secrets the agent configured. */
private fun AuthConfig.withoutConfiguredSecrets(): AuthConfig =
  copy(
    rawAuthCredential = rawAuthCredential?.withoutConfiguredSecrets(),
    exchangedAuthCredential = exchangedAuthCredential?.withoutConfiguredSecrets(),
  )

private fun AuthCredential.withoutConfiguredSecrets(): AuthCredential? =
  when (this) {
    is AuthCredential.ApiKey -> copy(apiKey = null)
    is AuthCredential.Http ->
      copy(credentials = credentials.copy(password = null, token = null), additionalHeaders = null)
    is AuthCredential.OAuth2 -> copy(clientSecret = null)
    is AuthCredential.ServiceAccount ->
      if (useDefaultCredential) copy(serviceAccountCredential = null) else null
  }
