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
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.LlmConstants
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.HttpOptions
import com.google.genai.kotlin.types.HttpRetryOptions
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of [Model] that interacts with Google Gemini models using the GenAI SDK.
 *
 * This class provides functionality to generate content from Gemini models, supporting both unary
 * and streaming responses. It can be configured to use either a Google AI API key or Vertex AI
 * credentials for authentication.
 *
 * GenAI SDK based [Gemini] currently prevents usage of `API_KEY` and `GoogleCredentials` on
 * Android. Use Firebase AI instead.
 */
class Gemini
internal constructor(
  internal val client: Client,
  override val name: String,
  private val models: GeminiModels,
) : Model {

  /**
   * Creates a [Gemini] from a preconfigured GenAI SDK [Client], for callers that need a custom
   * transport or auth.
   *
   * @param client The [Client] instance from the GenAI SDK used for making API calls.
   * @param name The name of the specific Gemini model to use (e.g.,
   *   "gemini-3.1-flash-lite-preview").
   */
  constructor(client: Client, name: String) : this(client, name, RealGeminiModels(client.models))

  /**
   * Wrapper around the GenAI SDK's generate calls, expressed in ADK types, to allow mocking in
   * tests. Implementations translate to and from the SDK, keeping the SDK off this interface.
   */
  internal interface GeminiModels {
    fun generateContentStream(
      model: String,
      contents: List<Content>,
      config: GenerateContentConfig,
    ): Flow<GenerateContentResponse>

    suspend fun generateContent(
      model: String,
      contents: List<Content>,
      config: GenerateContentConfig,
    ): GenerateContentResponse
  }

  internal class RealGeminiModels(private val delegate: com.google.genai.kotlin.Models) :
    GeminiModels {
    override fun generateContentStream(
      model: String,
      contents: List<Content>,
      config: GenerateContentConfig,
    ): Flow<GenerateContentResponse> =
      delegate
        .generateContentStream(model, contents.map { it.toGenaiSdk() }, config.toGenaiSdk())
        .map { it.fromGenaiSdk() }

    override suspend fun generateContent(
      model: String,
      contents: List<Content>,
      config: GenerateContentConfig,
    ): GenerateContentResponse =
      delegate
        .generateContent(model, contents.map { it.toGenaiSdk() }, config.toGenaiSdk())
        .fromGenaiSdk()
  }

  /**
   * Creates a [Gemini] instance using a Google AI API key for authentication. Its client retries
   * transient failures with the GenAI SDK's default retry options; pass your own [Client] for other
   * retry settings.
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
  ) : this(Client(apiKey = apiKey, httpOptions = adkHttpOptions()), name)

  /**
   * Creates a [Gemini] instance using Vertex AI credentials for authentication. Its client retries
   * transient failures with the GenAI SDK's default retry options; pass your own [Client] for other
   * retry settings.
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
      httpOptions = adkHttpOptions(),
    ),
    name,
  )

  /**
   * Test-only constructor that targets [baseUrl] (e.g. a local server) with the same HTTP options
   * as the public [apiKey] constructor, so tests can check the headers and retries on the wire.
   */
  internal constructor(
    name: String,
    apiKey: String?,
    baseUrl: String,
  ) : this(Client(apiKey = apiKey, httpOptions = adkHttpOptions(baseUrl)), name)

  @OptIn(FrameworkInternalApi::class)
  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val preparedRequest = request.prepareGenerateContentRequest(!client.enterprise)

    // The manager may rewrite the request to use a cache and returns metadata for the responses.
    val cacheManager =
      preparedRequest.cacheConfig?.let {
        GeminiContextCacheManager(name, GenaiCacheClient(client.caches), cacheScopeOf(client))
      }
    val cacheResult = cacheManager?.handleContextCaching(preparedRequest)
    val finalRequest = cacheResult?.request ?: preparedRequest
    val cacheMetadata = cacheResult?.cacheMetadata

    logger.debug { "LLM Request:\n${Json.toJsonString(buildLoggingRequestMap(finalRequest))}" }

    try {
      // Loops so ADK can resume a long generation when the model pauses and returns a token.
      val continuation = Continuation(finalRequest.contents, finalRequest.config)
      var contents = finalRequest.contents
      var config = finalRequest.config
      if (stream) {
        val aggregator = StreamingResponseAggregator()

        while (true) {
          val output = StreamedOutput(continuation)
          models.generateContentStream(name, contents, config).collect { response ->
            logger.debug {
              "LLM Streaming Response chunk: ${response.candidates.size} candidates, " +
                "finishReason=${response.candidates.firstOrNull()?.finishReason}"
            }
            val chunk = output.record(response)
            emit(aggregator.processResponse(LlmResponse.from(chunk)))
          }
          val next = continuation.advance(output.token, output.parts, output.usage) ?: break
          contents = next.contents
          config = finalRequest.config.copy(continuationToken = next.token)
        }

        // Emit the aggregated response, with usage summed over all requests and any cache metadata.
        aggregator.aggregate()?.let {
          emit(it.copy(usageMetadata = continuation.usage, cacheMetadata = cacheMetadata))
        }
      } else {
        var llmResponse: LlmResponse
        while (true) {
          val response = models.generateContent(name, contents, config)
          logger.debug {
            "LLM Response: ${response.candidates.size} candidates, " +
              "finishReason=${response.candidates.firstOrNull()?.finishReason}"
          }
          llmResponse = LlmResponse.from(response)
          val next =
            continuation.advance(
              continuation.resumeToken(response),
              llmResponse.content?.parts.orEmpty(),
              llmResponse.usageMetadata,
            ) ?: break
          contents = next.contents
          config = finalRequest.config.copy(continuationToken = next.token)
        }
        emit(continuation.complete(llmResponse).copy(cacheMetadata = cacheMetadata))
      }
    } catch (e: ClientException) {
      // Enhance a quota error with a pointer to the mitigation guidance, matching Python ADK.
      if (e.code == 429) throw ResourceExhaustedException(e) else throw e
    }
  }

  private fun buildLoggingRequestMap(request: LlmRequest): Map<String, Any?> = buildMap {
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
                  // The file URI can carry sensitive identifiers, so only the MIME type is logged.
                  put("file_data", "mime_type=${it.mimeType}")
                }
                part.functionCall?.let { put("function_call", it.name) }
                part.functionResponse?.let { put("function_response", it.name) }
              }
            },
        )
      },
    )
    put(LlmConstants.KEY_CONFIG, buildLoggingConfigMap(request.config))
  }

  /**
   * Builds a redacted view of the request config for logging. The system instruction can carry
   * sensitive data (e.g. injected session state), so only its presence is logged, never its
   * content.
   */
  private fun buildLoggingConfigMap(config: GenerateContentConfig): Map<String, Any?> = buildMap {
    put("has_system_instruction", config.systemInstruction != null)
    config.temperature?.let { put("temperature", it) }
    config.topP?.let { put("top_p", it) }
    config.topK?.let { put("top_k", it) }
    config.maxOutputTokens?.let { put("max_output_tokens", it) }
    config.responseMimeType?.let { put("response_mime_type", it) }
    config.tools?.let { tools ->
      put("tools", tools.flatMap { it.functionDeclarations ?: emptyList() }.map { it.name })
    }
  }

  companion object {
    // Usage-tracking headers shared across ADK SDKs: "google-adk/<v> gl-<lang>/<ver>".
    private val TRACKING_HEADERS = run {
      val frameworkLabel = "google-adk/$VERSION"
      val languageLabel = "gl-kotlin/${KotlinVersion.CURRENT}"
      val versionHeaderValue = "$frameworkLabel $languageLabel"
      mapOf("x-goog-api-client" to versionHeaderValue, "user-agent" to versionHeaderValue)
    }

    // The SDK makes one attempt unless retryOptions is set; HttpRetryOptions() uses its defaults.
    private fun adkHttpOptions(baseUrl: String? = null) =
      HttpOptions(baseUrl = baseUrl, headers = TRACKING_HEADERS, retryOptions = HttpRetryOptions())

    private val logger = LoggerFactory.getLogger(Gemini::class)
  }
}

private val RESOURCE_EXHAUSTED_FIX =
  """
  On how to mitigate this issue, please refer to:

  https://google.github.io/adk-docs/agents/models/google-gemini/#error-code-429-resource_exhausted
  """
    .trimIndent()

/**
 * A quota (429) error from the backend, enhanced with a pointer to the mitigation guidance. Keeps
 * the original error's code so callers that key off it still work, and chains it as the cause.
 */
private class ResourceExhaustedException(source: ClientException) :
  GenAiApiException(source.code, source.status, "") {
  override val message: String = "$RESOURCE_EXHAUSTED_FIX\n\n${source.message}"
  override val cause: Throwable = source
}

/**
 * The backend namespace that owns explicit cache resources, folded into the cache fingerprint so a
 * cache is never reused across backends or projects. Includes the backend type and, for the
 * enterprise backend, the project and location. The server base URL is not included because the SDK
 * [Client] does not expose it.
 */
internal fun cacheScopeOf(client: Client): Map<String, String> = buildMap {
  this["backend"] = if (client.enterprise) "vertex" else "gemini"
  if (client.enterprise) {
    client.project?.let { this["project"] = it }
    client.location?.let { this["location"] = it }
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
  return if (last().hasUserRole()) {
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
