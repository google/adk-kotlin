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
 * A transcription of audio, either the user's input or the model's output.
 *
 * Transcriptions arrive in chunks: each carries the newest text, and [finished] marks the last
 * chunk of an utterance.
 *
 * @property text The transcribed text for this chunk.
 * @property finished Whether this is the final chunk of the utterance.
 * @property languageCode The detected language of the audio.
 * @property speakerLabel The detected speaker, when diarization is enabled.
 * @property words Per-word timings, when word timestamps are enabled.
 */
@Serializable
data class Transcription(
  val text: String? = null,
  val finished: Boolean? = null,
  val languageCode: String? = null,
  val speakerLabel: String? = null,
  val words: List<WordInfo>? = null,
)
