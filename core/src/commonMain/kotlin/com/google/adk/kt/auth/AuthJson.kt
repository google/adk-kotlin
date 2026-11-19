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
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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
 * Returns [value] as the plain map of its JSON object, the form that session state and function
 * call arguments hold. Credential secrets stay in, since the map is ADK's own data.
 */
@SecretAccess
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
internal fun <T> encodeToPlainMap(
  serializer: SerializationStrategy<T>,
  value: T,
): Map<String, Any?> =
  adkSecretRevealingJson.encodeToJsonElement(serializer, value).jsonObject.mapValues { (_, element)
    ->
    jsonElementToAny(element)
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
 * Returns [value] as plain maps, lists and scalars, like [jsonElementToAny], but with each
 * [AuthCredential], [AuthConfig] and [Secret] left as the typed object. Keeping them typed lets
 * output leave their secrets out while storage still writes them.
 */
internal fun <T> Json.encodeToPlainWithTypedCredentials(
  serializer: SerializationStrategy<T>,
  value: T,
): Any? {
  val placeholders = CredentialPlaceholders(base = this)
  val element = placeholders.json.encodeToJsonElement(serializer, value)
  return jsonElementToAny(element, placeholders::credentialOrNull)
}

/**
 * Writes a placeholder for [credential] and returns true if this encoder runs under
 * [encodeToPlainWithTypedCredentials]; otherwise writes nothing and returns false. After true, the
 * caller writes nothing more.
 */
internal fun Encoder.tryEncodePlaceholder(credential: Any): Boolean {
  val placeholders = credentialPlaceholders() ?: return false
  placeholders.encodePlaceholder(this as JsonEncoder, credential)
  return true
}

/**
 * Returns this serializer, except that under [encodeToPlainWithTypedCredentials] it writes a
 * placeholder.
 */
internal fun <T : Any> KSerializer<T>.withCredentialPlaceholders(): KSerializer<T> {
  val serializer = this
  return object : KSerializer<T> by serializer {
    override fun serialize(encoder: Encoder, value: T) {
      if (encoder.tryEncodePlaceholder(value)) return
      serializer.serialize(encoder, value)
    }
  }
}

/**
 * Returns the `Json` that [AnySerializer][com.google.adk.kt.serialization.AnySerializer] writes
 * nested values with: [adkJson]'s settings, with this encoder's credential handling.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class, SecretAccess::class)
internal fun Encoder.jsonForAny(): Json =
  credentialPlaceholders()?.jsonForAny ?: if (revealsSecrets()) adkSecretRevealingJson else adkJson

private fun Encoder.credentialPlaceholders(): CredentialPlaceholders? =
  serializersModule.getContextual(CredentialPlaceholders::class) as? CredentialPlaceholders

/**
 * The credentials one [encodeToPlainWithTypedCredentials] call replaces with placeholders. Each
 * placeholder is an object whose only key is [marker], random so that no input can forge one, and
 * whose value is the credential's index. It is its own contextual serializer only so that an
 * encoder can find it; it never writes or reads.
 */
private class CredentialPlaceholders(base: Json) : KSerializer<CredentialPlaceholders> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.google.adk.kt.auth.CredentialPlaceholders", PrimitiveKind.STRING)

  private val credentials = mutableListOf<Any>()
  private val marker = Uuid.random()

  val json: Json = jsonFrom(base)

  // Revealing, so that a nested sign-in call is written as built, as storage writes it.
  @OptIn(ExperimentalAuthApi::class, SecretAccess::class)
  val jsonForAny: Json by lazy { jsonFrom(adkSecretRevealingJson) }

  fun encodePlaceholder(encoder: JsonEncoder, credential: Any) {
    encoder.encodeJsonElement(JsonObject(mapOf(marker to JsonPrimitive(credentials.size))))
    credentials += credential
  }

  /** Returns the credential [element] stands for, or null if it is no placeholder. */
  fun credentialOrNull(element: JsonObject): Any? {
    if (element.size != 1) return null
    val index = (element[marker] as? JsonPrimitive)?.intOrNull ?: return null
    return credentials.getOrNull(index)
  }

  /** Returns [base] with this registered, so that its encoders write placeholders. */
  private fun jsonFrom(base: Json): Json =
    Json(from = base) {
      serializersModule = SerializersModule {
        include(base.serializersModule)
        contextual(CredentialPlaceholders::class, this@CredentialPlaceholders)
      }
    }

  override fun serialize(encoder: Encoder, value: CredentialPlaceholders): Unit =
    throw UnsupportedOperationException()

  override fun deserialize(decoder: Decoder): CredentialPlaceholders =
    throw UnsupportedOperationException()
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
