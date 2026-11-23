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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule

/**
 * Registered only in [adkSecretRevealingJson]'s serializers module, so it marks an encoder that
 * writes secrets in clear, also in a `Json` built from that module with more serializers added.
 */
@Serializable private object RevealsSecrets

/** Whether this encoder writes credential secrets in clear, as [adkSecretRevealingJson] does. */
internal fun Encoder.revealsSecrets(): Boolean =
  serializersModule.getContextual(RevealsSecrets::class) != null

/**
 * [adkJson] that writes credential secrets in clear, for session storage, credential keys and
 * internal round trips. [adkJson] leaves them out, so ADK writes one only through this instance or
 * a `Json` built from it.
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
