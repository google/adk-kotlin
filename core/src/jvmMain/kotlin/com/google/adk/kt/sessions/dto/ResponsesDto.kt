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

package com.google.adk.kt.sessions.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire response for `reasoningEngines/{id}/sessions` (list). */
@Serializable
internal data class ListSessionsResponseDto(
  val sessions: List<SessionDto>? = null,
  val nextPageToken: String? = null,
)

/** Wire response for `reasoningEngines/{id}/sessions/{sid}/events` (list). */
@Serializable
internal data class ListEventsResponseDto(
  val sessionEvents: List<SessionEventDto>? = null,
  val nextPageToken: String? = null,
)

/** Wire request body for `POST reasoningEngines/{id}/sessions` (create). */
@Serializable
internal data class CreateSessionRequestDto(
  val userId: String,
  val sessionState: JsonElement? = null,
)
