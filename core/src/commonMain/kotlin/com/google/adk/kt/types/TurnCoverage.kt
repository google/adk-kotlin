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

/** Which realtime input counts towards the user's turn. */
@Serializable
enum class TurnCoverage {
  /** Unspecified; the model uses its default coverage. */
  TURN_COVERAGE_UNSPECIFIED,

  /** Only input received while activity is detected is included. */
  TURN_INCLUDES_ONLY_ACTIVITY,

  /** All realtime input is included, whether or not activity is detected. */
  TURN_INCLUDES_ALL_INPUT,

  /** Audio received while activity is detected, plus all video. */
  TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO,
}
