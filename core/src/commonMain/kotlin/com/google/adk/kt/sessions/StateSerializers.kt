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

package com.google.adk.kt.sessions

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.auth.AuthCredentialSerializer
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.Secret
import com.google.adk.kt.auth.decodeFromPlainOrNull
import com.google.adk.kt.auth.revealsSecrets
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.AnySerializer
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.workflow.OAUTH_CREDENTIAL_KEY_PREFIX
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@OptIn(FrameworkInternalApi::class)
private val logger = LoggerFactory.getLogger(StateMapSerializer::class)

/**
 * Reads session state with the credentials ADK stores under its credential keys typed, so that
 * writing the state again leaves their secrets out. Unless the encoder reveals secrets, it writes a
 * bare key or token under such a key as a placeholder; one under a key ADK doesn't own is written
 * as given.
 */
@FrameworkInternalApi
object StateMapSerializer : KSerializer<Map<String, Any>> {
  override val descriptor: SerialDescriptor = stateSerializer.descriptor

  override fun serialize(encoder: Encoder, value: Map<String, Any>) {
    val state = value.forOutput(encoder, ::decodeStateCredential)
    encoder.encodeSerializableValue(stateSerializer, state)
  }

  override fun deserialize(decoder: Decoder): Map<String, Any> =
    NullableStateMapSerializer.deserialize(decoder).mapValues { (_, value) ->
      checkNotNull(value) { "Unexpected JSON null for non-null Any value" }
    }
}

/** [StateMapSerializer] for a state delta, which is read into a map safe for concurrent writes. */
@OptIn(FrameworkInternalApi::class)
internal object MutableStateMapSerializer : KSerializer<MutableMap<String, Any>> {
  override val descriptor: SerialDescriptor = StateMapSerializer.descriptor

  override fun serialize(encoder: Encoder, value: MutableMap<String, Any>) {
    StateMapSerializer.serialize(encoder, value)
  }

  // A JSON null removes its key, as a delta another ADK language wrote can hold.
  override fun deserialize(decoder: Decoder): MutableMap<String, Any> =
    concurrentMutableMapOf<String, Any>().apply {
      for ((key, value) in NullableStateMapSerializer.deserialize(decoder)) {
        put(key, value ?: State.REMOVED)
      }
    }
}

/**
 * [StateMapSerializer] for a request's state, which can hold JSON `null` values; the caller decides
 * what a `null` means.
 */
@FrameworkInternalApi
object NullableStateMapSerializer : KSerializer<Map<String, Any?>> {
  // Shared with StateMapSerializer, so that one descriptor marks every state on the wire.
  override val descriptor: SerialDescriptor = stateSerializer.descriptor

  override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
    val state =
      value.forOutput(encoder) { key, stateValue ->
        stateValue?.let { decodeStateCredential(key, it) }
      }
    encoder.encodeSerializableValue(nullableStateSerializer, state)
  }

  override fun deserialize(decoder: Decoder): Map<String, Any?> = buildMap {
    for ((key, value) in decodeStateJson(decoder)) {
      if (value is JsonNull) put(key, null)
      else decodeStateValueOrNull(key, value)?.let { put(key, it) }
    }
  }
}

private val stateSerializer = MapSerializer(String.serializer(), AnySerializer)

private val nullableStateSerializer = MapSerializer(String.serializer(), AnySerializer.nullable)

/**
 * Reads the JSON object a state map is written as.
 *
 * @throws SerializationException if the input isn't a JSON object, as for any malformed input.
 * @throws IllegalStateException if [decoder] doesn't read JSON.
 */
private fun decodeStateJson(decoder: Decoder): JsonObject {
  val json =
    decoder as? JsonDecoder ?: throw IllegalStateException("State supports JSON formats only")
  return json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Malformed state.")
}

/**
 * Returns this state as [encoder] writes it: as is when it reveals secrets, so that storage keeps
 * them, and otherwise with each value passed through [typed].
 */
private inline fun <V> Map<String, V>.forOutput(
  encoder: Encoder,
  typed: (key: String, value: V) -> V,
): Map<String, V> =
  if (encoder.revealsSecrets()) this else mapValues { (key, value) -> typed(key, value) }

/**
 * Returns [value], held under state key [key], as a [Secret] if it is a bare key or token under an
 * ADK credential key, so that output leaves it out; otherwise as is. Output already leaves out the
 * secrets of a value in a credential's JSON form, whatever its key.
 */
@OptIn(ExperimentalAuthApi::class)
internal fun decodeStateCredential(key: String, value: Any): Any =
  if (value is String && key.isCredentialStateKey()) Secret(value) else value

/**
 * Returns [value], the JSON of state key [key], as session state holds it: under an ADK credential
 * key a typed credential, or a [Secret] for a bare key or token; otherwise a free-form value.
 * Returns null for JSON `null` and for a credential that doesn't decode, since keeping it as plain
 * values could put its secrets in output.
 */
@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
internal fun decodeStateValueOrNull(key: String, value: JsonElement): Any? {
  val plain = jsonElementToAny(value)
  // A removal stays one, so that the stored credential it removes goes too.
  if (plain == null || plain === State.REMOVED || !key.isCredentialStateKey()) return plain
  if (plain is String) return Secret(plain)
  val credential = decodeFromPlainOrNull(AuthCredentialSerializer, plain)
  // The key isn't logged: a derived key can reveal a weak secret.
  if (credential == null) logger.warn { "Dropping a malformed auth credential stored in state." }
  return credential
}

/**
 * Returns whether this state key is one under which ADK stores a credential: a derived credential
 * key, or a workflow node's kept OAuth2 credential, with or without an `app:` or `user:` scope. A
 * custom credential key isn't recognized, so a credential stored under one is the app's to type.
 */
@OptIn(ExperimentalAuthApi::class)
private fun String.isCredentialStateKey(): Boolean {
  // One scope at most, as session state has.
  val key =
    when {
      startsWith(State.APP_PREFIX) -> removePrefix(State.APP_PREFIX)
      startsWith(State.USER_PREFIX) -> removePrefix(State.USER_PREFIX)
      else -> this
    }
  return key.startsWith(OAUTH_CREDENTIAL_KEY_PREFIX) || CredentialKey.isDerived(key)
}
