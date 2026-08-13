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
import com.google.adk.kt.types.VertexRagStore
import com.google.adk.kt.types.VertexRagStoreRagResource
import kotlin.jvm.JvmStatic

/**
 * A built-in tool that uses Vertex AI RAG (Retrieval-Augmented Generation) to retrieve data.
 *
 * The model runs retrieval internally via the Gemini-native `vertexRagStore` retrieval kind; the
 * tool performs no local execution.
 *
 * @property ragCorpora Deprecated. Use [ragResources] instead. RagCorpora resource names.
 * @property ragResources The RAG sources to retrieve from (one corpus, or files from one corpus).
 * @property similarityTopK Number of top k results to return from the selected corpora.
 * @property vectorDistanceThreshold Only return results with a vector distance below the threshold.
 */
class VertexAiRagRetrieval(
  name: String,
  description: String,
  val ragCorpora: List<String>? = null,
  val ragResources: List<VertexRagStoreRagResource>? = null,
  val similarityTopK: Int? = null,
  val vectorDistanceThreshold: Double? = null,
) : BaseTool(name = name, description = description) {

  private val vertexRagStore =
    VertexRagStore(
      ragCorpora = ragCorpora,
      ragResources = ragResources,
      similarityTopK = similarityTopK,
      vectorDistanceThreshold = vectorDistanceThreshold,
    )

  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    throw UnsupportedOperationException("VertexAiRagRetrieval does not support local execution")
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val retrievalTool = Tool(retrieval = Retrieval(vertexRagStore = vertexRagStore))
    val existingTools = llmRequest.config.tools?.toMutableList() ?: mutableListOf()
    existingTools.add(retrievalTool)
    return llmRequest.copy(config = llmRequest.config.copy(tools = existingTools))
  }

  /**
   * Fluent builder for [VertexAiRagRetrieval], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var description: String? = null
    private var ragCorpora: List<String>? = null
    private var ragResources: List<VertexRagStoreRagResource>? = null
    private var similarityTopK: Int? = null
    private var vectorDistanceThreshold: Double? = null

    fun name(name: String): Builder = apply { this.name = name }

    fun description(description: String): Builder = apply { this.description = description }

    fun ragCorpora(ragCorpora: List<String>?): Builder = apply { this.ragCorpora = ragCorpora }

    fun ragResources(ragResources: List<VertexRagStoreRagResource>?): Builder = apply {
      this.ragResources = ragResources
    }

    fun similarityTopK(similarityTopK: Int?): Builder = apply {
      this.similarityTopK = similarityTopK
    }

    fun vectorDistanceThreshold(vectorDistanceThreshold: Double?): Builder = apply {
      this.vectorDistanceThreshold = vectorDistanceThreshold
    }

    fun build(): VertexAiRagRetrieval =
      VertexAiRagRetrieval(
        name = checkNotNull(name) { "VertexAiRagRetrieval.Builder requires name to be set." },
        description =
          checkNotNull(description) {
            "VertexAiRagRetrieval.Builder requires description to be set."
          },
        ragCorpora = ragCorpora,
        ragResources = ragResources,
        similarityTopK = similarityTopK,
        vectorDistanceThreshold = vectorDistanceThreshold,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
