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
 * A single transcribed word and where it falls in the audio.
 *
 * Populated only when word-level timestamps are requested via
 * [AudioTranscriptionConfig.wordTimestamp].
 *
 * @property word The transcribed word.
 * @property startOffset Offset of the word's start from the beginning of the audio.
 * @property endOffset Offset of the word's end from the beginning of the audio.
 */
@Serializable
data class WordInfo(
  val word: String? = null,
  val startOffset: String? = null,
  val endOffset: String? = null,
)
