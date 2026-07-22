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

package com.google.adk.kt.memory.dto

import kotlinx.serialization.Serializable

/**
 * One serialized event inside an uploaded RAG file. Each event becomes a single JSON object on its
 * own line (newline-delimited JSON), matching the Python ADK `VertexAiRagMemoryService` wire format
 * so a corpus can be shared across language implementations.
 *
 * [timestamp] is the event time in fractional **seconds** since the Unix epoch (the Python unit),
 * which the Kotlin service converts to/from its native epoch-millis representation at the boundary.
 */
@Serializable
internal data class MemoryRecordDto(
  val author: String? = null,
  val timestamp: Double = 0.0,
  val text: String = "",
)
