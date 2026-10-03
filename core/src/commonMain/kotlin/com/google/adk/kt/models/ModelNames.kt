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

private val MODEL_PATH_PATTERNS =
  listOf(
    Regex("^projects/[^/]+/locations/[^/]+/publishers/[^/]+/models/(.+)$"),
    Regex("^apigee/(?:[^/]+/)?(?:[^/]+/)?(.+)$"),
  )

private const val MODELS_PREFIX = "models/"
private const val PROJECTS_PREFIX = "projects/"
private const val GEMINI_PREFIX = "gemini-"
private const val GEMINI_3_PREFIX = "gemini-3."
private const val GEMINI_3_5_LIVE_TRANSLATE_PREFIX = "gemini-3.5-live-translate"
private const val LIVE_MARKER = "-live"

/**
 * The bare model name inside [modelString], which may be a plain name, a resource path, or a
 * provider-prefixed name.
 *
 * Handles `projects/.../models/x`, `apigee/.../x`, `models/x`, and provider prefixes such as
 * `gemini/gemini-2.5-flash` or `openrouter/google/gemini-2.5-pro`. Anything else is returned
 * unchanged.
 */
internal fun extractModelName(modelString: String): String {
  MODEL_PATH_PATTERNS.firstNotNullOfOrNull { it.matchEntire(modelString)?.groupValues?.get(1) }
    ?.let {
      return it
    }

  if (modelString.startsWith(MODELS_PREFIX)) {
    return modelString.removePrefix(MODELS_PREFIX)
  }

  // A malformed `projects/` path is returned whole, not unwrapped by the rule below.
  if (modelString.startsWith(PROJECTS_PREFIX)) {
    return modelString
  }

  // Provider-prefixed names, as LiteLLM writes them: only a Gemini last segment is unwrapped.
  if ('/' in modelString) {
    val lastSegment = modelString.substringAfterLast('/')
    if (lastSegment.startsWith(GEMINI_PREFIX)) {
      return lastSegment
    }
  }

  return modelString
}

/**
 * Whether [modelString] names a Gemini 3.x live model.
 *
 * Gemini 3.x live differs from earlier live models in ways a caller must handle: it takes a lone
 * text part as realtime input, it sends one final input transcription instead of chunks, and it
 * emits tool calls immediately rather than holding them until the turn ends.
 *
 * Live translate is excluded: it is 3.5 but behaves like the earlier models.
 */
internal fun isGemini3XLive(modelString: String?): Boolean {
  if (modelString.isNullOrEmpty()) return false
  val modelName = extractModelName(modelString)
  return modelName.startsWith(GEMINI_3_PREFIX) &&
    LIVE_MARKER in modelName &&
    !isGemini35LiveTranslate(modelString)
}

/** Whether [modelString] names a Gemini 3.5 live translate model. */
internal fun isGemini35LiveTranslate(modelString: String?): Boolean {
  if (modelString.isNullOrEmpty()) return false
  return extractModelName(modelString).startsWith(GEMINI_3_5_LIVE_TRANSLATE_PREFIX)
}
