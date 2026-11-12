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

import com.google.adk.kt.annotations.ExperimentalLiveApi
import kotlinx.coroutines.flow.Flow

/** Interface that provides a common interface for interacting with different LLMs. */
interface Model {
  /** The name of the model. */
  val name: String

  /**
   * The optional features this model supports, which change how ADK builds its requests. By default
   * only a model whose [name] is a `gemini-2.*` name lacks [LlmCapabilities.outputSchemaAndTools].
   * Override this to choose, for example `LlmCapabilities(outputSchemaAndTools = false)` to get the
   * final answer through `set_model_response` beside tools.
   */
  val capabilities: LlmCapabilities
    get() = LlmCapabilities(outputSchemaAndTools = canUseOutputSchemaWithTools)

  /**
   * Generates content for the given [LlmRequest]. This returns a [Flow] of [LlmResponse]s.
   *
   * @param request The request containing prompt and config.
   * @param stream Whether to enable streaming mode. If true, partial responses will be emitted.
   */
  fun generateContent(request: LlmRequest, stream: Boolean = false): Flow<LlmResponse>

  /**
   * Opens a live (bidirectional streaming) connection, configured by
   * [LlmRequest.liveConnectConfig].
   *
   * The returned connection is open but not yet primed; send history or content to start the
   * conversation, and close it when it ends.
   *
   * @param request The request whose live config, model name, system instruction and tools
   *   configure the session.
   * @throws UnsupportedOperationException if this model does not support live connections.
   */
  @ExperimentalLiveApi
  suspend fun connect(request: LlmRequest): LiveConnection = throw liveConnectionsUnsupported()
}

private val PATH_PATTERNS =
  listOf(
    Regex("^projects/[^/]+/locations/[^/]+/publishers/[^/]+/models/(.+)$"),
    Regex("^apigee/(?:[^/]+/)?(?:[^/]+/)?(.+)$"),
  )

/**
 * The short name of the model, extracted from the full model name if it's in a path-based format.
 */
internal val Model.shortName: String
  get() =
    PATH_PATTERNS.firstNotNullOfOrNull { it.matchEntire(name)?.groupValues?.get(1) }
      ?: name.removePrefix("models/")

/** Matches Gemini 2.x model names (e.g. `gemini-2.0-flash`, `gemini-2.5-pro`). */
private val GEMINI_2_PATTERN = Regex("^gemini-2\\..*")

/**
 * The name rule behind the default of [Model.capabilities]: whether the model takes an output
 * schema together with tools.
 *
 * Follows the Java ADK `ModelNameUtils.canUseOutputSchemaWithTools`: Gemini 2.x models cannot use a
 * response schema while tools are present, so the ADK falls back to the `set_model_response` tool
 * workaround (see `com.google.adk.kt.processors.OutputSchemaProcessor`).
 *
 * NOTE: this is deliberately *not* identical to the default of the Python ADK's
 * `BaseLlm.capabilities`, which reports the capability for Gemini models on Vertex AI and therefore
 * gives the opposite answer for Vertex Gemini 2.x. This implementation matches Java's conservative
 * rule instead.
 */
internal val Model.canUseOutputSchemaWithTools: Boolean
  get() = !GEMINI_2_PATTERN.matches(shortName)

/**
 * Whether ADK applies a response schema beside tools for this model, as [Model.capabilities] says.
 */
internal val Model.takesOutputSchemaWithTools: Boolean
  // A Mockito mock of Model returns null here despite the non-null type.
  get() = (capabilities as LlmCapabilities?)?.outputSchemaAndTools ?: canUseOutputSchemaWithTools

/** What a model without live support throws from [Model.connect]. */
internal fun Model.liveConnectionsUnsupported() =
  UnsupportedOperationException("live connections are not supported for $name")
