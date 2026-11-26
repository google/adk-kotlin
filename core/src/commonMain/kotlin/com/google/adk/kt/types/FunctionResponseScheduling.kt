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

/** When a live model reacts to a response to a non-blocking function call. */
@Serializable(with = FunctionResponseSchedulingSerializer::class)
enum class FunctionResponseScheduling {
  /** Unspecified; the model uses its default. */
  SCHEDULING_UNSPECIFIED,

  /** The model adds the response to its context without starting a turn or interrupting one. */
  SILENT,

  /** The model adds the response to its context and reacts once it is idle. */
  WHEN_IDLE,

  /** The model adds the response to its context, interrupts what it is saying, and reacts. */
  INTERRUPT,
}

/**
 * Serializes [FunctionResponseScheduling] by name. An unknown name decodes to
 * [FunctionResponseScheduling.SCHEDULING_UNSPECIFIED], so a stored event carrying a value added
 * later still loads; a value that is not a string still fails.
 */
internal object FunctionResponseSchedulingSerializer : KSerializer<FunctionResponseScheduling> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor(
      "com.google.adk.kt.types.FunctionResponseScheduling",
      PrimitiveKind.STRING,
    )

  override fun serialize(encoder: Encoder, value: FunctionResponseScheduling) {
    encoder.encodeString(value.name)
  }

  override fun deserialize(decoder: Decoder): FunctionResponseScheduling {
    val name = decoder.decodeString()
    return FunctionResponseScheduling.entries.firstOrNull { it.name == name }
      ?: FunctionResponseScheduling.SCHEDULING_UNSPECIFIED
  }
}
