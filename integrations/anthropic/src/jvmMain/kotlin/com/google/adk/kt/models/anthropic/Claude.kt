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
package com.google.adk.kt.models.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.RateLimitException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.vertex.backends.VertexBackend
import com.google.adk.kt.VERSION
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.CompletionException
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await

private const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

/** Lets Vertex AI attribute usage to ADK, in the format the other ADK SDKs send. */
private val TRACKING_HEADER = "google-adk/$VERSION gl-kotlin/${KotlinVersion.CURRENT}"

/** The project and location inside a Vertex AI resource name. */
private val VERTEX_RESOURCE = Regex("projects/([^/]+)/locations/([^/]+)/")

private const val RATE_LIMIT_HINT =
  "Claude rate limit reached; see https://docs.anthropic.com/en/api/errors#http-errors"

/**
 * A [Model] backed by Anthropic's Claude models through the official Anthropic Java SDK.
 *
 * Supports unary and streaming generation, function (tool) calling, system instructions, extended
 * thinking, image and PDF input, structured output, prompt caching, and token usage. Thinking
 * follows `ThinkingConfig.thinkingBudget`, which must be set when thinking is configured, and
 * ignores `thinkingLevel`. Pass a configured [AnthropicClient] to choose any backend the SDK
 * supports.
 *
 * @property name The Claude model name (e.g. `claude-sonnet-4-5`).
 * @param client The Anthropic SDK client that sends the requests.
 * @param maxTokens The default `max_tokens`, used when the request config sets none.
 * @param effort How much reasoning effort Claude spends on each request; null leaves the model's
 *   default. While effort or thinking is on, temperature, topP, and topK are not sent.
 */
class Claude
@JvmOverloads
constructor(
  override val name: String,
  private val client: AnthropicClient,
  private val maxTokens: Int = DEFAULT_MAX_TOKENS,
  private val effort: OutputConfig.Effort? = null,
) : Model {

  /**
   * Creates a [Claude] model that calls the Anthropic API directly.
   *
   * @param name The Claude model name (e.g. `claude-sonnet-4-5`).
   * @param apiKey The Anthropic API key; falls back to the credentials the SDK reads from the
   *   environment, such as `ANTHROPIC_API_KEY`.
   * @param maxTokens The default `max_tokens`, used when the request config sets none.
   * @param effort How much reasoning effort Claude spends on each request; null leaves the model's
   *   default. While effort or thinking is on, temperature, topP, and topK are not sent.
   * @throws IllegalStateException If no API key is passed and the environment has no credential.
   */
  @JvmOverloads
  constructor(
    name: String,
    apiKey: String? = null,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
    effort: OutputConfig.Effort? = null,
  ) : this(name, anthropicApiClient(apiKey), maxTokens, effort)

  /**
   * Creates a [Claude] model served through Vertex AI.
   *
   * @param name The Claude model id as published on Vertex AI (e.g. `claude-haiku-4-5`), or its
   *   full resource name, which also names the project and location.
   * @param vertexCredentials The Vertex AI project and location, falling back to those in a
   *   resource [name] and then to the `GOOGLE_CLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION`
   *   environment variables, and credentials that default to Application Default Credentials.
   * @param maxTokens The default `max_tokens`, used when the request config sets none.
   * @param effort How much reasoning effort Claude spends on each request; null leaves the model's
   *   default. While effort or thinking is on, temperature, topP, and topK are not sent.
   * @throws IllegalArgumentException If no project or location is set.
   * @throws IllegalStateException If no credentials are set and Application Default Credentials are
   *   unavailable.
   */
  @JvmOverloads
  constructor(
    name: String,
    vertexCredentials: VertexCredentials,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
    effort: OutputConfig.Effort? = null,
  ) : this(name, vertexClient(vertexCredentials, modelName = name), maxTokens, effort)

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
    generate(request, stream).catch { error ->
      if (error is RateLimitException) logger.warn { RATE_LIMIT_HINT }
      throw error
    }

  private fun generate(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    val params = request.toMessageCreateParams(name, maxTokens, effort)
    logger.debug { "Claude request: ${request.contents.size} contents, stream=$stream" }
    val messages = client.async().messages()
    if (stream) {
      val accumulator = MessageAccumulator.create()
      var modelVersion: String? = null
      messages.createStreaming(params).asFlow().collect { event ->
        accumulator.accumulate(event)
        event.messageStart().getOrNull()?.let { modelVersion = it.message().model().asString() }
        event.textDelta()?.let { emit(partial(Part(text = it), modelVersion)) }
        event.thinkingDelta()?.let { emit(partial(Part(text = it, thought = true), modelVersion)) }
      }
      // The accumulated message is the whole turn, with blocks in the order the model sent them.
      emit(accumulator.message().toLlmResponse())
    } else {
      emit(messages.create(params).await().toLlmResponse())
    }
  }

  companion object {
    /**
     * The `max_tokens` sent when the request config sets none, since the Messages API needs one.
     */
    const val DEFAULT_MAX_TOKENS: Int = 8192

    private val logger = LoggerFactory.getLogger(Claude::class)
  }
}

/**
 * Builds an SDK client for the Anthropic API, reading credentials from the environment when
 * [apiKey] is null.
 */
private fun anthropicApiClient(apiKey: String?): AnthropicClient {
  // The SDK builds a client without credentials and fails on the first request; fail here instead.
  check(apiKey != null || hasAnthropicCredentialSource()) {
    "Claude found no Anthropic credential: pass apiKey, set ANTHROPIC_API_KEY or" +
      " ANTHROPIC_AUTH_TOKEN, or configure an Anthropic profile."
  }
  val builder = AnthropicOkHttpClient.builder().fromEnv()
  if (apiKey != null) builder.apiKey(apiKey)
  return builder.build()
}

/**
 * Whether the environment holds a credential source the SDK looks up: an API key or auth token,
 * custom auth headers, a profile or federation setting, or the SDK's config directory. The SDK
 * keeps the credential it resolves internal, so the sources are checked here; tests pass [env] and
 * [property] to replace the environment and the system properties.
 */
internal fun hasAnthropicCredentialSource(
  env: (String) -> String? = System::getenv,
  property: (String) -> String? = System::getProperty,
): Boolean {
  val sources =
    listOf(
      property("anthropic.apiKey") ?: env("ANTHROPIC_API_KEY"),
      property("anthropic.authToken") ?: env("ANTHROPIC_AUTH_TOKEN"),
      env("ANTHROPIC_CUSTOM_HEADERS"),
      env("ANTHROPIC_PROFILE"),
      env("ANTHROPIC_FEDERATION_RULE_ID"),
      env("ANTHROPIC_CONFIG_DIR"),
    )
  if (sources.any { !it.isNullOrEmpty() }) return true
  // Without any of those, the SDK loads a profile from its default config directory.
  val configDir =
    if (property("os.name").orEmpty().lowercase().contains("windows")) {
      env("APPDATA")?.let { Paths.get(it, "Anthropic") }
    } else {
      (property("user.home") ?: env("HOME"))?.let { Paths.get(it, ".config", "anthropic") }
    }
  return configDir != null && Files.isDirectory(configDir)
}

/**
 * Builds an SDK client that reaches Claude through Vertex AI. Tests pass [baseUrl] to replace the
 * host and [env] to replace the environment that project and location fall back to.
 */
internal fun vertexClient(
  vertexCredentials: VertexCredentials,
  baseUrl: String? = null,
  env: (String) -> String? = System::getenv,
  modelName: String? = null,
): AnthropicClient {
  // A resource name `projects/P/locations/L/...` names its project and location, as in Python.
  val resource = modelName?.takeIf { it.startsWith("projects/") }?.let { VERTEX_RESOURCE.find(it) }
  // A blank value counts as unset, as in the Python ADK.
  fun setting(value: String?, fromResource: String?, field: String, envName: String): String =
    requireNotNull(value?.ifBlank { null } ?: fromResource ?: env(envName)?.ifBlank { null }) {
      "Claude on Vertex AI needs a $field: set VertexCredentials.$field or $envName."
    }
  val project =
    setting(
      value = vertexCredentials.project,
      fromResource = resource?.groupValues?.get(1),
      field = "project",
      envName = "GOOGLE_CLOUD_PROJECT",
    )
  val location =
    setting(
      value = vertexCredentials.location,
      fromResource = resource?.groupValues?.get(2),
      field = "location",
      envName = "GOOGLE_CLOUD_LOCATION",
    )
  var credentials =
    vertexCredentials.credentials
      ?: try {
        GoogleCredentials.getApplicationDefault()
      } catch (e: IOException) {
        // Java cannot catch an undeclared checked exception by type, so rethrow it unchecked.
        throw IllegalStateException(
          "Claude on Vertex AI needs credentials: set VertexCredentials.credentials or configure" +
            " Application Default Credentials.",
          e,
        )
      }
  // The backend does not scope credentials; user (ADC) credentials report needing none.
  if (credentials.createScopedRequired()) {
    credentials = credentials.createScoped(CLOUD_PLATFORM_SCOPE)
  }
  val backendBuilder =
    VertexBackend.builder().googleCredentials(credentials).region(location).project(project)
  if (baseUrl != null) backendBuilder.baseUrl(baseUrl)
  return AnthropicOkHttpClient.builder()
    .backend(backendBuilder.build())
    .putHeader("x-goog-api-client", TRACKING_HEADER)
    .putHeader("user-agent", TRACKING_HEADER)
    .build()
}

private fun RawMessageStreamEvent.textDelta(): String? =
  contentBlockDelta().getOrNull()?.delta()?.text()?.getOrNull()?.text()

private fun RawMessageStreamEvent.thinkingDelta(): String? =
  contentBlockDelta().getOrNull()?.delta()?.thinking()?.getOrNull()?.thinking()

private fun partial(part: Part, modelVersion: String?): LlmResponse =
  LlmResponse(
    content = Content(role = Role.MODEL, parts = listOf(part)),
    modelVersion = modelVersion,
    partial = true,
  )

/** Bridges the SDK's callback stream to a [Flow]; cancelling the collector closes the stream. */
private fun <T> AsyncStreamResponse<T>.asFlow(): Flow<T> = callbackFlow {
  this@asFlow.subscribe(
    object : AsyncStreamResponse.Handler<T> {
      override fun onNext(value: T) {
        trySendBlocking(value)
      }

      override fun onComplete(error: Optional<Throwable>) {
        // Unwrap the future's wrapper so a stream failure surfaces the SDK's typed exception.
        val cause = error.getOrNull()
        close(if (cause is CompletionException) cause.cause ?: cause else cause)
      }
    }
  )
  awaitClose { this@asFlow.close() }
}
