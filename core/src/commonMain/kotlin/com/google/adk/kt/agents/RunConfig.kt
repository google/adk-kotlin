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
import com.google.adk.kt.logging.LoggerFactory
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
 * Streaming modes for agent execution.
 *
 * This enum defines different streaming behaviors for how the agent returns events as model
 * response.
 */
enum class StreamingMode {
  /**
   * Non-streaming mode (default).
   *
   * In this mode:
   * - The runner returns one single content in a turn (one user / model interaction).
   * - No partial/intermediate events are produced
   * - Suitable for: CLI tools, batch processing, synchronous workflows
   */
  NONE,

  /**
   * Server-Sent Events (SSE) streaming mode.
   *
   * In this mode:
   * - The runner yields events progressively as the LLM generates responses
   * - Both partial events (streaming chunks) and aggregated events are yielded
   * - Suitable for: real-time display with typewriter effects in Web UIs, chat applications,
   *   interactive displays
   */
  SSE,

  /**
   * Bidirectional (live) streaming over one open connection.
   *
   * Marks the run as live; it does not open a live connection by itself, as in ADK Python.
   */
  BIDI,
}

/**
 * Configs for runtime behavior of agents.
 *
 * Properties from [responseModalities] onwards apply only to a live connection, which is configured
 * once when it opens and cannot be changed per turn. They only take effect through the experimental
 * live API.
 *
 * @property streamingMode Streaming mode, NONE, SSE or BIDI.
 * @property maxLlmCalls Limit on the total number of LLM calls per run. A positive value is
 *   enforced, except by a live run, which ignores it; a value <= 0 means unbounded.
 * @property customMetadata Custom metadata for the current invocation.
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
 */
data class RunConfig
@JvmOverloads
constructor(
  val streamingMode: StreamingMode = StreamingMode.NONE,
  val maxLlmCalls: Int = 500,
  val customMetadata: Map<String, Any>? = null,
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
) {
  init {
    require(maxLlmCalls != Int.MAX_VALUE) { "maxLlmCalls should be less than Int.MAX_VALUE." }
    if (maxLlmCalls <= 0) {
      logger.warn {
        "maxLlmCalls <= 0 disables the LLM-call limit, risking never-ending agent loops."
      }
    }
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .streamingMode(streamingMode)
      .maxLlmCalls(maxLlmCalls)
      .customMetadata(customMetadata)
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

  /**
   * Fluent builder for [RunConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var streamingMode: StreamingMode = StreamingMode.NONE
    private var maxLlmCalls: Int = 500
    private var customMetadata: Map<String, Any>? = null
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

    fun streamingMode(streamingMode: StreamingMode): Builder = apply {
      this.streamingMode = streamingMode
    }

    fun maxLlmCalls(maxLlmCalls: Int): Builder = apply { this.maxLlmCalls = maxLlmCalls }

    fun customMetadata(customMetadata: Map<String, Any>?): Builder = apply {
      this.customMetadata = customMetadata
    }

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

    fun build(): RunConfig =
      RunConfig(
        streamingMode = streamingMode,
        maxLlmCalls = maxLlmCalls,
        customMetadata = customMetadata,
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
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    private val logger = LoggerFactory.getLogger(RunConfig::class)
  }
}
