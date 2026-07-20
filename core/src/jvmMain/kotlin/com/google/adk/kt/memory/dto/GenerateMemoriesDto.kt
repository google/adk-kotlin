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
import kotlinx.serialization.json.JsonElement

/**
 * Wire model for `GenerateMemoriesRequest` from `memory_bank_service.proto` (the
 * `memories:generate` method). `parent` is bound to the URL path, so only these fields travel in
 * the body. Exactly one of [directContentsSource] / [directMemoriesSource] is set.
 */
@Serializable
internal data class GenerateMemoriesRequestDto(
  val scope: Map<String, String>,
  val directContentsSource: DirectContentsSourceDto? = null,
  val directMemoriesSource: DirectMemoriesSourceDto? = null,
  val disableConsolidation: Boolean? = null,
)

/** Source content (chat history) to generate memories from. */
@Serializable
internal data class DirectContentsSourceDto(val events: List<DirectContentsSourceEventDto>)

/** [content] is the genai `Content` JSON (role + parts), pre-encoded by the service. */
@Serializable internal data class DirectContentsSourceEventDto(val content: JsonElement)

/** Direct memories to upload with server-side consolidation (at most 5 per request). */
@Serializable internal data class DirectMemoriesSourceDto(val directMemories: List<DirectMemoryDto>)

@Serializable internal data class DirectMemoryDto(val fact: String)
