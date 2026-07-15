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

/** The probability threshold at or above which content is blocked for a [HarmCategory]. */
@Serializable
enum class HarmBlockThreshold {
  /** The threshold is unspecified. */
  HARM_BLOCK_THRESHOLD_UNSPECIFIED,

  /** Block content with a low probability of harm and above. */
  BLOCK_LOW_AND_ABOVE,

  /** Block content with a medium probability of harm and above. */
  BLOCK_MEDIUM_AND_ABOVE,

  /** Block only content with a high probability of harm. */
  BLOCK_ONLY_HIGH,

  /** Do not block any content based on probability. */
  BLOCK_NONE,

  /** Turn off the safety filter for this category. */
  OFF,
}
