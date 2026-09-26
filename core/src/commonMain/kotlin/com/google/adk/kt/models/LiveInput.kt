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
package com.google.adk.kt.models

import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import kotlin.jvm.JvmOverloads

/**
 * What one live request sends to the model: a [ContentInput] turn or a [RealtimeInput].
 *
 * The two go out on different channels, so a request carries one or the other.
 */
sealed interface LiveInput

/**
 * A user turn, or the function responses that answer the model's calls.
 *
 * The constructor throws [IllegalArgumentException] unless [content] has at least one part, carries
 * no function calls, and holds either only function responses or none of them.
 *
 * @property content The turn to send.
 * @property partial Whether more of the same turn follows, so the model waits instead of answering;
 *   not allowed with function responses.
 */
data class ContentInput
@JvmOverloads
constructor(val content: Content, val partial: Boolean = false) : LiveInput {
  init {
    require(content.parts.isNotEmpty()) { "content must have at least one part." }
    require(content.parts.none { it.functionCall != null }) {
      "User message cannot contain function calls."
    }
    val functionResponses = content.parts.count { it.functionResponse != null }
    require(functionResponses == 0 || functionResponses == content.parts.size) {
      "Function responses must be sent on their own, not mixed with other parts."
    }
    require(!partial || functionResponses == 0) { "partial does not apply to function responses." }
  }
}

/**
 * Input streamed to a live model outside the turn-by-turn content exchange.
 *
 * Realtime input is not a turn: media streams in continuously and the model decides when the user
 * has finished, unless activity detection is disabled and the caller marks the boundaries with
 * [ActivityStart] and [ActivityEnd]; realtime *text* has no arm here and is sent as content.
 *
 * [Audio] and [Video] are separate arms because the Kotlin GenAI SDK has no combined media
 * parameter, so the caller chooses which it is sending.
 */
sealed interface RealtimeInput : LiveInput {
  /** A chunk of audio, conventionally 16-bit mono PCM at 16kHz (`audio/pcm;rate=16000`). */
  data class Audio(val blob: Blob) : RealtimeInput

  /** A frame of video, conventionally a JPEG image. */
  data class Video(val blob: Blob) : RealtimeInput

  /**
   * Marks the start of user activity.
   *
   * Only valid when automatic activity detection is disabled via
   * [com.google.adk.kt.types.AutomaticActivityDetection.disabled].
   */
  data object ActivityStart : RealtimeInput

  /** Marks the end of user activity, under the same condition as [ActivityStart]. */
  data object ActivityEnd : RealtimeInput

  /**
   * Marks the end of the audio stream, forcing the model to flush what it has.
   *
   * Used when activity detection is enabled and the caller knows the audio has stopped.
   */
  data object AudioStreamEnd : RealtimeInput
}
