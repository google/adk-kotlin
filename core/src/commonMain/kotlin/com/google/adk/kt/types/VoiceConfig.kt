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
 * The configuration for the voice to use.
 *
 * @property replicatedVoiceConfig The configuration for a replicated voice, which is a clone of a
 *   user's voice. If unset, a default voice is used.
 * @property prebuiltVoiceConfig The configuration for a prebuilt voice.
 */
@Serializable
data class VoiceConfig(
  val replicatedVoiceConfig: ReplicatedVoiceConfig? = null,
  val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null,
)
