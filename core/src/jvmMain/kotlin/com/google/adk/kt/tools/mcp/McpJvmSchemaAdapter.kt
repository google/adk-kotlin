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

import com.google.adk.kt.types.FunctionDeclaration
import io.modelcontextprotocol.spec.McpSchema

/**
 * JVM adapter: convert a Java MCP SDK [McpSchema.Tool] through the shared map-based schema
 * converter.
 */
internal fun McpSchema.Tool.toAdkFunctionDeclaration(): FunctionDeclaration =
  FunctionDeclaration(
    name = name(),
    description = description() ?: "",
    parameters = inputSchema()?.let { jsonSchemaToAdkSchema(jsonObjectToMap(it)) },
    response = outputSchema()?.let { jsonSchemaToAdkResponseSchema(jsonObjectToMap(it)) },
  )

private fun jsonObjectToMap(value: Any): Map<String, Any> {
  val map = value as? Map<*, *> ?: error("MCP JSON Schema is expected to be a JSON object map.")
  val result = mutableMapOf<String, Any>()
  for ((key, entry) in map) {
    if (key is String && entry != null) result[key] = entry
  }
  return result
}
