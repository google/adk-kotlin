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

/** Metadata returned to client when grounding is enabled. */
@Serializable
data class GroundingMetadata(
  /** The image search queries used to retrieve grounding sources. */
  @JsonNames("image_search_queries") val imageSearchQueries: List<String>? = null,
  /** The cited source chunks that ground the response. */
  @JsonNames("grounding_chunks") val groundingChunks: List<GroundingChunk>? = null,
  /** Maps response segments to the grounding chunks that support them. */
  @JsonNames("grounding_supports") val groundingSupports: List<GroundingSupport>? = null,
  /** The web search queries used to retrieve grounding sources. */
  @JsonNames("web_search_queries") val webSearchQueries: List<String>? = null,
  /** The retrieval queries used to retrieve grounding sources. */
  @JsonNames("retrieval_queries") val retrievalQueries: List<String>? = null,
  /** The Google Search entry point for rendering search suggestions. */
  @JsonNames("search_entry_point") val searchEntryPoint: SearchEntryPoint? = null,
  /** Metadata about the retrieval step. */
  @JsonNames("retrieval_metadata") val retrievalMetadata: RetrievalMetadata? = null,
)
