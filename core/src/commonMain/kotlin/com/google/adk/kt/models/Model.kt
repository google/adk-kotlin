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

import kotlinx.coroutines.flow.Flow

/** Interface that provides a common interface for interacting with different LLMs. */
interface Model {
  /** The name of the model. */
  val name: String

  /**
   * Generates content for the given [LlmRequest]. This returns a [Flow] of [LlmResponse]s.
   *
   * @param request The request containing prompt and config.
   * @param stream Whether to enable streaming mode. If true, partial responses will be emitted.
   */
  fun generateContent(request: LlmRequest, stream: Boolean = false): Flow<LlmResponse>
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

/** Whether the model is a Gemini 2.0 or newer model. */
internal val Model.isGemini2OrAbove: Boolean
  get() =
    shortName.startsWith("gemini-") &&
      (shortName.removePrefix("gemini-").substringBefore("-").substringBefore(".").toIntOrNull()
        ?: 0) >= 2

/** Matches Gemini 2.x model names (e.g. `gemini-2.0-flash`, `gemini-2.5-pro`). */
private val GEMINI_2_PATTERN = Regex("^gemini-2\\..*")

/**
 * Whether the model supports configuring an output schema together with tools.
 *
 * Follows the Java ADK `ModelNameUtils.canUseOutputSchemaWithTools`: Gemini 2.x models cannot use a
 * response schema while tools are present, so the ADK falls back to the `set_model_response` tool
 * workaround (see `com.google.adk.kt.processors.OutputSchemaProcessor`).
 *
 * NOTE: this is deliberately *not* identical to the Python ADK's
 * `output_schema_utils.can_use_output_schema_with_tools`, which gates on `VERTEX_AI &&
 * is_gemini_eap_or_2_or_above(model)` and therefore returns the opposite answer for Vertex Gemini
 * 2.x. This implementation matches Java's conservative rule instead.
 */
internal val Model.canUseOutputSchemaWithTools: Boolean
  get() = !GEMINI_2_PATTERN.matches(shortName)
