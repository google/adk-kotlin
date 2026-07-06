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
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.GenerateContentResponse as GenAiGenerateContentResponse
import com.google.genai.kotlin.types.HttpOptions
import kotlin.jvm.JvmOverloads
import kotlinx.atomicfu.atomic
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
  internal val client: Client,
  override val name: String,
  private val models: GeminiModels = RealGeminiModels(client.models),
) : Model {

  /** Wrapper around GenAI SDK Models to allow mocking in tests. */
  interface GeminiModels {
    fun generateContentStream(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): Flow<GenAiGenerateContentResponse>

    suspend fun generateContent(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): GenAiGenerateContentResponse
  }

  class RealGeminiModels(private val delegate: com.google.genai.kotlin.Models) : GeminiModels {
    override fun generateContentStream(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): Flow<GenAiGenerateContentResponse> = delegate.generateContentStream(model, contents, config)

    override suspend fun generateContent(
      model: String,
      contents: List<GenAiContent>,
      config: com.google.genai.kotlin.types.GenerateContentConfig,
    ): GenAiGenerateContentResponse = delegate.generateContent(model, contents, config)
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
  ) : this(Client(apiKey = apiKey, httpOptions = HttpOptions(headers = TRACKING_HEADERS)), name)

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
    Client(
      project = vertexCredentials.project,
      location = vertexCredentials.location,
      credentials = vertexCredentials.credentials?.toGenaiSdk(),
      enterprise = true,
      httpOptions = HttpOptions(headers = TRACKING_HEADERS),
    ),
    name,
  )

  /**
   * Test-only constructor that targets [baseUrl] (e.g. a local server) while still applying the
   * same ADK tracking headers the public [apiKey] constructor sets, so tests can assert those
   * headers reach the wire.
   */
  internal constructor(
    name: String,
    apiKey: String?,
    baseUrl: String,
  ) : this(
    Client(
      apiKey = apiKey,
      httpOptions = HttpOptions(baseUrl = baseUrl, headers = TRACKING_HEADERS),
    ),
    name,
  )

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val preparedRequest = request.prepareGenerateContentRequest(!client.enterprise)
    if (
      !preparedRequest.config.labels.isNullOrEmpty() &&
        labelsDropWarningLogged.compareAndSet(false, true)
    ) {
      // Reachable only on the Vertex path; the API-key path clears labels in
      // sanitizeForGeminiApi(). toGenaiSdk() then drops them (the Kotlin SDK lacks the field).
      logger.warn {
        "GenerateContentConfig.labels is set but not supported by the Kotlin GenAI SDK; " +
          "the labels will be dropped from the request."
      }
    }
    val config = preparedRequest.config.toGenaiSdk()
    val contents = preparedRequest.contents.map { it.toGenaiSdk() }

    logger.debug {
      "LLM Request:\n${Json.toJsonString(buildLoggingRequestMap(preparedRequest, config))}"
    }

    if (stream) {
      val aggregator = StreamingResponseAggregator()

      models.generateContentStream(name, contents, config).collect { response ->
        logger.debug {
          "LLM Streaming Response chunk: ${response.candidates?.size ?: 0} candidates, " +
            "finishReason=${response.candidates?.firstOrNull()?.finishReason}"
        }
        emit(aggregator.processResponse(response.fromGenaiSdk()))
      }

      // After stream loop ends, emit final aggregated response
      aggregator.aggregate()?.let { emit(it) }
    } else {
      val response = models.generateContent(name, contents, config)
      logger.debug {
        "LLM Response: ${response.candidates?.size ?: 0} candidates, " +
          "finishReason=${response.candidates?.firstOrNull()?.finishReason}"
      }
      val llmResponse = LlmResponse.from(response.fromGenaiSdk())
      emit(llmResponse)
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

    // Guards the one-time warning about dropped GenerateContentConfig.labels (see generateContent).
    private val labelsDropWarningLogged = atomic(false)
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
