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

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.annotations.FrameworkInternalApi

/**
 * SDK-neutral MCP tool annotations, exposed for framework interop. Kept independent of the Java and
 * Kotlin MCP SDK types so the interop surface is the same on JVM and Android; each platform maps
 * its SDK's annotations into this. A null hint means the server omitted it: per the MCP spec,
 * callers should treat an absent readOnlyHint or idempotentHint as false and an absent
 * destructiveHint or openWorldHint as true.
 */
@FrameworkInternalApi
data class McpToolAnnotations(
  val title: String? = null,
  val readOnlyHint: Boolean? = null,
  val destructiveHint: Boolean? = null,
  val idempotentHint: Boolean? = null,
  val openWorldHint: Boolean? = null,
)
