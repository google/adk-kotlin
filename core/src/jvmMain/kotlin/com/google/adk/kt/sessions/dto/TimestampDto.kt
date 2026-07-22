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

package com.google.adk.kt.sessions.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wire-level representation of `google.protobuf.Timestamp` used by the Vertex AI Session Service.
 *
 * The service accepts either an ISO-8601 string or a `{seconds, nanos}` object for event
 * timestamps. This DTO always emits the object form (matching the Java ADK's writer) but its
 * [TimestampDtoSerializer] transparently reads both shapes.
 */
@Serializable(with = TimestampDtoSerializer::class)
internal data class TimestampDto(val seconds: Long = 0, val nanos: Long = 0) {
  fun toEpochMillis(): Long = seconds * 1000 + nanos / 1_000_000

  companion object {
    fun fromEpochMillis(epochMillis: Long): TimestampDto =
      TimestampDto(seconds = epochMillis / 1000, nanos = (epochMillis % 1000) * 1_000_000)
  }
}

/**
 * Serializer for [TimestampDto] that emits the `{seconds, nanos}` object form and accepts either
 * that shape or an ISO-8601 string on read.
 */
internal object TimestampDtoSerializer : KSerializer<TimestampDto> {
  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("com.google.adk.kt.sessions.dto.TimestampDto")

  override fun serialize(encoder: Encoder, value: TimestampDto) {
    val jsonEncoder =
      encoder as? JsonEncoder ?: error("TimestampDtoSerializer supports JSON formats only")
    jsonEncoder.encodeJsonElement(
      JsonObject(
        mapOf("seconds" to JsonPrimitive(value.seconds), "nanos" to JsonPrimitive(value.nanos))
      )
    )
  }

  override fun deserialize(decoder: Decoder): TimestampDto {
    val jsonDecoder =
      decoder as? JsonDecoder ?: error("TimestampDtoSerializer supports JSON formats only")
    val element = jsonDecoder.decodeJsonElement()
    return when (element) {
      is JsonObject -> {
        val seconds = element["seconds"]?.jsonPrimitive?.longOrNull ?: 0L
        val nanos = element["nanos"]?.jsonPrimitive?.longOrNull ?: 0L
        TimestampDto(seconds, nanos)
      }
      is JsonPrimitive -> {
        val text = element.contentOrNull ?: return TimestampDto()
        val instant = java.time.Instant.parse(text)
        TimestampDto(instant.epochSecond, instant.nano.toLong())
      }
      else -> TimestampDto()
    }
  }
}
