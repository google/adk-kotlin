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
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * The arguments of an `adk_request_credential` call. Input also takes each field's snake_case name.
 *
 * @property functionCallId The id of the tool call, or of the workflow node's sign-in request, that
 *   asked for the credential.
 * @property authConfig The sign-in request for the credential.
 * @property message What the user is asked to provide, which only a workflow node's request has.
 */
@Serializable
internal data class RequestCredentialArgs(
  @JsonNames("function_call_id") val functionCallId: String,
  @JsonNames("auth_config")
  @Serializable(with = AuthConfigSerializer::class)
  val authConfig: AuthConfig,
  val message: String? = null,
) {
  /**
   * Returns these arguments as function call args, with [authConfig] kept typed so that serializing
   * them leaves its secrets out.
   */
  fun toMap(): Map<String, Any?> = buildMap {
    put("functionCallId", functionCallId)
    put("authConfig", authConfig)
    if (message != null) put("message", message)
  }

  companion object {
    /** Returns [args] read as sign-in request arguments, or null if they are malformed. */
    fun fromMap(args: Map<String, Any?>): RequestCredentialArgs? =
      decodeFromPlainOrNull(serializer(), args)
  }
}

/**
 * Returns this sign-in request with its arguments decoded, so that its auth config is typed and
 * serializing it leaves its secrets out; malformed arguments are dropped. Any other call is
 * returned as is.
 */
internal fun FunctionCall.withDecodedSignInRequest(): FunctionCall {
  if (!isSignInRequest) return this
  return copy(args = RequestCredentialArgs.fromMap(args)?.toMap().orEmpty())
}

/**
 * Whether this call has the sign-in request's name, `adk_request_credential`. The name doesn't show
 * that ADK made the call, so a caller that relies on it checks more, as `Event.signInRequests`
 * does.
 */
internal val FunctionCall.isSignInRequest: Boolean
  get() = name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME

/** Whether this response answers a sign-in request, by the request's name. */
internal val FunctionResponse.isSignInResponse: Boolean
  get() = name == FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME
