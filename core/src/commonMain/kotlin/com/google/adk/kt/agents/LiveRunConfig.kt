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

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.ContextWindowCompressionConfig
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.RealtimeInputConfig
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.SpeechConfig
import com.google.adk.kt.types.TranslationConfig
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Settings of a live run, held in [RunConfig.live]. They belong to the experimental live API, the
 * only code that reads them, and may change without notice.
 *
 * Properties from [responseModalities] to [contextWindowCompression] configure the live connection,
 * set once when it opens and not changed per turn; [saveLiveBlob] instead tells ADK to record the
 * conversation's audio and never reaches the connection.
 *
 * @property responseModalities The modalities the model may return. Empty leaves it unset, and the
 *   SDK then asks for audio on a live connection, as unset does in ADK Python. A value set here is
 *   sent as is, as in ADK Python, so a modality the model cannot answer in fails the connection.
 * @property speechConfig The speech generation configuration.
 * @property outputAudioTranscription Transcription of the audio the model returns. Enabled by
 *   default with automatic language detection; set to null to disable it.
 * @property inputAudioTranscription Transcription of the audio the user sends. Enabled by default
 *   with automatic language detection; set to null to disable it.
 * @property realtimeInputConfig How realtime input is interpreted as turns, including voice
 *   activity detection.
 * @property explicitVadSignal Whether the model sends its voice activity detection signals. Only
 *   the Vertex AI backend accepts `true`; Gemini API clients reject it.
 * @property translationConfig Real-time speech-to-speech translation. Only translation models
 *   support it.
 * @property enableAffectiveDialog Whether the model detects emotion and adapts its responses.
 * @property proactivity Whether the model may decline to respond to a prompt.
 * @property sessionResumption Configuration of the session resumption mechanism.
 * @property contextWindowCompression Keeps the session's context below a given length.
 * @property saveLiveBlob Whether to save the live input and output audio as artifacts, each
 *   referenced from an event the run emits. ADK never deletes these recordings, not even with the
 *   session, so prefer an artifact service backed by durable storage over an in-memory one for
 *   anything but short sessions.
 */
data class LiveRunConfig
@ExperimentalLiveApi
@JvmOverloads
constructor(
  val responseModalities: List<Modality> = emptyList(),
  val speechConfig: SpeechConfig? = null,
  val outputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig(),
  val inputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig(),
  val realtimeInputConfig: RealtimeInputConfig? = null,
  val explicitVadSignal: Boolean? = null,
  val translationConfig: TranslationConfig? = null,
  val enableAffectiveDialog: Boolean? = null,
  val proactivity: ProactivityConfig? = null,
  val sessionResumption: SessionResumptionConfig? = null,
  val contextWindowCompression: ContextWindowCompressionConfig? = null,
  val saveLiveBlob: Boolean = false,
) {
  // Only the constructor and builders need the opt-in (not `copy`), so RunConfig can hold one.

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @ExperimentalLiveApi
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .responseModalities(responseModalities)
      .speechConfig(speechConfig)
      .outputAudioTranscription(outputAudioTranscription)
      .inputAudioTranscription(inputAudioTranscription)
      .realtimeInputConfig(realtimeInputConfig)
      .explicitVadSignal(explicitVadSignal)
      .translationConfig(translationConfig)
      .enableAffectiveDialog(enableAffectiveDialog)
      .proactivity(proactivity)
      .sessionResumption(sessionResumption)
      .contextWindowCompression(contextWindowCompression)
      .saveLiveBlob(saveLiveBlob)

  /**
   * Fluent builder for [LiveRunConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder @ExperimentalLiveApi constructor() {
    private var responseModalities: List<Modality> = emptyList()
    private var speechConfig: SpeechConfig? = null
    private var outputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig()
    private var inputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig()
    private var realtimeInputConfig: RealtimeInputConfig? = null
    private var explicitVadSignal: Boolean? = null
    private var translationConfig: TranslationConfig? = null
    private var enableAffectiveDialog: Boolean? = null
    private var proactivity: ProactivityConfig? = null
    private var sessionResumption: SessionResumptionConfig? = null
    private var contextWindowCompression: ContextWindowCompressionConfig? = null
    private var saveLiveBlob: Boolean = false

    fun responseModalities(responseModalities: List<Modality>): Builder = apply {
      this.responseModalities = responseModalities
    }

    fun speechConfig(speechConfig: SpeechConfig?): Builder = apply {
      this.speechConfig = speechConfig
    }

    fun outputAudioTranscription(outputAudioTranscription: AudioTranscriptionConfig?): Builder =
      apply {
        this.outputAudioTranscription = outputAudioTranscription
      }

    fun inputAudioTranscription(inputAudioTranscription: AudioTranscriptionConfig?): Builder =
      apply {
        this.inputAudioTranscription = inputAudioTranscription
      }

    fun realtimeInputConfig(realtimeInputConfig: RealtimeInputConfig?): Builder = apply {
      this.realtimeInputConfig = realtimeInputConfig
    }

    fun explicitVadSignal(explicitVadSignal: Boolean?): Builder = apply {
      this.explicitVadSignal = explicitVadSignal
    }

    fun translationConfig(translationConfig: TranslationConfig?): Builder = apply {
      this.translationConfig = translationConfig
    }

    fun enableAffectiveDialog(enableAffectiveDialog: Boolean?): Builder = apply {
      this.enableAffectiveDialog = enableAffectiveDialog
    }

    fun proactivity(proactivity: ProactivityConfig?): Builder = apply {
      this.proactivity = proactivity
    }

    fun sessionResumption(sessionResumption: SessionResumptionConfig?): Builder = apply {
      this.sessionResumption = sessionResumption
    }

    fun contextWindowCompression(
      contextWindowCompression: ContextWindowCompressionConfig?
    ): Builder = apply { this.contextWindowCompression = contextWindowCompression }

    fun saveLiveBlob(saveLiveBlob: Boolean): Builder = apply { this.saveLiveBlob = saveLiveBlob }

    @OptIn(ExperimentalLiveApi::class)
    fun build(): LiveRunConfig =
      LiveRunConfig(
        responseModalities = responseModalities,
        speechConfig = speechConfig,
        outputAudioTranscription = outputAudioTranscription,
        inputAudioTranscription = inputAudioTranscription,
        realtimeInputConfig = realtimeInputConfig,
        explicitVadSignal = explicitVadSignal,
        translationConfig = translationConfig,
        enableAffectiveDialog = enableAffectiveDialog,
        proactivity = proactivity,
        sessionResumption = sessionResumption,
        contextWindowCompression = contextWindowCompression,
        saveLiveBlob = saveLiveBlob,
      )
  }

  companion object {
    @ExperimentalLiveApi @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
