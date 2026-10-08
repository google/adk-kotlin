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
 * Configuration for a live (bidirectional streaming) model connection.
 *
 * This is sent once, when the connection is established, and governs the whole session; it cannot
 * be changed per turn.
 *
 * @property responseModalities The modalities the model may return. Defaults to audio when unset.
 * @property temperature Degree of randomness in token selection.
 * @property topP Tokens are selected from the most to least probable until their probabilities sum
 *   to this value.
 * @property topK The number of highest-probability tokens to sample from at each step.
 * @property maxOutputTokens Maximum number of tokens that can be generated in a response.
 * @property mediaResolution The resolution used when processing media input.
 * @property seed Fixing the seed makes the model attempt to give the same response to repeated
 *   requests.
 * @property speechConfig The speech generation configuration.
 * @property thinkingConfig Config for thinking features. Setting it for a model that does not
 *   support thinking is an error.
 * @property enableAffectiveDialog Whether the model detects emotion and adapts its responses.
 * @property systemInstruction Instructions for the model. Only text parts are supported.
 * @property tools Tools the model may call to generate its next response.
 * @property sessionResumption Configuration of the session resumption mechanism.
 * @property inputAudioTranscription Transcription of the audio the user sends. Unset disables it.
 * @property outputAudioTranscription Transcription of the audio the model returns. Unset disables
 *   it.
 * @property realtimeInputConfig How realtime input is interpreted as turns, including voice
 *   activity detection.
 * @property contextWindowCompression Keeps the session's context below a given length.
 * @property proactivity Whether the model may decline to respond to a prompt.
 * @property safetySettings Safety settings to apply to the session.
 * @property explicitVadSignal Whether the model sends its voice activity detection signals.
 * @property translationConfig Real-time speech-to-speech translation, for translation models.
 */
@Serializable
data class LiveConnectConfig
@JvmOverloads
constructor(
  val responseModalities: List<Modality>? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val maxOutputTokens: Int? = null,
  val mediaResolution: MediaResolution? = null,
  val seed: Int? = null,
  val speechConfig: SpeechConfig? = null,
  val thinkingConfig: ThinkingConfig? = null,
  val enableAffectiveDialog: Boolean? = null,
  val systemInstruction: Content? = null,
  val tools: List<Tool>? = null,
  val sessionResumption: SessionResumptionConfig? = null,
  val inputAudioTranscription: AudioTranscriptionConfig? = null,
  val outputAudioTranscription: AudioTranscriptionConfig? = null,
  val realtimeInputConfig: RealtimeInputConfig? = null,
  val contextWindowCompression: ContextWindowCompressionConfig? = null,
  val proactivity: ProactivityConfig? = null,
  val safetySettings: List<SafetySetting>? = null,
  val explicitVadSignal: Boolean? = null,
  val translationConfig: TranslationConfig? = null,
) {

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .responseModalities(responseModalities.orEmpty())
      .temperature(temperature)
      .topP(topP)
      .topK(topK)
      .maxOutputTokens(maxOutputTokens)
      .mediaResolution(mediaResolution)
      .seed(seed)
      .speechConfig(speechConfig)
      .thinkingConfig(thinkingConfig)
      .enableAffectiveDialog(enableAffectiveDialog)
      .systemInstruction(systemInstruction)
      .tools(tools.orEmpty())
      .sessionResumption(sessionResumption)
      .inputAudioTranscription(inputAudioTranscription)
      .outputAudioTranscription(outputAudioTranscription)
      .realtimeInputConfig(realtimeInputConfig)
      .contextWindowCompression(contextWindowCompression)
      .proactivity(proactivity)
      .safetySettings(safetySettings.orEmpty())
      .explicitVadSignal(explicitVadSignal)
      .translationConfig(translationConfig)

  /**
   * Fluent builder for [LiveConnectConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var responseModalities: List<Modality> = emptyList()
    private var temperature: Float? = null
    private var topP: Float? = null
    private var topK: Int? = null
    private var maxOutputTokens: Int? = null
    private var mediaResolution: MediaResolution? = null
    private var seed: Int? = null
    private var speechConfig: SpeechConfig? = null
    private var thinkingConfig: ThinkingConfig? = null
    private var enableAffectiveDialog: Boolean? = null
    private var systemInstruction: Content? = null
    private var tools: List<Tool> = emptyList()
    private var sessionResumption: SessionResumptionConfig? = null
    private var inputAudioTranscription: AudioTranscriptionConfig? = null
    private var outputAudioTranscription: AudioTranscriptionConfig? = null
    private var realtimeInputConfig: RealtimeInputConfig? = null
    private var contextWindowCompression: ContextWindowCompressionConfig? = null
    private var proactivity: ProactivityConfig? = null
    private var safetySettings: List<SafetySetting> = emptyList()
    private var explicitVadSignal: Boolean? = null
    private var translationConfig: TranslationConfig? = null

    /** An empty list leaves it unset. */
    fun responseModalities(responseModalities: List<Modality>): Builder = apply {
      this.responseModalities = responseModalities
    }

    fun temperature(temperature: Float?): Builder = apply { this.temperature = temperature }

    fun topP(topP: Float?): Builder = apply { this.topP = topP }

    fun topK(topK: Int?): Builder = apply { this.topK = topK }

    fun maxOutputTokens(maxOutputTokens: Int?): Builder = apply {
      this.maxOutputTokens = maxOutputTokens
    }

    fun mediaResolution(mediaResolution: MediaResolution?): Builder = apply {
      this.mediaResolution = mediaResolution
    }

    fun seed(seed: Int?): Builder = apply { this.seed = seed }

    fun speechConfig(speechConfig: SpeechConfig?): Builder = apply {
      this.speechConfig = speechConfig
    }

    fun thinkingConfig(thinkingConfig: ThinkingConfig?): Builder = apply {
      this.thinkingConfig = thinkingConfig
    }

    fun enableAffectiveDialog(enableAffectiveDialog: Boolean?): Builder = apply {
      this.enableAffectiveDialog = enableAffectiveDialog
    }

    fun systemInstruction(systemInstruction: Content?): Builder = apply {
      this.systemInstruction = systemInstruction
    }

    /** An empty list leaves it unset. */
    fun tools(tools: List<Tool>): Builder = apply { this.tools = tools }

    fun sessionResumption(sessionResumption: SessionResumptionConfig?): Builder = apply {
      this.sessionResumption = sessionResumption
    }

    fun inputAudioTranscription(inputAudioTranscription: AudioTranscriptionConfig?): Builder =
      apply {
        this.inputAudioTranscription = inputAudioTranscription
      }

    fun outputAudioTranscription(outputAudioTranscription: AudioTranscriptionConfig?): Builder =
      apply {
        this.outputAudioTranscription = outputAudioTranscription
      }

    fun realtimeInputConfig(realtimeInputConfig: RealtimeInputConfig?): Builder = apply {
      this.realtimeInputConfig = realtimeInputConfig
    }

    fun contextWindowCompression(
      contextWindowCompression: ContextWindowCompressionConfig?
    ): Builder = apply { this.contextWindowCompression = contextWindowCompression }

    fun proactivity(proactivity: ProactivityConfig?): Builder = apply {
      this.proactivity = proactivity
    }

    /** An empty list leaves it unset. */
    fun safetySettings(safetySettings: List<SafetySetting>): Builder = apply {
      this.safetySettings = safetySettings
    }

    fun explicitVadSignal(explicitVadSignal: Boolean?): Builder = apply {
      this.explicitVadSignal = explicitVadSignal
    }

    fun translationConfig(translationConfig: TranslationConfig?): Builder = apply {
      this.translationConfig = translationConfig
    }

    fun build(): LiveConnectConfig =
      LiveConnectConfig(
        responseModalities = responseModalities.ifEmpty { null },
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        mediaResolution = mediaResolution,
        seed = seed,
        speechConfig = speechConfig,
        thinkingConfig = thinkingConfig,
        enableAffectiveDialog = enableAffectiveDialog,
        systemInstruction = systemInstruction,
        tools = tools.ifEmpty { null },
        sessionResumption = sessionResumption,
        inputAudioTranscription = inputAudioTranscription,
        outputAudioTranscription = outputAudioTranscription,
        realtimeInputConfig = realtimeInputConfig,
        contextWindowCompression = contextWindowCompression,
        proactivity = proactivity,
        safetySettings = safetySettings.ifEmpty { null },
        explicitVadSignal = explicitVadSignal,
        translationConfig = translationConfig,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
