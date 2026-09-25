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

/** Maps a [Segment] of the response to the grounding chunks that support it. */
@Serializable
data class GroundingSupport(
  /** The segment of content this support applies to. */
  val segment: Segment? = null,
  /** Indices into [GroundingMetadata.groundingChunks] for the chunks supporting this segment. */
  @JsonNames("grounding_chunk_indices") val groundingChunkIndices: List<Int>? = null,
  /** Confidence scores (0-1) for the supporting chunks, parallel to [groundingChunkIndices]. */
  @JsonNames("confidence_scores") val confidenceScores: List<Float>? = null,
)
