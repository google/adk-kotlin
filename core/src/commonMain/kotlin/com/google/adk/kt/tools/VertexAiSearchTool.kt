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

package com.google.adk.kt.tools

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Retrieval
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.VertexAISearch
import com.google.adk.kt.types.VertexAISearchDataStoreSpec
import kotlin.jvm.JvmStatic

/**
 * A built-in tool using Vertex AI Search.
 *
 * This tool can be configured with either a [dataStoreId] (the Vertex AI search data store resource
 * ID) or a [searchEngineId] (the Vertex AI search engine resource ID).
 *
 * @property dataStoreId The Vertex AI search data store resource ID in the format of
 *   `projects/{project}/locations/{location}/collections/{collection}/dataStores/{dataStore}`.
 * @property dataStoreSpecs Specifications that define the specific DataStores to be searched. It
 *   should only be set if engine is used.
 * @property searchEngineId The Vertex AI search engine resource ID in the format of
 *   `projects/{project}/locations/{location}/collections/{collection}/engines/{engine}`.
 * @property filter The filter to apply to the search results.
 * @property maxResults The maximum number of results to return.
 * @property bypassMultiToolsLimit When true, allows this built-in tool to be used alongside other
 *   tools: it is exposed as a function tool (see [VertexAiSearchAgentTool]) since built-in tools
 *   cannot otherwise be combined with other tools in a single request.
 */
class VertexAiSearchTool(
  val dataStoreId: String? = null,
  val dataStoreSpecs: List<VertexAISearchDataStoreSpec>? = null,
  val searchEngineId: String? = null,
  val filter: String? = null,
  val maxResults: Int? = null,
  val bypassMultiToolsLimit: Boolean = false,
) : BaseTool(name = "vertex_ai_search", description = "vertex_ai_search") {

  init {
    require(dataStoreId.isNullOrEmpty() != searchEngineId.isNullOrEmpty()) {
      "One and only one of dataStoreId or searchEngineId must be provided."
    }
    if (!dataStoreSpecs.isNullOrEmpty()) {
      require(!searchEngineId.isNullOrEmpty()) {
        "searchEngineId must not be empty if dataStoreSpecs is not empty."
      }
    }
  }

  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    throw UnsupportedOperationException("VertexAiSearchTool does not support local execution")
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val vertexAiSearch =
      VertexAISearch(
        datastore = dataStoreId,
        dataStoreSpecs = dataStoreSpecs,
        engine = searchEngineId,
        filter = filter,
        maxResults = maxResults,
      )

    val retrievalTool = Tool(retrieval = Retrieval(vertexAiSearch = vertexAiSearch))

    val existingTools = llmRequest.config.tools?.toMutableList() ?: mutableListOf()
    existingTools.add(retrievalTool)
    return llmRequest.copy(config = llmRequest.config.copy(tools = existingTools))
  }

  /**
   * Fluent builder for [VertexAiSearchTool], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var dataStoreId: String? = null
    private var dataStoreSpecs: List<VertexAISearchDataStoreSpec>? = null
    private var searchEngineId: String? = null
    private var filter: String? = null
    private var maxResults: Int? = null
    private var bypassMultiToolsLimit: Boolean = false

    fun dataStoreId(dataStoreId: String?): Builder = apply { this.dataStoreId = dataStoreId }

    fun dataStoreSpecs(dataStoreSpecs: List<VertexAISearchDataStoreSpec>?): Builder = apply {
      this.dataStoreSpecs = dataStoreSpecs
    }

    fun searchEngineId(searchEngineId: String?): Builder = apply {
      this.searchEngineId = searchEngineId
    }

    fun filter(filter: String?): Builder = apply { this.filter = filter }

    fun maxResults(maxResults: Int?): Builder = apply { this.maxResults = maxResults }

    fun bypassMultiToolsLimit(bypassMultiToolsLimit: Boolean): Builder = apply {
      this.bypassMultiToolsLimit = bypassMultiToolsLimit
    }

    fun build(): VertexAiSearchTool =
      VertexAiSearchTool(
        dataStoreId = dataStoreId,
        dataStoreSpecs = dataStoreSpecs,
        searchEngineId = searchEngineId,
        filter = filter,
        maxResults = maxResults,
        bypassMultiToolsLimit = bypassMultiToolsLimit,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
