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

@file:OptIn(com.google.adk.kt.annotations.FrameworkInternalApi::class)

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.toAny
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/** Converts the Kotlin MCP SDK's [ToolSchema] through the shared map-based converter. */
internal fun ToolSchema.toAdkSchema(): Schema = jsonSchemaToAdkSchema(toJsonMap())

/** Converts output schema, or `null` when Vertex cannot accept it. */
internal fun ToolSchema.toAdkResponseSchema(): Schema? = jsonSchemaToAdkResponseSchema(toJsonMap())

private fun ToolSchema.toJsonMap(): Map<String, Any> = buildMap {
  put("type", "object")
  properties?.let { put("properties", it.jsonObjectMap()) }
  required?.takeIf { it.isNotEmpty() }?.let { put("required", it) }
  defs?.let { put("\$defs", it.jsonObjectMap()) }
}

@Suppress("UNCHECKED_CAST")
private fun JsonObject.jsonObjectMap(): Map<String, Any> = toAny() as Map<String, Any>
