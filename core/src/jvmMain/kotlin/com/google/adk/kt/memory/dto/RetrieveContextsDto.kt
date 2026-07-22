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
 * Wire-level representation of `RetrieveContextsRequest` from `vertex_rag_service.proto`.
 *
 * The `parent` field (`projects/{p}/locations/{l}`) is bound to the URL path, so only
 * [vertexRagStore] and [query] travel in the request body.
 */
@Serializable
internal data class RetrieveContextsRequestDto(
  val vertexRagStore: VertexRagStoreDto,
  val query: RagQueryDto,
)

/** Wire-level `RetrieveContextsRequest.VertexRagStore`: the RAG data source to retrieve from. */
@Serializable
internal data class VertexRagStoreDto(
  val ragResources: List<RagResourceDto>? = null,
  // Deprecated in the proto in favor of `ragRetrievalConfig`, but still honored and kept here for
  // parity with the Python/Java ADK RAG reference implementations.
  val vectorDistanceThreshold: Double? = null,
)

/** Wire-level `RetrieveContextsRequest.VertexRagStore.RagResource`: a corpus (optionally files). */
@Serializable
internal data class RagResourceDto(
  val ragCorpus: String? = null,
  val ragFileIds: List<String>? = null,
)

/** Wire-level `RagQuery`: the text query and how many contexts to retrieve. */
@Serializable
internal data class RagQueryDto(
  val text: String? = null,
  // Deprecated in the proto in favor of `ragRetrievalConfig.topK`, but still honored and kept here
  // for parity with the Python/Java ADK RAG reference implementations.
  val similarityTopK: Int? = null,
)

/** Wire-level `RetrieveContextsResponse`. */
@Serializable internal data class RetrieveContextsResponseDto(val contexts: RagContextsDto? = null)

/** Wire-level `RagContexts`: the list of relevant contexts for a query. */
@Serializable internal data class RagContextsDto(val contexts: List<RagContextDto> = emptyList())

/**
 * Wire-level `RagContexts.Context`.
 *
 * [sourceDisplayName] carries the encoded `app/user/session` scope key set at upload time (see
 * `VertexAiRagMemoryService`), and [text] holds the newline-delimited JSON event records.
 */
@Serializable
internal data class RagContextDto(
  val text: String? = null,
  val sourceUri: String? = null,
  val sourceDisplayName: String? = null,
  val score: Double? = null,
)
