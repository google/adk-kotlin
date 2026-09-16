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
 */
@Serializable
data class LiveConnectConfig(
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
)
