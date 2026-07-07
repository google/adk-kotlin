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

package com.google.adk.kt.serialization

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.sessions.State
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.modules.SerializersModule

/**
 * JSON-object marker used to round-trip the [State.REMOVED] sentinel through the event log. The
 * sentinel has no natural JSON form, so [AnySerializer] encodes it as `{"<marker>": true}` and
 * decodes a single-key object with this marker back into [State.REMOVED].
 */
private const val REMOVED_MARKER = "__ADK_SENTINEL_REMOVED__"

/**
 * `kotlinx.serialization` serializer for free-form `Any` values that appear in the [Event] graph
 * (state deltas, `FunctionCall.args`, `FunctionResponse.response`, `customMetadata`, tool
 * confirmation payloads).
 *
 * It maps Kotlin values to and from [JsonElement] and only supports JSON formats. It is
 * reflection-free and works in `commonMain` (unlike a `serializer(KClass)` lookup). Integral
 * numbers round-trip as [Long] and fractional numbers as [Double]. The [State.REMOVED] sentinel is
 * preserved via [REMOVED_MARKER].
 */
@OptIn(FrameworkInternalApi::class)
internal object AnySerializer : KSerializer<Any> {
  // The serialized form is an arbitrary JSON value (object, array, or primitive), so the descriptor
  // delegates to [JsonElement]'s rather than claiming a single primitive kind.
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun serialize(encoder: Encoder, value: Any) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw IllegalStateException("AnySerializer supports JSON formats only")
    jsonEncoder.encodeJsonElement(anyToJsonElement(value))
  }

  override fun deserialize(decoder: Decoder): Any {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw IllegalStateException("AnySerializer supports JSON formats only")
    return jsonElementToAny(jsonDecoder.decodeJsonElement())
      ?: throw IllegalStateException("Unexpected JSON null for non-null Any value")
  }
}

@FrameworkInternalApi
fun anyToJsonElement(value: Any?): JsonElement =
  when (value) {
    null -> JsonNull
    State.REMOVED -> JsonObject(mapOf(REMOVED_MARKER to JsonPrimitive(true)))
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Byte -> JsonPrimitive(value)
    is Short -> JsonPrimitive(value)
    is Map<*, *> ->
      JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) })
    is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
    else ->
      throw IllegalArgumentException(
        "AnySerializer cannot serialize value of type ${value::class.simpleName}. Tool results " +
          "must be JSON-native (Map/List/String/number/Boolean/null); return a Map or use @Tool " +
          "to return a data class."
      )
  }

@FrameworkInternalApi
fun jsonElementToAny(element: JsonElement): Any? =
  when (element) {
    is JsonNull -> null
    is JsonPrimitive ->
      when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.boolean
        element.longOrNull != null -> element.long
        element.doubleOrNull != null -> element.double
        else -> element.content
      }
    is JsonObject ->
      if (element.size == 1 && element.containsKey(REMOVED_MARKER)) {
        State.REMOVED
      } else {
        element.mapValues { (_, v) -> jsonElementToAny(v) }
      }
    is JsonArray -> element.map { jsonElementToAny(it) }
  }

/**
 * The shared `kotlinx.serialization` [Json] instance used to (de)serialize the [Event] graph for
 * persistence. Defaults are omitted to keep payloads small, unknown keys are ignored for
 * forward-compatibility, and [AnySerializer] is registered contextually for free-form `Any` values.
 */
@FrameworkInternalApi
val adkJson: Json = Json {
  encodeDefaults = false
  explicitNulls = false
  ignoreUnknownKeys = true
  serializersModule = SerializersModule { contextual(Any::class, AnySerializer) }
}
