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
import kotlinx.serialization.json.JsonNames

/** The media resolution applied to a [Part]'s media input. */
@Serializable
data class PartMediaResolution(
  /** The tokenization quality used for the media. */
  val level: PartMediaResolutionLevel? = null,
  /** The required sequence length for media tokenization. */
  @JsonNames("num_tokens") val numTokens: Int? = null,
)
