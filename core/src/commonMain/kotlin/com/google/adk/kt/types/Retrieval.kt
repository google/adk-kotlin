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
 * Defines a retrieval tool that model can call to access external knowledge.
 *
 * @property vertexAiSearch Set to use data source powered by Vertex AI Search.
 * @property vertexRagStore Set to use data source powered by a Vertex RAG store.
 */
@Serializable
data class Retrieval(
  val vertexAiSearch: VertexAISearch? = null,
  val vertexRagStore: VertexRagStore? = null,
)
