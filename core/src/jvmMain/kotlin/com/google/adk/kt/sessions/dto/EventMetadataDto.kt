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

/**
 * Wire-level representation of `EventMetadata` from `session.proto`.
 *
 * The streaming/turn signaling fields ([partial], [turnComplete], [interrupted], [branch],
 * [longRunningToolIds]) live under this nested object on the wire even though the ADK domain
 * [com.google.adk.kt.events.Event] carries them flat at the top level. Structured sub-objects
 * ([groundingMetadata], [usageMetadata]) are kept as [JsonElement] to decouple the DTO from the ADK
 * model shape; the mapper decodes them into the corresponding ADK types.
 */
@Serializable
internal data class EventMetadataDto(
  val partial: Boolean? = null,
  val turnComplete: Boolean? = null,
  val interrupted: Boolean? = null,
  val branch: String? = null,
  val longRunningToolIds: List<String>? = null,
  val groundingMetadata: JsonElement? = null,
  val usageMetadata: JsonElement? = null,
)
