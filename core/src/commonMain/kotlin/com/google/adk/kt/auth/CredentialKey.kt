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

@file:OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.crypto.sha256Hex
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.State
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The key under which ADK stores and looks up the credential an [AuthConfig] describes; configs
 * with the same key share it. [toString] redacts it, because a derived key is a digest of the raw
 * credential: build storage keys from [value].
 *
 * @property value The key as session state and credential stores use it.
 * @throws IllegalArgumentException if [value] is empty.
 */
@ExperimentalAuthApi
class CredentialKey(val value: String) {
  init {
    require(value.isNotEmpty()) { "CredentialKey requires a non-empty value." }
  }

  /** The state key of the app's sign-in reply, which lasts for one invocation. */
  internal val signInReplyStateKey: String
    get() = State.TEMP_PREFIX + value

  /** The state keys a stored credential is read from: [signInReplyStateKey], then [value]. */
  internal val stateKeys: List<String>
    get() = listOf(signInReplyStateKey, value)

  override fun equals(other: Any?): Boolean = other is CredentialKey && other.value == value

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = REDACTED
}

/**
 * Returns a credential key in the form ADK Python's `AuthConfig` derives: the type and digest of
 * [authScheme], then of [rawAuthCredential], without the fields ADK Python leaves out. The digests
 * are of ADK Kotlin's JSON, so the key matches ADK Python's only where both write the same JSON.
 */
internal fun deriveCredentialKey(
  authScheme: AuthScheme,
  rawAuthCredential: AuthCredential?,
): CredentialKey {
  val scheme = adkJson.encodeToJsonElement(AuthSchemeSerializer, authScheme.forDigest())
  val credential = rawAuthCredential?.let {
    val json = adkJson.encodeToJsonElement(AuthCredentialSerializer, it.forDigest()).jsonObject
    "${json.getValue("authType").jsonPrimitive.content}_${stableDigest(json)}"
  }
  return CredentialKey("adk_${authScheme.type}_${stableDigest(scheme)}_${credential.orEmpty()}")
}

/** ADK Python reads `bearerFormat` only for the `bearer` scheme, and leaves it out otherwise. */
private fun AuthScheme.forDigest(): AuthScheme =
  when (this) {
    is AuthScheme.Http -> if (scheme != "bearer") copy(bearerFormat = null) else this
    else -> this
  }

/**
 * ADK Python leaves out an OAuth2 credential's authorization URL, state, redirect URI,
 * authorization response and code, access and refresh tokens, and expiry.
 */
private fun AuthCredential.forDigest(): AuthCredential =
  when (this) {
    is AuthCredential.OAuth2 ->
      copy(
        authUri = null,
        state = null,
        redirectUri = null,
        authResponseUri = null,
        authCode = null,
        accessToken = null,
        refreshToken = null,
        expiresAt = null,
        expiresIn = null,
      )
    else -> this
  }

/**
 * Returns ADK Python's `_stable_model_digest` of [json]: the first 16 hex digits of the SHA-256 of
 * the JSON with sorted keys, which kotlinx writes with the same escapes as Python's `json.dumps`.
 */
private fun stableDigest(json: JsonElement): String =
  sha256Hex(json.sortedKeys().toString()).take(16)

// UTF-16 order, which differs from Python's code-point order only for characters above U+FFFF.
private fun JsonElement.sortedKeys(): JsonElement =
  when (this) {
    is JsonObject ->
      JsonObject(
        entries.sortedBy { it.key }.associate { (key, value) -> key to value.sortedKeys() }
      )
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    is JsonPrimitive -> this
  }
