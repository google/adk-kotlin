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
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.State
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Role

/** What [storeSignInResponse] did with an app's sign-in response. */
internal enum class StoreSignInResponseOutcome {
  /** The response's credential is stored. */
  STORED,

  /** The response is malformed or holds no credential, so nothing is stored. */
  NO_CREDENTIAL,

  /**
   * The response holds an OAuth2 credential without an access token for a scheme that signs in
   * through an authorization URL, so nothing is stored: ADK can't exchange an authorization code
   * yet.
   */
  NEEDS_CODE_EXCHANGE,
}

/**
 * Stores the credential in [response], the app's answer to this config's sign-in request, in
 * [state] under this config's sign-in response key, where tools read it until the invocation ends.
 * The response is an auth config, whose exchanged credential counts, or a credential. Its missing
 * OAuth2 client ID, redirect URI, code verifier, code challenge method and token endpoint auth
 * method come from this config's exchanged or raw credential; its client ID always comes from an
 * OAuth2 raw credential, and no client secret is stored.
 */
internal fun AuthConfig.storeSignInResponse(
  response: Any?,
  state: State,
): StoreSignInResponseOutcome {
  val answer =
    credentialFromSignInResponse(response) ?: return StoreSignInResponseOutcome.NO_CREDENTIAL
  val credential =
    answer
      .withMissingFieldsFrom(exchangedAuthCredential ?: rawAuthCredential)
      .withConfiguredClient(rawAuthCredential)
  if (needsCodeExchange(credential)) return StoreSignInResponseOutcome.NEEDS_CODE_EXCHANGE
  state[credentialKey.signInResponseStateKey] = credential.withoutClientSecret()
  return StoreSignInResponseOutcome.STORED
}

/**
 * Returns the `adk_request_credential` responses in the latest event with content, by id, or none
 * unless the user wrote that event.
 */
internal fun List<Event>.latestSignInResponses(): Map<String, Map<String, Any?>> {
  val event =
    lastOrNull { it.content != null }?.takeIf { it.author == Role.USER } ?: return emptyMap()
  return event
    .functionResponses()
    .filter { it.name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME }
    .mapNotNull { response -> response.id?.let { id -> id to response.response } }
    .toMap()
}

/**
 * Returns the arguments of the latest valid credential request with [id] in these events whose
 * config is one [requestedConfigs] holds for its tool call, or null if there is none. No other
 * request counts, so neither a peer nor the model can choose where or how a credential is stored,
 * provided these events and [requestedConfigs] come only from the requesting agent's events.
 */
internal fun List<Event>.findCredentialRequest(
  id: String,
  requestedConfigs: Map<String, List<AuthConfig>>,
): RequestCredentialArgs? =
  asSequence()
    .flatMap { it.functionCalls() }
    .filter { it.name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME && it.id == id }
    .mapNotNull { RequestCredentialArgs.fromMap(it.args) }
    .lastOrNull { it.authConfig in requestedConfigs[it.functionCallId].orEmpty() }

/**
 * Returns the credential in [response]: an auth config's exchanged credential, or a credential;
 * null if it holds none.
 */
private fun credentialFromSignInResponse(response: Any?): AuthCredential? {
  val responseConfig = decodeFromPlainOrNull(AuthConfigSerializer, response)
  if (responseConfig != null) return responseConfig.exchangedAuthCredential
  return decodeFromPlainOrNull(AuthCredentialSerializer, response)
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
