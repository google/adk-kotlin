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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.gcp.GoogleApiClient
import com.google.adk.kt.memory.VertexAiRagClient
import com.google.adk.kt.memory.dto.RagQueryDto
import com.google.adk.kt.memory.dto.RagResourceDto
import com.google.adk.kt.memory.dto.RetrieveContextsRequestDto
import com.google.adk.kt.memory.dto.VertexRagStoreDto
import com.google.adk.kt.memory.normalizeRagCorpusName
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlin.jvm.JvmStatic

/**
 * A client-side retrieval tool that fetches context from a Vertex AI RAG corpus.
 *
 * Unlike [VertexAiRagRetrieval], which grounds the model natively via `vertexRagStore` and runs no
 * local code, this tool performs the retrieval itself: [run] queries the Vertex AI RAG service
 * through a [VertexAiRagClient] and returns the matching context texts, so it works on any model as
 * an ordinary function tool. The corpus and [vectorDistanceThreshold] are fixed per instance; the
 * model supplies `query` and an optional `similarityTopK` per call.
 */
class VertexAiRagClientTool
private constructor(
  name: String,
  description: String,
  private val client: VertexAiRagClient,
  private val corpusName: String,
  private val vectorDistanceThreshold: Double?,
  private val ownedHttpClient: HttpClient?,
) : BaseRetrievalTool(name = name, description = description) {

  /**
   * Creates a tool that retrieves from the RAG corpus [ragCorpus] in [project] and [location].
   *
   * @param name The tool name exposed to the model.
   * @param description The tool description exposed to the model.
   * @param project The Google Cloud project. Required and read only from here, never the
   *   environment.
   * @param location The Google Cloud location. Required and read only from here, never the
   *   environment. The special value `"global"` selects the global endpoint.
   * @param ragCorpus The bare corpus id (e.g. `my-corpus`). It is expanded to
   *   `projects/{project}/locations/{location}/ragCorpora/{id}`; a full resource name is rejected.
   * @param vectorDistanceThreshold Only return contexts with a vector distance below this
   *   threshold, or `null` to use the service default.
   * @param credentials Credentials for the Vertex AI API; defaults to application-default
   *   credentials scoped for Google Cloud Platform.
   * @param httpClient The underlying ktor [HttpClient]. The tool takes ownership and closes it when
   *   the tool is closed, so pass a client dedicated to this tool.
   */
  constructor(
    name: String,
    description: String,
    project: String,
    location: String,
    ragCorpus: String,
    vectorDistanceThreshold: Double? = null,
    credentials: GoogleCredentials = GoogleApiClient.defaultCredentials(),
    httpClient: HttpClient = HttpClient(Java),
  ) : this(
    name = name,
    description = description,
    client = VertexAiRagClient(GoogleApiClient(httpClient, credentials), project, location),
    corpusName = normalizeRagCorpusName(ragCorpus, project, location),
    vectorDistanceThreshold = vectorDistanceThreshold,
    ownedHttpClient = httpClient,
  )

  /** Test seam: the caller supplies the [client], so the tool owns no [HttpClient] to close. */
  internal constructor(
    name: String,
    description: String,
    client: VertexAiRagClient,
    corpusName: String,
    vectorDistanceThreshold: Double?,
  ) : this(
    name = name,
    description = description,
    client = client,
    corpusName = corpusName,
    vectorDistanceThreshold = vectorDistanceThreshold,
    ownedHttpClient = null,
  )

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        Schema(
          type = Type.OBJECT,
          properties =
            mapOf(
              "query" to Schema(type = Type.STRING, description = "The query to retrieve."),
              "similarityTopK" to
                Schema(
                  type = Type.INTEGER,
                  description = "Maximum number of contexts to retrieve for this query.",
                ),
            ),
          required = listOf("query"),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val query = args["query"] as? String
    if (query.isNullOrBlank()) {
      return mapOf("error" to "Missing 'query' parameter.", "error_code" to "INVALID_ARGUMENTS")
    }
    val similarityTopK = (args["similarityTopK"] as? Number)?.toInt()

    val request =
      RetrieveContextsRequestDto(
        vertexRagStore =
          VertexRagStoreDto(
            ragResources = listOf(RagResourceDto(ragCorpus = corpusName)),
            vectorDistanceThreshold = vectorDistanceThreshold,
          ),
        query = RagQueryDto(text = query, similarityTopK = similarityTopK),
      )

    val texts =
      client.retrieveContexts(request).getOrThrow()?.contexts?.contexts.orEmpty().mapNotNull {
        it.text
      }
    return texts.ifEmpty { "No matching result found for corpus: $corpusName" }
  }

  override fun close() {
    ownedHttpClient?.close()
  }

  /**
   * Fluent builder for [VertexAiRagClientTool], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var description: String? = null
    private var project: String? = null
    private var location: String? = null
    private var ragCorpus: String? = null
    private var vectorDistanceThreshold: Double? = null
    private var credentials: GoogleCredentials? = null
    private var httpClient: HttpClient? = null

    fun name(name: String): Builder = apply { this.name = name }

    fun description(description: String): Builder = apply { this.description = description }

    fun project(project: String): Builder = apply { this.project = project }

    fun location(location: String): Builder = apply { this.location = location }

    fun ragCorpus(ragCorpus: String): Builder = apply { this.ragCorpus = ragCorpus }

    fun vectorDistanceThreshold(vectorDistanceThreshold: Double?): Builder = apply {
      this.vectorDistanceThreshold = vectorDistanceThreshold
    }

    fun credentials(credentials: GoogleCredentials): Builder = apply {
      this.credentials = credentials
    }

    fun httpClient(httpClient: HttpClient): Builder = apply { this.httpClient = httpClient }

    fun build(): VertexAiRagClientTool =
      VertexAiRagClientTool(
        name = checkNotNull(name) { "VertexAiRagClientTool requires name." },
        description = checkNotNull(description) { "VertexAiRagClientTool requires description." },
        project = checkNotNull(project) { "VertexAiRagClientTool requires project." },
        location = checkNotNull(location) { "VertexAiRagClientTool requires location." },
        ragCorpus = checkNotNull(ragCorpus) { "VertexAiRagClientTool requires ragCorpus." },
        vectorDistanceThreshold = vectorDistanceThreshold,
        credentials = credentials ?: GoogleApiClient.defaultCredentials(),
        httpClient = httpClient ?: HttpClient(Java),
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
