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

/**
 * Something sent to a live model between turns, outside the turn-by-turn content exchange.
 *
 * Realtime input is not a turn: media streams in continuously and the model decides when the user
 * has finished, unless activity detection is disabled and the caller marks the boundaries with
 * [ActivityStart] and [ActivityEnd]. Realtime *text* has no arm here -- text is sent as content.
 */
sealed interface RealtimeInput {
  /**
   * A chunk of audio, conventionally 16-bit mono PCM at 16kHz (`audio/pcm;rate=16000`).
   *
   * Audio and video are separate arms, and not by preference: the transport has separate audio and
   * video fields and no combined one, so the choice cannot be deferred. Collapsing them would only
   * move it into the connection, which would have to sniff the blob's mime type -- a guess, where
   * the caller already knows the answer.
   */
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
