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

package com.google.adk.kt

/**
 * Which of the two ways of reaching a Google model the SDK is configured for.
 *
 * Vertex AI takes Google Cloud credentials and a project; the Gemini API takes an API key from
 * Google AI Studio. A caller that has to build a request, a declaration or a client differently for
 * each branches on this.
 */
enum class GoogleLlmVariant {
  /** Vertex AI, reached with Google Cloud credentials. */
  VERTEX_AI,

  /** The Gemini API, reached with an API key from Google AI Studio. */
  GEMINI_API,
}

/**
 * Answers which Google backend this process is configured for.
 *
 * The user chooses by setting an environment variable, and the choice is read here by
 * [EnvUtils.isEnterpriseModeEnabled] rather than in each caller, so two parts of the SDK cannot
 * disagree about which backend they are building a request for. Mirrors `utils/variant_utils.py` in
 * the Python ADK, and like that module is for ADK internal use.
 */
object VariantUtils {

  /**
   * Returns the backend this process is configured for, read from the environment on every call.
   *
   * [GoogleLlmVariant.VERTEX_AI] when enterprise mode is enabled, and [GoogleLlmVariant.GEMINI_API]
   * otherwise, which is what a process that has configured nothing at all gets.
   */
  fun getGoogleLlmVariant(): GoogleLlmVariant =
    getGoogleLlmVariant(EnvUtils.isEnterpriseModeEnabled())

  /**
   * Returns the backend that [enterpriseModeEnabled] selects.
   *
   * Split from the lookup so both rows are testable: a process cannot put a variable into its own
   * environment.
   */
  internal fun getGoogleLlmVariant(enterpriseModeEnabled: Boolean): GoogleLlmVariant =
    if (enterpriseModeEnabled) GoogleLlmVariant.VERTEX_AI else GoogleLlmVariant.GEMINI_API
}
