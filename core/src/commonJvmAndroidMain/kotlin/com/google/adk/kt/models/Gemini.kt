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
package com.google.adk.kt.models

import com.google.adk.kt.VERSION
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.LlmConstants
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toGenaiSdk
import com.google.common.annotations.VisibleForTesting
import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Implementation of [Model] that interacts with Google Gemini models using the GenAI SDK.
 *
 * This class provides functionality to generate content from Gemini models, supporting both unary
 * and streaming responses. It can be configured to use either a Google AI API key or Vertex AI
 * credentials for authentication.
 *
 * @param client The [Client] instance from the GenAI SDK used for making API calls.
 * @param name The name of the specific Gemini model to use (e.g., "gemini-3.1-flash-lite-preview").
 */
class Gemini(
  private val client: Client,
  override val name: String,
  private val models: GeminiModels = RealGeminiModels(client.models),
) : Model {

  /** Internal wrapper around GenAI SDK Models to allow mocking in tests. */
  @VisibleForTesting
  interface GeminiModels {
    fun generateContentStream(
      model: String,
      contents: List<com.google.genai.types.Content>,
      config: com.google.genai.types.GenerateContentConfig,
    ): Iterable<com.google.genai.types.GenerateContentResponse>

    fun generateContent(
      model: String,
      contents: List<com.google.genai.types.Content>,
      config: com.google.genai.types.GenerateContentConfig,
    ): com.google.genai.types.GenerateContentResponse
  }

  @VisibleForTesting
  class RealGeminiModels(private val delegate: com.google.genai.Models) : GeminiModels {
    override fun generateContentStream(
      model: String,
      contents: List<com.google.genai.types.Content>,
      config: com.google.genai.types.GenerateContentConfig,
    ): Iterable<com.google.genai.types.GenerateContentResponse> =
      delegate.generateContentStream(model, contents, config)

    override fun generateContent(
      model: String,
      contents: List<com.google.genai.types.Content>,
      config: com.google.genai.types.GenerateContentConfig,
    ): com.google.genai.types.GenerateContentResponse =
      delegate.generateContent(model, contents, config)
  }

  /**
   * Creates a [Gemini] instance using a Google AI API key for authentication.
   *
   * @param name The name of the specific Gemini model to use (e.g.,
   *   "gemini-3.1-flash-lite-preview").
   * @param apiKey The Google AI API key. If not provided, falls back to GOOGLE_API_KEY or
   *   GEMINI_API_KEY environment variables on GenAI SDK level.
   */
  @JvmOverloads
  constructor(
    name: String,
    apiKey: String? = null,
  ) : this(
    Client.builder()
      .apply {
        apiKey?.let { apiKey(it) }
        httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
      }
      .build(),
    name,
  )

  /**
   * Creates a [Gemini] instance using Vertex AI credentials for authentication.
   *
   * @param name The name of the specific Gemini model to use (e.g.,
   *   "gemini-3.1-flash-lite-preview").
   * @param vertexCredentials The Vertex AI credentials to use.
   */
  constructor(
    name: String,
    vertexCredentials: VertexCredentials,
  ) : this(
    Client.builder()
      .apply {
        vertexAI(true)
        httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
        vertexCredentials.project?.let { project(it) }
        vertexCredentials.location?.let { location(it) }
        vertexCredentials.credentials?.let { credentials(it) }
      }
      .build(),
    name,
  )

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val preparedRequest = request.prepareGenerateContentRequest(!client.vertexAI())

    // Handle context caching when configured. The manager may rewrite the request to reference an
    // existing cache (dropping the cached prefix) and returns the metadata to attach to responses.
    val cacheManager =
      preparedRequest.cacheConfig?.let {
        GeminiContextCacheManager(name, GeminiContextCacheManager.RealCacheClient(client.caches))
      }
    val cacheResult = cacheManager?.handleContextCaching(preparedRequest)
    val finalRequest = cacheResult?.request ?: preparedRequest
    val cacheMetadata = cacheResult?.cacheMetadata

    fun attachCacheMetadata(response: LlmResponse): LlmResponse {
      // A non-null cacheMetadata implies a non-null cacheManager (both come from cacheResult).
      val metadata = cacheMetadata ?: return response
      return cacheManager.populateCacheMetadataInResponse(response, metadata)
    }

    val config = finalRequest.config.toGenaiSdk()
    val contents = finalRequest.contents.map { it.toGenaiSdk() }

    logger.debug {
      "LLM Request:\n${Json.toJsonString(buildLoggingRequestMap(finalRequest, config))}"
    }

    if (stream) {
      val stream = models.generateContentStream(name, contents, config)
      val aggregator = StreamingResponseAggregator()

      for (response in stream) {
        logger.debug {
          "LLM Streaming Response chunk: ${response.candidates().map { it.size }.orElse(0)} candidates, " +
            "finishReason=${response.finishReason()}"
        }
        emit(aggregator.processResponse(response.fromGenaiSdk()))
      }

      // After stream loop ends, emit final aggregated response
      aggregator.aggregate()?.let { emit(attachCacheMetadata(it)) }
    } else {
      val response = models.generateContent(name, contents, config)
      logger.debug {
        "LLM Response: ${response.candidates().map { it.size }.orElse(0)} candidates, " +
          "finishReason=${response.finishReason()}"
      }
      val llmResponse = LlmResponse.from(response.fromGenaiSdk())
      emit(attachCacheMetadata(llmResponse))
    }
  }

  private fun buildLoggingRequestMap(
    request: LlmRequest,
    config: GenerateContentConfig?,
  ): Map<String, Any?> = buildMap {
    put(LlmConstants.KEY_MODEL, name)
    put(
      LlmConstants.KEY_CONTENTS,
      request.contents.map { content ->
        mapOf(
          "role" to content.role,
          "parts" to
            content.parts.map { part ->
              buildMap {
                part.text?.let { put("text", "${it.length} chars") }
                part.inlineData?.let {
                  put("inline_data", "${it.data?.size} bytes, mime_type=${it.mimeType}")
                }
                part.fileData?.let {
                  put("file_data", "file_uri=${it.fileUri}, mime_type=${it.mimeType}")
                }
                part.functionCall?.let { put("function_call", it.name) }
                part.functionResponse?.let { put("function_response", it.name) }
              }
            },
        )
      },
    )
    put(LlmConstants.KEY_CONFIG, config)
  }

  companion object {
    // Usage-tracking headers shared across ADK SDKs: "google-adk/<v> gl-<lang>/<ver>".
    private val TRACKING_HEADERS = run {
      val frameworkLabel = "google-adk/$VERSION"
      val languageLabel = "gl-kotlin/${KotlinVersion.CURRENT}"
      val versionHeaderValue = "$frameworkLabel $languageLabel"
      mapOf("x-goog-api-client" to versionHeaderValue, "user-agent" to versionHeaderValue)
    }

    private val logger = LoggerFactory.getLogger(Gemini::class)
  }
}

/**
 * Prepares an [LlmRequest] for the GenerateContent API.
 *
 * This method can optionally sanitize the request and ensures that the last content part is from
 * the user to prompt a model response.
 *
 * @param sanitize Whether to sanitize the request to be compatible with the Gemini API backend.
 * @return The prepared [LlmRequest].
 */
internal fun LlmRequest.prepareGenerateContentRequest(sanitize: Boolean): LlmRequest {
  val req = if (sanitize) sanitizeForGeminiApi() else this
  return req.copy(contents = req.contents.ensureModelResponse().toMutableList())
}

/**
 * Sanitizes the request to ensure it is compatible with the Gemini API backend. Required as there
 * are some parameters that if included in the request will raise a runtime error if sent to the
 * wrong backend (e.g. image names only work on Vertex AI).
 *
 * @return The sanitized request.
 */
internal fun LlmRequest.sanitizeForGeminiApi(): LlmRequest {
  // Using API key from Google AI Studio to call model doesn't support labels.
  if (contents.isEmpty()) return copy(config = config.copy(labels = null))

  return copy(
    config = config.copy(labels = null),
    contents = contents.map { it.sanitizeForGeminiApi() }.toMutableList(),
  )
}

private fun Content.sanitizeForGeminiApi(): Content =
  copy(parts = parts.map { it.sanitizeForGeminiApi() })

private fun Part.sanitizeForGeminiApi(): Part {
  // The display_name parameter for file uploads is not supported by the Gemini API,
  // so it must be removed to prevent request failures.
  val sanitizedInline = inlineData?.takeIf { it.displayName != null }?.copy(displayName = null)
  val sanitizedFile = fileData?.takeIf { it.displayName != null }?.copy(displayName = null)

  return if (sanitizedInline == null && sanitizedFile == null) {
    this
  } else {
    copy(inlineData = sanitizedInline ?: inlineData, fileData = sanitizedFile ?: fileData)
  }
}

/**
 * Ensures that the content is conducive to prompting a model response by ensuring the last content
 * part is from the user.
 */
internal fun List<Content>.ensureModelResponse(): List<Content> {
  if (isEmpty()) {
    return listOf(
      Content(
        role = Role.USER,
        parts = listOf(Part(text = "Handle the requests as specified in the System Instruction.")),
      )
    )
  }
  return if (last().role?.equals(Role.USER, ignoreCase = true) == true) {
    this
  } else {
    this +
      Content(
        role = Role.USER,
        parts =
          listOf(
            Part(
              text =
                "Continue processing previous requests as instructed. Exit or provide a summary if no more outputs are needed."
            )
          ),
      )
  }
}
