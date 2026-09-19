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

/**
 * Why a live turn ended.
 *
 * Absent on an ordinary completion; present when the turn was cut short, most often by a safety
 * filter on either the input or the generated content.
 */
@Serializable(with = TurnCompleteReasonSerializer::class)
enum class TurnCompleteReason {
  TURN_COMPLETE_REASON_UNSPECIFIED,
  MALFORMED_FUNCTION_CALL,
  RESPONSE_REJECTED,
  NEED_MORE_INPUT,
  PROHIBITED_INPUT_CONTENT,
  IMAGE_PROHIBITED_INPUT_CONTENT,
  INPUT_TEXT_CONTAIN_PROMINENT_PERSON_PROHIBITED,
  INPUT_IMAGE_CELEBRITY,
  INPUT_IMAGE_PHOTO_REALISTIC_CHILD_PROHIBITED,
  INPUT_TEXT_NCII_PROHIBITED,
  INPUT_OTHER,
  INPUT_IP_PROHIBITED,
  BLOCKLIST,
  UNSAFE_PROMPT_FOR_IMAGE_GENERATION,
  GENERATED_IMAGE_SAFETY,
  GENERATED_CONTENT_SAFETY,
  GENERATED_AUDIO_SAFETY,
  GENERATED_VIDEO_SAFETY,
  GENERATED_CONTENT_PROHIBITED,
  GENERATED_CONTENT_BLOCKLIST,
  GENERATED_IMAGE_PROHIBITED,
  GENERATED_IMAGE_CELEBRITY,
  GENERATED_IMAGE_PROMINENT_PEOPLE_DETECTED_BY_REWRITER,
  GENERATED_IMAGE_IDENTIFIABLE_PEOPLE,
  GENERATED_IMAGE_MINORS,
  OUTPUT_IMAGE_IP_PROHIBITED,
  GENERATED_OTHER,
  MAX_REGENERATION_REACHED,
}

/**
 * Serializes [TurnCompleteReason] by name, decoding unknown values to
 * [TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED].
 */
internal object TurnCompleteReasonSerializer : KSerializer<TurnCompleteReason> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.google.adk.kt.types.TurnCompleteReason", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TurnCompleteReason) {
    encoder.encodeString(value.name)
  }

  override fun deserialize(decoder: Decoder): TurnCompleteReason =
    runCatching { TurnCompleteReason.valueOf(decoder.decodeString()) }
      .getOrDefault(TurnCompleteReason.TURN_COMPLETE_REASON_UNSPECIFIED)
}
