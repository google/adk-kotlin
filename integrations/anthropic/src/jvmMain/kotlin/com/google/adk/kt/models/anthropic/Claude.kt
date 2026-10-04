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
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawContentBlockDelta
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.vertex.backends.VertexBackend
import com.google.adk.kt.VERSION
import com.google.adk.kt.annotations.AdkJavaInteropApi
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

private const val RATE_LIMIT_HINT =
  "Claude rate limit reached; see https://docs.anthropic.com/en/api/errors#http-errors"

/** The Vertex AI organization policy that turns Claude structured output off by default. */
private const val PARTNER_FEATURES_POLICY = "constraints/vertexai.allowedPartnerModelFeatures"

private const val STRUCTURED_OUTPUT_POLICY_HINT =
  "The project's organization policy blocks Claude structured output on Vertex AI, which an" +
    " agent's output schema needs: allow structured_outputs in $PARTNER_FEATURES_POLICY, or" +
    " remove the output schema."

/**
 * A [Model] backed by Anthropic's Claude models through the official Anthropic Java SDK.
 *
 * Supports streaming, tool calling, extended thinking, image and PDF input, structured output, and
 * prompt caching. Thinking needs `ThinkingConfig.thinkingBudget` and ignores `thinkingLevel`.
 *
 * @property name The Claude model name (e.g. `claude-sonnet-4-5`).
 * @param client The Anthropic SDK client that sends the requests; the caller owns and closes it.
 * @param maxTokens The default `max_tokens` (8192), used when the request config sets none.
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
   * Creates a [Claude] model that calls the Anthropic API directly. The SDK client it creates is
   * closed when the model is garbage collected; pass your own [AnthropicClient] to close it sooner.
   *
   * @param name The Claude model name (e.g. `claude-sonnet-4-5`).
   * @param apiKey The Anthropic API key; when null or blank, the SDK reads credentials from the
   *   environment, such as `ANTHROPIC_API_KEY`.
   * @param maxTokens The default `max_tokens` (8192), used when the request config sets none.
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
   * Creates a [Claude] model served through Vertex AI. The SDK client it creates is closed when the
   * model is garbage collected; pass your own [AnthropicClient] to close it sooner.
   *
   * @param name The Claude model id as published on Vertex AI (e.g. `claude-haiku-4-5`), or its
   *   full resource name, which also names the project and location.
   * @param vertexCredentials The Vertex AI project, location, and credentials. Project and location
   *   fall back to those in a resource [name], then to the `GOOGLE_CLOUD_PROJECT` and
   *   `GOOGLE_CLOUD_LOCATION` environment variables; credentials default to Application Default
   *   Credentials.
   * @param maxTokens The default `max_tokens` (8192), used when the request config sets none.
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
      if (request.config.responseSchema != null && error.isPartnerFeaturePolicyError()) {
        throw IllegalStateException(STRUCTURED_OUTPUT_POLICY_HINT, error)
      }
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
        // The accumulator rejects a tool_use block with no argument deltas; Python reads it as {}.
        event
          .contentBlockStart()
          .getOrNull()
          ?.takeIf { it.contentBlock().isToolUse() }
          ?.let { accumulator.accumulate(emptyInputJsonDelta(it.index())) }
        event.messageStart().getOrNull()?.let { modelVersion = it.message().model().asString() }
        event.textDelta()?.let { emit(partial(Part(text = it), modelVersion)) }
        event.thinkingDelta()?.let { emit(partial(Part(text = it, thought = true), modelVersion)) }
      }
      // The accumulated message is the whole turn, with blocks in the order the model sent them.
      emit(accumulator.message().toLlmResponse())
    } else {
      // Cancelling stops the wait, but the SDK's future cannot cancel the HTTP call it wraps.
      emit(messages.create(params).await().toLlmResponse())
    }
  }

  /**
   * Fluent builder for [Claude], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var client: AnthropicClient? = null
    private var apiKey: String? = null
    private var vertexCredentials: VertexCredentials? = null
    private var maxTokens: Int = DEFAULT_MAX_TOKENS
    private var effort: OutputConfig.Effort? = null

    fun name(name: String): Builder = apply { this.name = name }

    /** Sets the SDK client; set at most one of this, [apiKey], and [vertexCredentials]. */
    fun client(client: AnthropicClient?): Builder = apply { this.client = client }

    fun apiKey(apiKey: String?): Builder = apply { this.apiKey = apiKey }

    fun vertexCredentials(vertexCredentials: VertexCredentials?): Builder = apply {
      this.vertexCredentials = vertexCredentials
    }

    fun maxTokens(maxTokens: Int): Builder = apply { this.maxTokens = maxTokens }

    fun effort(effort: OutputConfig.Effort?): Builder = apply { this.effort = effort }

    /**
     * Builds the [Claude] model, calling the Anthropic API unless a client or Vertex AI credentials
     * are set.
     */
    fun build(): Claude {
      val name = checkNotNull(name) { "Claude.Builder needs a name." }
      // A blank key counts as unset, as in the constructor.
      check(listOfNotNull(client, apiKey?.ifBlank { null }, vertexCredentials).size <= 1) {
        "Claude.Builder takes at most one of client, apiKey, and vertexCredentials."
      }
      val client = client ?: vertexCredentials?.let { vertexClient(it, modelName = name) }
      return if (client != null) {
        Claude(name, client, maxTokens, effort)
      } else {
        Claude(name, apiKey, maxTokens, effort)
      }
    }
  }

  companion object {
    /** Default `max_tokens` when the request config sets none; the Messages API requires it. */
    private const val DEFAULT_MAX_TOKENS = 8192

    private val logger = LoggerFactory.getLogger(Claude::class)

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

private fun Throwable.isPartnerFeaturePolicyError(): Boolean =
  this is AnthropicServiceException && body().toString().contains(PARTNER_FEATURES_POLICY)

/**
 * Builds an SDK client for the Anthropic API, reading credentials from the environment when
 * [apiKey] is null or blank. Tests pass [hasCredentialSource] to replace the environment check.
 */
internal fun anthropicApiClient(
  apiKey: String?,
  hasCredentialSource: () -> Boolean = ::hasAnthropicCredentialSource,
): AnthropicClient {
  val key = apiKey?.ifBlank { null }
  // Without a credential the SDK sends no auth headers; fail here, as the Python ADK does.
  check(key != null || hasCredentialSource()) {
    "Claude found no Anthropic credential: pass apiKey, set ANTHROPIC_API_KEY or" +
      " ANTHROPIC_AUTH_TOKEN, or configure an Anthropic profile."
  }
  val builder = AnthropicOkHttpClient.builder().fromEnv()
  if (key != null) builder.apiKey(key)
  return builder.build()
}

/**
 * Whether the environment holds a credential source the SDK looks up: an API key or auth token,
 * custom auth headers, a profile or federation setting, or a profile in the SDK's config directory.
 * The SDK neither fails without a credential nor exposes the one it resolves, so the sources are
 * checked here; tests pass [env] and [property] to replace the environment and the system
 * properties.
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
    )
  if (sources.any { !it.isNullOrBlank() }) return true
  // Otherwise the SDK loads a profile from the `configs` folder of its config directory.
  val configDir =
    env("ANTHROPIC_CONFIG_DIR")?.let { Paths.get(it) }
      ?: if (property("os.name").orEmpty().contains("windows", ignoreCase = true)) {
        env("APPDATA")?.let { Paths.get(it, "Anthropic") }
      } else {
        (property("user.home") ?: env("HOME"))?.let { Paths.get(it, ".config", "anthropic") }
      }
  return configDir != null && Files.isDirectory(configDir.resolve("configs"))
}

/**
 * Builds an SDK client that reaches Claude through Vertex AI; a resource [modelName] also names the
 * project and location. Tests replace the host, the environment, and Application Default
 * Credentials through [baseUrl], [env], and [defaultCredentials].
 */
internal fun vertexClient(
  vertexCredentials: VertexCredentials,
  baseUrl: String? = null,
  env: (String) -> String? = System::getenv,
  modelName: String? = null,
  defaultCredentials: () -> GoogleCredentials = GoogleCredentials::getApplicationDefault,
): AnthropicClient {
  val resource = modelName?.let { VERTEX_RESOURCE_NAME.find(it) }
  // A blank value counts as unset.
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
        defaultCredentials()
      } catch (e: IOException) {
        // Java cannot catch an undeclared checked exception by type, so rethrow it unchecked.
        throw IllegalStateException(
          "Claude on Vertex AI needs credentials: set VertexCredentials.credentials or configure" +
            " Application Default Credentials.",
          e,
        )
      }
  // The backend does not scope credentials; service accounts need a scope, user credentials don't.
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

private fun emptyInputJsonDelta(index: Long): RawMessageStreamEvent =
  RawMessageStreamEvent.ofContentBlockDelta(
    RawContentBlockDeltaEvent.builder()
      .index(index)
      .delta(RawContentBlockDelta.ofInputJson(""))
      .build()
  )

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
