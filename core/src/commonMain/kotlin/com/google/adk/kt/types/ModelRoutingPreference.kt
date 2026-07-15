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

/** The model routing preference for automated routing. */
@Serializable
enum class ModelRoutingPreference {
  /** The routing preference is unknown. */
  UNKNOWN,

  /** Prioritize response quality when routing. */
  PRIORITIZE_QUALITY,

  /** Balance response quality and cost when routing. */
  BALANCED,

  /** Prioritize cost when routing. */
  PRIORITIZE_COST,
}
