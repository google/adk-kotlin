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

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.events.Event
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.sessions.State
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
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
 * Returns this config as the response to its sign-in request: its JSON object, with its credentials
 * kept typed so that output leaves their secrets out.
 */
@OptIn(FrameworkInternalApi::class)
internal fun AuthConfig.toSignInResponse(): Map<String, Any?> = buildMap {
  // A scheme holds no secret, so its JSON form is safe; the credentials stay typed for output.
  put("authScheme", jsonElementToAny(adkJson.encodeToJsonElement(AuthSchemeSerializer, authScheme)))
  if (rawAuthCredential != null) put("rawAuthCredential", rawAuthCredential)
  if (exchangedAuthCredential != null) put(EXCHANGED_AUTH_CREDENTIAL_KEY, exchangedAuthCredential)
  put("credentialKey", credentialKey.value)
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
  val requested = exchangedAuthCredential ?: rawAuthCredential
  val credential = answer.withMissingFieldsFrom(requested).withConfiguredClient(rawAuthCredential)
  if (!credential.hasSecret(requested)) return StoreSignInResponseOutcome.NO_CREDENTIAL
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
    .filter { it.isSignInResponse }
    .mapNotNull { response -> response.id?.let { id -> id to response.response } }
    .toMap()
}

/**
 * Returns this sign-in response with its body decoded and typed, so that serializing it leaves its
 * secrets out: an auth config, a bare credential, or a bare key under `result` as a [Secret]; any
 * other body is dropped. Any other response is returned as is.
 */
internal fun FunctionResponse.withDecodedSignInResponse(): FunctionResponse {
  if (!isSignInResponse) return this
  val decoded =
    when (val body = parseSignInResponseBody(response)) {
      is SignInResponseBody.Config -> body.config.toSignInResponse()
      is SignInResponseBody.Credential -> mapOf(EXCHANGED_AUTH_CREDENTIAL_KEY to body.credential)
      is SignInResponseBody.Key -> mapOf(BaseTool.RESULT_KEY to body.key)
      null -> emptyMap()
    }
  return copy(response = decoded)
}

/**
 * Returns this message with each answer to an ADK sign-in request, found by its name or by the id
 * of the request it answers in [events], as a typed auth config; a bare credential takes the rest
 * of its config from that request. An answer that holds no credential, or a bare credential whose
 * request isn't in [events], is left empty, so output never holds its secrets as plain values.
 */
internal fun Content.withCompletedSignInResponses(events: List<Event>): Content =
  copy(
    parts =
      parts.map { part ->
        val response = part.functionResponse ?: return@map part
        val requestCall = response.id?.let { events.findLatestSignInRequest(it) }
        if (!response.isSignInResponse && requestCall == null) return@map part
        val request = requestCall?.let { RequestCredentialArgs.fromMap(it.args) }
        // Named as a sign-in response, so that it reads back typed and the processor finds it.
        val completed =
          response.copy(
            name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
            response = response.completeResponseBody(request),
          )
        part.copy(functionResponse = completed)
      }
  )

/**
 * Returns the latest long-running call with [id] in these events if it is a sign-in request, or
 * null. The latest call counts even when it is another kind, so no older request can stand in for
 * it.
 */
private fun List<Event>.findLatestSignInRequest(id: String): FunctionCall? =
  asReversed()
    .asSequence()
    .flatMap { event ->
      // ADK marks its requests long-running, and also a model's call to a long-running tool.
      event.functionCalls().asReversed().filter { it.id in event.longRunningToolIds }
    }
    .firstOrNull { it.id == id }
    ?.takeIf { it.isSignInRequest }

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
    .filter { it.isSignInRequest && it.id == id }
    .mapNotNull { RequestCredentialArgs.fromMap(it.args) }
    .lastOrNull { it.authConfig in requestedConfigs[it.functionCallId].orEmpty() }

/**
 * Returns this answer's body as a typed auth config: its own, or [request]'s with the bare
 * credential or key as its exchanged credential, since a bare key needs [request]'s scheme to be
 * typed. Empty if the body holds none of these, or is bare and [request] is null.
 */
private fun FunctionResponse.completeResponseBody(
  request: RequestCredentialArgs?
): Map<String, Any?> {
  val requested = request?.authConfig
  val config =
    when (val body = parseSignInResponseBody(response)) {
      is SignInResponseBody.Config -> body.config
      is SignInResponseBody.Credential -> requested?.copy(exchangedAuthCredential = body.credential)
      is SignInResponseBody.Key ->
        requested?.credentialFromKey(body.key)?.let { requested.copy(exchangedAuthCredential = it) }
      null -> null
    }
  return config?.toSignInResponse().orEmpty()
}

/**
 * Returns [key] as the credential of this config's API key or HTTP scheme, or null for HTTP basic
 * or any other scheme, which needs more than a key.
 */
@OptIn(SecretAccess::class)
private fun AuthConfig.credentialFromKey(key: Secret): AuthCredential? =
  when (authScheme) {
    is AuthScheme.ApiKey,
    is AuthScheme.Http -> credentialFromString(key.reveal())
    else -> null
  }

/**
 * Returns the credential in [response]: an auth config's exchanged credential, or a credential;
 * null if it holds none.
 */
private fun credentialFromSignInResponse(response: Any?): AuthCredential? =
  when (val body = parseSignInResponseBody(response)) {
    is SignInResponseBody.Config -> body.config.exchangedAuthCredential
    is SignInResponseBody.Credential -> body.credential
    // The runner completes a bare key into a config before the processor stores it.
    is SignInResponseBody.Key,
    null -> null
  }

/** A sign-in response's body, as [parseSignInResponseBody] reads it. */
private sealed interface SignInResponseBody {
  /** A full auth config, whose exchanged credential answers the request. */
  data class Config(val config: AuthConfig) : SignInResponseBody

  /** A bare credential, which takes the rest of its config from the request it answers. */
  data class Credential(val credential: AuthCredential) : SignInResponseBody

  /** A bare key or token, the credential of the request's API key or HTTP scheme. */
  data class Key(val key: Secret) : SignInResponseBody
}

/**
 * Returns [response] read as a sign-in response's body: an auth config, a credential as plain
 * values or under its exchanged credential's key, or a non-empty key under `result`; null if it is
 * none of them.
 */
@OptIn(SecretAccess::class)
private fun parseSignInResponseBody(response: Any?): SignInResponseBody? {
  decodeFromPlainOrNull(AuthConfigSerializer, response)?.let {
    return SignInResponseBody.Config(it)
  }
  val fields = response as? Map<*, *>
  val credential = fields?.soleValueOrNull(EXCHANGED_AUTH_CREDENTIAL_KEY) ?: response
  decodeFromPlainOrNull(AuthCredentialSerializer, credential)?.let {
    return SignInResponseBody.Credential(it)
  }
  val key =
    when (val value = fields?.soleValueOrNull(BaseTool.RESULT_KEY)) {
      is Secret -> value.reveal()
      // A config or credential sent as JSON text is not a key.
      is String -> value.takeUnless { SchemaUtils.readJson(it).getOrNull() is Map<*, *> }
      is Int,
      is Long -> value.toString()
      else -> null
    }
  // Output writes REDACTED in place of a key, so a response that holds it holds no key.
  return key
    ?.takeIf { it.isNotEmpty() && it != REDACTED }
    ?.let { SignInResponseBody.Key(Secret(it)) }
}

/** Returns the value under [key] if it is this map's only key, or null. */
private fun Map<*, *>.soleValueOrNull(key: String): Any? = if (size == 1) get(key) else null

/**
 * Whether this credential holds a secret, unlike output a client posts back. An API key or HTTP
 * credential needs a key, token, password or extra header, or a username that [requested], the
 * request's own credential, lacks, since HTTP basic allows an empty password.
 */
private fun AuthCredential.hasSecret(requested: AuthCredential?): Boolean =
  when (this) {
    is AuthCredential.ApiKey -> !apiKey.isNullOrEmpty()
    is AuthCredential.Http ->
      !credentials.token.isNullOrEmpty() ||
        !credentials.password.isNullOrEmpty() ||
        !additionalHeaders.isNullOrEmpty() ||
        (!credentials.username.isNullOrEmpty() &&
          credentials.username != (requested as? AuthCredential.Http)?.credentials?.username)
    else -> true
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

/** The key of a sign-in response's exchanged credential. */
private const val EXCHANGED_AUTH_CREDENTIAL_KEY = "exchangedAuthCredential"
