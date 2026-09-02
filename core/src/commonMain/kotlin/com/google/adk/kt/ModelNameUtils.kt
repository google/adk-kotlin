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

/** Path forms that wrap a model name, each capturing the bare name in group 1. */
private val PATH_PATTERNS =
  listOf(
    Regex("^projects/[^/]+/locations/[^/]+/publishers/[^/]+/models/(.+)$"),
    Regex("^apigee/(?:[^/]+/)?(?:[^/]+/)?(.+)$"),
  )

private const val MODELS_PREFIX = "models/"
private const val PROJECTS_PREFIX = "projects/"
private const val GEMINI_PREFIX = "gemini-"

/**
 * Reads model ids that arrive in different forms.
 *
 * The same model is named bare by the Gemini API, as a publisher path by Vertex, behind a `models/`
 * prefix by the model-listing endpoint, behind an Apigee proxy path, or behind a provider prefix by
 * a LiteLLM-style config. Mirrors `utils/model_name_utils.py` in the Python ADK.
 */
object ModelNameUtils {

  /**
   * Returns the bare model name from [modelString], whatever form it arrived in.
   *
   * A name this does not recognise comes back whole, including another provider's prefixed id such
   * as `openai/gpt-4o` and a `projects/` path that names something other than a publisher model.
   * Both are deliberate: their last segment is not a model id, and reading it as one would turn a
   * model that is not Gemini into one that looks like it.
   */
  fun extractModelName(modelString: String): String {
    val fromPath = PATH_PATTERNS.firstNotNullOfOrNull {
      it.matchEntire(modelString)?.groupValues?.get(1)
    }
    if (fromPath != null) {
      return fromPath
    }

    if (modelString.startsWith(MODELS_PREFIX)) {
      return modelString.removePrefix(MODELS_PREFIX)
    }

    // A `projects/` path that did not match the publisher pattern above, such as a tuned-model
    // endpoint. Returned whole so the provider-prefix step below cannot read its last segment as a
    // model id.
    if (modelString.startsWith(PROJECTS_PREFIX)) {
      return modelString
    }

    // A provider-prefixed name such as `gemini/gemini-2.5-flash` or
    // `openrouter/google/gemini-2.5-pro:online`. Only Gemini names are unwrapped; what another
    // provider calls its models is its own business.
    if (modelString.contains('/')) {
      val bareName = modelString.substringAfterLast('/')
      if (bareName.startsWith(GEMINI_PREFIX)) {
        return bareName
      }
    }

    return modelString
  }

  /**
   * Whether [modelString] names a Gemini model, seeing through every form [extractModelName]
   * recognises.
   *
   * A null or empty id answers `false` rather than throwing, because a model id read from
   * configuration is absent often enough that making every caller write that branch is the wrong
   * trade.
   */
  fun isGeminiModel(modelString: String?): Boolean {
    if (modelString.isNullOrEmpty()) return false
    return extractModelName(modelString).startsWith(GEMINI_PREFIX)
  }
}
