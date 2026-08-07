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
package com.google.adk.kt.types

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A tool call the model runs on its own server side.
 *
 * The client does not execute this call; it echoes the part back on the next request together with
 * the matching [ToolResponse], or the model redoes the work it already did.
 *
 * @property id Unique identifier the matching [ToolResponse] repeats.
 * @property toolType The type of tool that was called.
 * @property args The tool call arguments.
 */
@Serializable
data class ToolCall(
  val id: String? = null,
  val toolType: ToolType? = null,
  val args: Map<String, @Contextual Any?>? = null,
)
