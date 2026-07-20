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
 * Wire model for `Memory` from `memory_bank.proto`. Used both as the `CreateMemory` request body
 * and inside the `RetrieveMemories` response. On write, only [fact] and [scope] are set; the
 * output-only fields ([name], [updateTime]) are populated on read. Defaults make read robust to a
 * memory missing a field.
 */
@Serializable
internal data class MemoryDto(
  val fact: String = "",
  val scope: Map<String, String> = emptyMap(),
  val displayName: String? = null,
  val description: String? = null,
  val name: String? = null,
  val updateTime: String? = null,
)
