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

package com.google.adk.kt.memory.dto

import kotlinx.serialization.Serializable

/**
 * Wire model for `RetrieveMemoriesRequest` (the `memories:retrieve` method). `parent` is bound to
 * the URL path; [scope] and [similaritySearchParams] travel in the body.
 */
@Serializable
internal data class RetrieveMemoriesRequestDto(
  val scope: Map<String, String>,
  val similaritySearchParams: SimilaritySearchParamsDto? = null,
)

/** Semantic-similarity retrieval parameters. */
@Serializable
internal data class SimilaritySearchParamsDto(val searchQuery: String, val topK: Int? = null)

/** Wire model for `RetrieveMemoriesResponse`. */
@Serializable
internal data class RetrieveMemoriesResponseDto(
  val retrievedMemories: List<RetrievedMemoryDto> = emptyList(),
  val nextPageToken: String? = null,
)

@Serializable
internal data class RetrievedMemoryDto(val memory: MemoryDto? = null, val distance: Double? = null)
