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

/**
 * Retrieve from a Vertex RAG store for grounding.
 *
 * @property ragCorpora Deprecated. Use [ragResources] instead. RagCorpora resource names.
 * @property ragResources The RAG sources to retrieve from (one corpus, or files from one corpus).
 * @property similarityTopK Number of top k results to return from the selected corpora.
 * @property vectorDistanceThreshold Only return results with a vector distance below the threshold.
 */
@Serializable
data class VertexRagStore(
  val ragCorpora: List<String>? = null,
  val ragResources: List<VertexRagStoreRagResource>? = null,
  val similarityTopK: Int? = null,
  val vectorDistanceThreshold: Double? = null,
)
