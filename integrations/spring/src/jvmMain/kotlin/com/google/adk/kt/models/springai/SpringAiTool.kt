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
// Uses ADK's @FrameworkInternalApi helpers (jsonElementToAny, jsonSchemaToAdkSchema); opt in once
// for the whole file.
@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.models.springai

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.mcp.jsonSchemaToAdkSchema
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json as KxJson
import org.springframework.ai.chat.model.ToolContext as SpringToolContext
import org.springframework.ai.tool.ToolCallback

/**
 * Exposes a Spring AI [ToolCallback] as an ADK [BaseTool], so an existing Spring AI tool can be
 * used by any ADK Kotlin agent and model. Name, description, and input schema come from the
 * callback's `ToolDefinition`; ADK owns the tool loop, so [run] serializes the arguments, invokes
 * the callback on the IO dispatcher (the call is blocking), and decodes the JSON result. The ADK
 * [ToolContext] is passed to the callback under [ADK_TOOL_CONTEXT_KEY], and a `returnDirect`
 * callback maps to ADK's `skipSummarization`.
 *
 * @param toolCallback The Spring AI tool to wrap.
 */
class SpringAiTool(private val toolCallback: ToolCallback) :
  BaseTool(
    name = toolCallback.toolDefinition.name(),
    // description() is a platform String!; guard against null since BaseTool.description is
    // non-null.
    description = toolCallback.toolDefinition.description() ?: "",
  ) {

  // The wrapped ToolDefinition cannot change, so parse its schema once rather than per request.
  private val cachedDeclaration: FunctionDeclaration by lazy {
    FunctionDeclaration(
      name = name,
      description = description,
      parameters = parseJsonSchema(toolCallback.toolDefinition.inputSchema()),
    )
  }

  override fun declaration(): FunctionDeclaration = cachedDeclaration

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val toolInput = Json.toJsonString(args)
    // Bridge the ADK ToolContext to Spring AI under a documented key; unaware tools ignore it.
    val springContext = SpringToolContext(mapOf(ADK_TOOL_CONTEXT_KEY to context))
    val result = withContext(Dispatchers.IO) { toolCallback.call(toolInput, springContext) }
    // A JSON null is no result, which the runtime answers with a `null` result, as for `Unit`.
    val decoded = decodeToolResult(result) ?: Unit
    // returnDirect maps to ADK skipSummarization; a failing tool throws from call() above, so this
    // is reached only on success.
    if (toolCallback.toolMetadata.returnDirect()) {
      context.actions.skipSummarization = true
    }
    return decoded
  }

  companion object {
    /**
     * Key under which the ADK [ToolContext] is placed in the Spring AI `ToolContext`. Matches ADK
     * Java's Spring AI bridge so an ADK-aware Spring tool reads the same key from either port.
     */
    const val ADK_TOOL_CONTEXT_KEY: String = "adk_tool_context"

    /** Wraps each Spring AI [ToolCallback] as an ADK tool. */
    @JvmStatic
    fun from(toolCallbacks: List<ToolCallback>): List<SpringAiTool> = toolCallbacks.map {
      SpringAiTool(it)
    }
  }
}

/**
 * Parses a JSON Schema string (from Spring AI's `ToolDefinition.inputSchema()`) into an ADK
 * [Schema] using ADK core's converter, which resolves `$ref`/`$defs`, splits type unions into
 * `anyOf`, narrows `required`, and bounds recursion - so the tool schema does not depend on a
 * second converter. A null, blank, or unparseable schema yields an empty object schema.
 */
private fun parseJsonSchema(schema: String?): Schema {
  // Decode with kotlinx (not Gson): kotlinx keeps integer enum/default values as Long instead of
  // widening them to Double, matching the decoded tool-result path.
  val map =
    schema
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { jsonElementToAny(KxJson.parseToJsonElement(it)) }.getOrNull() }
      ?.let { it as? Map<*, *> }
      ?.mapNotNull { (key, value) -> if (key is String && value != null) key to value else null }
      ?.toMap()
  return if (map.isNullOrEmpty()) Schema(type = Type.OBJECT, properties = emptyMap())
  else jsonSchemaToAdkSchema(map)
}

/**
 * Decodes a Spring AI tool result (JSON) into a JSON-native value: a [Map] for an object, a [List]
 * for an array, or a scalar for anything else. Falls back to the raw string when it is not valid
 * JSON.
 */
private fun decodeToolResult(raw: String): Any? =
  runCatching { jsonElementToAny(KxJson.parseToJsonElement(raw)) }.getOrElse { raw }
