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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Reads and writes an [AuthConfig] as a JSON object, with its scheme and credentials in the formats
 * of [AuthSchemeSerializer] and [AuthCredentialSerializer]. Input also takes each multi-word
 * field's snake_case name, as the server's request bodies do. A config without a credential key
 * gets a derived one, as from the [AuthConfig] constructor.
 */
internal object AuthConfigSerializer :
  KSerializer<AuthConfig> by surrogateSerializer(
    surrogate = AuthConfigSurrogate.serializer(),
    toSurrogate = ::AuthConfigSurrogate,
    fromSurrogate = AuthConfigSurrogate::toAuthConfig,
  )

@Serializable
private class AuthConfigSurrogate(
  @JsonNames("auth_scheme")
  @Serializable(with = AuthSchemeSerializer::class)
  val authScheme: AuthScheme,
  @JsonNames("raw_auth_credential")
  @Serializable(with = AuthCredentialSerializer::class)
  val rawAuthCredential: AuthCredential? = null,
  @JsonNames("exchanged_auth_credential")
  @Serializable(with = AuthCredentialSerializer::class)
  val exchangedAuthCredential: AuthCredential? = null,
  @JsonNames("credential_key") val credentialKey: String? = null,
) {
  constructor(
    config: AuthConfig
  ) : this(
    config.authScheme,
    config.rawAuthCredential,
    config.exchangedAuthCredential,
    config.credentialKey.value,
  )

  // CredentialKey rejects an empty key with its own message.
  fun toAuthConfig(): AuthConfig =
    AuthConfig(
      authScheme,
      rawAuthCredential,
      exchangedAuthCredential,
      credentialKey?.let(::CredentialKey) ?: deriveCredentialKey(authScheme, rawAuthCredential),
    )
}
