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
 * Configures transcription of audio on a live connection.
 *
 * An instance with all fields unset still enables transcription with automatic language detection.
 *
 * @property languageCodes BCP-47 language codes hinting at the languages present in the audio. If
 *   omitted or empty, the language is detected automatically.
 * @property customVocabulary Phrases that bias the speech model towards recognising these terms.
 * @property diarization Whether to label distinct speakers.
 * @property wordTimestamp Whether to generate word-level timestamps.
 */
@Serializable
data class AudioTranscriptionConfig(
  val languageCodes: List<String>? = null,
  val customVocabulary: List<String>? = null,
  val diarization: Boolean? = null,
  val wordTimestamp: Boolean? = null,
)
