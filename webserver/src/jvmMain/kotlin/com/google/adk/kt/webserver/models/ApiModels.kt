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

package com.google.adk.kt.webserver.models

import com.google.adk.kt.types.Content
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Response for the `/version` endpoint.
 *
 * Exactly one of [languageVersion] and [legacyLanguageVersion] is set; `adkJson` omits the other,
 * so the response never carries both spellings for a strict decoder to reject.
 */
@Serializable
data class VersionInfo(
  val version: String,
  val language: String,
  val languageVersion: String? = null,
  @SerialName("language_version") val legacyLanguageVersion: String? = null,
) {
  companion object {
    /** Builds the response in the enforced camelCase spelling, or the default one. */
    fun of(version: String, language: String, languageVersion: String, camelCase: Boolean) =
      if (camelCase) VersionInfo(version, language, languageVersion = languageVersion)
      else VersionInfo(version, language, legacyLanguageVersion = languageVersion)
  }
}

@Serializable
data class AgentRunRequest(
  @JsonNames("app_name") val appName: String,
  @JsonNames("user_id") val userId: String,
  @JsonNames("session_id") val sessionId: String? = null,
  @JsonNames("new_message") val newMessage: Content? = null,
  val streaming: Boolean = false,
  @JsonNames("state_delta") val stateDelta: Map<String, @Contextual Any>? = null,
  @JsonNames("invocation_id") val invocationId: String? = null,
)

@Serializable internal data class RunResponse(val output: String, val sessionId: String)

/**
 * Terminal frame for `/run_sse` when a run fails after the stream has opened.
 *
 * Carries the `error` key, valued `"<Type>: <message>"`.
 */
@Serializable internal data class SseError(val error: String)

@Serializable
data class SessionDto(
  val id: String?,
  val appName: String,
  val userId: String,
  val state: Map<String, @Contextual Any>?,
  val events: List<com.google.adk.kt.events.Event>?,
  val lastUpdateTime: Long?,
)
