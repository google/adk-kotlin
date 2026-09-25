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

/** Metadata about the retrieval step performed for a grounded response. */
@Serializable
data class RetrievalMetadata(
  /** Score in [0, 1] indicating how likely Google Search could help answer the prompt. */
  @JsonNames("google_search_dynamic_retrieval_score")
  val googleSearchDynamicRetrievalScore: Float? = null
)
