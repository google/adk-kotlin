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
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.LlmConstants
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SpeechConfig
import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.kotlin.Client
import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.GenAiApiException
import com.google.genai.kotlin.types.HttpOptions
import com.google.genai.kotlin.types.HttpRetryOptions
import com.google.genai.kotlin.types.LiveConnectConfig as GenAiLiveConnectConfig
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
  /**
   * The speech config for live sessions opened by this model. When set, it replaces the request's
   * own speech config at connect, as in ADK Python.
   */
  val speechConfig: SpeechConfig? = null,
) : Model {

  /**
   * How live sessions are opened. A settable property, not a constructor parameter, because tests
   * construct [Gemini] through its public constructors and swap this afterward.
   */
  internal var live: GeminiLive = RealGeminiLive(client.live)

  /**
   * Wrapper around the GenAI SDK's live client, serving the same purpose as [GeminiModels]: the
   * SDK's `LiveSession` is final and only obtainable by opening a real websocket, so a test that
   * wants to see what a connect was configured with needs a seam here.
   */
  internal interface GeminiLive {
    suspend fun connect(model: String, config: GenAiLiveConnectConfig): LiveSessionHandle
  }

  /** The real [GeminiLive], opening an actual session. */
  private class RealGeminiLive(private val delegate: com.google.genai.kotlin.Live) : GeminiLive {
    override suspend fun connect(model: String, config: GenAiLiveConnectConfig): LiveSessionHandle =
      SdkLiveSessionHandle(delegate.connect(model, config))
  }

  /**
   * Creates a [Gemini] from a preconfigured GenAI SDK [Client], for callers that need a custom
   * transport or auth.
   *
   * @param client The [Client] instance from the GenAI SDK used for making API calls.
   * @param name The name of the specific Gemini model to use (e.g.,
   *   "gemini-3.1-flash-lite-preview").
   * @param speechConfig The speech config for live sessions; see [Gemini.speechConfig].
   */
  @JvmOverloads
  constructor(
    client: Client,
    name: String,
    speechConfig: SpeechConfig? = null,
  ) : this(client, name, RealGeminiModels(client.models), speechConfig)

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
   * @param speechConfig The speech config for live sessions; see [Gemini.speechConfig].
   */
  @JvmOverloads
  constructor(
    name: String,
    apiKey: String? = null,
    speechConfig: SpeechConfig? = null,
  ) : this(Client(apiKey = apiKey, httpOptions = adkHttpOptions()), name, speechConfig)

  /**
   * Creates a [Gemini] instance using Vertex AI credentials for authentication. Its client retries
   * transient failures with the GenAI SDK's default retry options; pass your own [Client] for other
   * retry settings.
   *
   * @param name The name of the specific Gemini model to use (e.g.,
   *   "gemini-3.1-flash-lite-preview").
   * @param vertexCredentials The Vertex AI credentials to use.
   * @param speechConfig The speech config for live sessions; see [Gemini.speechConfig].
   */
  @JvmOverloads
  constructor(
    name: String,
    vertexCredentials: VertexCredentials,
    speechConfig: SpeechConfig? = null,
  ) : this(
    Client(
      project = vertexCredentials.project,
      location = vertexCredentials.location,
      credentials = vertexCredentials.credentials?.toGenaiSdk(),
      enterprise = true,
      httpOptions = adkHttpOptions(),
    ),
    name,
    speechConfig,
  )

  /**
   * Opens a live session configured by [LlmRequest.liveConnectConfig] (using `request.model` when
   * set), returning once the server confirms setup and throwing if the server refuses it. If the
   * caller is cancelled while the session opens, the session is closed in the background.
   * Reconnection is not handled here, though a Vertex AI resume with `transparent` unset asks for
   * transparent resumption.
   */
  @ExperimentalLiveApi
  override suspend fun connect(request: LlmRequest): LiveConnection {
    val modelName = request.model?.name ?: name
    logger.debug { "Opening live connection to $modelName." }
    val config =
      request.liveConnectConfig
        .let { live -> speechConfig?.let { live.copy(speechConfig = it) } ?: live }
        .let { live -> if (client.enterprise) live.resumingTransparently() else live }
        .carryingAgentConfig(request.config)
        .toGenaiSdk()
    val collector = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
    val handle = openHandle(modelName, config, collector)
    val connection =
      GeminiLiveConnection(handle, modelVersion = modelName, pumpDispatcher = collector)
    // Return only once the server confirms setup; a refused setup throws and closes the socket.
    connection.awaitSetup()
    return connection
  }

  /**
   * Opens the live session off the caller's cancellation, so a cancelled open closes the socket the
   * SDK would otherwise orphan instead of leaking it.
   */
  private suspend fun openHandle(
    modelName: String,
    config: GenAiLiveConnectConfig,
    collector: CoroutineContext,
  ): LiveSessionHandle {
    val opener = live
    @Suppress("UnsafeCoroutineCrossing") // Only locals the caller hands over cross the scope.
    val opening = CoroutineScope(collector).async { opener.connect(modelName, config) }
    return try {
      // A completed await() skips the cancel check, so check here and let the closer run.
      opening.await().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
      // The open's own cancellation must not cancel an active caller; wrap it as the pump does.
      if (currentCoroutineContext().isActive) {
        throw IllegalStateException("The live session failed to open.", e)
      }
      // Closed once it opens; cancelling the open instead is what loses the socket.
      @Suppress("UnsafeCoroutineCrossing") // Same reason as above.
      val unusedCloser =
        CoroutineScope(collector).launch {
          runCatching { GeminiLiveConnection.closeOrDrop(opening.await()) }
        }
      throw e
    }
  }

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
      val continuation = Continuation(finalRequest.contents)
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
              response.resumeToken(),
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

  /**
   * Fluent builder for [Gemini], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var client: Client? = null
    private var apiKey: String? = null
    private var vertexCredentials: VertexCredentials? = null
    private var speechConfig: SpeechConfig? = null

    fun name(name: String): Builder = apply { this.name = name }

    /** Sets the SDK client; set at most one of this, [apiKey], and [vertexCredentials]. */
    fun client(client: Client?): Builder = apply { this.client = client }

    /** Sets the Google AI API key; set at most one of this, [client], and [vertexCredentials]. */
    fun apiKey(apiKey: String?): Builder = apply { this.apiKey = apiKey }

    /** Sets the Vertex AI credentials; set at most one of this, [client], and [apiKey]. */
    fun vertexCredentials(vertexCredentials: VertexCredentials?): Builder = apply {
      this.vertexCredentials = vertexCredentials
    }

    fun speechConfig(speechConfig: SpeechConfig?): Builder = apply {
      this.speechConfig = speechConfig
    }

    /**
     * Builds the [Gemini] model, using a Google AI API key unless a client or Vertex AI credentials
     * are set.
     */
    fun build(): Gemini {
      val name = checkNotNull(name) { "Gemini.Builder requires name to be set." }
      val client = client
      val apiKey = apiKey
      val vertexCredentials = vertexCredentials
      check(listOfNotNull(client, apiKey, vertexCredentials).size <= 1) {
        "Gemini.Builder accepts at most one of client, apiKey or vertexCredentials."
      }
      return when {
        client != null -> Gemini(client, name, speechConfig)
        vertexCredentials != null -> Gemini(name, vertexCredentials, speechConfig)
        else -> Gemini(name, apiKey, speechConfig)
      }
    }
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    /**
     * Test-only factory that targets [baseUrl] (for example a local server) with the same HTTP
     * options as the public `apiKey` constructor. It is a function rather than an internal
     * constructor because Java sees internal constructors, and a third `String` parameter would
     * make `new Gemini("m", "k", null)` ambiguous.
     */
    internal fun withBaseUrl(name: String, apiKey: String?, baseUrl: String): Gemini =
      Gemini(Client(apiKey = apiKey, httpOptions = adkHttpOptions(baseUrl)), name)

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

/**
 * On a resume that leaves `transparent` unset, asks Vertex AI for transparent resumption, as ADK
 * Python does, so the server reports the last client message it consumed.
 */
internal fun LiveConnectConfig.resumingTransparently(): LiveConnectConfig {
  val resumption = sessionResumption ?: return this
  if (resumption.handle == null || resumption.transparent != null) return this
  return copy(sessionResumption = resumption.copy(transparent = true))
}

/**
 * Returns this live config with the agent's own configuration folded in.
 *
 * The two halves of a request are assembled in different places: `LlmRequest.liveConnectConfig`
 * carries what the run config chose, while the agent's instruction and tools live on
 * `LlmRequest.config`; without this fold a live agent has no persona and the model is never told
 * the tools exist.
 */
internal fun LiveConnectConfig.carryingAgentConfig(
  config: GenerateContentConfig
): LiveConnectConfig =
  copy(
    // Instruction/tools replace the live config's; absent one omitted, not sent empty (1007).
    systemInstruction = config.systemInstruction?.copy(role = Role.SYSTEM),
    tools = config.tools,
    // The agent's thinking wins; the live config's own safety wins over the agent's.
    thinkingConfig = config.thinkingConfig ?: thinkingConfig,
    safetySettings = safetySettings ?: config.safetySettings,
  )
