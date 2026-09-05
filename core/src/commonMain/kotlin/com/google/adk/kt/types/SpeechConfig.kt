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

import kotlinx.serialization.Serializable

/**
 * Config for speech generation and transcription.
 *
 * @property voiceConfig The configuration in case of single-voice output.
 * @property languageCode The language code (ISO 639-1) for the speech synthesis.
 * @property multiSpeakerVoiceConfig The configuration for a multi-speaker request. Mutually
 *   exclusive with [voiceConfig].
 */
@Serializable
data class SpeechConfig(
  val voiceConfig: VoiceConfig? = null,
  val languageCode: String? = null,
  val multiSpeakerVoiceConfig: MultiSpeakerVoiceConfig? = null,
)
