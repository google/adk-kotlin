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

/** How [AudioTranscriptionConfig] transcribes speech; defaults to [VERBATIM] when unspecified. */
@Serializable
enum class AudioTranscriptionConfigMode {
  /** Leaves the mode unspecified, which defaults to [VERBATIM]. */
  MODE_UNSPECIFIED,

  /** Transcribes speech as spoken. */
  VERBATIM,

  /**
   * Removes disfluencies (filler words, repetitions, and false starts), cleans up grammar, formats
   * paragraphs and lists, and applies inline self-corrections. Incompatible with
   * [AudioTranscriptionConfig.wordTimestamp] and [AudioTranscriptionConfig.diarization].
   */
  SMART,
}
