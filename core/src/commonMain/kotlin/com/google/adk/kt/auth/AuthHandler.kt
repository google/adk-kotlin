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
import com.google.adk.kt.serialization.decodeFromPlainOrNull
import com.google.adk.kt.sessions.State

private object AuthHandler

private val logger = LoggerFactory.getLogger(AuthHandler::class)

/** What [storeSignInReply] did with an app's sign-in reply. */
internal enum class SignInReplyResult {
  /** The reply's credential is stored. */
  STORED,

  /** The reply is malformed or holds no credential, so nothing is stored. */
  NO_CREDENTIAL,

  /**
   * The reply holds an OAuth2 credential without an access token for a scheme that signs in through
   * an authorization URL, so nothing is stored: ADK can't exchange an authorization code yet.
   */
  NEEDS_CODE_EXCHANGE,
}

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

/**
 * Stores the credential in [reply], the app's answer to this config's sign-in request, in [state]
 * under this config's sign-in reply key, where tools read it until the invocation ends. The reply
 * is an auth config, whose exchanged credential counts, or a credential. Its missing OAuth2 client
 * ID, redirect URI, code verifier, code challenge method and token endpoint auth method come from
 * this config's exchanged or raw credential; its client ID always comes from an OAuth2 raw
 * credential, and no client secret is stored.
 */
internal fun AuthConfig.storeSignInReply(reply: Any?, state: State): SignInReplyResult {
  val answer = signInReply(reply) ?: return SignInReplyResult.NO_CREDENTIAL
  val credential =
    answer
      .withMissingFieldsFrom(exchangedAuthCredential ?: rawAuthCredential)
      .withConfiguredClient(rawAuthCredential)
  if (needsCodeExchange(credential)) return SignInReplyResult.NEEDS_CODE_EXCHANGE
  state[credentialKey.signInReplyStateKey] = credential.withoutClientSecret()
  return SignInReplyResult.STORED
}

/**
 * Returns the credential stored in [state] for this config, under its `temp:` key first, or null if
 * there is none. A malformed value is skipped with a warning. When this config has an OAuth2 raw
 * credential, its client ID and secret replace the stored ones, which came back through the app.
 */
internal fun AuthConfig.storedCredential(state: Map<String, Any>): AuthCredential? =
  credentialKey.stateKeys
    .firstNotNullOfOrNull { key -> credentialFrom(state[key]) }
    ?.withConfiguredClient(rawAuthCredential)

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

/**
 * Returns the credential in [reply]: an auth config's exchanged credential, or a credential; null
 * if it holds none.
 */
private fun signInReply(reply: Any?): AuthCredential? {
  val replyConfig = decodeFromPlainOrNull(AuthConfigSerializer, reply)
  if (replyConfig != null) return replyConfig.exchangedAuthCredential
  return decodeFromPlainOrNull(AuthCredentialSerializer, reply)
}

/**
 * Whether [credential] still needs a token exchange, which ADK can't do yet: an OAuth2 credential
 * without an access token, for a scheme that signs in through an authorization URL.
 */
internal fun AuthConfig.needsCodeExchange(credential: AuthCredential): Boolean =
  authScheme.usesAuthorizationUrl &&
    credential is AuthCredential.OAuth2 &&
    credential.accessToken.isNullOrEmpty()

/** Returns [value] as a credential for this config, or null if there is none or it is malformed. */
private fun AuthConfig.credentialFrom(value: Any?): AuthCredential? {
  if (value is AuthCredential) return value
  val credential =
    when {
      value is Map<*, *> -> decodeFromPlainOrNull(AuthCredentialSerializer, value)
      value is String && value.isNotEmpty() -> credentialFromString(value)
      else -> return null
    }
  // The key isn't logged: a derived key can reveal a weak secret.
  if (credential == null) logger.warn { "Ignoring a malformed auth credential stored in state." }
  return credential
}

/**
 * Returns [value] as the key or token of this config's scheme, or null for HTTP basic, which needs
 * a username and password.
 */
private fun AuthConfig.credentialFromString(value: String): AuthCredential? =
  when (val authScheme = authScheme) {
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

/**
 * Returns this credential with its missing client ID, redirect URI, code verifier, code challenge
 * method and token endpoint auth method taken from [source] when both are OAuth2.
 */
private fun AuthCredential.withMissingFieldsFrom(source: AuthCredential?): AuthCredential {
  if (this !is AuthCredential.OAuth2 || source !is AuthCredential.OAuth2) return this
  return copy(
    clientId = clientId ?: source.clientId,
    redirectUri = redirectUri ?: source.redirectUri,
    codeVerifier = codeVerifier ?: source.codeVerifier,
    codeChallengeMethod = codeChallengeMethod ?: source.codeChallengeMethod,
    tokenEndpointAuthMethod = tokenEndpointAuthMethod ?: source.tokenEndpointAuthMethod,
  )
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
    is AuthCredential.OAuth2 -> withoutClientSecret()
    is AuthCredential.ServiceAccount ->
      if (useDefaultCredential) copy(serviceAccountCredential = null) else null
  }
