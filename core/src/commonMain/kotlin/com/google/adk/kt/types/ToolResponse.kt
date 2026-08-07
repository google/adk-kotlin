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
 * The output of a server-side [ToolCall] the model ran itself.
 *
 * Echoed back on the next request alongside its [ToolCall], or the model redoes the work it already
 * did, or fails because a call has no matching response.
 *
 * @property id The identifier of the [ToolCall] this response is for.
 * @property toolType The type of tool that was called.
 * @property response The tool response.
 */
@Serializable
data class ToolResponse(
  val id: String? = null,
  val toolType: ToolType? = null,
  val response: Map<String, @Contextual Any?>? = null,
)
