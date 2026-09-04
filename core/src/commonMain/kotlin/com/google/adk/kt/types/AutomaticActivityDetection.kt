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
 * Configures server-side detection of user activity (voice activity detection).
 *
 * @property disabled If disabled, the client must send activity signals itself. If enabled,
 *   detected voice and text input count as activity.
 * @property startOfSpeechSensitivity How likely speech is to be detected.
 * @property endOfSpeechSensitivity How likely detected speech is to be treated as ended.
 * @property prefixPaddingMs Duration of detected speech required before start-of-speech is
 *   committed. Lower values detect shorter utterances but yield more false positives.
 * @property silenceDurationMs Duration of detected silence required before end-of-speech is
 *   committed. Larger values tolerate longer gaps but increase latency.
 */
@Serializable
data class AutomaticActivityDetection(
  val disabled: Boolean? = null,
  val startOfSpeechSensitivity: StartSensitivity? = null,
  val endOfSpeechSensitivity: EndSensitivity? = null,
  val prefixPaddingMs: Int? = null,
  val silenceDurationMs: Int? = null,
)
