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

package com.google.adk.kt.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Which edge of a stretch of user speech the server detected. */
@Serializable(with = VoiceActivityTypeSerializer::class)
enum class VoiceActivityType {
  /** The type is unspecified. */
  TYPE_UNSPECIFIED,

  /** The user started speaking. */
  ACTIVITY_START,

  /** The user stopped speaking. */
  ACTIVITY_END,
}

/**
 * Serializes [VoiceActivityType] by name, decoding unknown values to
 * [VoiceActivityType.TYPE_UNSPECIFIED].
 */
internal object VoiceActivityTypeSerializer : KSerializer<VoiceActivityType> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.google.adk.kt.types.VoiceActivityType", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: VoiceActivityType) {
    encoder.encodeString(value.name)
  }

  override fun deserialize(decoder: Decoder): VoiceActivityType =
    runCatching { VoiceActivityType.valueOf(decoder.decodeString()) }
      .getOrDefault(VoiceActivityType.TYPE_UNSPECIFIED)
}
