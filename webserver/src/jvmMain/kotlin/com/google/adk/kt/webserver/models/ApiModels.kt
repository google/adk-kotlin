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
import com.google.gson.annotations.SerializedName

/**
 * Response for the `/version` endpoint. The [languageVersion] field is serialized as
 * `language_version`.
 */
data class VersionInfo(
  val version: String,
  val language: String,
  @SerializedName("language_version") val languageVersion: String,
)

data class AgentRunRequest(
  val appName: String,
  val userId: String,
  val sessionId: String? = null,
  val newMessage: Content? = null,
  val streaming: Boolean = false,
  val stateDelta: Map<String, Any>? = null,
  val invocationId: String? = null,
)

data class RunRequest(val agentId: String, val input: String, val sessionId: String? = null)

data class RunResponse(val output: String, val sessionId: String)

data class TurnModel(val role: String, val content: String)

data class SessionModel(val sessionId: String, val turnHistory: List<TurnModel>)

data class ErrorResponse(val error: String, val message: String, val details: String? = null)

/**
 * Model representing an SSE (Server-Sent Event) message.
 *
 * @property type The type of event (e.g. "message", "error", "done").
 * @property content The JSON string or text content of the event.
 * @property timestamp The ISO-8601 timestamp of the event.
 */
data class SseModel(val type: String, val content: String, val timestamp: String)

data class SessionDto(
  val id: String?,
  val appName: String,
  val userId: String,
  val state: Map<String, Any>?,
  val events: List<com.google.adk.kt.events.Event>?,
  val lastUpdateTime: Long?,
)
