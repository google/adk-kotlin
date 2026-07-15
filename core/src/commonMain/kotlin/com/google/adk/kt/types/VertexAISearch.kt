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
 * Retrieve from Vertex AI Search datastore or engine for grounding. datastore and engine are
 * mutually exclusive.
 *
 * @property dataStoreSpecs Specifications that define the specific DataStores to be searched, along
 *   with configurations for those data stores. This is only considered for Engines with multiple
 *   data stores. It should only be set if engine is used.
 * @property dataStore Optional. Fully-qualified Vertex AI Search data store resource ID. Format:
 *   `projects/{project}/locations/{location}/collections/{collection}/dataStores/{dataStore}`
 * @property engine Optional. Fully-qualified Vertex AI Search engine resource ID. Format:
 *   `projects/{project}/locations/{location}/collections/{collection}/engines/{engine}`
 * @property filter Optional. Filter strings to be passed to the search API.
 * @property maxResults Optional. Number of search results to return per query. The default value is
 *     10. The maximum allowed value is 10.
 */
@Serializable
data class VertexAISearch(
  val dataStoreSpecs: List<VertexAISearchDataStoreSpec>? = null,
  val datastore: String? = null,
  val engine: String? = null,
  val filter: String? = null,
  val maxResults: Int? = null,
)
