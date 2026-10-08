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
import kotlinx.serialization.json.JsonObject

/**
 * Wire-level representation of `SessionEvent` from `session.proto`.
 *
 * [content] is decoded to [com.google.adk.kt.types.Content] by the mapper, and [name] is only read;
 * its last segment is the event id unless a readable [rawEvent] has one. [rawEvent] holds the whole
 * event in ADK Python's shape, since the API drops some typed fields.
 */
@Serializable
internal data class SessionEventDto(
  val name: String? = null,
  val author: String? = null,
  val invocationId: String? = null,
  val timestamp: TimestampDto? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val content: JsonElement? = null,
  val actions: EventActionsDto? = null,
  val eventMetadata: EventMetadataDto? = null,
  val rawEvent: JsonObject? = null,
)
