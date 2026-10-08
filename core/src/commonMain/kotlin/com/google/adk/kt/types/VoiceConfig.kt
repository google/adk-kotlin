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
 * The configuration for the voice to use.
 *
 * @property replicatedVoiceConfig The configuration for a replicated voice, which is a clone of a
 *   user's voice. If unset, a default voice is used.
 * @property prebuiltVoiceConfig The configuration for a prebuilt voice.
 * @property voice The speaker identifier for synthesis.
 */
@Serializable
data class VoiceConfig
@JvmOverloads
constructor(
  val replicatedVoiceConfig: ReplicatedVoiceConfig? = null,
  val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null,
  val voice: String? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .replicatedVoiceConfig(replicatedVoiceConfig)
      .prebuiltVoiceConfig(prebuiltVoiceConfig)
      .voice(voice)

  /**
   * Fluent builder for [VoiceConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var replicatedVoiceConfig: ReplicatedVoiceConfig? = null
    private var prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
    private var voice: String? = null

    fun replicatedVoiceConfig(replicatedVoiceConfig: ReplicatedVoiceConfig?): Builder = apply {
      this.replicatedVoiceConfig = replicatedVoiceConfig
    }

    fun prebuiltVoiceConfig(prebuiltVoiceConfig: PrebuiltVoiceConfig?): Builder = apply {
      this.prebuiltVoiceConfig = prebuiltVoiceConfig
    }

    fun voice(voice: String?): Builder = apply { this.voice = voice }

    fun build(): VoiceConfig =
      VoiceConfig(
        replicatedVoiceConfig = replicatedVoiceConfig,
        prebuiltVoiceConfig = prebuiltVoiceConfig,
        voice = voice,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
