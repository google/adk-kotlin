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

package com.google.adk.kt.telemetry

import com.google.adk.kt.platform.getEnv
import kotlinx.serialization.json.JsonElement

/**
 * Global configuration for ADK telemetry behavior.
 *
 * @property captureMessageContent Whether to capture raw prompts and payloads into spans. Seeded at
 *   startup from the [ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS] env var (`true`/`1` enables it);
 *   defaults to `false` when unset, diverging from Python/Java/JS (which default to `true`) to
 *   avoid recording PII or large payloads. `@Volatile` for cross-thread visibility.
 */
object TelemetryConfig {
  @Volatile var captureMessageContent: Boolean = defaultCaptureMessageContent()
}

/** Placeholder emitted for content payloads when message-content capture is disabled. */
internal const val EMPTY_JSON: String = "{}"

/** Emitted in place of a payload when serialization throws, so a bad payload never fails a span. */
internal const val SERIALIZATION_ERROR_JSON: String = "{\"error\": \"serialization failed\"}"

/**
 * Returns the JSON serialization of [payload] when [TelemetryConfig.captureMessageContent] is
 * enabled, otherwise the [EMPTY_JSON] placeholder.
 *
 * Callers build [payload] from the shared `adkJson` serializer, so a given value serializes to
 * identical JSON on every platform. The placeholder is always emitted (rather than omitting the
 * attribute) because the ADK Dev UI `JSON.parse`s these span attributes unconditionally. [payload]
 * is only evaluated when capture is enabled, so callers may build expensive payloads lazily.
 */
internal fun capturedJson(payload: () -> JsonElement): String {
  if (!TelemetryConfig.captureMessageContent) return EMPTY_JSON
  return try {
    payload().toString()
  } catch (e: Throwable) {
    SERIALIZATION_ERROR_JSON
  }
}

/**
 * Name of the ADK environment variable that toggles capturing prompt/response content in spans.
 *
 * Matches the variable read by Python (`_should_add_request_response_to_spans`), Java, and JS ADK.
 * Unlike those ADKs (which default capture ON when the variable is unset), the Kotlin ADK only
 * enables capture when this variable is explicitly set to `true`/`1`; see [TelemetryConfig].
 */
internal const val ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS = "ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS"

/** Environment values (case-insensitive, surrounding whitespace ignored) that enable capture. */
private val CAPTURE_ENABLED_VALUES = setOf("true", "1")

/**
 * Resolves the startup default for [TelemetryConfig.captureMessageContent] from the environment.
 */
internal fun defaultCaptureMessageContent(): Boolean =
  parseCaptureMessageContent(getEnv(ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS))

/**
 * Parses a raw [ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS] value into the capture flag.
 *
 * Returns true only for `true`/`1` (case-insensitive, surrounding whitespace ignored). A null
 * (unset) value or any other string returns false, preserving the Kotlin ADK's safe default.
 */
internal fun parseCaptureMessageContent(rawValue: String?): Boolean {
  val normalized = rawValue?.trim()?.lowercase() ?: return false
  return normalized in CAPTURE_ENABLED_VALUES
}
