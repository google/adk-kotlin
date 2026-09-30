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
 * Whether the model is still working on the user's prompt.
 *
 * A newer live model may answer one prompt with several turns, so a completed turn alone does not
 * mean the model is done. Only [IN_PROGRESS] on a completed turn means more output for this prompt
 * will follow.
 */
@Serializable(with = InteractionStatusSerializer::class)
enum class InteractionStatus {
  /** Unspecified, or a status this version of ADK does not recognize. */
  INTERACTION_STATUS_UNSPECIFIED,

  /** More output may follow: the model is still processing input or reasoning in the background. */
  IN_PROGRESS,

  /** Deprecated by the service in favor of [IDLE], and means the same. */
  REQUIRES_ACTION,

  /** The model has finished processing the prompt and is waiting for user input. */
  IDLE,
}

/**
 * Serializes [InteractionStatus] by name. An unknown name decodes to
 * [InteractionStatus.INTERACTION_STATUS_UNSPECIFIED], so a status added later does not fail the
 * whole document; a value that is not a string still fails.
 */
private object InteractionStatusSerializer : KSerializer<InteractionStatus> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.google.adk.kt.types.InteractionStatus", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: InteractionStatus) {
    encoder.encodeString(value.name)
  }

  override fun deserialize(decoder: Decoder): InteractionStatus {
    val name = decoder.decodeString()
    return InteractionStatus.entries.firstOrNull { it.name == name }
      ?: InteractionStatus.INTERACTION_STATUS_UNSPECIFIED
  }
}
