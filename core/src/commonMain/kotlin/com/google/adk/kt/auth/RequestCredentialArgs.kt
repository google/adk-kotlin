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
import com.google.adk.kt.serialization.decodeFromPlainOrNull
import com.google.adk.kt.serialization.encodeToPlainMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * The arguments of an `adk_request_credential` call. Input also takes each field's snake_case name.
 *
 * @property functionCallId The id of the tool call that asked for the credential.
 * @property authConfig The sign-in request for the credential.
 */
@Serializable
internal data class RequestCredentialArgs(
  @JsonNames("function_call_id") val functionCallId: String,
  @JsonNames("auth_config")
  @Serializable(with = AuthConfigSerializer::class)
  val authConfig: AuthConfig,
) {
  /** Returns these arguments as function call args. */
  fun toMap(): Map<String, Any?> = encodeToPlainMap(serializer(), this)

  companion object {
    /** Returns [args] read as sign-in request arguments, or null if they are malformed. */
    fun fromMap(args: Map<String, Any?>): RequestCredentialArgs? =
      decodeFromPlainOrNull(serializer(), args)
  }
}
