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

import com.google.genai.kotlin.types.ByteArrayAsBase64Serializer
import kotlinx.serialization.Serializable

/**
 * Configuration for a replicated (cloned) voice.
 *
 * Either [consentAudio] or a previously issued [voiceConsentSignature] must accompany the sample;
 * the signature avoids re-verifying the same consent on every request.
 *
 * @property mimeType The mime type of the voice sample. Currently only `audio/wav` is supported,
 *   meaning 16-bit signed little-endian data at a 24kHz sampling rate.
 * @property voiceSampleAudio The sample of the custom voice.
 * @property consentAudio Recorded consent verifying ownership of the voice, in the same format.
 * @property voiceConsentSignature Signature of a previously verified consent audio.
 */
@Serializable
data class ReplicatedVoiceConfig(
  val mimeType: String? = null,
  @Serializable(with = ByteArrayAsBase64Serializer::class) val voiceSampleAudio: ByteArray? = null,
  @Serializable(with = ByteArrayAsBase64Serializer::class) val consentAudio: ByteArray? = null,
  val voiceConsentSignature: VoiceConsentSignature? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ReplicatedVoiceConfig) return false

    return mimeType == other.mimeType &&
      voiceSampleAudio.contentEquals(other.voiceSampleAudio) &&
      consentAudio.contentEquals(other.consentAudio) &&
      voiceConsentSignature == other.voiceConsentSignature
  }

  override fun hashCode(): Int {
    var result = mimeType?.hashCode() ?: 0
    result = 31 * result + (voiceSampleAudio?.contentHashCode() ?: 0)
    result = 31 * result + (consentAudio?.contentHashCode() ?: 0)
    result = 31 * result + (voiceConsentSignature?.hashCode() ?: 0)
    return result
  }
}
