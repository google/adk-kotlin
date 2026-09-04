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

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Pins that both of [ReplicatedVoiceConfig]'s `ByteArray` fields serialize as base64, and that the
 * type compares by content.
 *
 * They are proto `bytes`, which proto3 JSON encodes as a base64 string, and the Gen AI SDK
 * annotates its own copy of this type the same way. Without the annotation kotlinx-serialization
 * writes an array of signed numbers instead, which is silent: the type still round-trips through
 * itself, so only an assertion on the wire form catches it.
 */
class ReplicatedVoiceConfigSerializationTest {

  private val json = Json

  @Test
  fun replicatedVoiceConfig_encoded_writesBothByteFieldsAsBase64() {
    val config =
      ReplicatedVoiceConfig(
        mimeType = "audio/wav",
        voiceSampleAudio = byteArrayOf(1, 2, 3),
        consentAudio = byteArrayOf(4, 5, 6),
      )

    val encoded = json.encodeToString(ReplicatedVoiceConfig.serializer(), config)

    assertTrue(encoded.contains("\"voiceSampleAudio\":\"AQID\""), encoded)
    assertTrue(encoded.contains("\"consentAudio\":\"BAUG\""), encoded)
  }

  @Test
  fun replicatedVoiceConfig_roundTrip_preservesBothByteFields() {
    val config =
      ReplicatedVoiceConfig(
        voiceSampleAudio = byteArrayOf(1, 2, 3),
        consentAudio = byteArrayOf(4, 5, 6),
      )

    val decoded =
      json.decodeFromString(
        ReplicatedVoiceConfig.serializer(),
        json.encodeToString(ReplicatedVoiceConfig.serializer(), config),
      )

    assertContentEquals(config.voiceSampleAudio, decoded.voiceSampleAudio)
    assertContentEquals(config.consentAudio, decoded.consentAudio)
  }

  @Test
  fun replicatedVoiceConfig_bytesAbove0x7f_surviveTheRoundTrip() {
    // A number-array encoding writes these as negatives, so a regression shows here first.
    val config = ReplicatedVoiceConfig(voiceSampleAudio = byteArrayOf(-1, -128, 127, 0))

    val decoded =
      json.decodeFromString(
        ReplicatedVoiceConfig.serializer(),
        json.encodeToString(ReplicatedVoiceConfig.serializer(), config),
      )

    assertContentEquals(byteArrayOf(-1, -128, 127, 0), decoded.voiceSampleAudio)
  }

  @Test
  fun equals_distinctArraysHoldingTheSameBytes_areEqualAndAgreeOnHashCode() {
    // A data class would compare these two by array identity and report them unequal.
    val config =
      ReplicatedVoiceConfig(voiceSampleAudio = byteArrayOf(1, 2, 3), consentAudio = byteArrayOf(4))
    val separatelyBuilt =
      ReplicatedVoiceConfig(voiceSampleAudio = byteArrayOf(1, 2, 3), consentAudio = byteArrayOf(4))

    assertEquals(config, separatelyBuilt)
    assertEquals(config.hashCode(), separatelyBuilt.hashCode())
  }

  @Test
  fun equals_anySingleFieldDiffering_areNotEqual() {
    // One case per property, so dropping any one comparison from the override fails a case.
    val config =
      ReplicatedVoiceConfig(
        mimeType = "audio/wav",
        voiceSampleAudio = byteArrayOf(1, 2, 3),
        consentAudio = byteArrayOf(4),
        voiceConsentSignature = VoiceConsentSignature("signed"),
      )

    assertNotEquals(config, config.copy(mimeType = "audio/pcm"))
    assertNotEquals(config, config.copy(voiceSampleAudio = byteArrayOf(1, 2, 4)))
    assertNotEquals(config, config.copy(consentAudio = byteArrayOf(5)))
    assertNotEquals(config, config.copy(voiceConsentSignature = VoiceConsentSignature("other")))
  }
}
