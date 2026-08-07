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

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * The type of tool a server-side [ToolCall] invoked.
 *
 * A value class rather than an enum so a type the model adds later round-trips unchanged instead of
 * failing to parse.
 */
@Serializable
@JvmInline
value class ToolType(val value: String) {
  companion object {
    /** Unspecified tool type. */
    val TOOL_TYPE_UNSPECIFIED = ToolType("TOOL_TYPE_UNSPECIFIED")

    /** Google search tool. */
    val GOOGLE_SEARCH_WEB = ToolType("GOOGLE_SEARCH_WEB")

    /** Image search tool. */
    val GOOGLE_SEARCH_IMAGE = ToolType("GOOGLE_SEARCH_IMAGE")

    /** URL context tool. */
    val URL_CONTEXT = ToolType("URL_CONTEXT")

    /** Google maps tool. */
    val GOOGLE_MAPS = ToolType("GOOGLE_MAPS")

    /** File search tool. */
    val FILE_SEARCH = ToolType("FILE_SEARCH")
  }
}
