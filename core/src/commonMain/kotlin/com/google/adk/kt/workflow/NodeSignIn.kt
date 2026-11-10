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

@file:OptIn(ExperimentalAuthApi::class, SecretAccess::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthCredentialSerializer
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.RequestCredentialArgs
import com.google.adk.kt.auth.signInConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.serialization.encodeToPlainMap
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

/**
 * Returns the long-running `adk_request_credential` event asking the app for [authConfig]'s
 * credential, with [interruptId] as its call id. Its state delta keeps the OAuth2 `state` and PKCE
 * code verifier for the reply, under keys that outlast the pause, and its request leaves out the
 * verifier.
 *
 * @throws IllegalArgumentException if an OAuth2 or OpenID Connect [authConfig] can't produce an
 *   authorization URL, as [signInConfig] describes.
 */
internal fun signInRequestEvent(authConfig: AuthConfig, interruptId: String): Event {
  val requestConfig = authConfig.signInConfig()
  val generatedCredential = requestConfig.exchangedAuthCredential as? AuthCredential.OAuth2
  val stateDelta = mutableMapOf<String, Any>()
  if (generatedCredential != null) {
    if (!generatedCredential.state.isNullOrEmpty()) {
      stateDelta["adk_oauth_state:$interruptId"] = generatedCredential.state
    }
    if (!generatedCredential.codeVerifier?.reveal().isNullOrEmpty()) {
      stateDelta["adk_oauth_credential:$interruptId"] =
        encodeToPlainMap(
          AuthCredentialSerializer,
          AuthCredential.OAuth2(
            variant = generatedCredential.variant,
            codeVerifier = generatedCredential.codeVerifier,
            codeChallengeMethod = generatedCredential.codeChallengeMethod,
          ),
        )
    }
  }
  val args =
    RequestCredentialArgs(
      functionCallId = interruptId,
      authConfig =
        if (generatedCredential == null) {
          requestConfig
        } else {
          requestConfig.copy(
            exchangedAuthCredential = generatedCredential.copy(codeVerifier = null)
          )
        },
      message = authConfig.signInMessage(),
    )
  val call =
    FunctionCall(
      name = FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME,
      id = interruptId,
      args = args.toMap(),
    )
  return Event(
    content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = call))),
    longRunningToolIds = setOf(interruptId),
    actions = EventActions(stateDelta = stateDelta),
  )
}

/** Returns what the user is asked to provide for this config. */
private fun AuthConfig.signInMessage(): String =
  when (rawAuthCredential) {
    is AuthCredential.ApiKey -> {
      val name = (authScheme as? AuthScheme.ApiKey)?.name ?: "API key"
      "Please provide your API key for $name."
    }
    is AuthCredential.OAuth2 -> "Please complete the authentication flow."
    is AuthCredential.Http,
    is AuthCredential.ServiceAccount,
    null -> "Please provide your authentication credentials."
  }
