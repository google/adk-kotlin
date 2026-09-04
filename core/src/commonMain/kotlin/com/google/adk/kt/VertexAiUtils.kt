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

import com.google.adk.kt.platform.getEnv

/**
 * Answers which Express Mode API key, if any, this process should reach Vertex AI with.
 *
 * There are two mutually exclusive ways to address Vertex AI: a project and a location
 * authenticated by Google Cloud credentials, or a single Express Mode API key that stands in for
 * all of it. Mirrors `utils/vertex_ai_utils.py` in the Python ADK, and like that module is for ADK
 * internal use.
 */
object VertexAiUtils {

  /** The variable an Express Mode key is read from when the caller passes none. */
  private const val API_KEY_ENV_VAR = "GOOGLE_API_KEY"

  /**
   * Returns the Express Mode API key this process should use, or null when it should not use one.
   *
   * The key is [expressModeApiKey] when the caller passed one, otherwise `GOOGLE_API_KEY` from the
   * environment. Outside enterprise mode the answer is always null, including when the caller
   * passed a key, because Express Mode is a way of reaching Vertex and a process not configured for
   * Vertex has nothing for the key to address.
   *
   * @param project The Google Cloud project id, or null when the caller gave none.
   * @param location The Google Cloud location, or null when the caller gave none.
   * @param expressModeApiKey The Express Mode API key the caller supplied, or null.
   * @throws IllegalArgumentException if a project or a location is given alongside
   *   [expressModeApiKey]. A value counts as given whenever it is non-null, so `project = ""`
   *   alongside a key is rejected too; Python instead tests these arguments for truthiness.
   */
  fun getExpressModeApiKey(
    project: String?,
    location: String?,
    expressModeApiKey: String?,
  ): String? {
    // Rejected before either lookup below, because arguments to the overload would otherwise be
    // evaluated first and this misuse would read the environment on its way to being rejected.
    requireOneFormOfAddressing(project, location, expressModeApiKey)
    return getExpressModeApiKey(
      project,
      location,
      expressModeApiKey,
      enterpriseModeEnabled = EnvUtils.isEnterpriseModeEnabled(),
      apiKeyFromEnvironment = getEnv(API_KEY_ENV_VAR),
    )
  }

  /**
   * Returns the Express Mode API key, given whether enterprise mode is on and what `GOOGLE_API_KEY`
   * holds, where [apiKeyFromEnvironment] is null when that variable is not set.
   *
   * Split from the lookup so every row of the rule is testable: a process cannot put a variable
   * into its own environment.
   */
  internal fun getExpressModeApiKey(
    project: String?,
    location: String?,
    expressModeApiKey: String?,
    enterpriseModeEnabled: Boolean,
    apiKeyFromEnvironment: String?,
  ): String? {
    requireOneFormOfAddressing(project, location, expressModeApiKey)
    if (!enterpriseModeEnabled) {
      return null
    }
    return expressModeApiKey ?: apiKeyFromEnvironment
  }

  /** Throws if the caller gave both forms of Vertex addressing at once. */
  private fun requireOneFormOfAddressing(
    project: String?,
    location: String?,
    expressModeApiKey: String?,
  ) {
    require(expressModeApiKey == null || (project == null && location == null)) {
      "Cannot specify project or location and expressModeApiKey. Either use project and location," +
        " or just the expressModeApiKey."
    }
  }
}
