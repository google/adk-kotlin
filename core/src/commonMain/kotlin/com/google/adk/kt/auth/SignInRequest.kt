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
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

/**
 * A request from ADK for a credential one of the agent's tools needs, as
 * [Event.signInRequests][com.google.adk.kt.events.Event.signInRequests] finds it. Send the user to
 * [authUri] when there is one, then answer with [createResponse].
 *
 * @property id The id of the `adk_request_credential` call; the response carries it.
 * @property authConfig What the tool needs: the scheme, and for OAuth2 and OpenID Connect the
 *   authorization URL.
 */
@ExperimentalAuthApi
class SignInRequest internal constructor(val id: String, val authConfig: AuthConfig) {

  /** Where to send the user to sign in, from the request's OAuth2 `authUri`, or null if none. */
  val authUri: String?
    get() = (authConfig.exchangedAuthCredential as? AuthCredential.OAuth2)?.authUri

  /**
   * Returns the message that answers this request with [credential], the one the user signed in
   * for; an OAuth2 credential's tokens, code, expiry and any redirect URI fill in the request's own
   * credential. Pass the message to the runner as is, not through JSON, which leaves the
   * credential's secrets out. Answer all pending requests in one message by combining their parts,
   * since a resumable app can end the agent after the first answer.
   *
   * @throws IllegalArgumentException if [credential] is an OAuth2 credential without an access
   *   token for an OAuth2 or OpenID Connect scheme, since ADK can't exchange a code yet.
   */
  @OptIn(FrameworkInternalApi::class)
  fun createResponse(credential: AuthCredential): Content {
    val answer = credential.filledInto(authConfig.exchangedAuthCredential)
    val config = authConfig.copy(exchangedAuthCredential = answer)
    require(!config.needsCodeExchange(answer)) {
      "A sign-in response needs an access token; ADK can't exchange an authorization code yet."
    }
    // The config's JSON object, with its credentials kept typed so that output leaves out secrets.
    val response = buildMap {
      put(
        "authScheme",
        jsonElementToAny(adkJson.encodeToJsonElement(AuthSchemeSerializer, config.authScheme)),
      )
      config.rawAuthCredential?.let { put("rawAuthCredential", it) }
      config.exchangedAuthCredential?.let { put("exchangedAuthCredential", it) }
      put("credentialKey", config.credentialKey.value)
    }
    return Content(
      role = Role.USER,
      parts =
        listOf(
          Part(
            functionResponse =
              FunctionResponse(
                name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
                id = id,
                response = response,
              )
          )
        ),
    )
  }

  override fun equals(other: Any?): Boolean =
    other is SignInRequest && other.id == id && other.authConfig == authConfig

  override fun hashCode(): Int = 31 * id.hashCode() + authConfig.hashCode()

  override fun toString(): String = "SignInRequest(id=$id, authConfig=$authConfig)"
}

/**
 * Returns this answer filled into the [request] credential when both are OAuth2: the answer gives
 * what the sign-in returned and any redirect URI the app chose, and the request keeps everything
 * else, including its authorization URL, state and client. Any other answer replaces the request
 * credential.
 */
private fun AuthCredential.filledInto(request: AuthCredential?): AuthCredential {
  if (this !is AuthCredential.OAuth2 || request !is AuthCredential.OAuth2) return this
  return request.copy(
    redirectUri = redirectUri ?: request.redirectUri,
    authResponseUri = authResponseUri,
    authCode = authCode,
    accessToken = accessToken,
    refreshToken = refreshToken,
    idToken = idToken,
    expiresAt = expiresAt,
    expiresIn = expiresIn,
  )
}
