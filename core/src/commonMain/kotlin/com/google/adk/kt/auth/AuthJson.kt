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

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.annotations.SecretAccess
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule

/**
 * Registered only in [adkSecretRevealingJson]'s serializers module, so it marks an encoder that
 * writes secrets in clear, also in a `Json` built from that module with more serializers added.
 */
@Serializable private object RevealsSecrets

/** Whether this encoder writes credential secrets in clear, as [adkSecretRevealingJson] does. */
internal fun Encoder.revealsSecrets(): Boolean = serializersModule.revealsSecrets()

/** Whether this instance writes credential secrets in clear, as [adkSecretRevealingJson] does. */
internal fun Json.revealsSecrets(): Boolean = serializersModule.revealsSecrets()

private fun SerializersModule.revealsSecrets(): Boolean =
  getContextual(RevealsSecrets::class) != null

/**
 * [adkJson] that writes credential secrets in clear, for session storage, credential keys and
 * internal round trips. [adkJson] leaves them out, so ADK writes one only through this instance or
 * a `Json` built from it. It writes plain maps as they are, so a stored value reads back unchanged.
 */
@SecretAccess
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
internal val adkSecretRevealingJson: Json =
  Json(from = adkJson) {
    serializersModule = SerializersModule {
      include(adkJson.serializersModule)
      contextual(RevealsSecrets::class, RevealsSecrets.serializer())
    }
  }

/**
 * Returns the JSON form of [value] if it is an [AuthCredential], an [AuthConfig] or a [Secret], or
 * null if it is none of them. Its secrets are written only when this is [adkSecretRevealingJson] or
 * derived from it.
 */
@OptIn(ExperimentalAuthApi::class)
internal fun Json.encodeCredentialOrNull(value: Any): JsonElement? =
  when (value) {
    is AuthCredential -> encodeToJsonElement(AuthCredentialSerializer, value)
    is AuthConfig -> encodeToJsonElement(AuthConfigSerializer, value)
    is Secret -> encodeToJsonElement(SecretSerializer, value)
    else -> null
  }

/**
 * Returns [value], a plain value as session state or function call arguments hold it, decoded with
 * [deserializer], or null if it has no JSON form or [deserializer] rejects it with an
 * `IllegalArgumentException`, which includes `SerializationException`.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
internal fun <T> decodeFromPlainOrNull(deserializer: DeserializationStrategy<T>, value: Any?): T? =
  try {
    // Revealing, so that a typed credential inside [value] keeps its secrets.
    adkJson.decodeFromJsonElement(deserializer, anyToJsonElement(value, adkSecretRevealingJson))
  } catch (e: IllegalArgumentException) {
    null
  }

/**
 * Returns this map without its secret keys if it is a credential's JSON form, as a session reloads
 * one, or null if it is not one. Like ADK Python, it goes by key names rather than a decode, so a
 * credential that fails to decode still loses its secrets.
 */
internal fun Map<*, *>.withoutCredentialSecretsOrNull(): Map<*, *>? =
  if (isCredentialJson()) withoutKeys(CREDENTIAL_SECRET_KEYS) else null

/** Returns this JSON with each credential's JSON form in it, at any depth, without its secrets. */
internal fun JsonElement.withoutCredentialSecrets(): JsonElement =
  when (this) {
    is JsonObject ->
      if (isCredentialJson()) withoutJsonKeys(CREDENTIAL_SECRET_KEYS)
      else JsonObject(mapValues { (_, value) -> value.withoutCredentialSecrets() })
    is JsonArray -> JsonArray(map { it.withoutCredentialSecrets() })
    else -> this
  }

// Scans the entries, since a sorted map with non-string keys throws when looked up by a string.
private fun Map<*, *>.isCredentialJson(): Boolean = entries.any { (key, value) ->
  val kind = if (value is JsonPrimitive && value.isString) value.content else value
  (key == "authType" || key == "auth_type") && kind in AUTH_TYPE_VALUES
}

/**
 * Returns this JSON without [keys], at any depth; named apart from the `Map` one, which a
 * `JsonObject` also is.
 */
private fun JsonElement.withoutJsonKeys(keys: Set<String>): JsonElement =
  when (this) {
    is JsonObject ->
      JsonObject(filterKeys { it !in keys }.mapValues { (_, value) -> value.withoutJsonKeys(keys) })
    is JsonArray -> JsonArray(map { it.withoutJsonKeys(keys) })
    else -> this
  }

/** Returns this map without [keys], at any depth. */
private fun Map<*, *>.withoutKeys(keys: Set<String>): Map<*, *> {
  fun withoutNestedKeys(value: Any?): Any? =
    when (value) {
      is Map<*, *> -> value.withoutKeys(keys)
      // Any collection, since a model converter writes a set or an array as a JSON array.
      is Iterable<*> -> value.map { withoutNestedKeys(it) }
      is Array<*> -> value.map { withoutNestedKeys(it) }
      else -> value
    }
  return filterKeys { it !in keys }.mapValues { (_, value) -> withoutNestedKeys(value) }
}

/**
 * Secret fields of a credential's JSON form, camelCase and snake_case, as ADK Python lists them.
 */
private val CREDENTIAL_SECRET_KEYS: Set<String> =
  listOf(
      "apiKey",
      "password",
      "token",
      "additionalHeaders",
      "clientSecret",
      "authResponseUri",
      "authCode",
      "accessToken",
      "refreshToken",
      "idToken",
      "codeVerifier",
      "privateKeyId",
      "privateKey",
    )
    .flatMap { listOf(it, it.toSnakeCase()) }
    .toSet()

/** Credential kinds, also as ADK Python's enum names. */
private val AUTH_TYPE_VALUES: Set<String> =
  listOf("apiKey", "http", "oauth2", "openIdConnect", "serviceAccount")
    .flatMap { listOf(it, it.toSnakeCase(), it.toSnakeCase().uppercase()) }
    .toSet()

/** `privateKeyId` to `private_key_id`; enough for these names, which have no acronyms. */
private fun String.toSnakeCase(): String =
  asIterable().joinToString("") { if (it.isUpperCase()) "_${it.lowercaseChar()}" else "$it" }
