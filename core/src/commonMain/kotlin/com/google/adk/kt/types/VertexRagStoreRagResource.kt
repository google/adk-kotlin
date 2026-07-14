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

/**
 * The representation of a RAG source for a [VertexRagStore].
 *
 * @property ragCorpus RagCorpora resource name.
 * @property ragFileIds `rag_file_id`s, which should belong to the corpus in [ragCorpus].
 */
data class VertexRagStoreRagResource(
  val ragCorpus: String? = null,
  val ragFileIds: List<String>? = null,
)
