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

import com.google.genai.kotlin.types.DurationStringSerializer
import kotlin.time.Duration
import kotlinx.serialization.Serializable

/**
 * A server-detected change in whether the user is speaking.
 *
 * @property voiceActivityType Whether speech started or stopped.
 * @property audioOffset Where in the audio stream the change was detected.
 */
@Serializable
data class VoiceActivity(
  val voiceActivityType: VoiceActivityType? = null,
  @Serializable(with = DurationStringSerializer::class) val audioOffset: Duration? = null,
)
