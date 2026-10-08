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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
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
 * @property mode Configures transcription mode; if unspecified, defaults to `VERBATIM`. Timestamps
 *   and diarization are incompatible with `SMART`.
 */
@Serializable
data class AudioTranscriptionConfig
@JvmOverloads
constructor(
  val languageCodes: List<String>? = null,
  val customVocabulary: List<String>? = null,
  val diarization: Boolean? = null,
  val wordTimestamp: Boolean? = null,
  val mode: AudioTranscriptionConfigMode? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .languageCodes(languageCodes.orEmpty())
      .customVocabulary(customVocabulary.orEmpty())
      .diarization(diarization)
      .wordTimestamp(wordTimestamp)
      .mode(mode)

  /**
   * Fluent builder for [AudioTranscriptionConfig], provided primarily for Java callers. Any
   * property left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var languageCodes: List<String> = emptyList()
    private var customVocabulary: List<String> = emptyList()
    private var diarization: Boolean? = null
    private var wordTimestamp: Boolean? = null
    private var mode: AudioTranscriptionConfigMode? = null

    /** An empty list leaves it unset. */
    fun languageCodes(languageCodes: List<String>): Builder = apply {
      this.languageCodes = languageCodes
    }

    /** An empty list leaves it unset. */
    fun customVocabulary(customVocabulary: List<String>): Builder = apply {
      this.customVocabulary = customVocabulary
    }

    fun diarization(diarization: Boolean?): Builder = apply { this.diarization = diarization }

    fun wordTimestamp(wordTimestamp: Boolean?): Builder = apply {
      this.wordTimestamp = wordTimestamp
    }

    fun mode(mode: AudioTranscriptionConfigMode?): Builder = apply { this.mode = mode }

    fun build(): AudioTranscriptionConfig =
      AudioTranscriptionConfig(
        languageCodes = languageCodes.ifEmpty { null },
        customVocabulary = customVocabulary.ifEmpty { null },
        diarization = diarization,
        wordTimestamp = wordTimestamp,
        mode = mode,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
